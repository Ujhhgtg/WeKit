package dev.ujhhgtg.wekit.features.core

import android.content.Context
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.cache.DexItemState

/**
 * Why a feature cannot be started or enabled. Each error is shown as one line under its
 * description.
 */
sealed interface FeatureError {
    data object DexIncomplete : FeatureError

    data class DexFailed(val message: String) : FeatureError

    data class DependencyDexIncomplete(val dependency: BaseFeature) : FeatureError

    data class DependencyDexFailed(val dependency: BaseFeature, val message: String) : FeatureError

    data class Declared(@StringRes val messageRes: Int) : FeatureError

    fun localizedMessage(context: Context): String =
        when (this) {
            DexIncomplete -> context.getString(R.string.feature_error_dex_incomplete)
            is DexFailed -> context.getString(R.string.feature_error_dex_failed)
            is DependencyDexIncomplete ->
                context.getString(
                    R.string.feature_error_dependency_dex_incomplete,
                    dependency.localizedName(context),
                )
            is DependencyDexFailed ->
                context.getString(
                    R.string.feature_error_dependency_dex_failed,
                    dependency.localizedName(context),
                )
            is Declared -> context.getString(messageRes)
        }

    companion object {
        /**
         * Own Dex state, then the Dex state of every transitive dependency, then declared
         * restrictions.
         */
        fun of(feature: BaseFeature): List<FeatureError> = buildList {
            when (val state = feature.dexState) {
                DexItemState.Valid -> {}
                DexItemState.NeedsResolution -> add(DexIncomplete)
                is DexItemState.Failed -> add(DexFailed(state.message))
            }
            FeatureDependencies.transitive(feature).forEach { dependency ->
                when (val state = dependency.dexState) {
                    DexItemState.Valid -> {}
                    DexItemState.NeedsResolution -> add(DependencyDexIncomplete(dependency))
                    is DexItemState.Failed -> add(DependencyDexFailed(dependency, state.message))
                }
            }
            feature.declaredErrors.forEach { add(Declared(it)) }
        }
    }
}
