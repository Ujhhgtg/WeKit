package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import java.util.WeakHashMap

/**
 * Back handling for overlays injected into LauncherUI.
 *
 * LauncherUI turns a root back into `moveTaskToBack(boolean)`, so that call is the one place every
 * home-screen overlay can intercept. Handlers are tried newest first, like
 * `OnBackPressedDispatcher`, so an overlay opened on top of another gets the gesture first.
 */
object WeLauncherBackApi : ApiFeature() {
    override val technicalId = "微信主界面返回手势服务"
    override val nameRes = R.string.feature_we_launcher_back_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_launcher_back_api_description

    /** Returns true when it consumed the back. */
    fun interface Handler {
        fun onBack(): Boolean
    }

    private class Registration(val isActive: () -> Boolean, val handler: Handler)

    private val registrations = WeakHashMap<Activity, ArrayDeque<Registration>>()

    // One back gesture reaches moveTaskToBack more than once. After a handler consumes it,
    // the repeats of the same gesture are swallowed until the next frame instead of being
    // delivered again (which would, e.g., pop a sub-page and then close its host too).
    private val consumedThisFrame = WeakHashMap<Activity, Boolean>()

    /**
     * Registers [handler] for [activity]; invoke the returned function to unregister it.
     *
     * [isActive] must be true exactly when [handler] would consume a back right now: predictive
     * back reads it ahead of the gesture to decide whether the system may animate back-to-home.
     */
    fun register(activity: Activity, isActive: () -> Boolean, handler: Handler): () -> Unit {
        val registration = Registration(isActive, handler)
        registrations.getOrPut(activity) { ArrayDeque() }.addLast(registration)
        return { registrations[activity]?.remove(registration) }
    }

    /** Whether a registered handler would consume a back on [activity] right now. */
    fun hasActiveHandler(activity: Activity): Boolean =
        registrations[activity]?.any { it.isActive() } == true

    /** Whether a back of the current gesture has already been consumed on [activity]. */
    fun isConsumedThisFrame(activity: Activity): Boolean = consumedThisFrame[activity] == true

    override fun onEnable() {
        LauncherUI::class.reflekt()
            .firstMethod {
                name = "moveTaskToBack"
                parameters(Boolean::class)
            }
            .hookBefore {
                val activity = thisObject as Activity
                if (isConsumedThisFrame(activity)) {
                    result = true
                    return@hookBefore
                }
                val consumed =
                    registrations[activity]?.toList()?.asReversed()?.any { it.handler.onBack() } ==
                        true
                if (!consumed) return@hookBefore
                consumedThisFrame[activity] = true
                activity.window.decorView.postOnAnimation { consumedThisFrame.remove(activity) }
                result = true
            }
    }
}
