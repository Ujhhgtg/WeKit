package dev.ujhhgtg.wekit.features.items.system.agent

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity

/**
 * Process-wide foreground/background tracker for the WeChat host, built on
 * [Application.ActivityLifecycleCallbacks] (there is no such global signal elsewhere in the app).
 *
 * "Foreground" = at least one host Activity is started (between onStart and onStop). The started
 * count is the standard, orientation-change-safe way to detect this. A single listener is enough
 * for our needs ([WeAgentOverlayController]); transitions are only reported when the boolean flips.
 */
object WeChatForegroundTracker {

    @Volatile
    var isForeground = false
        private set

    /** Invoked on every foreground ↔ background transition with the new state. */
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
    }
}
