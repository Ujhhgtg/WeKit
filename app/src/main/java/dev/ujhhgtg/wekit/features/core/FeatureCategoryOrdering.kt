package dev.ujhhgtg.wekit.features.core

/**
 * Orders features inside one concrete settings category.
 *
 * Explicit orders form a leading cross-type group. Features without an explicit order keep the
 * default Switch-before-Clickable grouping, followed by any non-switch feature.
 */
fun featureCategoryComparator(
    nameComparator: Comparator<BaseFeature>,
): Comparator<BaseFeature> = Comparator { first, second ->
    val firstOrder = (first as? SwitchFeature)?.displayOrder
    val secondOrder = (second as? SwitchFeature)?.displayOrder
    when {
        firstOrder != null && secondOrder == null -> -1
        firstOrder == null && secondOrder != null -> 1
        firstOrder != null && secondOrder != null ->
            firstOrder.compareTo(secondOrder)
                .takeIf { it != 0 }
                ?: nameComparator.compare(first, second).takeIf { it != 0 }
                ?: first.technicalId.compareTo(second.technicalId)

        else -> featureTypeRank(first).compareTo(featureTypeRank(second))
            .takeIf { it != 0 }
            ?: nameComparator.compare(first, second).takeIf { it != 0 }
            ?: first.technicalId.compareTo(second.technicalId)
    }
}

private fun featureTypeRank(feature: BaseFeature): Int = when (feature) {
    is ClickableFeature -> 1
    is SwitchFeature -> 0
    else -> 2
}
