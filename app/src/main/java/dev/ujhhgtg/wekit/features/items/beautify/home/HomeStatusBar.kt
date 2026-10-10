@file:Suppress("DEPRECATION")

package dev.ujhhgtg.wekit.features.items.beautify.home

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import java.util.IdentityHashMap

class HomeStatusBar(private val activity: Activity, private val shell: View) {
    private class FrameState(view: View) {
        val paddingTop = view.paddingTop
        val fitsSystemWindows = view.fitsSystemWindows
        var height = view.reflekt().firstMethod {
            superclass()
            name = "getDrawnStatusBarHeight"
            parameters()
        }.invoke() as Int
    }
    private class WindowState(activity: Activity) {
        private val window = activity.window
        val color = window.statusBarColor
        val flags = window.attributes.flags
        val systemUi = window.decorView.systemUiVisibility
        val contrast = if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced else false
        val cutout = window.attributes.layoutInDisplayCutoutMode
        val light = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    }
    private var original: WindowState? = null
    private val frames = IdentityHashMap<View, FrameState>()
    private val shellPosition = IntArray(2)
    var topInsetPx by mutableIntStateOf(0)
        private set

    fun apply(active: Boolean) {
        if (!active) {
            restore()
            return
        }
        if (original == null) original = WindowState(activity)
        val window = activity.window
        val decor = window.decorView
        val flags = (window.attributes.flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) and
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
        if (window.attributes.flags != flags) window.setFlags(flags, FLAGS_MASK)
        if (window.statusBarColor != 0) window.statusBarColor = 0
        if (Build.VERSION.SDK_INT >= 29 && window.isStatusBarContrastEnforced) window.isStatusBarContrastEnforced = false
        val systemUi = decor.systemUiVisibility or LAYOUT_FLAGS
        if (decor.systemUiVisibility != systemUi) decor.systemUiVisibility = systemUi
        if (window.attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, decor)
        val light = !activity.isDarkMode
        if (controller.isAppearanceLightStatusBars != light) controller.isAppearanceLightStatusBars = light
        val frameClass = BeautifyHome.methodStatusBarHeight.method.declaringClass
        var view = shell.parent as? View
        while (view != null && view !== decor) {
            if (frameClass.isInstance(view)) {
                frames.getOrPut(view) { FrameState(view) }
                if (view.fitsSystemWindows) view.fitsSystemWindows = false
                if (view.paddingTop != 0 || drawnHeight(view) != 0) {
                    BeautifyHome.methodStatusBarHeight.method.invoke(view, 0)
                }
            }
            view = view.parent as? View
        }
        // WeChat's StatusBarHeightWatcher consumes insets above the injected ComposeView.
        // Read them at the window, then express the top boundary in the shell's coordinates.
        val insets = ViewCompat.getRootWindowInsets(decor)
        updateTopInset(insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
            ?: frames.values.maxOfOrNull { it.height } ?: 0)
    }

    fun captureHeight(view: View, height: Int): Boolean {
        val frame = frames[view] ?: return false
        if (height > 0) {
            frame.height = height
            updateTopInset(height)
        }
        return true
    }

    private fun updateTopInset(height: Int) {
        shell.getLocationInWindow(shellPosition)
        topInsetPx = (height - shellPosition[1]).coerceAtLeast(0)
    }

    fun restore() {
        val state = original ?: return
        original = null
        // Remove interception state before calling the native height method to restore it.
        val restoreFrames = frames.toMap()
        frames.clear()
        restoreFrames.forEach { (view, frame) ->
            view.fitsSystemWindows = frame.fitsSystemWindows
            BeautifyHome.methodStatusBarHeight.method.invoke(view, frame.height)
            view.setPadding(view.paddingLeft, frame.paddingTop, view.paddingRight, view.paddingBottom)
        }
        val window = activity.window
        val decor = window.decorView
        window.setFlags(state.flags, FLAGS_MASK)
        window.statusBarColor = state.color
        if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = state.contrast
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = state.cutout }
        decor.systemUiVisibility = (decor.systemUiVisibility and LAYOUT_FLAGS.inv()) or (state.systemUi and LAYOUT_FLAGS)
        WindowCompat.getInsetsController(window, decor).isAppearanceLightStatusBars = state.light
        decor.requestApplyInsets()
    }

    private fun drawnHeight(view: View): Int = view.reflekt().firstMethod {
        superclass()
        name = "getDrawnStatusBarHeight"
        parameters()
    }.invoke() as Int

    companion object {
        private const val FLAGS_MASK = -2080374784
        private const val LAYOUT_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
}
