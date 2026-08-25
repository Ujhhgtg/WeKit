package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.features.core.BaseFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.luckypray.dexkit.DexKitBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import sun.misc.Unsafe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration

class DexResolutionCoordinatorTest {

    @Test
    fun registryUsesFullyQualifiedDelegateIdentity() {
        val owner = TestOwner()
        val methodA = owner.inlineMethod("methodA")
        val metadata = metadataFor(owner, inline = listOf(methodA))

        val registry = DexResolutionRegistry.create(listOf(owner), metadata)

        assertEquals("${TestOwner::class.java.name}#methodA", registry.node(methodA).stableId)
    }

    @Test
    fun customOutputsShareOneCustomPhase() {
        val owner = TestOwner()
        val methodOne = owner.customMethod("methodOne")
        val methodTwo = owner.customMethod("methodTwo")
        val metadata = metadataFor(owner, custom = listOf(methodOne, methodTwo))

        val registry = DexResolutionRegistry.create(listOf(owner), metadata)

        assertSame(registry.customPhase(owner), registry.producerOf(methodOne))
        assertSame(registry.customPhase(owner), registry.producerOf(methodTwo))
    }

    @Test
    fun duplicateRuntimeOwnerIdentityFailsDuringRegistryCreation() {
        val owner = TestOwner()
        val methodA = owner.inlineMethod("methodA")
        val metadata = metadataFor(owner, inline = listOf(methodA))

        assertThrows(IllegalArgumentException::class.java) {
            DexResolutionRegistry.create(listOf(owner, owner), metadata)
        }
    }

