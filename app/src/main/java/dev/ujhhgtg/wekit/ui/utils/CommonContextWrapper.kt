package dev.ujhhgtg.wekit.ui.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders

class CommonContextWrapper(
    val base: Context,
    internal val windowContext: Context? = base.resolveWindowContext(),
) : ContextThemeWrapper(base, base.theme) {

    init {
        ResourcesInjector.injectModuleRes(resources)
    }

    override fun getClassLoader(): ClassLoader = ClassLoaders.MODULE

    // A `createConfigurationContext`-derived context has no window token, so showing a Dialog
    // with it throws BadTokenException. Delegate WINDOW_SERVICE to the nearest Activity (if any)
    // so dialogs opened with such a context still get a valid token.
    override fun getSystemService(name: String): Any? {
        if (name == WINDOW_SERVICE) {
            windowContext?.let { return it.getSystemService(name) }
        }
        return super.getSystemService(name)
    }
}

/**
 * Finds the nearest [Activity] reachable through [ContextWrapper.baseContext], or the window
 * context already carried by an outer [CommonContextWrapper]. Returns null when none exists.
 */
internal fun Context.resolveWindowContext(): Context? {
    var current: Context? = this
    while (current != null) {
        when (current) {
            is Activity -> return current
            is CommonContextWrapper -> current.windowContext?.let { return it }
        }
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
