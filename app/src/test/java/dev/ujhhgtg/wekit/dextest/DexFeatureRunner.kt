package dev.ujhhgtg.wekit.dextest

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.resolution.DexHostMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexNodeResult
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionCoordinator
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionRegistry
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.DexResolutionTestEntry
import java.util.TreeSet
import org.luckypray.dexkit.DexKitBridge
import kotlin.time.TimeSource

internal sealed class LoadedDexOwner {
    abstract val entry: DexResolutionTestEntry
    abstract val elapsedMillis: Long

    data class Ready(
        override val entry: DexResolutionTestEntry,
        val owner: BaseFeature,
        override val elapsedMillis: Long,
    ) : LoadedDexOwner() {
        val resolver: IResolveDex = owner as IResolveDex
    }

    data class Failed(
        override val entry: DexResolutionTestEntry,
        val error: DexTestError,
        override val elapsedMillis: Long,
    ) : LoadedDexOwner()
}

internal fun loadDexOwners(
    entries: List<DexResolutionTestEntry>,
    classLoader: ClassLoader,
): List<LoadedDexOwner> = entries.map { entry ->
    val started = TimeSource.Monotonic.markNow()
    try {
        val owner = loadFeature(entry, classLoader)
        require(owner is IResolveDex) { "${entry.className} does not implement IResolveDex" }
        LoadedDexOwner.Ready(
            entry = entry,
            owner = owner,
            elapsedMillis = started.elapsedNow().inWholeMilliseconds,
        )
    } catch (error: Throwable) {
        error.rethrowIfFatal()
        LoadedDexOwner.Failed(
            entry = entry,
            error = error.toDexTestError(),
            elapsedMillis = started.elapsedNow().inWholeMilliseconds,
        )
    }
}

internal fun resolveDexFeatureReports(
    loadedOwners: List<LoadedDexOwner>,
    selectedEntries: List<DexResolutionTestEntry>,
    dexKit: DexKitBridge,
    host: DexHostMetadata,
    registryFactory: (List<IResolveDex>) -> DexResolutionRegistry = DexResolutionRegistry::create,
    coordinatorFactory: (DexResolutionRegistry, DexKitBridge, DexHostMetadata) -> DexResolutionCoordinator =
        ::DexResolutionCoordinator,
): List<DexTestFeatureReport> {
    val loadedById = loadedOwners.associateBy { it.entry.className }
    val selected = selectedEntries.map { entry ->
        requireNotNull(loadedById[entry.className]) {
            "Selected Dex owner was not loaded: ${entry.className}"
        }
    }
    val readyOwners = loadedOwners.filterIsInstance<LoadedDexOwner.Ready>()
    readyOwners.flatMap { it.owner.dexDelegates }.forEach(BaseDexDelegate::resetForDexTest)

    val registry = registryFactory(readyOwners.map(LoadedDexOwner.Ready::resolver))
    val coordinator = coordinatorFactory(registry, dexKit, host)
    val selectedReady = selected.filterIsInstance<LoadedDexOwner.Ready>()
    val reportedOwnerIds = selected.mapTo(sortedSetOf()) { it.entry.className }
    val completedOwnerIds = mutableSetOf<String>()

    if (selectedReady.isNotEmpty()) {
        coordinator.resolveOwners(selectedReady.map(LoadedDexOwner.Ready::resolver))
        completedOwnerIds += selectedReady.map { it.entry.className }
    }

    while (true) {
        val dependencyOwnerIds = dependencyOwnerClosure(registry, coordinator, reportedOwnerIds)
        val added = reportedOwnerIds.addAll(dependencyOwnerIds)
        val pendingOwners = reportedOwnerIds
            .asSequence()
            .filterNot(completedOwnerIds::contains)
            .mapNotNull { ownerId -> loadedById[ownerId] as? LoadedDexOwner.Ready }
            .sortedBy { it.entry.className }
            .toList()
        if (pendingOwners.isEmpty()) {
            if (!added) break
            continue
        }
        coordinator.resolveOwners(pendingOwners.map(LoadedDexOwner.Ready::resolver))
        completedOwnerIds += pendingOwners.map { it.entry.className }
    }

    return reportedOwnerIds.map { ownerId ->
        when (val loaded = loadedById.getValue(ownerId)) {
            is LoadedDexOwner.Ready -> buildDexFeatureReport(
                owner = loaded,
                coordinator = coordinator,
                elapsedMillis = loaded.elapsedMillis,
            )
            is LoadedDexOwner.Failed -> DexTestFeatureReport(
                className = loaded.entry.className,
                displayName = loaded.entry.className,
                outcome = DexTestFeatureOutcome.INITIALIZATION_FAILURE,
                elapsedMillis = loaded.elapsedMillis,
                featureError = loaded.error,
            )
        }
    }
}