    @Test
    fun duplicateGeneratedProducerStableIdFailsDuringRegistryCreation() {
        val firstOwner = object : TestOwner() {}
        val first = firstOwner.inlineMethod("first")
        val secondOwner = object : TestOwner() {}
        val second = secondOwner.inlineMethod("second")
        val firstMetadata = metadataFor(firstOwner).getValue(firstOwner.javaClass.name)
        val secondMetadata = metadataFor(secondOwner).getValue(secondOwner.javaClass.name)
        val duplicate = secondMetadata.producers.values.single().copy(stableId = first.stableId)
        val metadata = mapOf(
            firstOwner.javaClass.name to firstMetadata,
            secondOwner.javaClass.name to secondMetadata.copy(
                producers = mapOf(first.stableId to duplicate),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            DexResolutionRegistry.create(listOf(firstOwner, secondOwner), metadata)
        }

        assertEquals("Duplicate Dex producer stable ID: ${first.stableId}", error.message)
        assertNotEquals(first.stableId, second.stableId)
    }

    @Test
    fun registryRejectsResolverThatDoesNotExtendBaseFeature() {
        val owner = object : IResolveDex {
            override val dexDelegates = emptyList<BaseDexDelegate>()
        }

        assertThrows(IllegalArgumentException::class.java) {
            DexResolutionRegistry.create(listOf(owner), emptyMap())
        }
    }

    @Test
    fun missingOwnerMetadataFailsDuringRegistryCreation() {
        val owner = TestOwner()
        owner.inlineMethod("methodA")

        assertThrows(IllegalArgumentException::class.java) {
            DexResolutionRegistry.create(listOf(owner), emptyMap())
        }
    }

    @Test
    fun missingPropertyMetadataFailsDuringRegistryCreation() {
        val owner = TestOwner()
        owner.inlineMethod("methodA")
        val ownerName = owner.javaClass.name
        val metadata = mapOf(
            ownerName to DexOwnerMetadata(
                ownerClassName = ownerName,
                ownerSafetyFingerprint = "owner",
                producers = emptyMap(),
                customOutputPropertyNames = emptySet(),
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            DexResolutionRegistry.create(listOf(owner), metadata)
        }
    }

    @Test
    fun resolvingConsumerFirstResolvesItsDirectDependency() {
        val events = mutableListOf<String>()
        val api = object : TestOwner() {}
        val methodA = api.inlineMethod("methodA") { delegate ->
            assertEquals("test", DexResolutionContext.host.versionName)
            events += "A"
            delegate.setDescriptor("api.A", "a", "()V")
            true
        }
        val consumer = object : TestOwner() {}
        val methodB = consumer.inlineMethod("methodB") { delegate ->
            DexResolutionContext.requireData(methodA)
            assertEquals("test", DexResolutionContext.host.versionName)
            events += "B"
            delegate.setDescriptor("feature.B", "b", "()V")
            true
        }
        val coordinator = coordinator(api, consumer)

        coordinator.resolveOwners(listOf(consumer))

        assertEquals(listOf("A", "B"), events)
        assertEquals(setOf(methodA.stableId), coordinator.dependenciesOf(methodB.stableId))
        assertEquals(0, api.startupInvocations.get())
        assertEquals(0, consumer.startupInvocations.get())
    }

    @Test
    fun rootCallbacksBracketActualRootOrderWithoutPromotingDependencies() {
        val events = mutableListOf<String>()
        val prerequisiteOwner = object : TestOwner() {}
        val prerequisite = prerequisiteOwner.inlineMethod("prerequisite") { delegate ->
            events += "dependency"
            delegate.setDescriptor("callback.Dependency", "run", "()V")
            true
        }
        val first = object : TestOwner() {}
        first.inlineMethod("first") { delegate ->
            DexResolutionContext.requireData(prerequisite)
            events += "first"
            delegate.setDescriptor("callback.First", "run", "()V")
            true
        }
        val second = object : TestOwner() {}
        second.inlineMethod("second") { delegate ->
            events += "second"
            delegate.setDescriptor("callback.Second", "run", "()V")
            true
        }
        val coordinator = coordinator(prerequisiteOwner, first, second)

        coordinator.resolveOwners(
            listOf(first, second),
            onRootStart = { events += "start:${it.javaClass.name}" },
            onRootFinish = { owner, _ -> events += "finish:${owner.javaClass.name}" },
        )

        assertEquals(
            listOf(
                "start:${first.javaClass.name}",
                "dependency",
                "first",
                "finish:${first.javaClass.name}",
                "start:${second.javaClass.name}",
                "second",
                "finish:${second.javaClass.name}",
            ),
            events,
        )
    }

    @Test
    fun dependencyResolutionIsIndependentOfPropertyRegistrationOrder() {
        val events = mutableListOf<String>()
        val owner = object : TestOwner() {}
        lateinit var prerequisite: DexMethodDelegate
        val consumer = owner.inlineMethod("aConsumer") { delegate ->
            DexResolutionContext.requireData(prerequisite)
            events += "consumer"
            delegate.setDescriptor("feature.Consumer", "run", "()V")
            true
        }
        prerequisite = owner.inlineMethod("zPrerequisite") { delegate ->
            events += "prerequisite"
            delegate.setDescriptor("feature.Prerequisite", "run", "()V")
            true
        }
        val coordinator = coordinator(owner)

        coordinator.resolveOwners(listOf(owner))

        assertEquals(listOf("prerequisite", "consumer"), events)
        assertEquals(setOf(prerequisite.stableId), coordinator.dependenciesOf(consumer.stableId))
    }

    @Test
    fun transitiveDependenciesResolveBeforeRoot() {
        val events = mutableListOf<String>()
        val first = object : TestOwner() {}
        val methodA = first.inlineMethod("methodA") { delegate ->
            events += "A"
            delegate.setDescriptor("graph.A", "run", "()V")
            true
        }
        val middle = object : TestOwner() {}
        val methodB = middle.inlineMethod("methodB") { delegate ->
            DexResolutionContext.requireData(methodA)
            events += "B"
            delegate.setDescriptor("graph.B", "run", "()V")
            true
        }
        val last = object : TestOwner() {}
        val methodC = last.inlineMethod("methodC") { delegate ->
            DexResolutionContext.requireData(methodB)
            events += "C"
            delegate.setDescriptor("graph.C", "run", "()V")
            true
        }
        val coordinator = coordinator(first, middle, last)

        coordinator.resolveDelegate(methodC)

        assertEquals(listOf("A", "B", "C"), events)
        assertEquals(setOf(methodA.stableId), coordinator.dependenciesOf(methodB.stableId))
        assertEquals(setOf(methodB.stableId), coordinator.dependenciesOf(methodC.stableId))
    }

    @Test
    fun dataRequirementOutsideResolutionStillFailsClearly() {
        val owner = TestOwner()
        val method = owner.inlineMethod("method")

        val error = assertThrows(IllegalStateException::class.java) {
            DexResolutionContext.requireData(method)
        }

        assertEquals("Dex resolution context is not active", error.message)
    }

    @Test
    fun concurrentCallersExecuteSharedInlineProducerOnce() = runBlocking {
        val executions = AtomicInteger()
        val started = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val owner = object : TestOwner() {}
        val shared = owner.inlineMethod("shared") { delegate ->
            executions.incrementAndGet()
            started.complete(Unit)
            release.join()
            delegate.setDescriptor("concurrent.Shared", "run", "()V")
            true
        }
        val coordinator = coordinator(owner)

        coroutineScope {
            val callers = List(16) {
                async(Dispatchers.IO) { coordinator.resolveDelegate(shared) }
            }
            started.join()
            release.complete(Unit)
            callers.awaitAll()
        }

        assertEquals(1, executions.get())
    }

    @Test
    fun twoConcurrentConsumersWaitForOnePrerequisiteExecution() = runBlocking {
        val executions = AtomicInteger()
        val started = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val api = object : TestOwner() {}
        val shared = api.inlineMethod("shared") { delegate ->
            executions.incrementAndGet()
            started.complete(Unit)
            release.join()
            delegate.setDescriptor("concurrent.Api", "shared", "()V")
            true
        }
        val first = object : TestOwner() {}
        val firstConsumer = first.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(shared)
            delegate.setDescriptor("concurrent.First", "run", "()V")
            true
        }
        val second = object : TestOwner() {}
        val secondConsumer = second.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(shared)
            delegate.setDescriptor("concurrent.Second", "run", "()V")
            true
        }
        val coordinator = coordinator(api, first, second)

        coroutineScope {
            val callers = listOf(
                async(Dispatchers.IO) { coordinator.resolveDelegate(firstConsumer) },
                async(Dispatchers.IO) { coordinator.resolveDelegate(secondConsumer) },
            )
            started.join()
            release.complete(Unit)
            callers.awaitAll()
        }

        assertEquals(1, executions.get())
    }

