package dev.ujhhgtg.wekit.features.items.system.agent

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ComposeView
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.OverlayMode
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.agent.WeAgentBall
import dev.ujhhgtg.wekit.ui.agent.WeAgentPanel
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Manages the WeAgent system overlay (`TYPE_APPLICATION_OVERLAY`): a draggable floating ball and an
 * expandable panel window, both added to the [WindowManager] rather than a host Activity's view
 * tree. This survives across all WeChat Activities (and even when WeChat is backgrounded) with no
 * per-Activity hooks.
 *
 * The overlay lives in WeChat's process, so the effective `SYSTEM_ALERT_WINDOW` grant is WeChat's;
 * we gate mounting on [Settings.canDrawOverlays] and toast guidance if it's missing.
 */
object WeAgentOverlayController {

    private const val TAG = "WeAgentOverlayController"

    private const val PREF_BALL_X = "weagent_ball_x"
    private const val PREF_BALL_Y = "weagent_ball_y"

    private val wm: WindowManager
        get() = HostInfo.application.getSystemService<WindowManager>()

    private var ballView: ComposeView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null

    // Ball window position captured at drag start (absolute-offset dragging, set in onDragStart).
    private var dragStartX = 0
    private var dragStartY = 0

    /** Whether the ball window is currently attached to the [WindowManager]. */
    @Volatile
    var isShown = false
        private set

    /** Whether the feature is enabled (user wants the overlay). Distinct from actual attachment. */
    @Volatile
    private var desiredVisible = false

    /** Which visibility rule the ball follows (§ 界面 setting). */
    @Volatile
    private var mode = OverlayMode.DISABLED

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(HostInfo.application)

    /**
     * Marks the overlay as desired (feature enabled) and reconciles visibility. Under
     * [OverlayMode.FOREGROUND_ONLY] the ball is only attached while WeChat is foreground; the
     * tracker drives later attach/detach. Idempotent.
     */
    fun show() {
        desiredVisible = true
        if (mode == OverlayMode.DISABLED) return
        if (!canDrawOverlays()) {
            showOverlayPermissionToast()
            WeLogger.w(TAG, "no SYSTEM_ALERT_WINDOW permission for host process")
            return
        }
        wireForegroundTracker()
        reconcile()
    }

    /** Marks the overlay as no longer desired and detaches it. */
    fun hide() {
        desiredVisible = false
        reconcile()
    }

    /**
     * Sets the ball's visibility rule. Registers the foreground tracker for
     * [OverlayMode.FOREGROUND_ONLY] so background transitions detach the ball, and reconciles
     * immediately (e.g. re-attaches if WeChat is already foreground, or detaches now if it isn't).
     */
    fun setMode(newMode: OverlayMode) {
        mode = newMode
        if (newMode == OverlayMode.FOREGROUND_ONLY) wireForegroundTracker()
        reconcile()
    }

    private fun wireForegroundTracker() {
        WeChatForegroundTracker.onChanged = { reconcile() }
        WeChatForegroundTracker.ensureRegistered()
    }

    /** True when the ball should currently be attached given desire, mode, permission, and foreground. */
    private fun shouldBeVisible(): Boolean = when (mode) {
        OverlayMode.DISABLED -> false
        OverlayMode.ALWAYS -> desiredVisible && canDrawOverlays()
        OverlayMode.FOREGROUND_ONLY ->
            desiredVisible && canDrawOverlays() && WeChatForegroundTracker.isForeground
    }

    /** Attaches or detaches the ball window to match [shouldBeVisible]. Must run on the main thread. */
    private fun reconcile() {
        val want = shouldBeVisible()
        if (want && !isShown) {
            runCatching { addBall() }.onFailure { WeLogger.e(TAG, "failed to add ball", it) }
            isShown = true
        } else if (!want && isShown) {
            removePanel()
            ballView?.let { runCatching { wm.removeView(it) } }
            ballView = null
            ballParams = null
            isShown = false
        }
    }

    // -----------------------------------------------------------------------------------------
    // Ball window
    // -----------------------------------------------------------------------------------------

