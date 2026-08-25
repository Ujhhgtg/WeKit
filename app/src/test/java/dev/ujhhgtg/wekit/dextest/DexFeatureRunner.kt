package dev.ujhhgtg.wekit.dextest

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.GeneratedMethodHashes
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.resolution.DexHostMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.dexkit.resolution.resolveAllDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.DexResolutionTestEntry
import org.luckypray.dexkit.DexKitBridge
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
            methodHash = GeneratedMethodHashes.HASHES[entry.className].orEmpty(),
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
            methodHash = GeneratedMethodHashes.HASHES[entry.className].orEmpty(),
            outcome = DexTestFeatureOutcome.INITIALIZATION_FAILURE,
            elapsedMillis = started.elapsedNow().inWholeMilliseconds,
            featureError = DexTestError(message = "${entry.className} does not implement IResolveDex"),
        )

    feature.dexDelegates.forEach(BaseDexDelegate::resetForDexTest)

    val error = runCatching {
        resolver.resolveAllDex(dexKit, host)
    }.exceptionOrNull()
    error?.rethrowIfFatal()

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
        methodHash = GeneratedMethodHashes.HASHES[entry.className].orEmpty(),
        outcome = featureOutcome(delegates, error),
        elapsedMillis = started.elapsedNow().inWholeMilliseconds,
        delegates = delegates,
        featureError = error?.toDexTestError(),
    )
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
