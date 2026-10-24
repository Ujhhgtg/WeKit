package dev.ujhhgtg.wekit.agent.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.createBitmap
import dev.ujhhgtg.wekit.agent.jvm.JvmBridgeException
import dev.ujhhgtg.wekit.agent.jvm.JvmObjectRegistry
import dev.ujhhgtg.wekit.agent.ui.UiAutomator.touchDown
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity

/**
 * In-process UI automation over WeChat's own windows. Runs inside the WeChat process (Xposed), so
 * it can walk the live view tree and dispatch synthesised [MotionEvent]/[KeyEvent]s directly to the
 * top activity's `decorView` — same technique as SwipeToQuote / WeAgentBall — with no extra
 * permissions.
 *
 * Limits: only reaches views in WeChat's own activities/dialogs. Cannot touch system dialogs, the
 * status bar, or other apps. FLAG_SECURE surfaces (payment keyboard) capture as black.
 *
 * **Coordinates are screen pixels.** View bounds come from [View.getLocationOnScreen]; touch
 * coordinates are converted to decorView-local before dispatch (decorView may not sit at 0,0 under
 * split-screen / insets).
 *
 * Every method expects to run on the main thread; the tool layer marshals via
 * `JvmValueBridge.onMain`. Views/windows are returned to the model as `#N` handles via
 * [JvmObjectRegistry].
 */
object UiAutomator {

    // -----------------------------------------------------------------------------------------
    // Handle / window resolution
    // -----------------------------------------------------------------------------------------

    fun resolveDecor(windowRef: String?): View {
        if (windowRef.isNullOrBlank()) {
            val act =
                getTopMostActivity() ?: throw JvmBridgeException("No top-most WeChat activity")
            return act.window?.decorView
                ?: throw JvmBridgeException("Top activity has no decorView")
        }
        return when (
            val obj =
                JvmObjectRegistry.resolve(windowRef)
                    ?: throw JvmBridgeException(JvmObjectRegistry.missingHandleMessage(windowRef))
        ) {
            is View -> obj.rootView
            is Activity ->
                obj.window?.decorView
                    ?: throw JvmBridgeException("Activity handle has no decorView")

            is android.view.Window -> obj.decorView
            else -> throw JvmBridgeException("'$windowRef' is not a View/Activity/Window")
        }
    }

    fun resolveView(ref: String): View {
        val obj =
            JvmObjectRegistry.resolve(ref)
                ?: throw JvmBridgeException(JvmObjectRegistry.missingHandleMessage(ref))
        return obj as? View
            ?: throw JvmBridgeException("'$ref' is not a View (${obj.javaClass.name})")
    }

    // -----------------------------------------------------------------------------------------
    // View-tree walking helpers
    // -----------------------------------------------------------------------------------------

