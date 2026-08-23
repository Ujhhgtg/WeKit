package dev.ujhhgtg.wekit.activity.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.features.core.NewFeatures
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs

object FeatureCategoryState {
    var revision by mutableIntStateOf(0)
        private set

    val newItems: List<BaseFeature> by lazy {
        val visibleCategories = FEATURE_CATEGORIES.mapTo(mutableSetOf()) { it.id }
        FeaturesProvider.ALL_FEATURES.associateBy { it.technicalId }.values
            .mapNotNull { item ->
                FeaturesProvider.SOURCE_KEY_BY_FEATURE[item]
                    ?.let(NewFeatures.ADDED_AT_BY_SOURCE_KEY::get)
                    ?.let { addedAt -> item to addedAt }
            }
            .filter { (item, _) -> item.categoryIds.any { it in visibleCategories } }
            .sortedWith(
                compareByDescending<Pair<BaseFeature, Long>> { it.second }
                    .thenBy { it.first.technicalId },
            )
            .map { (item, _) -> item }
    }

    fun enabledItems(): List<SwitchFeature> =
        FeaturesProvider.ALL_FEATURES
            .associateBy { it.technicalId }
            .values
            .filterIsInstance<SwitchFeature>()
            .filter { feature ->
                WePrefs.getBoolOrDef(feature.technicalId, feature.defaultEnabled) ||
                    (feature is ClickableFeature && feature.alwaysEnabled)
            }
            .sortedBy { it.technicalId }

    fun notifyToggleChanged() {
        revision++
    }
}
