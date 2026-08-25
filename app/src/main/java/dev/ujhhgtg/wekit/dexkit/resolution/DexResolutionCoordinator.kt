package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import org.luckypray.dexkit.DexKitBridge
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

sealed interface DexNodeResult {
    data class Resolved(val diagnostic: DexResolutionDiagnostic) : DexNodeResult
    data class Failed(val error: Throwable) : DexNodeResult
}

data class DexResolutionBatchResult(
    val resultsByProducer: Map<String, DexNodeResult>,
)

class DexResolutionCycleException(
    val path: List<String>,
) : IllegalStateException("Dex resolution dependency cycle: ${path.joinToString(" -> ")}")

private class DexResolutionDependencyException(
    dependencyId: String,
) : IllegalStateException("Dex resolution dependency failed: $dependencyId")

class DexResolutionCoordinator(
    val registry: DexResolutionRegistry,
    internal val dexKit: DexKitBridge,
    val host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
) {
    private enum class NodeState { UNRESOLVED, RESOLVING, RESOLVED, FAILED }

    private class RuntimeNode {
        val lock = Any()
        @Volatile var state = NodeState.UNRESOLVED
        val completion = CompletableFuture<DexNodeResult>()
    }

    private val runtimeNodes = registry.producersById.mapValues { RuntimeNode() }
    private val dependencyLock = Any()
    private val mutableDependencies = mutableMapOf<String, MutableSet<String>>()
    private val mutableEffectiveFingerprints = ConcurrentHashMap<String, String>()

    val dependenciesByProducer: Map<String, Set<String>>
        get() = synchronized(dependencyLock) {
            registry.producersById.keys.associateWithTo(TreeMap()) { producerId ->
                mutableDependencies[producerId].orEmpty().toSortedSet()
            }
        }

    val effectiveFingerprintByProducer: Map<String, String>
        get() = mutableEffectiveFingerprints.toSortedMap()

    fun dependenciesOf(producerId: String): Set<String> = synchronized(dependencyLock) {
        mutableDependencies[producerId].orEmpty().toSortedSet()
    }

    fun resolveOwners(owners: Collection<IResolveDex>): DexResolutionBatchResult {
        val results = TreeMap<String, DexNodeResult>()
        owners.forEach { owner ->
            val ownerId = owner.javaClass.name
            require(registry.ownersById[ownerId] === owner) {
                "Dex resolution owner is not registered: $ownerId"
            }
            owner.dexDelegates.sortedBy { it.stableId }.forEach { delegate ->
                val producer = registry.producerOf(delegate)
                results[producer.stableId] = resolveProducer(producer)
            }
        }
        return DexResolutionBatchResult(results)
    }

    fun resolveDelegate(delegate: BaseDexDelegate): DexNodeResult {
        val result = resolveProducer(registry.producerOf(delegate))
        return when (result) {
            is DexNodeResult.Failed -> result
            is DexNodeResult.Resolved -> DexNodeResult.Resolved(delegate.diagnostic)
        }
    }

    internal fun requireData(fromProducerId: String, delegate: BaseDexDelegate): String {
        val dependency = registry.producerOf(delegate)
        if (dependency.stableId == fromProducerId && delegate.diagnostic.status in setOf(
                DexResolutionStatus.SUCCESS,
                DexResolutionStatus.EXPECTED_FAILURE,
            )
        ) {
            return delegate.getDescriptorString()
                ?: throw DexResolutionDependencyException(delegate.stableId)
        }
        addDependency(fromProducerId, dependency.stableId)
        val result = resolveProducer(dependency)
        if (result is DexNodeResult.Failed) {
            markBlocked(fromProducerId, failureKey(delegate, dependency))
            throw result.error
        }
        if (delegate.diagnostic.status !in setOf(
                DexResolutionStatus.SUCCESS,
                DexResolutionStatus.EXPECTED_FAILURE,
            )
        ) {
            markBlocked(fromProducerId, delegate.stableId)
            throw DexResolutionDependencyException(delegate.stableId)
        }
        return delegate.getDescriptorString()
            ?: throw DexResolutionDependencyException(delegate.stableId)
    }

    private fun resolveProducer(producer: DexProducerNode): DexNodeResult {
        val runtime = runtimeNodes.getValue(producer.stableId)
        val execute = synchronized(runtime.lock) {
            if (runtime.state == NodeState.UNRESOLVED) {
                runtime.state = NodeState.RESOLVING
                true
            } else {
                false
            }
        }
        if (!execute) return runtime.completion.join()

        val result = try {
            DexResolutionContext.withResolutionSession(
                DexResolutionSession(this, producer.stableId)
            ) {
                executeProducer(producer)
            }
        } catch (error: Throwable) {
            recordProducerFailure(producer, error)
            DexNodeResult.Failed(error)
        }

        if (result is DexNodeResult.Resolved && result.diagnostic.status in setOf(
                DexResolutionStatus.SUCCESS,
                DexResolutionStatus.EXPECTED_FAILURE,
            )
        ) {
            computeEffectiveFingerprint(producer)
        }
        synchronized(runtime.lock) {
            runtime.state = if (result is DexNodeResult.Resolved) NodeState.RESOLVED else NodeState.FAILED
        }
        runtime.completion.complete(result)
        return result
    }

    private fun executeProducer(producer: DexProducerNode): DexNodeResult {
        val inlineDelegate = producer.inlineDelegate
        if (inlineDelegate != null) {
            inlineDelegate.inlineProducer!!.invoke(this, inlineDelegate)
            inlineDelegate.markIncomplete()
            return DexNodeResult.Resolved(inlineDelegate.diagnostic)
        }

        (producer.owner as IResolveDex).resolveDex(dexKit)
        producer.outputs.forEach(BaseDexDelegate::markIncomplete)
        val diagnostic = producer.outputs
            .map(BaseDexDelegate::diagnostic)
            .maxByOrNull { diagnosticSeverity(it.status) }
            ?: DexResolutionDiagnostic(DexResolutionStatus.SUCCESS)
        return DexNodeResult.Resolved(diagnostic)
    }

    private fun recordProducerFailure(producer: DexProducerNode, error: Throwable) {
        if (producer.inlineDelegate != null) {
            val delegate = producer.inlineDelegate
            if (delegate.diagnostic.status !in setOf(
                    DexResolutionStatus.UNEXPECTED_FAILURE,
                    DexResolutionStatus.BLOCKED,
                )
            ) {
                delegate.recordUnexpectedFailure(error, (error as? DexResolutionCycleException)?.path)
            }
            return
        }

        val failing = producer.outputs.firstOrNull {
            it.diagnostic.status == DexResolutionStatus.UNEXPECTED_FAILURE
        }
        val blockedBy = failing?.stableId ?: producer.stableId
        producer.outputs.forEach { delegate ->
            if (delegate !== failing) delegate.markBlocked(blockedBy)
        }
    }

    private fun markBlocked(producerId: String, blockedBy: String) {
        registry.producersById.getValue(producerId).outputs.forEach { it.markBlocked(blockedBy) }
    }

    private fun failureKey(delegate: BaseDexDelegate, producer: DexProducerNode): String =
        when (delegate.diagnostic.status) {
            DexResolutionStatus.UNEXPECTED_FAILURE -> delegate.stableId
            DexResolutionStatus.BLOCKED -> delegate.diagnostic.blockedBy ?: producer.stableId
            else -> producer.stableId
        }

    private fun addDependency(from: String, to: String) {
        require(registry.producersById.containsKey(from)) { "Unknown Dex producer: $from" }
        synchronized(dependencyLock) {
            val path = findPath(to, from)
            if (path != null) throw DexResolutionCycleException(path + to)
            mutableDependencies.getOrPut(from) { sortedSetOf() }.add(to)
        }
    }

    private fun findPath(start: String, target: String): List<String>? {
        if (start == target) return listOf(start)
        val visited = mutableSetOf<String>()
        fun search(current: String): List<String>? {
            if (!visited.add(current)) return null
            mutableDependencies[current].orEmpty().sorted().forEach { next ->
                if (next == target) return listOf(current, next)
                search(next)?.let { return listOf(current) + it }
            }
            return null
        }
        return search(start)
    }

    private fun computeEffectiveFingerprint(producer: DexProducerNode) {
        val dependencyFingerprints = dependenciesOf(producer.stableId).associateWith { dependencyId ->
            mutableEffectiveFingerprints[dependencyId] ?: return
        }
        mutableEffectiveFingerprints[producer.stableId] = effectiveFingerprint(
            producer.metadata,
            dependencyFingerprints,
        )
    }

    private fun diagnosticSeverity(status: DexResolutionStatus): Int = when (status) {
        DexResolutionStatus.PENDING -> 0
        DexResolutionStatus.SUCCESS -> 1
        DexResolutionStatus.EXPECTED_FAILURE -> 2
        DexResolutionStatus.INCOMPLETE -> 3
        DexResolutionStatus.BLOCKED -> 4
        DexResolutionStatus.UNEXPECTED_FAILURE -> 5
    }
}

fun effectiveFingerprint(
    producer: DexProducerMetadata,
    dependencies: Map<String, String>,
): String = sha256(
    buildString {
        append("dex-cache-v2\n")
        append(producer.stableId).append('\n')
        append(producer.localFingerprint).append('\n')
        dependencies.toSortedMap().forEach { (id, fingerprint) ->
            append(id).append('=').append(fingerprint).append('\n')
        }
    }
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
