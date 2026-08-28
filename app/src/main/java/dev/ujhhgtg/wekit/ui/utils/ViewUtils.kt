@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.ui.utils

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter

/**
 * Lazily traverses the View hierarchy using a Pre-order Depth-First Search (DFS).
 * Uses an iterative stack to avoid the performance penalty of recursive yieldAll.
 */
val View.allViews: Sequence<View>
    get() = sequence {
        val stack = mutableListOf(this@allViews)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.lastIndex)
            yield(current)
            if (current is ViewGroup) {
                // Push children in reverse order to maintain standard left-to-right DFS
                for (i in current.childCount - 1 downTo 0) {
                    stack.add(current.getChildAt(i))
                }
            }
        }
    }

fun View.findViewsByClassName(className: String): Sequence<View> {
    return allViews
        .filter { it.javaClass.name == className || it.javaClass.simpleName == className }
}

fun View.findViewByClassName(className: String): View? {
    return findViewsByClassName(className).firstOrNull()
}

fun View?.findViewsWhich(predicate: (View) -> Boolean): Sequence<View> {
    if (this == null) return emptySequence()
    return this.allViews
        .filter(predicate)
}

fun View?.findViewWhich(predicate: (View) -> Boolean): View? {
    return findViewsWhich(predicate).firstOrNull()
}

fun View.findViewByChildIndexes(vararg indexes: Int): View? {
    var current: View = this
    for (index in indexes) {
        current = (current as? ViewGroup)?.getChildAt(index) ?: return null
    }
    return current
}

fun ListAdapter.iterator(parent: ViewGroup): Iterator<View> =
    object : Iterator<View> {

        private var index = 0
        override fun hasNext() = index < count
        override fun next(): View = getView(index++, null, parent)
    }

fun ListAdapter.iterable(parent: ViewGroup): Iterable<View> =
    Iterable { iterator(parent) }

inline val Activity.rootView: ViewGroup
    get() = findViewById(android.R.id.content)

inline fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

val View.idString
    get() = if (this.id != View.NO_ID) {
        runCatching { this.resources.getResourceEntryName(this.id) }.getOrDefault(null)
    } else null

fun View.removeSelf() {
    (parent as? ViewGroup)?.removeView(this)
}
