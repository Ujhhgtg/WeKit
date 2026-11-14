package dev.ujhhgtg.wekit.features.items.chat.panel

import kotlinx.serialization.Serializable

@Serializable
data class PanelCustomOrders(
    val packs: List<String> = emptyList(),
    val items: Map<String, List<String>> = emptyMap(),
)

fun customOrderIndex(order: List<String>?, key: String): Int {
    val index = order?.indexOf(key) ?: -1
    return if (index >= 0) index else Int.MAX_VALUE
}

fun normalizedCustomOrder(requested: List<String>, available: Collection<String>): List<String> {
    val availableSet = available.toHashSet()
    val retained = requested.distinct().filter { it in availableSet }
    val retainedSet = retained.toHashSet()
    return retained + available.filterNot { it in retainedSet }
}
