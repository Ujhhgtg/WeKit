package dev.ujhhgtg.wekit.utils.monet

import kotlinx.serialization.Serializable

enum class MonetBubbleStyle { MODERN, CLASSIC, PRO }

/**
 * A WeChat resource targeted by the runtime table. The loader has no idmap, so [id] is the
 * authority; [qualifiers] and [specFlags] mirror WeChat's own entry so the loader's configs can
 * tie or beat every config WeChat would pick.
 */
@Serializable
data class MonetBinding(
    val id: Int,
    val type: String,
    val name: String,
    val qualifiers: List<String>,
    val specFlags: Int,
)

/**
 * A color whose value is WeChat's brand green with alpha (selection highlight, `Brand_Alpha_*`,
 * …). Such colors have no semantic role; they become the theme primary at the same alpha. A `null`
 * [nightAlpha] means the night value is not brand green and stays WeChat's.
 */
@Serializable
data class MonetTint(val binding: MonetBinding, val lightAlpha: Int, val nightAlpha: Int?)

/** Four-state checkbox selector; only its enabled, checked SVG is replaced. */
@Serializable
data class MonetCheckbox(
    val binding: MonetBinding,
    val disabledChecked: Int,
    val disabledUnchecked: Int,
    val unchecked: Int,
)

/** Semantic role → WeChat resource, cached per loaded WeChat resource set ([fingerprint]). */
@Serializable
data class MonetBindings(
    val fingerprint: String,
    /** WeChat's type string pool in table order (`typeId == index + 1`). */
    val typeNames: List<String>,
    val roles: Map<String, MonetBinding>,
    val unresolved: List<String>,
    val tints: List<MonetTint> = emptyList(),
    val checkboxes: List<MonetCheckbox> = emptyList(),
)

fun interface MonetDexEvidenceProvider {
    fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence>
}

data class MonetDexCandidate(val resourceId: Int, val type: String, val name: String)

data class MonetResourceDexEvidence(
    val resourceId: Int,
    val methods: List<MonetMethodDexEvidence>,
)

data class MonetMethodDexEvidence(
    val descriptor: String,
    val ownerPackage: String,
    val methodShape: String,
    val stableStrings: List<String>,
    val invokedMethodShapes: List<String>,
    val neighboringResourceIds: List<Int>,
    val fieldAccesses: List<MonetFieldAccessEvidence>,
)

data class MonetFieldAccessEvidence(val descriptor: String, val access: MonetFieldAccess)

enum class MonetFieldAccess { READ, WRITE }

enum class MonetResolveStage {
    LOADING_APKS,
    BUILDING_RESOURCE_GRAPH,
    RESOLVING_ROLES,
    BUILDING_PACKAGE,
}

data class MonetResolveProgress(
    val stage: MonetResolveStage,
    val detail: String,
    val completed: Int?,
    val total: Int?,
) {
    init {
        require(detail.isNotBlank())
        require((completed == null) == (total == null))
        if (completed != null && total != null) require(total > 0 && completed in 0..total)
    }
}
