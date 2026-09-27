package dev.ujhhgtg.wekit.features.items.system.predictive_back

import android.app.Dialog
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import java.util.WeakHashMap

/**
 * Keeps legacy BACK semantics for WeChat dialogs shown from an activity this feature opted in.
 *
 * Dialogs inherit the opt-in from their Activity context. The platform then invokes
 * `Dialog.onBackPressed()` (i.e. `cancel()`) directly, so `DialogInterface.OnKeyListener`s and key
 * overrides never see BACK. WeChat's common bottom sheet relies on one to pop nested sub-pages and to
 * refuse dismissal. Such dialogs get a callback above the default one that replays the legacy chain;
 * plain dialogs keep the platform's own dialog-close animation.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object DialogBackBridge {

    private val callbacks = WeakHashMap<Dialog, OnBackInvokedCallback>()

    private val keyHandlerNames = setOf("onKeyDown", "onKeyUp", "dispatchKeyEvent")

    /** Registers the replay callback once, if [dialog] needs legacy key handling. */
    fun onDialogShowing(dialog: Dialog) {
        if (dialog in callbacks || !needsLegacyBack(dialog)) return
        val callback = OnBackInvokedCallback {
            val window = dialog.window!!
            if (hideImeIfVisible(window)) return@OnBackInvokedCallback
            @Suppress("DEPRECATION")
            replayLegacyBack(window) { dialog.onBackPressed() }
        }
        // The window's proxy dispatcher keeps it across re-shows of the same dialog.
        dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        callbacks[dialog] = callback
    }

    /** Removes every replay callback (feature disabled). */
    fun clear() {
        callbacks.forEach { (dialog, callback) -> dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
        callbacks.clear()
    }

    private fun needsLegacyBack(dialog: Dialog): Boolean {
        if (!PredictiveBackGestures.isOptedIn(dialog.context)) return false
        if (!isHostClass(dialog.javaClass)) return false
        val keyListener = dialog.reflekt().firstField {
            name = "mOnKeyListener"
            superclass()
        }.get()
        return keyListener != null || overridesKeyHandling(dialog.javaClass)
    }

    /** Whether a WeChat class between [clazz] and [Dialog] declares a key handler. */
    private fun overridesKeyHandling(clazz: Class<*>): Boolean {
        var current: Class<*> = clazz
        while (current != Dialog::class.java) {
            if (isHostClass(current) && current.reflekt().firstMethodOrNull { name { it in keyHandlerNames } } != null) {
                return true
            }
            current = current.superclass!!
        }
        return false
    }

    /** Framework, AndroidX and WeKit dialogs already handle predictive back themselves. */
    private fun isHostClass(clazz: Class<*>): Boolean =
        clazz.classLoader !== Dialog::class.java.classLoader &&
            clazz.classLoader !== ClassLoaders.MODULE &&
            !clazz.name.startsWith("androidx.")
}
