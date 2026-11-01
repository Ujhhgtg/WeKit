package dev.ujhhgtg.wekit.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Looper
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.asHidden
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hides the window that contains a tracked [View] from screenshots and screen recording.
 *
 * While a tracked view is visible it
 * 1. adds `FLAG_SECURE` to the owning Activity window (and to the view's own window if that is a
 *    separate one, e.g. a Dialog or PopupWindow), and
 * 2. on API 29+ marks the corresponding surface layers with `setSkipScreenshot` via reflection.
 *
 * Every flag / layer is reference counted, so several tracked views can share a window and the
 * original state is restored once the last one goes away.
 *
 * All functions must be called on the main thread.
 */
object ScreenCaptureBlocker {
    private const val FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE
    private const val TAG_KEY = 0x5ec0e001
    private const val MAX_CONTEXT_DEPTH = 12

    private val windowLeases = WeakHashMap<Window, FlagLease>()
    private val rootLeases = WeakHashMap<View, FlagLease>()
    private val layers = WeakHashMap<View, SkipLayer>()
    private val watches: MutableSet<Watch> = Collections.newSetFromMap(WeakHashMap())

    fun dialog(dialog: Dialog?) {
        dialog?.window?.decorView?.let { track(it) }
    }

    fun popup(popup: PopupWindow?) {
        popup?.contentView?.let { track(it) }
    }

    fun activity(activity: Activity?) {
        activity?.window?.decorView?.let { track(it) }
    }

    fun track(view: View?) {
        if (view == null || view.getTag(TAG_KEY) is Watch) return
        val watch = Watch(view)
        view.setTag(TAG_KEY, watch)
        synchronized(watches) { watches.add(watch) }
        view.addOnAttachStateChangeListener(watch)
        if (view.isAttachedToWindow) watch.onViewAttachedToWindow(view)
    }

