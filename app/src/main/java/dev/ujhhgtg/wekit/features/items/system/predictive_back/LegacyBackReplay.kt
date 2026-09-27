package dev.ujhhgtg.wekit.features.items.system.predictive_back

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Window
import android.view.WindowInsets
import androidx.annotation.RequiresApi

/**
 * Runs one BACK press through [window]'s legacy key path, as if predictive back were off.
 *
 * Once a window opts into predictive back, `ViewRootImpl` turns BACK into callback invocations and
 * WeChat's `dispatchKeyEvent`/`onKeyDown` chains never see it. This replays the DOWN/UP pair the
 * way `ViewRootImpl.sendBackKeyEvent` builds it, through the DecorView so action modes are still
 * handled first. The platform also disables `Activity.onKeyUp`'s / `Dialog.onKeyUp`'s legacy
 * `onBackPressed()` branch in that mode, so an unhandled, tracked UP falls through to [fallback]
 * exactly where the legacy branch would have run.
 */
fun replayLegacyBack(window: Window, fallback: () -> Unit) {
    val decor = window.decorView
    val time = SystemClock.uptimeMillis()
    decor.dispatchKeyEvent(backKeyEvent(time, KeyEvent.ACTION_DOWN))
    val up = backKeyEvent(time, KeyEvent.ACTION_UP)
    // KeyEvent.DispatcherState marks the UP as tracking while dispatching it when the DOWN was tracked.
    val handled = decor.dispatchKeyEvent(up)
    if (!handled && up.isTracking && !up.isCanceled) fallback()
}

/**
 * Hides the IME when it is showing. The IME's own back callback normally sits above ours, but a
 * callback registered after the IME appeared would be on top instead; closing the IME is what the
 * user's back would have done.
 */
@RequiresApi(Build.VERSION_CODES.R)
fun hideImeIfVisible(window: Window): Boolean {
    val insets = window.decorView.rootWindowInsets ?: return false
    if (!insets.isVisible(WindowInsets.Type.ime())) return false
    window.insetsController!!.hide(WindowInsets.Type.ime())
    return true
}

private fun backKeyEvent(time: Long, action: Int) = KeyEvent(
    time, time, action, KeyEvent.KEYCODE_BACK, 0, 0,
    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
    KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
    InputDevice.SOURCE_KEYBOARD,
)
