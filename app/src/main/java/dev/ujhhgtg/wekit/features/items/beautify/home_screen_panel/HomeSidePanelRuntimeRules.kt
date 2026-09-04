package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import java.security.MessageDigest

data class HomeSidePanelRuntimeDelta(
    val activate: Set<String> = emptySet(),
    val deactivate: Set<String> = emptySet(),
    val reconfigure: Set<String> = emptySet(),
)

enum class HomeSidePanelLiveCachePolicy(
    val importLegacyCaches: Boolean,
    val persistLiveCaches: Boolean,
) {
    STORED(importLegacyCaches = false, persistLiveCaches = true),
    MIGRATED(importLegacyCaches = true, persistLiveCaches = true),
    FALLBACK(importLegacyCaches = false, persistLiveCaches = false),
}

fun homeSidePanelLiveCachePolicy(
    load: HomeSidePanelLayoutLoad,
): HomeSidePanelLiveCachePolicy = when (load) {
    is HomeSidePanelLayoutLoad.Stored -> HomeSidePanelLiveCachePolicy.STORED
    is HomeSidePanelLayoutLoad.Migrated -> HomeSidePanelLiveCachePolicy.MIGRATED
    is HomeSidePanelLayoutLoad.Fallback -> HomeSidePanelLiveCachePolicy.FALLBACK
}

fun homeSidePanelRuntimeDelta(
    old: HomeSidePanelLayout?,
    new: HomeSidePanelLayout,
): HomeSidePanelRuntimeDelta {
    val oldCards = old?.cards.orEmpty().runtimeCardsById()
    val newCards = new.cards.runtimeCardsById()
    val sharedIds = oldCards.keys intersect newCards.keys
    return HomeSidePanelRuntimeDelta(
        activate = newCards.keys - oldCards.keys,
        deactivate = oldCards.keys - newCards.keys,
        reconfigure = sharedIds.filterTo(linkedSetOf()) { id -> oldCards[id] != newCards[id] },
    )
}

fun weatherCacheFingerprint(city: WeatherCity): String =
    sha256("weather|${city.cityNum}")

fun hitokotoCacheFingerprint(settings: HitokotoSettings): String = sha256(
    buildString {
        append("hitokoto|")
        append(settings.categories.sorted().joinToString(","))
        append('|')
        append(settings.minLength?.toString().orEmpty())
        append('|')
        append(settings.maxLength?.toString().orEmpty())
    },
)

fun <T> retainCommittedCardEntries(
    entries: Map<String, T>,
    committed: HomeSidePanelLayout,
): Map<String, T> {
    val committedIds = committed.cards.mapTo(hashSetOf(), HomeSidePanelCardConfig::id)
    return entries.filterKeys { it in committedIds }
}

private fun List<HomeSidePanelCardConfig>.runtimeCardsById(): Map<String, HomeSidePanelCardConfig> =
    filter { it.hasRuntimeState() }.associateBy(HomeSidePanelCardConfig::id)

fun HomeSidePanelCardConfig.hasRuntimeState(): Boolean = when (this) {
    is WeatherCardConfig,
    is WalletCardConfig,
    is HitokotoCardConfig,
    -> true

    else -> false
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
