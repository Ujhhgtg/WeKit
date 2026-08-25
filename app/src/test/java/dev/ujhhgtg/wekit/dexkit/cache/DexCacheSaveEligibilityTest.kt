package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dexkit.resolution.DexHostMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexOwnerMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerKind
import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionCoordinator
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionRegistry
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.features.core.BaseFeature
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.luckypray.dexkit.DexKitBridge
import sun.misc.Unsafe

class DexCacheSaveEligibilityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun incompleteConsumerIsSkippedWhileCompletedPrerequisiteIsSaved() {
        val prerequisiteOwner = object : CacheSaveOwner("Prerequisite") {}
        val prerequisite = prerequisiteOwner.method("target") { delegate ->
            delegate.setDescriptor("save.Prerequisite", "target", "()V")
            true
        }
        val consumer = object : CacheSaveOwner("Consumer") {}
        consumer.method("target") {
            DexResolutionContext.requireData(prerequisite)
            true
        }
        val (registry, coordinator) = graph(prerequisiteOwner, consumer)

        coordinator.resolveOwners(listOf(consumer))
        saveResolvedOwners(tempDir, registry, coordinator, listOf(consumer))

        assertTrue(tempDir.resolve("Prerequisite.json").exists())
        assertFalse(tempDir.resolve("Consumer.json").exists())
    }

    @Test
    fun expectedPlaceholderIsSavedAndUnexpectedPlaceholderIsRejected() {
        val expectedOwner = object : CacheSaveOwner("Expected") {}
        expectedOwner.method("target") { delegate ->
            delegate.setPlaceholderDescriptor(expectedFailure = true)
            false
        }
        val unexpectedOwner = object : CacheSaveOwner("Unexpected") {}
        unexpectedOwner.method("target") { delegate ->
            delegate.setPlaceholderDescriptor()
            false
        }
        val (registry, coordinator) = graph(expectedOwner, unexpectedOwner)

        coordinator.resolveOwners(listOf(expectedOwner, unexpectedOwner))
        saveResolvedOwners(tempDir, registry, coordinator, listOf(expectedOwner, unexpectedOwner))

        val manifest = Json.decodeFromString<DexCacheManifest>(tempDir.resolve("Expected.json").readText())
        val entry = manifest.delegates.values.single()
        assertEquals(DexResolutionStatus.EXPECTED_FAILURE, entry.status)
        assertTrue(entry.isPlaceholder)
        assertFalse(tempDir.resolve("Unexpected.json").exists())
    }

    @Test
    fun malformedOrCrossKindResolvedDescriptorIsNeverWritten() {
        val malformedOwner = object : CacheSaveOwner("Malformed") {}
        malformedOwner.method("target") { delegate ->
            delegate.setDescriptor("save.Malformed", "target", "(Q)V")
            true
        }
        val constructorOwner = object : CacheSaveOwner("ConstructorAsMethod") {}
        constructorOwner.method("target") { delegate ->
            delegate.setDescriptor("save.CrossKind", "<init>", "()V")
            true
        }
        val (registry, coordinator) = graph(malformedOwner, constructorOwner)

        coordinator.resolveOwners(listOf(malformedOwner, constructorOwner))
        saveResolvedOwners(tempDir, registry, coordinator, listOf(malformedOwner, constructorOwner))

        assertFalse(tempDir.resolve("Malformed.json").exists())
        assertFalse(tempDir.resolve("ConstructorAsMethod.json").exists())
    }

    private fun graph(vararg owners: CacheSaveOwner): Pair<DexResolutionRegistry, DexResolutionCoordinator> {
        val metadata = owners.associate { owner ->
            val producers = owner.dexDelegates.associate { delegate ->
                delegate.stableId to DexProducerMetadata(
                    stableId = delegate.stableId,
                    ownerClassName = owner.javaClass.name,
                    propertyName = delegate.propertyName,
                    kind = DexProducerKind.INLINE_METHOD,
                    localFingerprint = "local-${delegate.propertyName}",
                    usesSourceDirSafetyFingerprint = false,
                )
            }
            owner.javaClass.name to DexOwnerMetadata(
                ownerClassName = owner.javaClass.name,
                producers = producers,
                customOutputPropertyNames = emptySet(),
            )
        }
        val registry = DexResolutionRegistry.create(owners.toList(), metadata)
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val dexKit = (field.get(null) as Unsafe).allocateInstance(DexKitBridge::class.java) as DexKitBridge
        return registry to DexResolutionCoordinator(
            registry,
            dexKit,
            DexHostMetadata(1, "test", false),
        )
    }
}

private open class CacheSaveOwner(
    override val technicalId: String,
) : BaseFeature(), IResolveDex {
    override val nameRes = 0
    override val categoryIds = emptyList<String>()

    fun method(
        propertyName: String,
        block: DexResolutionCoordinator.(DexMethodDelegate) -> Boolean,
    ): DexMethodDelegate = DexMethodDelegate(this, propertyName, block).also(::registerDexDelegate)
}
