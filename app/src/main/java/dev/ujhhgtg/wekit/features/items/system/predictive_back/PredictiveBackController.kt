package dev.ujhhgtg.wekit.features.items.system.predictive_back

import android.app.Activity
import android.os.Build
import android.view.ViewTreeObserver
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * Predictive back for one opted-in WeChat activity.
 *
 * - While [host] is idle (a legacy BACK would leave the activity), Activity's own system callback
 *   stays on top so the platform plays its back-to-home / cross-activity animation; its body is
 *   replaced by [IdleBridge], which runs WeChat's legacy back chain.
 * - Whenever something would consume BACK first, [callback] is registered above it. It drives
 *   WeChat's chat swipe for a plain in-window chat and otherwise replays the legacy chain on
 *   release.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PredictiveBackController
private constructor(
    private val activity: Activity,
    private val host: BackHost,
    private val originalDefaultCallback: OnBackInvokedCallback,
) {
    private val window = activity.window
    private val decor = window.decorView
    private val dispatcher = activity.onBackInvokedDispatcher
    private val defaultCallbackField =
        activity.reflekt().firstField {
            name = "mDefaultBackCallback"
            superclass()
        }
    private val idleBridge = IdleBridge()

    private var callbackRegistered = false
    private var gestureActive = false
    private var driver: ChatSwipeDriver? = null
    private var replayingIdleBack = false
    private var leftDuringReplay = false

    /** Set after a state reader threw: stay registered and only replay legacy back from then on. */
    private var degraded = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        syncRegistration()
        true
    }

    private val callback =
        object : OnBackAnimationCallback {
            override fun onBackStarted(backEvent: BackEvent) = guarded { startGesture(backEvent) }

            override fun onBackProgressed(backEvent: BackEvent) = guarded {
                val d = driver ?: return@guarded
                if (d.isAttached) d.moveToProgress(backEvent.progress) else driver = null
            }

            override fun onBackCancelled() = guarded {
                driver?.takeIf { it.isAttached }?.cancel()
                endGesture()
            }

            override fun onBackInvoked() = guarded {
                val d = driver
                endGesture()
                if (d != null) {
                    if (!d.isAttached) return@guarded
                    if (host.isChatForeground()) d.commit() else d.cancel()
                    return@guarded
                }
                if (hideImeIfVisible(window)) return@guarded
                @Suppress("DEPRECATION") replayLegacyBack(window) { activity.onBackPressed() }
            }
        }

    private fun attach() {
        dispatcher.unregisterOnBackInvokedCallback(originalDefaultCallback)
        REGISTER_SYSTEM_CALLBACK.invoke(dispatcher, idleBridge)
        defaultCallbackField.set(idleBridge)
        decor.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    fun detach() {
        decor.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        if (callbackRegistered) dispatcher.unregisterOnBackInvokedCallback(callback)
        callbackRegistered = false
        dispatcher.unregisterOnBackInvokedCallback(idleBridge)
        REGISTER_SYSTEM_CALLBACK.invoke(dispatcher, originalDefaultCallback)
        defaultCallbackField.set(originalDefaultCallback)
    }

    /**
     * Called when the activity starts leaving outside `finish()`: `Activity.moveTaskToBack`, or
     * Activity's own default back (`onBackInvoked`, which finishes it asynchronously via ATMS).
     */
    fun onLeaving() {
        if (replayingIdleBack) leftDuringReplay = true
    }

    private fun startGesture(backEvent: BackEvent) {
        gestureActive = true
        driver = null
        if (degraded || isButtonPress(backEvent)) return
        val layout = host.readyChatSwipeLayout()
        if (layout == null) {
            // Something consumes this back first; WeChat's own chain takes it on release.
            WeLogger.d(TAG, "no preview on ${activity.javaClass.name}: ${host.describe()}")
            return
        }
        driver = ChatSwipeDriver.start(layout, layout.targetContentView)
    }

    private fun endGesture() {
        gestureActive = false
        driver = null
        // Deferred: unregistering inside a callback re-enters onBackCancelled via the progress
        // animator.
        decor.post(::syncRegistration)
    }

    private fun syncRegistration() {
        val needed =
            degraded ||
                try {
                    !host.isIdle()
                } catch (e: Throwable) {
                    degrade(e)
                    true
                }
        if (needed == callbackRegistered || gestureActive || isSystemBackGestureInProgress()) return
        if (needed) {
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
        } else {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
        callbackRegistered = needed
    }

    /** Toggling the top callback mid-gesture would retarget the system's own back animation. */
    private fun isSystemBackGestureInProgress(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        val viewRoot =
            decor
                .reflekt()
                .firstMethod {
                    name = "getViewRootImpl"
                    superclass()
                }
                .invoke() ?: return false
        val windowDispatcher =
            viewRoot.reflekt().firstMethod { name = "getOnBackInvokedDispatcher" }.invoke()!!
        return windowDispatcher.reflekt().firstMethod { name = "isBackGestureInProgress" }.invoke()
            as Boolean
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // A throw here would crash WeChat on a back gesture; fall back to plain legacy back.
            degrade(e)
            driver = null
        }
    }

    private fun degrade(e: Throwable) {
        if (!degraded)
            WeLogger.e(
                TAG,
                "predictive back failed on ${activity.javaClass.name}, falling back to legacy back",
                e,
            )
        degraded = true
    }

    /**
     * Activity's default back callback with its body replaced: the system still sees a system
     * callback (and animates leaving), but the activity leaves through WeChat's own chain.
     */
    private inner class IdleBridge : OnBackInvokedCallback {
        override fun onBackInvoked() {
            replayingIdleBack = true
            leftDuringReplay = false
            try {
                @Suppress("DEPRECATION") replayLegacyBack(window) { activity.onBackPressed() }
            } finally {
                replayingIdleBack = false
            }
            if (leftDuringReplay || activity.isFinishing || host.consumedOutsideKeyChain()) return
            // The system has already animated leaving; an untracked consumer took the back instead.
            val state = runCatching { host.describe() }.getOrElse { "unreadable: $it" }
            WeLogger.w(TAG, "legacy back did not leave ${activity.javaClass.name}: $state")
            if (host.leavesAfterUntrackedConsumer) originalDefaultCallback.onBackInvoked()
        }
    }

    companion object {
        private const val TAG = "PredictiveBackGestures"

        /** `BackEvent.EDGE_NONE` (API 36), used for key presses. */
        private const val EDGE_NONE = 2

        private val REGISTER_SYSTEM_CALLBACK =
            OnBackInvokedDispatcher::class.reflekt().firstMethod {
                name = "registerSystemOnBackInvokedCallback"
            }

        /**
         * Key presses start with a zero-coordinate event (EDGE_LEFT on 34/35, EDGE_NONE on 36+).
         */
        private fun isButtonPress(e: BackEvent): Boolean =
            e.swipeEdge == EDGE_NONE || e.touchX == 0f && e.touchY == 0f

        /** Returns null when [activity] did not opt into predictive back (no default callback). */
        fun attachTo(activity: Activity, host: BackHost): PredictiveBackController? {
            val original =
                activity
                    .reflekt()
                    .firstField {
                        name = "mDefaultBackCallback"
                        superclass()
                    }
                    .get() as? OnBackInvokedCallback? ?: return null
            return PredictiveBackController(activity, host, original).also { it.attach() }
        }
    }
}
