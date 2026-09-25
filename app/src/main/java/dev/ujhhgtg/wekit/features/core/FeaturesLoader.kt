package dev.ujhhgtg.wekit.features.core

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.dexkit.cache.DexItemState
import dev.ujhhgtg.wekit.features.items.system.SafeMode
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.DexResolutionMode
import dev.ujhhgtg.wekit.ui.content.DexResolutionPrompt
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_FEATURES
        allFeatures.filterIsInstance<SwitchFeature>().forEach(SwitchFeature::loadPersistedState)

        val safeMode = SafeMode.isEnabled
        val featuresToStart = if (safeMode) {
            allFeatures.filterIsInstance<ApiFeature>()
        } else {
            allFeatures
        }
        if (safeMode) {
            WeLogger.i(
                TAG,
                "safe mode active: loading only ${featuresToStart.size} ApiFeature(s), " +
                    "skipping ${allFeatures.size - featuresToStart.size} feature(s)",
            )
        }

        // States are loaded for every Dex feature so settings can show errors even for features
        // that SafeMode keeps from starting; only features about to start can request resolution.
        allFeatures.filterIsInstance<IResolveDex>().forEach(::loadDexState)
        val needsResolution = featuresToStart
            .filterIsInstance<IResolveDex>()
            .filter { (it as BaseFeature).dexState == DexItemState.NeedsResolution }

        if (needsResolution.isNotEmpty()) {
            // Starting any feature now could run it against a missing dependency; the resolution
            // dialog restarts WeChat once the caches are rebuilt, and every process starts clean.
            WeLogger.i(TAG, "${needsResolution.size} item(s) need Dex resolution; not starting any feature")
            if (TargetProcesses.isInMain) {
                DexResolutionPrompt.showWhenLauncherReady(needsResolution, DexResolutionMode.Startup)
            }
            return
        }

        val elapsed = measureTime {
            featuresToStart.forEach { feature ->
                val errors = feature.errors
                if (errors.isNotEmpty()) {
                    WeLogger.w(TAG, "skipping ${feature.technicalPath}: $errors")
                    return@forEach
                }

                feature.startup()
            }
        }
        WeLogger.i(TAG, "loading all features took $elapsed")

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            val context = LocalizedContextFactory.create(
                HostInfo.application,
                WeKitLocaleController.resolvedLocale,
                LocaleResourceMode.InjectedHost,
            )
            showToast(context, context.getString(R.string.noncompose_features_loaded))
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * Reads the persisted state of [item] and, when valid, restores its delegates from the cache.
     * A cache that cannot be restored is deleted, and the item needs resolution again.
     */
    private fun loadDexState(item: IResolveDex) {
        val feature = item as BaseFeature
        val path = feature.technicalPath
        val state = DexCacheManager.stateOf(item)
        feature.dexState = state
        when (state) {
            DexItemState.Valid -> try {
                val missingKeys = item.loadFromCache(DexCacheManager.loadItemCache(item)!!)
                check(missingKeys.isEmpty()) { "cache lost keys while loading: $missingKeys" }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache(feature.technicalId) }
                feature.dexState = DexItemState.NeedsResolution
            }

            DexItemState.NeedsResolution -> WeLogger.w(TAG, "$path needs Dex resolution")
            is DexItemState.Failed -> WeLogger.w(TAG, "$path has a recorded Dex failure: ${state.message}")
        }
    }
}
