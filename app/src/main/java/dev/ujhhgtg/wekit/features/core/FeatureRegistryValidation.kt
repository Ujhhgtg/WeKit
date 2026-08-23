package dev.ujhhgtg.wekit.features.core

internal fun validateFeatures(features: List<BaseFeature>): List<BaseFeature> {
    features.forEach { feature ->
        require(feature.technicalId.isNotEmpty()) {
            "Feature ${feature.javaClass.name} has an empty technical ID"
        }
        require(feature.categoryIds.isNotEmpty()) {
            "Feature ${feature.javaClass.name} has no categories"
        }
        val unknownCategories = feature.categoryIds.filterNot(FeatureCategoryIds.ALL::contains)
        require(unknownCategories.isEmpty()) {
            "Feature ${feature.javaClass.name} has unknown categories: $unknownCategories"
        }
    }
    val duplicates = features.groupBy(BaseFeature::technicalId)
        .filterValues { it.size > 1 }
    require(duplicates.isEmpty()) {
        duplicates.entries.joinToString(", ") { (technicalId, matchingFeatures) ->
            "Duplicate Feature technical ID '$technicalId': " +
                matchingFeatures.joinToString { it.javaClass.name }
        }
    }
    return features
}
