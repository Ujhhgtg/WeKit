package dev.ujhhgtg.wekit.ui.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.utils.WeLogger

fun <T : View> View.findViewByClassName(className: String): T? {
    if (this.javaClass.name == className || this.javaClass.simpleName == className) {
        @Suppress("UNCHECKED_CAST")
        return this as T
    }

    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val result = getChildAt(i).findViewByClassName<T>(className)
            if (result != null) return result
        }
    }

    return null
}

fun <T : View> View.findViewsByClassName(className: String): List<T> {
    val results = mutableListOf<T>()

    if (this.javaClass.name == className || this.javaClass.simpleName == className) {
        @Suppress("UNCHECKED_CAST")
        results.add(this as T)
    }

    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            results.addAll(getChildAt(i).findViewsByClassName(className))
        }
    }

    return results
}

fun <T : View> View?.findViewWhich(predicate: (View) -> Boolean): T? {
    if (this == null) return null

    if (predicate(this)) {
        @Suppress("UNCHECKED_CAST")
        return this as T
    }

    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val result = getChildAt(i).findViewWhich<T>(predicate)
            if (result != null) return result
        }
    }

    return null
}

fun <T : View> View?.findViewsWhich(predicate: (View) -> Boolean): List<T> {
    val results = mutableListOf<T>()

    if (this == null) return results

    if (predicate(this)) {
        @Suppress("UNCHECKED_CAST")
        results.add(this as T)
    }

    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            results.addAll(getChildAt(i).findViewsWhich(predicate))
        }
    }

    return results
}

fun <T : View> View.findViewByChildIndexes(vararg indexes: Int): T? {
    var current: View = this
    for (index in indexes) {
        current = (current as? ViewGroup)?.getChildAt(index) ?: return null
    }
    @Suppress("UNCHECKED_CAST")
    return current as? T
}

fun debugViewTree(view: View, connector: String = "", indent: String = "") {
    val idStr = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("UNKNOWN_ID")
    } else "NO_ID"
    WeLogger.d(
        nameOf(::debugViewTree),
        "$indent$connector${view.javaClass.name} [ID: $idStr / ${view.id}] [TAG: ${view.tag?.javaClass?.name ?: "null"}]"
    )
    if (view is ViewGroup) {
        val children = (0 until view.childCount).mapNotNull { view.getChildAt(it) }
        children.forEachIndexed { i, child ->
            val isLast = i == children.lastIndex
            val childConnector = if (isLast) "└─ " else "├─ "
            val childIndent =
                indent + if (connector.isEmpty()) "" else if (connector.startsWith("└")) "   " else "│  "
            debugViewTree(child, childConnector, childIndent)
        }
    }
}

fun ListAdapter.iterator(parent: ViewGroup): Iterator<View> =
    object : Iterator<View> {

        private var index = 0
        override fun hasNext() = index < count
        override fun next(): View {
            index++
            return getView(index, null, parent)
        }
    }

fun ListAdapter.iterable(parent: ViewGroup): Iterable<View> =
    Iterable { iterator(parent) }

val Activity.rootView: View
    get() = this.findViewById(android.R.id.content)