    fun init() {
        "android.view.WindowManagerGlobal"
            .toClass()
            .reflekt()
            .firstMethod("addView")
            .hookBeforeDirectly {
                val view = args[0] as? View ?: return@hookBeforeDirectly
                if (!view.isModuleWindow()) return@hookBeforeDirectly
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    track(view)
                } else {
                    view.post { track(view) }
                }
            }
    }

    private fun isModuleClass(cls: Class<*>) = cls.classLoader === ClassLoaders.MODULE

    private fun View.isModuleWindow(): Boolean {
        if (isModuleClass(javaClass)) return true // e.g. Compose PopupLayout
        val owner = ownerActivity() ?: return false // dialogs/popups inside module activities
        return isModuleClass(owner.javaClass)
    }

    private fun View.ownerActivity(): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private val Int.isSecure: Boolean
        get() = this and FLAG_SECURE != 0

    private fun findOwnerWindow(view: View): Window? {
        var context: Context? = view.context
        var depth = 0
        while (context != null && depth++ < MAX_CONTEXT_DEPTH) {
            if (context is Activity) return context.window
            if (context !is ContextWrapper) return null
            val next = context.baseContext
            if (next === context) return null
            context = next
        }
        return null
    }

    // region FLAG_SECURE leases

    private class FlagLease(val originallySecure: Boolean) {
        var count = 0
    }

    private fun acquireWindow(window: Window) {
        val lease = windowLeases.getOrPut(window) { FlagLease(window.attributes.flags.isSecure) }
        lease.count++
        window.addFlags(FLAG_SECURE)
    }

    private fun releaseWindow(window: Window) {
        val lease = windowLeases[window] ?: return
        if (--lease.count > 0) return
        windowLeases.remove(window)
        if (!lease.originallySecure) window.clearFlags(FLAG_SECURE)
    }

    /** Root views whose own window is separate from the owner Activity (dialogs, popups). */
    private fun acquireRoot(root: View) {
        val params = root.layoutParams as? WindowManager.LayoutParams
        val lease = rootLeases.getOrPut(root) { FlagLease(params?.flags?.isSecure == true) }
        lease.count++
        setRootSecure(root, true)
    }

    private fun releaseRoot(root: View) {
        val lease = rootLeases[root] ?: return
        if (--lease.count > 0) return
        rootLeases.remove(root)
        if (!lease.originallySecure) setRootSecure(root, false)
    }

    private fun setRootSecure(root: View, secure: Boolean) {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = if (secure) params.flags or FLAG_SECURE else params.flags and FLAG_SECURE.inv()
        if (flags == params.flags) return
        params.flags = flags
        try {
            val manager = root.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            manager?.updateViewLayout(root, params)
        } catch (_: IllegalArgumentException) {
            // window already detached
        }
    }

    // endregion

    // region skipScreenshot layers

    private fun takeLayer(root: View?): SkipLayer? {
        if (root == null) {
            return null
        }
        val layer = layers.getOrPut(root) { SkipLayer(root) }
        layer.count++
        return layer
    }

    private fun dropLayer(layer: SkipLayer?) {
        if (layer == null || --layer.count > 0) return
        layer.restore()
        layer.rootRef.get()?.let { layers.remove(it) }
    }

    private class SkipLayer(root: View) {
        // Weak: the map key is the root view, a strong reference here would keep it alive forever.
        val rootRef = WeakReference(root)
        var count = 0
        private var applied: SurfaceControl? = null
        private var appliedId = 0L

        fun sync() {
            val root = rootRef.get() ?: return
            if (!root.isAttachedToWindow) return
            val current = SurfaceApi.surfaceOf(root) ?: return
            if (!current.isValid) return
            val id = SurfaceApi.nativeId(current)
            if (current === applied && id == appliedId) return
            restore()
            SurfaceApi.setSkipScreenshot(current, true)
            applied = current
            appliedId = id
        }

        fun restore() {
            val control = applied ?: return
            val id = appliedId
            applied = null
            appliedId = 0
            try {
                if (control.isValid && SurfaceApi.nativeId(control) == id) {
                    SurfaceApi.setSkipScreenshot(control, false)
                }
            } catch (_: Exception) {}
        }
    }

    private object SurfaceApi {
        fun surfaceOf(view: View): SurfaceControl? {
            val impl = view.asHidden().viewRootImpl ?: return null
            return impl.surfaceControl
        }

        fun nativeId(control: SurfaceControl): Long {
            return control.asHidden().mNativeObject
        }

        fun setSkipScreenshot(control: SurfaceControl, value: Boolean) {
            SurfaceControl.Transaction().use { transaction ->
                transaction.asHidden().setSkipScreenshot(control, value)
                transaction.apply()
            }
        }
    }

    // endregion

    private class Watch(val view: View) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {

        private val rect = Rect()
        private var observer: ViewTreeObserver? = null
        private var heldWindow: Window? = null
        private var active = false
        private var flaggedRoot: View? = null
        private var ownLayer: SkipLayer? = null
        private var hostLayer: SkipLayer? = null

        override fun onViewAttachedToWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = v.viewTreeObserver.also { if (it.isAlive) it.addOnPreDrawListener(this) }
            sync()
        }

        override fun onViewDetachedFromWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            if (active) deactivate()
        }

        override fun onPreDraw(): Boolean {
            sync()
            return true
        }

        fun sync() {
            if (!isOnScreen()) {
                if (active) deactivate()
                return
            }
            if (!active) {
                heldWindow = findOwnerWindow(view)?.also { acquireWindow(it) }
                active = true
            }

            // Re-assert the flag in case the app cleared it behind our back.
            val window = heldWindow
            if (window != null && !window.attributes.flags.isSecure) window.addFlags(FLAG_SECURE)

            val root = view.rootView
            val hostDecor = window?.peekDecorView()
            // The owner window's decor is already covered by its window lease.
            updateRootFlag(
                root.takeIf { it !== hostDecor && it.layoutParams is WindowManager.LayoutParams },
            )

            if (ownLayer?.rootRef?.get() !== root) {
                dropLayer(ownLayer)
                ownLayer = takeLayer(root)
            }
            val host = hostDecor?.takeIf { it !== root }
            if (hostLayer?.rootRef?.get() !== host) {
                dropLayer(hostLayer)
                hostLayer = takeLayer(host)
            }
            ownLayer?.sync()
            hostLayer?.sync()
        }

        private fun isOnScreen(): Boolean {
            if (!view.isShown || !view.getGlobalVisibleRect(rect) || rect.isEmpty) return false
            var current: View? = view
            while (current != null) {
                if (current.alpha <= 0.001f) return false
                current = current.parent as? View
            }
            return true
        }

        private fun updateRootFlag(root: View?) {
            val previous = flaggedRoot
            if (previous !== root) {
                previous?.let { releaseRoot(it) }
                flaggedRoot = root
                root?.let { acquireRoot(it) }
            } else if (root != null) {
                setRootSecure(root, true) // no-op unless the app rewrote the params
            }
        }

        private fun deactivate() {
            dropLayer(ownLayer)
            dropLayer(hostLayer)
            ownLayer = null
            hostLayer = null
            updateRootFlag(null)
            heldWindow?.let { releaseWindow(it) }
            heldWindow = null
            active = false
        }
    }
}
