package dev.ujhhgtg.wekit.ui.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import dev.ujhhgtg.wekit.utils.HostInfo

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

inline val Activity.rootView: ViewGroup
    get() = this.findViewById(android.R.id.content)

@Suppress("NOTHING_TO_INLINE")
inline fun Int.dpToPx(): Int =
    (this * HostInfo.application.resources.displayMetrics.density).toInt()
