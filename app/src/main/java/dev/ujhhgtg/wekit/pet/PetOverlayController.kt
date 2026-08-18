package dev.ujhhgtg.wekit.pet

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
import androidx.activity.findOnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ComposeView
import dev.ujhhgtg.wekit.pet.ui.PetOverlayContent
import dev.ujhhgtg.wekit.pet.ui.RenameDialog
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Manages the pet system overlay (`TYPE_APPLICATION_OVERLAY`): a draggable,
 * transparent sprite window added to the [WindowManager]. A tap pets the pet;
 * a drag repositions it. Mirrors [dev.ujhhgtg.wekit.features.items.system.agent.WeAgentOverlayController]'s
 * window-management skeleton.
 */
object PetOverlayController {

    private const val TAG = "PetOverlayController"

    private const val PREF_PET_X = "pet_overlay_x"
    private const val PREF_PET_Y = "pet_overlay_y"

    private val wm: WindowManager
        get() = HostInfo.application.getSystemService<WindowManager>()

    private var petView: ComposeView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var renameView: View? = null

    private var dragStartX = 0
    private var dragStartY = 0

    @Volatile
    var isShown = false
        private set

    @Volatile
    private var desiredVisible = false

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(HostInfo.application)

    fun show() {
        desiredVisible = true
        if (!canDrawOverlays()) {
            showToast("请在系统设置中为微信开启「显示在其他应用上层」")
            WeLogger.w(TAG, "no SYSTEM_ALERT_WINDOW permission for host process")
            return
        }
        reconcile()
    }

    fun hide() {
        desiredVisible = false
        closeRenameWindow()
        reconcile()
    }

    private fun reconcile() {
        if (desiredVisible && !isShown) {
            runCatching { addPet() }.onFailure { WeLogger.e(TAG, "failed to add pet overlay", it) }
            isShown = true
        } else if (!desiredVisible && isShown) {
            petView?.let { runCatching { wm.removeView(it) } }
            petView = null
            petParams = null
            isShown = false
        }
    }

    private fun addPet() {
        val metrics = HostInfo.application.resources.displayMetrics
        val density = metrics.density
        val petHeightPx = (petSizeDp() * density).toInt()
        val petWidthPx = (petSizeDp() * petAspect() * density).toInt()
        val params = baseLayoutParams(focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = WePrefs.getIntOrDef(PREF_PET_X, (metrics.widthPixels - petWidthPx - (24 * density).toInt()).coerceAtLeast(0))
            y = WePrefs.getIntOrDef(PREF_PET_Y, (metrics.heightPixels - petHeightPx - (300 * density).toInt()).coerceAtLeast(0))
        }
        petParams = params

        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    PetOverlayContent(
                        onDragStart = {
                            petParams?.let { dragStartX = it.x; dragStartY = it.y }
                        },
                        onDrag = { dx, dy ->
                            val p = petParams
                            val v = petView
                            if (p != null && v != null) {
                                p.x = dragStartX + dx.toInt()
                                p.y = dragStartY + dy.toInt()
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        },
                        onDragEnd = {
                            val p = petParams ?: return@PetOverlayContent
                            val v = petView
                            if (v != null) {
                                clampToScreen(v, p)
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                            WePrefs.putInt(PREF_PET_X, p.x)
                            WePrefs.putInt(PREF_PET_Y, p.y)
                        },
                    )
                }
            }
        }
        petView = view
        wm.addView(view, params)
    }

    private fun clampToScreen(view: View, params: WindowManager.LayoutParams) {
        val metrics = view.resources.displayMetrics
        // Bubble slot (44dp) rides above the sprite, so the window is taller than the pet itself.
        val w = if (view.width > 0) view.width else (petSizeDp() * petAspect() * metrics.density).toInt()
        val h = if (view.height > 0) view.height else ((petSizeDp() + 44) * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
    }

    /** Current pet sprite height in dp, clamped to the display config bounds. */
    private fun petSizeDp(): Int = PetService.displaySize.value.coerceIn(DISPLAY_SIZE_MIN, DISPLAY_SIZE_MAX)

    /** Atlas aspect ratio (width/height), used to derive the sprite width from its height. */
    private fun petAspect(): Float =
        PetService.petEntry.value?.definition?.cell?.let { it.width.toFloat() / it.height.toFloat() }
            ?: (192f / 208f)

    /**
     * Called after the user resizes the pet. Re-applies the layout so a WRAP_CONTENT overlay
     * window re-measures its Compose content and adopts the new size. Deferred to the next frame so
     * the size-driven Compose recomposition lands first. No-op when not mounted.
     */
    fun onSizeChanged() {
        val v = petView ?: return
        val p = petParams ?: return
        v.post {
            runCatching { wm.updateViewLayout(v, p) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Rename dialog window
    // -----------------------------------------------------------------------------------------

    /** Opens the focusable rename dialog window. No-op if already open or missing overlay permission. */
    fun openRenameWindow() {
        if (renameView != null) return
        if (!canDrawOverlays()) {
            showToast("请在系统设置中为微信开启「显示在其他应用上层」")
            return
        }
        val params = baseLayoutParams(focusable = true).apply {
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val owner = LifecycleOwnerProvider.lifecycleOwner
        val host = PetRenameHost(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setBackHandler { closeRenameWindow() }
        }
        val composeView = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    RenameDialog(
                        initialName = PetService.petDisplayName.value,
                        onConfirm = { name ->
                            PetService.rename(name)
                            closeRenameWindow()
                        },
                        onDismiss = { closeRenameWindow() },
                    )
                }
            }
        }
        host.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        renameView = host
        runCatching { wm.addView(host, params) }.onFailure { WeLogger.e(TAG, "failed to add rename window", it) }
    }

    /** Closes the rename dialog window (no-op when not open). */
    fun closeRenameWindow() {
        renameView?.let { runCatching { wm.removeView(it) } }
        renameView = null
    }

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
 * Root view for the focusable rename dialog window. A ComposeView attached directly through
 * WindowManager has no Activity back dispatcher, so this host handles both legacy key dispatch and
 * Android 13+ system Back to close the dialog.
 */
private class PetRenameHost(context: Context) : FrameLayout(context) {
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
            systemBackCallback = PetRenameBackApi33.register(this) {
                backHandler?.invoke()
            }
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) {
            PetRenameBackApi33.unregister(this, systemBackCallback)
        }
        systemBackCallback = null
        super.onDetachedFromWindow()
    }
}

@RequiresApi(33)
private object PetRenameBackApi33 {
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