    @Test
    fun inlineProducerCanConsumeCustomOutput() {
        val customExecutions = AtomicInteger()
        val customOwner = object : TestOwner() {}
        val customOutput = customOwner.customMethod("customOutput")
        customOwner.customResolver = {
            customExecutions.incrementAndGet()
            customOutput.setDescriptor("custom.Output", "load", "()V")
        }
        val consumerOwner = object : TestOwner() {}
        val consumer = consumerOwner.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(customOutput)
            delegate.setDescriptor("custom.Consumer", "run", "()V")
            true
        }
        val coordinator = coordinator(customOwner, consumerOwner)

        coordinator.resolveDelegate(consumer)

        assertEquals(1, customExecutions.get())
        assertEquals(
            setOf("${customOwner.javaClass.name}#resolveDex"),
            coordinator.dependenciesOf(consumer.stableId),
        )
    }

    @Test
    fun customPhaseCanConsumeInlineProducer() {
        val api = object : TestOwner() {}
        val inline = api.inlineMethod("inline") { delegate ->
            delegate.setDescriptor("custom.Api", "load", "()V")
            true
        }
        val customOwner = object : TestOwner() {}
        val customOutput = customOwner.customMethod("customOutput")
        customOwner.customResolver = {
            DexResolutionContext.requireData(inline)
            customOutput.setDescriptor("custom.Output", "run", "()V")
        }
        val coordinator = coordinator(api, customOwner)

        coordinator.resolveDelegate(customOutput)

        val phaseId = "${customOwner.javaClass.name}#resolveDex"
        assertEquals(DexResolutionStatus.SUCCESS, customOutput.diagnostic.status)
        assertEquals(setOf(inline.stableId), coordinator.dependenciesOf(phaseId))
    }

    @Test
    fun customPhaseCanReadOutputAlreadyAssignedBySamePhase() {
        val owner = object : TestOwner() {}
        val first = owner.customMethod("first")
        val second = owner.customMethod("second")
        owner.customResolver = {
            first.setDescriptor("custom.First", "run", "()V")
            DexResolutionContext.requireData(first)
            second.setDescriptor("custom.Second", "run", "()V")
        }
        val coordinator = coordinator(owner)

        coordinator.resolveDelegate(second)

        val phaseId = "${owner.javaClass.name}#resolveDex"
        assertEquals(DexResolutionStatus.SUCCESS, first.diagnostic.status)
        assertEquals(DexResolutionStatus.SUCCESS, second.diagnostic.status)
        assertEquals(emptySet<String>(), coordinator.dependenciesOf(phaseId))
    }

    @Test
    fun oneCustomPhaseExecutionProducesMultipleOutputs() {
        val executions = AtomicInteger()
        val owner = object : TestOwner() {}
        val first = owner.customMethod("first")
        val second = owner.customMethod("second")
        owner.customResolver = {
            executions.incrementAndGet()
            first.setDescriptor("custom.First", "run", "()V")
            second.setDescriptor("custom.Second", "run", "()V")
        }
        val coordinator = coordinator(owner)

        coordinator.resolveOwners(listOf(owner))

        assertEquals(1, executions.get())
        assertEquals(DexResolutionStatus.SUCCESS, first.diagnostic.status)
        assertEquals(DexResolutionStatus.SUCCESS, second.diagnostic.status)
    }

    @Test
    fun customOutputLeftUnsetBecomesIncomplete() {
        val owner = object : TestOwner() {}
        val resolved = owner.customMethod("resolved")
        val missing = owner.customMethod("missing")
        owner.customResolver = {
            resolved.setDescriptor("custom.Resolved", "run", "()V")
        }
        val coordinator = coordinator(owner)

        coordinator.resolveDelegate(missing)

        assertEquals(DexResolutionStatus.SUCCESS, resolved.diagnostic.status)
        assertEquals(DexResolutionStatus.INCOMPLETE, missing.diagnostic.status)
    }

    @Test
    fun successfulCustomOutputCannotHideIncompleteSiblingProducer() {
        val customOwner = object : TestOwner() {}
        val successful = customOwner.customMethod("successful")
        val incomplete = customOwner.customMethod("incomplete")
        customOwner.customResolver = {
            successful.setDescriptor("custom.Successful", "run", "()V")
        }
        val consumerOwner = object : TestOwner() {}
        val consumer = consumerOwner.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(successful)
            delegate.setDescriptor("custom.Consumer", "run", "()V")
            true
        }
        val coordinator = coordinator(customOwner, consumerOwner)

        val consumerResult = coordinator.resolveDelegate(consumer)
        val producerResult = coordinator.resolveDelegate(successful)

        assertEquals(DexResolutionStatus.SUCCESS, successful.diagnostic.status)
        assertEquals(DexResolutionStatus.INCOMPLETE, incomplete.diagnostic.status)
        assertEquals(DexResolutionStatus.INCOMPLETE, (producerResult as DexNodeResult.Resolved).diagnostic.status)
        assertEquals(DexResolutionStatus.BLOCKED, consumer.diagnostic.status)
        assertEquals(incomplete.stableId, consumer.diagnostic.blockedBy)
        assertEquals(
            "Dex resolution dependency failed: ${incomplete.stableId}",
            (consumerResult as DexNodeResult.Failed).error.message,
        )
        assertEquals(
            false,
            coordinator.effectiveFingerprintByProducer.containsKey("${customOwner.javaClass.name}#resolveDex"),
        )
    }

    @Test
    fun successfulCustomOutputCannotHideUnexpectedSiblingProducer() {
        val customOwner = object : TestOwner() {}
        val successful = customOwner.customMethod("successful")
        val failing = customOwner.customMethod("failing")
        val expected = IllegalStateException("classified without throwing")
        customOwner.customResolver = {
            successful.setDescriptor("custom.Successful", "run", "()V")
            failing.recordUnexpectedFailure(expected)
        }
        val consumerOwner = object : TestOwner() {}
        val consumer = consumerOwner.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(successful)
            delegate.setDescriptor("custom.Consumer", "run", "()V")
            true
        }
        val coordinator = coordinator(customOwner, consumerOwner)

        val consumerResult = coordinator.resolveDelegate(consumer)
        val producerResult = coordinator.resolveDelegate(successful)

        assertEquals(DexResolutionStatus.SUCCESS, successful.diagnostic.status)
        assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, failing.diagnostic.status)
        assertEquals(
            DexResolutionStatus.UNEXPECTED_FAILURE,
            (producerResult as DexNodeResult.Resolved).diagnostic.status,
        )
        assertEquals(DexResolutionStatus.BLOCKED, consumer.diagnostic.status)
        assertEquals(failing.stableId, consumer.diagnostic.blockedBy)
        assertEquals(
            "Dex resolution dependency failed: ${failing.stableId}",
            (consumerResult as DexNodeResult.Failed).error.message,
        )
    }

    @Test
    fun cycleReportsCompleteOrderedPathAndBlocksWaitingConsumer() {
        val firstOwner = object : TestOwner() {}
        val secondOwner = object : TestOwner() {}
        lateinit var first: DexMethodDelegate
        lateinit var second: DexMethodDelegate
        first = firstOwner.inlineMethod("methodOne") { delegate ->
            DexResolutionContext.requireData(second)
            delegate.setDescriptor("cycle.First", "run", "()V")
            true
        }
        second = secondOwner.inlineMethod("classTwo") { delegate ->
            DexResolutionContext.requireData(first)
            delegate.setDescriptor("cycle.Second", "run", "()V")
            true
        }
        val coordinator = coordinator(firstOwner, secondOwner)
        val expectedPath = listOf(first.stableId, second.stableId, first.stableId)

        val result = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { coordinator.resolveDelegate(first) },
        )

        val error = (result as DexNodeResult.Failed).error
        assertEquals(
            "Dex resolution dependency cycle: ${expectedPath.joinToString(" -> ")}",
            error.message,
        )
        assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, second.diagnostic.status)
        assertEquals(expectedPath, second.diagnostic.dependencyPath)
        assertEquals(DexResolutionStatus.BLOCKED, first.diagnostic.status)
        assertEquals(second.stableId, first.diagnostic.blockedBy)
    }

    @Test
    fun pendingSamePhaseReadAttributesCycleToRequestedOutput() {
        val owner = object : TestOwner() {}
        val detecting = owner.customMethod("detecting")
        val remaining = owner.customMethod("remaining")
        owner.customResolver = {
            DexResolutionContext.requireData(detecting)
            detecting.setDescriptor("cycle.Detecting", "run", "()V")
            remaining.setDescriptor("cycle.Remaining", "run", "()V")
        }
        val coordinator = coordinator(owner)
        val phaseId = "${owner.javaClass.name}#resolveDex"
        val expectedPath = listOf(phaseId, phaseId)

        val result = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { coordinator.resolveDelegate(detecting) },
        )

        val error = (result as DexNodeResult.Failed).error as DexResolutionCycleException
        assertEquals(expectedPath, error.path)
        assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, detecting.diagnostic.status)
        assertEquals(expectedPath, detecting.diagnostic.dependencyPath)
        assertEquals(DexResolutionStatus.BLOCKED, remaining.diagnostic.status)
        assertEquals(detecting.stableId, remaining.diagnostic.blockedBy)
    }

    @Test
    fun cycleDoesNotOverwriteEarlierUnexpectedOutputFailure() {
        val owner = object : TestOwner() {}
        val detecting = owner.customMethod("detecting")
        val remaining = owner.customMethod("remaining")
        val originalError = IllegalStateException("original delegate failure")
        lateinit var originalDiagnostic: DexResolutionDiagnostic
        owner.customResolver = {
            detecting.recordUnexpectedFailure(originalError)
            originalDiagnostic = detecting.diagnostic
            DexResolutionContext.requireData(detecting)
        }
        val consumerOwner = object : TestOwner() {}
        val consumer = consumerOwner.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(detecting)
            delegate.setDescriptor("cycle.Consumer", "run", "()V")
            true
        }
        val coordinator = coordinator(owner, consumerOwner)

        val cycleResult = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { coordinator.resolveDelegate(detecting) },
        )
        val consumerResult = coordinator.resolveDelegate(consumer)

        val cycleError = (cycleResult as DexNodeResult.Failed).error as DexResolutionCycleException
        assertEquals(originalDiagnostic, detecting.diagnostic)
        assertEquals("original delegate failure", detecting.diagnostic.message)
        assertEquals(null, detecting.diagnostic.dependencyPath)
        assertEquals(DexResolutionStatus.BLOCKED, remaining.diagnostic.status)
        assertEquals(detecting.stableId, remaining.diagnostic.blockedBy)
        assertEquals(DexResolutionStatus.BLOCKED, consumer.diagnostic.status)
        assertEquals(detecting.stableId, consumer.diagnostic.blockedBy)
        assertSame(cycleError, (consumerResult as DexNodeResult.Failed).error)
    }

    @Test
    fun cycleDoesNotOverwriteEarlierBlockedOutputFailure() {
        val owner = object : TestOwner() {}
        val detecting = owner.customMethod("detecting")
        val remaining = owner.customMethod("remaining")
        val originalBlockedBy = "dev.example.Upstream#failed"
        lateinit var originalDiagnostic: DexResolutionDiagnostic
        owner.customResolver = {
            detecting.markBlocked(originalBlockedBy)
            originalDiagnostic = detecting.diagnostic
            DexResolutionContext.requireData(detecting)
        }
        val consumerOwner = object : TestOwner() {}
        val consumer = consumerOwner.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(detecting)
            delegate.setDescriptor("cycle.Consumer", "run", "()V")
            true
        }
        val coordinator = coordinator(owner, consumerOwner)

        val cycleResult = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { coordinator.resolveDelegate(detecting) },
        )
        val consumerResult = coordinator.resolveDelegate(consumer)

        val cycleError = (cycleResult as DexNodeResult.Failed).error as DexResolutionCycleException
        assertEquals(originalDiagnostic, detecting.diagnostic)
        assertEquals(DexResolutionStatus.BLOCKED, detecting.diagnostic.status)
        assertEquals(originalBlockedBy, detecting.diagnostic.blockedBy)
        assertEquals(DexResolutionStatus.BLOCKED, remaining.diagnostic.status)
        assertEquals(detecting.stableId, remaining.diagnostic.blockedBy)
        assertEquals(DexResolutionStatus.BLOCKED, consumer.diagnostic.status)
        assertEquals(originalBlockedBy, consumer.diagnostic.blockedBy)
        assertSame(cycleError, (consumerResult as DexNodeResult.Failed).error)
    }

    @Test
    fun customToCustomCycleAttributesFailureAndBlocksEveryDependent() {
        val firstOwner = object : TestOwner() {}
        val first = firstOwner.customMethod("first")
        val firstRemaining = firstOwner.customMethod("remaining")
        val secondOwner = object : TestOwner() {}
        val second = secondOwner.customMethod("second")
        val secondRemaining = secondOwner.customMethod("remaining")
        firstOwner.customResolver = {
            DexResolutionContext.requireData(second)
            first.setDescriptor("cycle.First", "run", "()V")
            firstRemaining.setDescriptor("cycle.FirstRemaining", "run", "()V")
        }
        secondOwner.customResolver = {
            DexResolutionContext.requireData(first)
            second.setDescriptor("cycle.Second", "run", "()V")
            secondRemaining.setDescriptor("cycle.SecondRemaining", "run", "()V")
        }
        val consumerOwner = object : TestOwner() {}
        val consumer = consumerOwner.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(first)
            delegate.setDescriptor("cycle.Consumer", "run", "()V")
            true
        }
        val coordinator = coordinator(firstOwner, secondOwner, consumerOwner)
        val firstPhaseId = "${firstOwner.javaClass.name}#resolveDex"
        val secondPhaseId = "${secondOwner.javaClass.name}#resolveDex"
        val expectedPath = listOf(firstPhaseId, secondPhaseId, firstPhaseId)

        val cycleResult = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { coordinator.resolveDelegate(first) },
        )
        val consumerResult = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { coordinator.resolveDelegate(consumer) },
        )

        val error = (cycleResult as DexNodeResult.Failed).error as DexResolutionCycleException
        assertEquals(expectedPath, error.path)
        assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, first.diagnostic.status)
        assertEquals(expectedPath, first.diagnostic.dependencyPath)
        listOf(firstRemaining, second, secondRemaining, consumer).forEach { delegate ->
            assertEquals(DexResolutionStatus.BLOCKED, delegate.diagnostic.status)
            assertEquals(first.stableId, delegate.diagnostic.blockedBy)
        }
        assertSame(error, (consumerResult as DexNodeResult.Failed).error)
    }

    @Test
    fun crossThreadCycleFailsBeforeEitherProducerWaitsForever() {
        val barrier = CyclicBarrier(2)
        val firstOwner = object : TestOwner() {}
        val secondOwner = object : TestOwner() {}
        lateinit var first: DexMethodDelegate
        lateinit var second: DexMethodDelegate
        first = firstOwner.inlineMethod("first") { delegate ->
            barrier.await()
            DexResolutionContext.requireData(second)
            delegate.setDescriptor("cycle.First", "run", "()V")
            true
        }
        second = secondOwner.inlineMethod("second") { delegate ->
            barrier.await()
            DexResolutionContext.requireData(first)
            delegate.setDescriptor("cycle.Second", "run", "()V")
            true
        }
        val coordinator = coordinator(firstOwner, secondOwner)

        val results = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier<List<DexNodeResult>> {
                runBlocking {
                    listOf(
                        async(Dispatchers.IO) { coordinator.resolveDelegate(first) },
                        async(Dispatchers.IO) { coordinator.resolveDelegate(second) },
                    ).awaitAll()
                }
            },
        )

        assertEquals(2, results.filterIsInstance<DexNodeResult.Failed>().size)
        assertEquals(
            setOf(DexResolutionStatus.UNEXPECTED_FAILURE, DexResolutionStatus.BLOCKED),
            setOf(first.diagnostic.status, second.diagnostic.status),
        )
    }

    @Test
    fun customPhasePreservesFirstDelegateFailureAndBlocksOtherOutputs() {
        val owner = object : TestOwner() {}
        val failing = owner.customMethod("failing")
        val pending = owner.customMethod("pending")
        val expected = IllegalStateException("custom failure")
        owner.customResolver = {
            failing.recordUnexpectedFailure(expected)
            throw expected
        }
        val coordinator = coordinator(owner)

        val result = coordinator.resolveDelegate(pending)

        assertSame(expected, (result as DexNodeResult.Failed).error)
        assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, failing.diagnostic.status)
        assertEquals(DexResolutionStatus.BLOCKED, pending.diagnostic.status)
        assertEquals(failing.stableId, pending.diagnostic.blockedBy)
    }

    @Test
    fun consumerInheritsDelegateFailureThatBlockedCustomOutput() {
        val customOwner = object : TestOwner() {}
        val failing = customOwner.customMethod("failing")
        val pending = customOwner.customMethod("pending")
        customOwner.customResolver = {
            val error = IllegalStateException("custom failure")
            failing.recordUnexpectedFailure(error)
            throw error
        }
        val consumerOwner = object : TestOwner() {}
        val consumer = consumerOwner.inlineMethod("consumer") { delegate ->
            DexResolutionContext.requireData(pending)
            delegate.setDescriptor("custom.Consumer", "run", "()V")
            true
        }
        val coordinator = coordinator(customOwner, consumerOwner)

        coordinator.resolveDelegate(consumer)

        assertEquals(DexResolutionStatus.BLOCKED, pending.diagnostic.status)
        assertEquals(failing.stableId, pending.diagnostic.blockedBy)
        assertEquals(DexResolutionStatus.BLOCKED, consumer.diagnostic.status)
        assertEquals(failing.stableId, consumer.diagnostic.blockedBy)
    }

    @Test
    fun customPhaseFailureWithoutDelegateDiagnosticBlocksByPhaseId() {
        val owner = object : TestOwner() {}
        val first = owner.customMethod("first")
        val second = owner.customMethod("second")
        owner.customResolver = { error("phase failure") }
        val coordinator = coordinator(owner)

        coordinator.resolveDelegate(first)

        val phaseId = "${owner.javaClass.name}#resolveDex"
        assertEquals(DexResolutionStatus.BLOCKED, first.diagnostic.status)
        assertEquals(DexResolutionStatus.BLOCKED, second.diagnostic.status)
        assertEquals(phaseId, first.diagnostic.blockedBy)
        assertEquals(phaseId, second.diagnostic.blockedBy)
    }

    @Test
    fun effectiveFingerprintUsesV2FormulaWithSortedDependencies() {
        val producer = DexProducerMetadata(
            stableId = "p",
            ownerClassName = "owner",
            propertyName = "property",
            kind = DexProducerKind.INLINE_METHOD,
            localFingerprint = "l",
            usesOwnerSafetyFingerprint = false,
        )

        val fingerprint = effectiveFingerprint(
            producer,
            linkedMapOf("b" to "2", "a" to "1"),
        )

        assertEquals("626663b9ac1031fd2459b8afd059de1a17d759023c5d13065995987b7f01078a", fingerprint)
    }

    @Test
    fun runtimeDependencyDiscoveryOrderDoesNotChangeEffectiveFingerprint() {
        var reverse = false
        val firstOwner = object : TestOwner() {}
        val first = firstOwner.inlineMethod("first") { delegate ->
            delegate.setDescriptor("fingerprint.First", "run", "()V")
            true
        }
        val secondOwner = object : TestOwner() {}
        val second = secondOwner.inlineMethod("second") { delegate ->
            delegate.setDescriptor("fingerprint.Second", "run", "()V")
            true
        }
        val rootOwner = object : TestOwner() {}
        val root = rootOwner.inlineMethod("root") { delegate ->
            if (reverse) {
                DexResolutionContext.requireData(second)
                DexResolutionContext.requireData(first)
            } else {
                DexResolutionContext.requireData(first)
                DexResolutionContext.requireData(second)
            }
            delegate.setDescriptor("fingerprint.Root", "run", "()V")
            true
        }
        val owners = listOf(firstOwner, secondOwner, rootOwner)
        val firstCoordinator = coordinator(*owners.toTypedArray())
        firstCoordinator.resolveDelegate(root)
        val firstFingerprint = firstCoordinator.effectiveFingerprintByProducer.getValue(root.stableId)

        owners.flatMap { it.dexDelegates }.forEach(BaseDexDelegate::resetForDexTest)
        reverse = true
        val secondCoordinator = coordinator(*owners.toTypedArray())
        secondCoordinator.resolveDelegate(root)

        assertEquals(
            firstFingerprint,
            secondCoordinator.effectiveFingerprintByProducer.getValue(root.stableId),
        )
        assertEquals(
            setOf(first.stableId, second.stableId),
            secondCoordinator.dependenciesOf(root.stableId),
        )
        assertEquals(
            secondCoordinator.dependenciesByProducer.keys.sorted(),
            secondCoordinator.dependenciesByProducer.keys.toList(),
        )
        secondCoordinator.dependenciesByProducer.values.forEach { dependencies ->
            assertEquals(dependencies.sorted(), dependencies.toList())
        }
    }

    @Test
    fun transitiveLocalFingerprintChangeInvalidatesEveryConsumer() {
        val leafOwner = object : TestOwner() {}
        val leaf = leafOwner.inlineMethod("leaf") { delegate ->
            delegate.setDescriptor("fingerprint.Leaf", "run", "()V")
            true
        }
        val middleOwner = object : TestOwner() {}
        val middle = middleOwner.inlineMethod("middle") { delegate ->
            DexResolutionContext.requireData(leaf)
            delegate.setDescriptor("fingerprint.Middle", "run", "()V")
            true
        }
        val rootOwner = object : TestOwner() {}
        val root = rootOwner.inlineMethod("root") { delegate ->
            DexResolutionContext.requireData(middle)
            delegate.setDescriptor("fingerprint.Root", "run", "()V")
            true
        }
        val owners = listOf(leafOwner, middleOwner, rootOwner)
        val firstCoordinator = coordinatorWithFingerprints(owners, mapOf(leaf.stableId to "leaf-v1"))
        firstCoordinator.resolveDelegate(root)

        owners.flatMap { it.dexDelegates }.forEach(BaseDexDelegate::resetForDexTest)
        val secondCoordinator = coordinatorWithFingerprints(owners, mapOf(leaf.stableId to "leaf-v2"))
        secondCoordinator.resolveDelegate(root)

        assertNotEquals(
            firstCoordinator.effectiveFingerprintByProducer.getValue(leaf.stableId),
            secondCoordinator.effectiveFingerprintByProducer.getValue(leaf.stableId),
        )
        assertNotEquals(
            firstCoordinator.effectiveFingerprintByProducer.getValue(middle.stableId),
            secondCoordinator.effectiveFingerprintByProducer.getValue(middle.stableId),
        )
        assertNotEquals(
            firstCoordinator.effectiveFingerprintByProducer.getValue(root.stableId),
            secondCoordinator.effectiveFingerprintByProducer.getValue(root.stableId),
        )
    }

    private fun coordinator(vararg owners: TestOwner): DexResolutionCoordinator {
        return coordinatorWithFingerprints(owners.toList(), emptyMap())
    }

    private fun coordinatorWithFingerprints(
        owners: List<TestOwner>,
        localFingerprints: Map<String, String>,
    ): DexResolutionCoordinator {
        val metadata = owners.associate { owner ->
            metadataFor(owner, localFingerprints = localFingerprints).entries.single().toPair()
        }
        return DexResolutionCoordinator(
            registry = DexResolutionRegistry.create(owners, metadata),
            dexKit = allocateDexKitWithoutNativeState(),
            host = DexHostMetadata(versionCode = 1, versionName = "test", isGooglePlay = false),
        )
    }

    private fun metadataFor(
        owner: TestOwner,
        inline: List<BaseDexDelegate> = owner.dexDelegates.filter { it.inlineProducer != null },
        custom: List<BaseDexDelegate> = owner.dexDelegates.filter { it.inlineProducer == null },
        localFingerprints: Map<String, String> = emptyMap(),
    ): Map<String, DexOwnerMetadata> {
        val ownerName = owner.javaClass.name
        val inlineProducers = inline.associate { delegate ->
            delegate.stableId to DexProducerMetadata(
                stableId = delegate.stableId,
                ownerClassName = ownerName,
                propertyName = delegate.propertyName,
                kind = DexProducerKind.INLINE_METHOD,
                localFingerprint = localFingerprints[delegate.stableId] ?: "inline-${delegate.propertyName}",
                usesOwnerSafetyFingerprint = false,
            )
        }
        val customProducer = if (custom.isEmpty()) {
            emptyMap()
        } else {
            val stableId = "$ownerName#resolveDex"
            mapOf(
                stableId to DexProducerMetadata(
                    stableId = stableId,
                    ownerClassName = ownerName,
                    propertyName = null,
                    kind = DexProducerKind.CUSTOM,
                    localFingerprint = "custom",
                    usesOwnerSafetyFingerprint = false,
                )
            )
        }
        return mapOf(
            ownerName to DexOwnerMetadata(
                ownerClassName = ownerName,
                ownerSafetyFingerprint = "owner",
                producers = inlineProducers + customProducer,
                customOutputPropertyNames = custom.mapTo(sortedSetOf()) { it.propertyName },
            )
        )
    }

    private fun allocateDexKitWithoutNativeState(): DexKitBridge {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (field.get(null) as Unsafe).allocateInstance(DexKitBridge::class.java) as DexKitBridge
    }
}

private open class TestOwner : BaseFeature(), IResolveDex {
    override val technicalId = "test"
    override val nameRes = 0
    override val categoryIds = emptyList<String>()
    val startupInvocations = AtomicInteger()
    var customResolver: DexKitBridge.() -> Unit = {}

    override fun startup() {
        startupInvocations.incrementAndGet()
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        customResolver(dexKit)
    }

    fun inlineMethod(
        propertyName: String,
        block: DexResolutionCoordinator.(DexMethodDelegate) -> Boolean = { true },
    ): DexMethodDelegate = DexMethodDelegate(this, propertyName, block).also(::registerDexDelegate)

    fun customMethod(propertyName: String): DexMethodDelegate =
        DexMethodDelegate(this, propertyName).also(::registerDexDelegate)
}
