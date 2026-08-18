package dev.ujhhgtg.wekit.features.items.system.agent

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide foreground/background tracker for the WeChat host, built on
 * [Application.ActivityLifecycleCallbacks] (there is no such global signal elsewhere in the app).
 *
 * "Foreground" = at least one host Activity is started (between onStart and onStop). The started
 * count is the standard, orientation-change-safe way to detect this. Transitions are reported only
 * when the boolean flips. Supports multiple listeners ([addListener]) plus a single-listener
 * accessor ([onChanged]) for [WeAgentOverlayController], so the WeAgent ball and the pet overlay
 * can observe the same transitions without conflict.
 */
object WeChatForegroundTracker {

    @Volatile
    var isForeground = false
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * Registers a foreground ↔ background transition listener. Listeners are additive —
     * the same lambda added twice fires twice (callers should manage idempotency themselves
     * or call [removeListener]).
     */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    /** Removes a previously added listener. */
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Backwards-compatible single-listener accessor (kept with overwrite semantics for
     * [dev.ujhhgtg.wekit.features.items.system.agent.WeAgentOverlayController]). Independent
     * from [addListener], so the WeAgent ball and the pet overlay can observe transitions
     * without stepping on each other.
     */
    var onChanged: ((Boolean) -> Unit)? = null

    private var startedCount = 0
    private var registered = false

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedCount++
            if (startedCount == 1) update(true)
        }

        override fun onActivityStopped(activity: Activity) {
            startedCount = (startedCount - 1).coerceAtLeast(0)
            if (startedCount == 0) update(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Registers the lifecycle callbacks (idempotent) and seeds the initial state. */
    fun ensureRegistered() {
        if (registered) return
        registered = true
        // Seed from the current activity stack so we don't start out wrongly "background".
        isForeground = getTopMostActivity(allowPaused = false) != null
        if (isForeground) startedCount = 1
        HostInfo.application.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun update(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        onChanged?.invoke(foreground)
        listeners.forEach { it(foreground) }
    }
}
