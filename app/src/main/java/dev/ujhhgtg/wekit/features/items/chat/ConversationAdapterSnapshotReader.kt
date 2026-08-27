package dev.ujhhgtg.wekit.features.items.chat

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.AbstractList

/**
 * Reads the conversation models from an adapter's backing list without routing every row through
 * BaseAdapter.getItem(). The resolved WeChat MVVM adapter keeps that list one object below the
 * adapter and wraps each conversation in a row model; both field hops are discovered once by
 * comparing a single row with the adapter's original getItem() result.
 */
internal class ConversationAdapterSnapshotReader {
    private data class AccessPlan(
        val holderField: Field?,
        val listField: Field,
        val itemField: Field?,
    ) {
        fun read(adapter: Any): List<Any?>? {
            val holder = holderField?.get(adapter) ?: adapter
            val rows = listField.get(holder) as? List<*> ?: return null
            return if (itemField == null) rows else object : AbstractList<Any?>() {
                override val size: Int get() = rows.size
                override fun get(index: Int): Any? = rows[index]?.let(itemField::get)
            }
        }
    }

    private data class Candidate(val holderField: Field?, val holder: Any, val listField: Field)

    private val plans = ConcurrentHashMap<Class<*>, AccessPlan>()

    fun read(adapter: Any, expectedCount: Int?, originalItemAt: (Int) -> Any?): List<Any?>? {
        plans[adapter.javaClass]?.let { plan ->
            runCatching { plan.read(adapter) }.getOrNull()
                ?.takeIf { expectedCount == null || it.size == expectedCount }
                ?.let { return it }
        }
        if (expectedCount != null && expectedCount <= 0) return emptyList()

        val sampleIndex = 0
        val expected = originalItemAt(sampleIndex)
        val plan = candidates(adapter).firstNotNullOfOrNull { candidate ->
            val rows = runCatching { candidate.listField.get(candidate.holder) as? List<*> }
                .getOrNull() ?: return@firstNotNullOfOrNull null
            if (rows.isEmpty() || expectedCount != null && rows.size != expectedCount) {
                return@firstNotNullOfOrNull null
            }
            val row = rows[sampleIndex]
            when {
                row === expected -> AccessPlan(candidate.holderField, candidate.listField, null)
                row == null -> null
                else -> instanceFields(row.javaClass).firstOrNull { field ->
                    runCatching { field.get(row) === expected }.getOrDefault(false)
                }?.let { itemField ->
                    AccessPlan(candidate.holderField, candidate.listField, itemField)
                }
            }
        }
        if (plan != null) {
            plans[adapter.javaClass] = plan
            runCatching { plan.read(adapter) }.getOrNull()
                ?.takeIf { expectedCount == null || it.size == expectedCount }
                ?.let { return it }
        }

        return null
    }

    private fun candidates(adapter: Any): Sequence<Candidate> = sequence {
        for (field in instanceFields(adapter.javaClass)) {
            val value = runCatching { field.get(adapter) }.getOrNull() ?: continue
            if (value is List<*>) yield(Candidate(null, adapter, field))
            if (isLeafType(value.javaClass)) continue
            for (nestedField in instanceFields(value.javaClass)) {
                if (List::class.java.isAssignableFrom(nestedField.type)) {
                    yield(Candidate(field, value, nestedField))
                }
            }
        }
    }

    private fun instanceFields(type: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                runCatching { field.isAccessible = true }.getOrElse { continue }
                yield(field)
            }
            current = current.superclass
        }
    }

    private fun isLeafType(type: Class<*>): Boolean =
        type.isArray || type.isEnum || type.name.startsWith("java.") || type.name.startsWith("kotlin.")
}