internal fun buildDexFeatureReport(
    owner: LoadedDexOwner.Ready,
    coordinator: DexResolutionCoordinator,
    elapsedMillis: Long,
): DexTestFeatureReport {
    val registry = coordinator.registry
    val results = owner.owner.dexDelegates
        .map(registry::producerOf)
        .distinctBy { it.stableId }
        .associate { producer ->
            require(producer.outputs.none { it.diagnostic.status == DexResolutionStatus.PENDING }) {
                "Dex producer was not settled before report assembly: ${producer.stableId}"
            }
            producer.stableId to coordinator.resolveDelegate(producer.outputs.first())
        }
    val error = results.values.filterIsInstance<DexNodeResult.Failed>().firstOrNull()?.error
    error?.rethrowIfFatal()
    val delegates = owner.owner.dexDelegates.sortedBy { it.stableId }.map { delegate ->
        val diagnostic = delegate.diagnostic
        val producer = registry.producerOf(delegate)
        DexTestDelegateReport(
            id = delegate.stableId,
            status = diagnostic.status,
            descriptor = diagnostic.descriptor ?: delegate.getDescriptorString(),
            isPlaceholder = delegate.isPlaceholder,
            producerFingerprint = producer.metadata.localFingerprint,
            effectiveFingerprint = coordinator.effectiveFingerprintByProducer[producer.stableId],
            dependencies = coordinator.dependenciesOf(producer.stableId).sorted(),
            message = diagnostic.message,
            exceptionType = diagnostic.exceptionType,
            stackTrace = diagnostic.stackTrace,
            blockedBy = diagnostic.blockedBy,
        )
    }
    return DexTestFeatureReport(
        className = owner.entry.className,
        displayName = displayName(owner.owner),
        outcome = featureOutcome(delegates, error),
        elapsedMillis = elapsedMillis,
        delegates = delegates,
        featureError = error?.toDexTestError(),
    )
}

private fun dependencyOwnerClosure(
    registry: DexResolutionRegistry,
    coordinator: DexResolutionCoordinator,
    ownerIds: Set<String>,
): Set<String> {
    val pending = TreeSet<String>()
    ownerIds.forEach { ownerId ->
        registry.producersById.values
            .filter { it.metadata.ownerClassName == ownerId }
            .mapTo(pending) { it.stableId }
    }
    val visited = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
        val producerId = pending.first().also(pending::remove)
        if (!visited.add(producerId)) continue
        coordinator.dependenciesOf(producerId).forEach(pending::add)
    }
    return visited.mapTo(sortedSetOf()) { producerId ->
        registry.producersById.getValue(producerId).metadata.ownerClassName
    }
}

private fun loadFeature(entry: DexResolutionTestEntry, classLoader: ClassLoader): BaseFeature {
    val clazz = Class.forName(entry.className, true, classLoader)
    val instance = clazz.getField("INSTANCE").get(null)
    return instance as? BaseFeature
        ?: error("${entry.className} INSTANCE is not a BaseFeature")
}

private fun featureOutcome(
    delegates: List<DexTestDelegateReport>,
    error: Throwable?,
): DexTestFeatureOutcome = when {
    error != null -> DexTestFeatureOutcome.FAIL
    delegates.any {
        it.status == DexResolutionStatus.UNEXPECTED_FAILURE ||
            it.status == DexResolutionStatus.BLOCKED ||
            it.status == DexResolutionStatus.INCOMPLETE ||
            it.status == DexResolutionStatus.PENDING
    } -> DexTestFeatureOutcome.FAIL
    delegates.any { it.status == DexResolutionStatus.EXPECTED_FAILURE } -> DexTestFeatureOutcome.PASS_WITH_EXPECTED_FAILURES
    else -> DexTestFeatureOutcome.PASS
}

private fun displayName(feature: BaseFeature) =
    "${feature.categoryIds.joinToString(",")}/${feature.technicalId}"

internal fun Throwable.toDexTestError() = DexTestError(
    message = message ?: cause?.message,
    exceptionType = javaClass.name,
    stackTrace = stackTraceToString(),
)

private fun Throwable.rethrowIfFatal() {
    if (this is VirtualMachineError || this is ThreadDeath) throw this
}