    private fun addBall() {
        val params = baseLayoutParams(focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = WePrefs.getIntOrDef(PREF_BALL_X, 24)
            y = WePrefs.getIntOrDef(PREF_BALL_Y, 240)
        }
        ballParams = params

        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentBall(
                        state = WeAgentService.ballState.value,
                        onClick = { togglePanel() },
                        onDragStart = {
                            ballParams?.let { dragStartX = it.x; dragStartY = it.y }
                        },
                        onDrag = { dx, dy ->
                            val p = ballParams
                            val v = ballView
                            if (p != null && v != null) {
                                p.x = dragStartX + dx.toInt()
                                p.y = dragStartY + dy.toInt()
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        },
                        onDragEnd = {
                            val p = ballParams ?: return@WeAgentBall
                            val v = ballView
                            if (v != null) {
                                clampToScreen(v, p)
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                            WePrefs.putInt(PREF_BALL_X, p.x)
                            WePrefs.putInt(PREF_BALL_Y, p.y)
                        },
                    )
                }
            }
        }
        ballView = view
        wm.addView(view, params)
    }

    /** Keeps the ball fully on-screen after a drag. */
    private fun clampToScreen(view: View, params: WindowManager.LayoutParams) {
        val metrics = view.resources.displayMetrics
        val w = if (view.width > 0) view.width else (52 * metrics.density).toInt()
        val h = if (view.height > 0) view.height else (52 * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
    }

    // -----------------------------------------------------------------------------------------
    // Panel window
    // -----------------------------------------------------------------------------------------

    fun togglePanel() {
        if (panelView != null) removePanel() else addPanel()
    }

    /**
     * Opens the panel independently of the ball — used by entry points that don't go through the
     * overlay ball (e.g. the chat toolbar item), so the panel stays reachable with
     * [OverlayMode.DISABLED]. No-op when the panel is already up. Must run on the main thread.
     */
    fun openPanel() {
        if (panelView != null) return
        if (!canDrawOverlays()) {
            showOverlayPermissionToast()
            WeLogger.w(TAG, "no SYSTEM_ALERT_WINDOW permission for host process")
            return
        }
        addPanel()
    }

    private fun addPanel() {
        val params = baseLayoutParams(focusable = true).apply {
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            // Unspecified soft-input mode degrades to pan, which only lifts the window enough to
            // expose the focused text field's cursor and leaves the send button under the IME.
            // Resizing shrinks the window above the keyboard; imePadding() in WeAgentPanel covers
            // devices where overlay windows are not resized.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = WeAgentPanelHost(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setBackHandler { removePanel() }
        }
        val composeView = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentPanel(
                        onDismiss = { removePanel() },
                        onBackHandlerChanged = view::setBackHandler,
                    )
                }
            }
        }
        view.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panelView = view
        runCatching { wm.addView(view, params) }.onFailure { WeLogger.e(TAG, "failed to add panel", it) }
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    private fun showOverlayPermissionToast() {
        val localized = LocalizedContextFactory.create(
            HostInfo.application,
            WeKitLocaleController.resolvedLocale,
            LocaleResourceMode.InjectedHost,
        )
        showToast(localized.getString(R.string.agent_overlay_permission_required))
    }

    // -----------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun baseLayoutParams(focusable: Boolean): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }
}

/**
 * Root view for the focusable panel window. Unlike an Activity decor view, a ComposeView attached
 * directly through WindowManager has no Activity back dispatcher, so the window root handles both
 * legacy key dispatch and Android 13+ system Back itself.
 */
private class WeAgentPanelHost(context: Context) : FrameLayout(context) {
    private var backHandler: (() -> Unit)? = null
    private var systemBackCallback: Any? = null

    fun setBackHandler(handler: (() -> Unit)?) {
        backHandler = handler
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (backHandler != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            val state = keyDispatcherState ?: return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                state.startTracking(event, this)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && state.isTracking(event) && !event.isCanceled) {
                backHandler?.invoke()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            systemBackCallback = WeAgentPanelBackApi33.register(this) {
                backHandler?.invoke()
            }
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) {
            WeAgentPanelBackApi33.unregister(this, systemBackCallback)
        }
        systemBackCallback = null
        super.onDetachedFromWindow()
    }
}

@RequiresApi(33)
private object WeAgentPanelBackApi33 {
    fun register(view: View, onBack: () -> Unit): Any? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback(onBack)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        return callback
    }

    fun unregister(view: View, callback: Any?) {
        if (callback is OnBackInvokedCallback) {
            view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
        }
    }
}
