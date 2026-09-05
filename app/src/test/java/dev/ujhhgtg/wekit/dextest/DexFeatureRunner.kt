package dev.ujhhgtg.wekit.dextest

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.GeneratedMethodHashes
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.resolution.DexHostMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.dexkit.resolution.resolveAllDex
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionEvent
import dev.ujhhgtg.wekit.dexkit.resolution.resolveDexBatch
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.DexResolutionTestEntry
import org.luckypray.dexkit.DexKitBridge
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

internal fun runDexFeature(
    entry: DexResolutionTestEntry,
    dexKit: DexKitBridge,
    host: DexHostMetadata,
    classLoader: ClassLoader,
): DexTestFeatureReport {
    val started = TimeSource.Monotonic.markNow()
    val feature = try {
        loadFeature(entry, classLoader)
    } catch (error: Throwable) {
        error.rethrowIfFatal()
        return DexTestFeatureReport(
            className = entry.className,
            displayName = entry.className,
            methodHash = "",
            outcome = DexTestFeatureOutcome.INITIALIZATION_FAILURE,
            elapsedMillis = started.elapsedNow().inWholeMilliseconds,
            featureError = error.toDexTestError(),
        )
    }
    return runDexFeature(feature, entry, dexKit, host, started)
}

internal fun runDexFeature(
    feature: BaseFeature,
    entry: DexResolutionTestEntry,
    dexKit: DexKitBridge,
    host: DexHostMetadata,
    started: TimeMark = TimeSource.Monotonic.markNow(),
): DexTestFeatureReport {
    val resolver = feature as? IResolveDex
        ?: return DexTestFeatureReport(
            className = entry.className,
            displayName = displayName(feature),
            technicalId = feature.technicalId,
            methodHash = GeneratedMethodHashes.HASHES[feature.technicalId].orEmpty(),
            outcome = DexTestFeatureOutcome.INITIALIZATION_FAILURE,
            elapsedMillis = started.elapsedNow().inWholeMilliseconds,
            featureError = DexTestError(message = "${entry.className} does not implement IResolveDex"),
        )

    feature.dexDelegates.forEach(BaseDexDelegate::resetForResolution)

    val error = runCatching {
        resolver.resolveAllDex(dexKit, host)
    }.exceptionOrNull()
    error?.rethrowIfFatal()

    return dexFeatureReport(feature, entry, error, started.elapsedNow().inWholeMilliseconds)
}

private fun dexFeatureReport(
    feature: BaseFeature,
    entry: DexResolutionTestEntry,
    error: Throwable?,
    elapsedMillis: Long,
): DexTestFeatureReport {

    val pending = feature.dexDelegates.filter { it.diagnostic.status == DexResolutionStatus.PENDING }
    if (error == null) {
        pending.forEach(BaseDexDelegate::markIncomplete)
    } else {
        val failingKey = feature.dexDelegates
            .firstOrNull { it.diagnostic.status == DexResolutionStatus.UNEXPECTED_FAILURE }
            ?.key
            ?: "${entry.className}#resolveDex"
        pending.forEach { it.markBlocked(failingKey) }
    }

    val delegates = feature.dexDelegates.map { delegate ->
        val diagnostic = delegate.diagnostic
        DexTestDelegateReport(
            key = delegate.key,
            status = diagnostic.status,
            descriptor = diagnostic.descriptor ?: delegate.getDescriptorString(),
            isPlaceholder = delegate.isPlaceholder,
            message = diagnostic.message,
            exceptionType = diagnostic.exceptionType,
            stackTrace = diagnostic.stackTrace,
            blockedBy = diagnostic.blockedBy,
        )
    }
    return DexTestFeatureReport(
        className = entry.className,
        displayName = displayName(feature),
        technicalId = feature.technicalId,
        methodHash = GeneratedMethodHashes.HASHES[feature.technicalId].orEmpty(),
        outcome = featureOutcome(delegates, error),
        elapsedMillis = elapsedMillis,
        delegates = delegates,
        featureError = error?.toDexTestError(),
    )
}

internal fun runDexBatchFeatures(
    entries: List<DexResolutionTestEntry>,
    dexKit: DexKitBridge,
    host: DexHostMetadata,
    classLoader: ClassLoader,
    workerCount: Int,
): List<DexTestFeatureReport> {
    val reports = mutableMapOf<DexResolutionTestEntry, DexTestFeatureReport>()
    val loaded = entries.mapNotNull { entry ->
        val started = TimeSource.Monotonic.markNow()
        try {
            val feature = loadFeature(entry, classLoader)
            check(feature is IResolveDex) { "${entry.className} does not implement IResolveDex" }
            entry to feature
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            reports[entry] = DexTestFeatureReport(
                className = entry.className,
                displayName = entry.className,
                methodHash = "",
                outcome = DexTestFeatureOutcome.INITIALIZATION_FAILURE,
                elapsedMillis = started.elapsedNow().inWholeMilliseconds,
                featureError = error.toDexTestError(),
            )
            null
        }
    }
    // Initialize and reset all roots before workers start; never reset a dependency
    // while another worker can be reading it. Build reports only after the batch joins.
    loaded.forEach { (_, feature) -> feature.dexDelegates.forEach(BaseDexDelegate::resetForResolution) }
    val finished = mutableMapOf<IResolveDex, DexResolutionEvent.Finished>()
    runBlocking {
        resolveDexBatch(loaded.map { it.second as IResolveDex }, dexKit, host, workerCount) { event ->
            if (event is DexResolutionEvent.Finished) finished[event.item] = event
        }
    }
    loaded.forEach { (entry, feature) ->
        val result = finished.getValue(feature as IResolveDex)
        reports[entry] = dexFeatureReport(feature, entry, result.error, result.elapsedMillis)
    }
    return entries.map(reports::getValue)
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
            it.status == DexResolutionStatus.INCOMPLETE
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