    /** Returns the on-screen bounding rectangle of a view. */
    fun screenBounds(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Iterates all descendants (depth-first). */
    private fun walk(v: View, action: (View, Int) -> Unit, depth: Int = 0) {
        action(v, depth)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), action, depth + 1)
    }

    private fun View.idName(): String =
        if (id != View.NO_ID)
            runCatching { resources.getResourceEntryName(id) }.getOrDefault(id.toString())
        else "NO_ID"

    private fun View.visStr(): String =
        when (visibility) {
            View.VISIBLE -> "vis"
            View.INVISIBLE -> "inv"
            else -> "gone"
        }

    private fun View.textStr(): String? =
        (this as? TextView)?.text?.toString()?.takeIf { it.isNotEmpty() }

    private fun View.flagStr(): String = buildList {
        if (isClickable) add("clickable")
        if (isLongClickable) add("long-clickable")
        if (isFocusable) add("focusable")
        if (isFocused) add("focused")
        if (!isEnabled) add("DISABLED")
    }
        .joinToString(" ")

    // -----------------------------------------------------------------------------------------
    // Perception: dump-tree, find-views, describe-view
    // -----------------------------------------------------------------------------------------

    /**
     * Produce an indented tree listing. Each visible (unless [includeInvisible]) node is stored as
     * a handle and shown with its handle id, class, resource-id, bounds, text and interaction
     * flags. At most [maxDepth] levels are walked (0 = unlimited).
     */
    fun dumpTree(
        decor: View,
        maxDepth: Int,
        onlyInteractive: Boolean,
        includeInvisible: Boolean,
    ): String {
        val lines = StringBuilder()
        walk(
            decor,
            { v, depth ->
                if (maxDepth in 1..<depth) return@walk
                if (!includeInvisible && v.visibility != View.VISIBLE) return@walk
                if (onlyInteractive && !(v.isClickable || v.isLongClickable || v is EditText))
                    return@walk
                val handle = JvmObjectRegistry.store(v)
                val b = screenBounds(v)
                val text = v.textStr()
                val desc = v.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
                lines.append("  ".repeat(depth))
                lines.append("$handle ${v.javaClass.simpleName}")
                lines.append("  id=${v.idName()}")
                lines.append("  [${b.left},${b.top} ${b.width()}×${b.height()}]")
                lines.append("  ${v.visStr()}")
                val flags = v.flagStr()
                if (flags.isNotEmpty()) lines.append("  $flags")
                if (text != null) lines.append("  text=\"$text\"")
                if (desc != null) lines.append("  desc=\"$desc\"")
                lines.append("\n")
            },
        )
        return lines.toString().trimEnd().ifEmpty { "(no views)" }
    }

    /**
     * Find views matching any of the supplied filters (substring match, case-insensitive). All
     * filters are optional; if none is given, every view is returned.
     */
    fun findViews(
        decor: View,
        text: String?,
        id: String?,
        className: String?,
        onlyInteractive: Boolean,
    ): String {
        val results = mutableListOf<String>()
        walk(
            decor,
            { v, _ ->
                if (onlyInteractive && !(v.isClickable || v.isLongClickable || v is EditText))
                    return@walk
                val vText = v.textStr().orEmpty()
                val vDesc = v.contentDescription?.toString().orEmpty()
                val vId = v.idName()
                val vClass = v.javaClass.simpleName
                val textOk =
                    text == null ||
                        vText.contains(text, ignoreCase = true) ||
                        vDesc.contains(text, ignoreCase = true)
                val idOk = id == null || vId.contains(id, ignoreCase = true)
                val classOk = className == null || vClass.contains(className, ignoreCase = true)
                if (textOk && idOk && classOk) {
                    val handle = JvmObjectRegistry.store(v)
                    val b = screenBounds(v)
                    val info = buildString {
                        append("$handle  ${v.javaClass.simpleName}  id=${vId}")
                        append("  [${b.left},${b.top} ${b.width()}×${b.height()}]")
                        if (vText.isNotEmpty()) append("  text=\"$vText\"")
                        if (vDesc.isNotEmpty()) append("  desc=\"$vDesc\"")
                        val flags = v.flagStr()
                        if (flags.isNotEmpty()) append("  $flags")
                    }
                    results += info
                }
            },
        )
        return if (results.isEmpty()) "No views matched."
        else "${results.size} match(es):\n" + results.joinToString("\n")
    }

    /** Describe all attributes of a single view, storing it as a handle if not already one. */
    fun describeView(v: View): String {
        val handle = JvmObjectRegistry.store(v)
        val b = screenBounds(v)
        return buildString {
            append("Handle: $handle\n")
            append("Class:  ${v.javaClass.name}\n")
            append("Id:     ${v.idName()} (${v.id})\n")
            append(
                "Bounds: [${b.left}, ${b.top}, ${b.right}, ${b.bottom}]  ${b.width()}×${b.height()}\n",
            )
            append("Vis:    ${v.visStr()}\n")
            val flags = v.flagStr()
            if (flags.isNotEmpty()) append("Flags:  $flags\n")
            v.textStr()?.let { append("Text:   \"$it\"\n") }
            v.contentDescription
                ?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?.let { append("Desc:   \"$it\"\n") }
            v.tag?.let { append("Tag:    $it\n") }
            if (v is ViewGroup) append("Children: ${v.childCount}\n")
        }
            .trimEnd()
    }

    // -----------------------------------------------------------------------------------------
    // Screenshot capture
    // -----------------------------------------------------------------------------------------

    /**
     * Capture the content of [targetView] (defaults to the decor) as a [Bitmap], scaled down so
     * neither dimension exceeds [maxDim] pixels. Uses `View.draw` into a software-backed canvas —
     * works in-process without extra permissions; respects FLAG_SECURE (those areas are black).
     */
    fun screenshot(targetView: View, maxDim: Int): Bitmap {
        val w = targetView.width
        val h = targetView.height
        if (w <= 0 || h <= 0)
            throw JvmBridgeException("View has zero size ($w×$h) — not yet laid out?")
        val scale =
            if (maxDim > 0 && (w > maxDim || h > maxDim)) maxDim.toFloat() / maxOf(w, h).toFloat()
            else 1f
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(bw, bh)
        val canvas = Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        targetView.draw(canvas)
        return bitmap
    }

    // -----------------------------------------------------------------------------------------
    // Touch dispatch helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Convert screen-pixel (x, y) to the coordinate space of [decor] by subtracting the decor's own
     * on-screen position (usually (0,0) but not always under split-screen).
     */
    private fun toDecorLocal(decor: View, screenX: Float, screenY: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        return screenX - loc[0] to screenY - loc[1]
    }

    /** Synthesize and dispatch a single DOWN+UP tap to [decor] at screen coordinates. */
    fun tap(decor: View, screenX: Float, screenY: Float) {
        val (lx, ly) = toDecorLocal(decor, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, lx, ly, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, lx, ly, 0)
        decor.dispatchTouchEvent(down)
        down.recycle()
        decor.dispatchTouchEvent(up)
        up.recycle()
    }

    /** Synthesise [count] taps at the same position with [intervalMs] between each DOWN+UP. */
    fun multiTap(decor: View, screenX: Float, screenY: Float, count: Int, intervalMs: Long) {
        repeat(count.coerceAtLeast(1)) {
            tap(decor, screenX, screenY)
            if (it < count - 1) Thread.sleep(intervalMs.coerceAtLeast(1))
        }
    }

    /** Synthesise a long-press DOWN event held for [durationMs] then UP. */
    fun longPress(decor: View, screenX: Float, screenY: Float, durationMs: Long) {
        val (lx, ly) = toDecorLocal(decor, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, lx, ly, 0)
        decor.dispatchTouchEvent(down)
        down.recycle()
        Thread.sleep(durationMs.coerceAtLeast(100))
        val up = MotionEvent.obtain(now, now + durationMs, MotionEvent.ACTION_UP, lx, ly, 0)
        decor.dispatchTouchEvent(up)
        up.recycle()
    }

    /**
     * Swipe from (x1,y1) to (x2,y2) over [durationMs] ms, emitting [steps] intermediate MOVE events
     * for a smooth gesture. [steps] defaults to max(10, durationMs/16) (≈60fps pacing).
     */
    fun swipe(
        decor: View,
        screenX1: Float,
        screenY1: Float,
        screenX2: Float,
        screenY2: Float,
        durationMs: Long,
        steps: Int,
    ) {
        val n = steps.coerceAtLeast(2)
        val (lx1, ly1) = toDecorLocal(decor, screenX1, screenY1)
        val (lx2, ly2) = toDecorLocal(decor, screenX2, screenY2)
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, lx1, ly1, 0)
        decor.dispatchTouchEvent(down)
        down.recycle()
        val stepDelay = durationMs / n
        for (i in 1 until n) {
            Thread.sleep(stepDelay)
            val t = SystemClock.uptimeMillis()
            val fx = lx1 + (lx2 - lx1) * i / (n - 1)
            val fy = ly1 + (ly2 - ly1) * i / (n - 1)
            val mv = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE, fx, fy, 0)
            decor.dispatchTouchEvent(mv)
            mv.recycle()
        }
        Thread.sleep(stepDelay)
        val t2 = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(downTime, t2, MotionEvent.ACTION_UP, lx2, ly2, 0)
        decor.dispatchTouchEvent(up)
        up.recycle()
    }

    // -----------------------------------------------------------------------------------------
    // Held-gesture primitives (DOWN / MOVE / UP as separate tool calls)
    // -----------------------------------------------------------------------------------------

    /**
     * Mutable state kept for the current held gesture. A live gesture can be auto-cancelled.
     *
     * [decor] is held **weakly**: this is a static slot that only [touchUp] used to clear, so an
     * errored/cancelled turn between `ui-touch-down` and `ui-touch-up` pinned the Activity's entire
     * view hierarchy for the rest of the process's life. If the decor is gone the gesture is simply
     * dead and gets dropped.
     */
    data class GestureState(
        val downTime: Long,
        val decor: java.lang.ref.WeakReference<View>,
        val lastX: Float,
        val lastY: Float,
    )

    @SuppressLint("StaticFieldLeak") @Volatile var activeGesture: GestureState? = null

    /** Start a held touch gesture (ACTION_DOWN). Auto-cancels any previous active gesture. */
    fun touchDown(decor: View, screenX: Float, screenY: Float) {
        // Cancel stale gesture to avoid leaking a stuck DOWN event.
        cancelActiveGesture()
        val (lx, ly) = toDecorLocal(decor, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, lx, ly, 0)
        decor.dispatchTouchEvent(down)
        down.recycle()
        activeGesture = GestureState(now, java.lang.ref.WeakReference(decor), screenX, screenY)
    }

    /** Continue a held gesture with ACTION_MOVE. Requires a prior [touchDown]. */
    fun touchMove(screenX: Float, screenY: Float): String {
        val gs = activeGesture ?: return "No active gesture (call ui-touch-down first)."
        val decor =
            gs.decor.get()
                ?: run {
                    activeGesture = null
                    return "The gesture's window is gone (the activity was destroyed); call ui-touch-down again."
                }
        val (lx, ly) = toDecorLocal(decor, screenX, screenY)
        val t = SystemClock.uptimeMillis()
        val mv = MotionEvent.obtain(gs.downTime, t, MotionEvent.ACTION_MOVE, lx, ly, 0)
        decor.dispatchTouchEvent(mv)
        mv.recycle()
        activeGesture = gs.copy(lastX = screenX, lastY = screenY)
        return "Moved to ($screenX, $screenY)."
    }

    /** Release a held gesture with ACTION_UP. */
    fun touchUp(screenX: Float, screenY: Float): String {
        val gs = activeGesture ?: return "No active gesture to release."
        activeGesture = null
        val decor =
            gs.decor.get()
                ?: return "The gesture's window is gone (the activity was destroyed); nothing to release."
        val (lx, ly) = toDecorLocal(decor, screenX, screenY)
        val t = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(gs.downTime, t, MotionEvent.ACTION_UP, lx, ly, 0)
        decor.dispatchTouchEvent(up)
        up.recycle()
        return "Released at ($screenX, $screenY)."
    }

    /**
     * Dispatch ACTION_CANCEL for a still-held gesture and drop the reference. Called both by
     * [touchDown] (superseding gesture) and from the engine's turn teardown — without the latter, a
     * turn that fails or is cancelled between `ui-touch-down` and `ui-touch-up` leaves WeChat with
     * a stuck ACTION_DOWN in its input state (subsequent real touches misbehave). Safe to call from
     * any thread and when no gesture is active; dispatch is posted to the main thread without
     * blocking.
     */
    fun cancelActiveGesture() {
        val gs = activeGesture ?: return
        activeGesture = null
        val decor = gs.decor.get() ?: return
        val dispatch = {
            val (lx, ly) = toDecorLocal(decor, gs.lastX, gs.lastY)
            val t = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(gs.downTime, t, MotionEvent.ACTION_CANCEL, lx, ly, 0)
            decor.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch() else mainHandler.post(dispatch)
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // -----------------------------------------------------------------------------------------
    // View-level actions
    // -----------------------------------------------------------------------------------------

    fun clickView(v: View) = v.performClick()

    fun longClickView(v: View) = v.performLongClick()

    /**
     * Set text on a [TextView] / [EditText]. Clears any existing content and fires text-changed
     * callbacks so listeners (e.g. WeChat's input bar watcher) observe the new value.
     */
    fun setText(v: View, text: String) {
        when (v) {
            is EditText -> {
                v.setText(text)
                v.setSelection(text.length)
            }

            is TextView -> v.text = text
            else -> throw JvmBridgeException("View is not a TextView/EditText: ${v.javaClass.name}")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Key events
    // -----------------------------------------------------------------------------------------

    /**
     * Dispatch KEY_DOWN + KEY_UP for [keyCode] to the top activity. Common codes: BACK=4, ENTER=66.
     */
    fun pressKey(keyCode: Int) {
        val act =
            getTopMostActivity() ?: throw JvmBridgeException("No top-most activity for key event")
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        act.dispatchKeyEvent(down)
        act.dispatchKeyEvent(up)
    }
}
