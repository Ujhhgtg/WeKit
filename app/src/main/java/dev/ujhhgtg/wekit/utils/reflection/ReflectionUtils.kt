@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils.reflection

import java.lang.reflect.AccessibleObject

inline fun <T : AccessibleObject> T.makeAccessible(): T {
    this.isAccessible = true
    return this
}
