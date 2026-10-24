package dev.ujhhgtg.wekit.features.api.agent

import android.graphics.Bitmap
import dev.ujhhgtg.wekit.agent.jvm.JvmValueBridge
import dev.ujhhgtg.wekit.agent.model.LlmImage
import dev.ujhhgtg.wekit.agent.ui.UiAutomator
import dev.ujhhgtg.wekit.agent.ui.UiImageSink
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentTool.Companion.BUILTIN_UI
import dev.ujhhgtg.wekit.features.core.AgentToolParam
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * The `builtin-ui` automation tools for WeAgent. Every function is discovered by the KSP scanner
 * via `@AgentTool(group = BUILTIN_UI)`. All read/query tools are `sideEffect = false` (ENABLED);
 * all action tools are `sideEffect = true` (MANUAL_APPROVAL).
 *
 * ## Perception
 * - `ui-dump-tree` — structured indented view-tree dump with handles, bounds, flags, and text.
 * - `ui-find-views` — query views by text / id / class substring.
 * - `ui-describe-view`— full attribute listing for a single view handle.
 * - `ui-screenshot` — capture the current screen (or a view subtree) as a PNG; stages the image in
 *   [UiImageSink] for injection into the next model request.
 *
 * ## Actions (all main-thread, all MANUAL_APPROVAL by default)
 * - `ui-click-view` — `View.performClick` on a handle.
 * - `ui-long-click-view` — `View.performLongClick` on a handle.
 * - `ui-set-text` — set text on a TextView/EditText handle.
 * - `ui-input-text` — set text on the currently-focused view.
 * - `ui-tap` — DOWN+UP at absolute screen coords.
 * - `ui-multi-tap` — repeated taps (连点 / double-tap).
 * - `ui-long-press` — held DOWN for N ms then UP.
 * - `ui-swipe` — smooth swipe between two screen points.
 * - `ui-touch-down` — start a held gesture (ACTION_DOWN).
 * - `ui-touch-move` — continue a held gesture (ACTION_MOVE).
 * - `ui-touch-up` — release a held gesture (ACTION_UP).
 * - `ui-press-key` — send a KeyEvent to the top activity.
 */
object WeUiAutomationToolBindings {

    // ------------------------------------------------------------------ perception (read-only)

    @AgentTool(
        name = "ui-dump-tree",
        description =
            "Dump the current WeChat window's view hierarchy as an indented text tree. " +
                "Each node is stored as a handle (#N) with its class, resource-id, on-screen bounds " +
                "(screen pixels), visibility, interaction flags, and text / content-description. Use " +
                "the returned handles with other ui-* or jvm-* tools. " +
                "`windowRef` (optional) is a handle to an Activity/Window/View to use instead of the " +
                "current top activity. " +
                "`maxDepth` limits recursion depth (0 = unlimited, default 0). " +
                "`onlyInteractive` = true shows only clickable/focusable/editable views. " +
                "`includeInvisible` = true includes INVISIBLE/GONE nodes.",
        sideEffect = false,
        group = BUILTIN_UI,
    )
    fun uiDumpTree(
        @AgentToolParam("Handle to Activity/Window/View root; null = current top activity")
        windowRef: String?,
        @AgentToolParam("Max recursion depth (0 = unlimited)") maxDepth: Int?,
        @AgentToolParam("Only show clickable/focusable/editable views") onlyInteractive: Boolean?,
        @AgentToolParam("Include INVISIBLE and GONE nodes") includeInvisible: Boolean?,
    ): String = guard {
        JvmValueBridge.onMain {
            val decor = UiAutomator.resolveDecor(windowRef)
            UiAutomator.dumpTree(
                decor = decor,
                maxDepth = maxDepth ?: 0,
                onlyInteractive = onlyInteractive ?: false,
                includeInvisible = includeInvisible ?: false,
            )
        }
    }

    @AgentTool(
        name = "ui-find-views",
        description =
            "Search the view tree for nodes matching all given filters " +
                "(substring match, case-insensitive). All filters are optional — if none given, " +
                "every view is listed. Returns handles and brief summaries. " +
                "`text` matches a view's text or content-description. " +
                "`id` matches the resource-id entry name. " +
                "`className` matches the simple class name.",
        sideEffect = false,
        group = BUILTIN_UI,
    )
    fun uiFindViews(
        @AgentToolParam("Handle to root view/activity; null = top activity") windowRef: String?,
        @AgentToolParam("Substring to match in view text or content-description") text: String?,
        @AgentToolParam("Substring to match in resource-id entry name") id: String?,
        @AgentToolParam("Substring to match in simple class name") className: String?,
        @AgentToolParam("Only return clickable/focusable/editable views") onlyInteractive: Boolean?,
    ): String = guard {
        JvmValueBridge.onMain {
            val decor = UiAutomator.resolveDecor(windowRef)
            UiAutomator.findViews(
                decor = decor,
                text = text,
                id = id,
                className = className,
                onlyInteractive = onlyInteractive ?: false,
            )
        }
    }

    @AgentTool(
        name = "ui-describe-view",
        description =
            "Return a full attribute description (class, id, bounds, visibility, flags, " +
                "text, tag, child-count) for a single view. `ref` is a #N handle from ui-dump-tree or " +
                "ui-find-views.",
        sideEffect = false,
        group = BUILTIN_UI,
    )
    fun uiDescribeView(
        @AgentToolParam("Handle #N of the view to describe") ref: String,
    ): String = guard {
        JvmValueBridge.onMain {
            UiAutomator.describeView(UiAutomator.resolveView(ref))
        }
    }

    @AgentTool(
        name = "ui-screenshot",
        description =
            "Capture the current WeChat screen (or a view subtree) as a PNG image and " +
                "inject it into the conversation so you can see it. " +
                "Only works when the current session's model has 'supportsVision' enabled. " +
                "`viewRef` (optional): a #N handle to capture just that view's subtree; " +
                "null = whole window. " +
                "`maxDimension` (optional): max width or height in pixels after downscaling (default 1024). " +
                "Returns a short acknowledgement; the actual image is delivered as a separate message.",
        sideEffect = false, // read-only from WeChat's perspective; gated separately by
        // ToolVisibility.visionTools
        group = BUILTIN_UI,
    )
    suspend fun uiScreenshot(
        @AgentToolParam("Handle #N to capture a subtree; null = whole window") viewRef: String?,
        @AgentToolParam("Max width/height after downscaling (default 1024)") maxDimension: Int?,
    ): String {
        val sink =
            coroutineContext[UiImageSink]
                ?: return "Error: UiImageSink not installed — this tool must be called from the agent engine."
        return guard {
            val maxDim = (maxDimension ?: 1024).coerceAtLeast(128)
            val bitmap = JvmValueBridge.onMain {
                val target =
                    if (!viewRef.isNullOrBlank()) UiAutomator.resolveView(viewRef)
                    else UiAutomator.resolveDecor(null)
                UiAutomator.screenshot(target, maxDim)
            }
            val base64 = bitmapToBase64Png(bitmap)
            sink.stage(LlmImage(base64 = base64, mimeType = "image/png"))
            "截图已捕获（${bitmap.width}×${bitmap.height} px）。图片已注入下一条消息，你可以直接看到界面内容。"
        }
    }

    // ------------------------------------------------------------------ view-level actions

    @AgentTool(
        name = "ui-click-view",
        description =
            "Call View.performClick() on a view handle. Triggers click listeners just " +
                "as a real finger tap would. `ref` is a #N handle from ui-dump-tree / ui-find-views.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiClickView(
        @AgentToolParam("Handle #N of the view to click") ref: String,
    ): String = guard {
        JvmValueBridge.onMain { UiAutomator.clickView(UiAutomator.resolveView(ref)) }
        "Clicked $ref."
    }

    @AgentTool(
        name = "ui-long-click-view",
        description = "Call View.performLongClick() on a view handle.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiLongClickView(
        @AgentToolParam("Handle #N of the view to long-click") ref: String,
    ): String = guard {
        JvmValueBridge.onMain { UiAutomator.longClickView(UiAutomator.resolveView(ref)) }
        "Long-clicked $ref."
    }

    @AgentTool(
        name = "ui-set-text",
        description =
            "Set text on a TextView or EditText view handle. Replaces all existing " +
                "content and moves the cursor to the end, triggering TextWatcher callbacks.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiSetText(
        @AgentToolParam("Handle #N of the TextView/EditText") ref: String,
        @AgentToolParam("Text to set") text: String,
    ): String = guard {
        JvmValueBridge.onMain { UiAutomator.setText(UiAutomator.resolveView(ref), text) }
        "Text set on $ref."
    }

    @AgentTool(
        name = "ui-input-text",
        description =
            "Set text on whichever view currently has focus in the top activity " +
                "(typically the chat input bar). Equivalent to ui-set-text on the focused view.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiInputText(
        @AgentToolParam("Text to type into the focused view") text: String,
    ): String = guard {
        JvmValueBridge.onMain {
            val act =
                dev.ujhhgtg.wekit.utils.android.getTopMostActivity()
                    ?: throw dev.ujhhgtg.wekit.agent.jvm.JvmBridgeException("No top-most activity")
            val focused =
                act.currentFocus
                    ?: throw dev.ujhhgtg.wekit.agent.jvm.JvmBridgeException("No focused view")
            UiAutomator.setText(focused, text)
        }
        "Text set on focused view."
    }

    // ------------------------------------------------------------------ coordinate actions

    @AgentTool(
        name = "ui-tap",
        description =
            "Synthesise a DOWN+UP tap at absolute screen coordinates (pixels). " +
                "Use ui-dump-tree to discover bounds first. `windowRef` optionally scopes the " +
                "dispatch to a specific window handle.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiTap(
        @AgentToolParam("Screen X coordinate (pixels from left)") x: Double,
        @AgentToolParam("Screen Y coordinate (pixels from top)") y: Double,
        @AgentToolParam("Handle to target window/activity; null = top activity") windowRef: String?,
    ): String = guard {
        JvmValueBridge.onMain {
            val decor = UiAutomator.resolveDecor(windowRef)
            UiAutomator.tap(decor, x.toFloat(), y.toFloat())
        }
        "Tapped ($x, $y)."
    }

    @AgentTool(
        name = "ui-multi-tap",
        description =
            "Tap the same screen coordinate multiple times (连点 / double-tap). " +
                "`count` is the number of taps; `intervalMs` is the pause between taps (default 80ms).",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiMultiTap(
        @AgentToolParam("Screen X coordinate") x: Double,
        @AgentToolParam("Screen Y coordinate") y: Double,
        @AgentToolParam("Number of taps (e.g. 2 for double-tap)") count: Int?,
        @AgentToolParam("Interval between taps in ms (default 80)") intervalMs: Long?,
        @AgentToolParam("Handle to target window; null = top activity") windowRef: String?,
    ): String = guard {
        val n = (count ?: 2).coerceAtLeast(1)
        JvmValueBridge.onMain {
            val decor = UiAutomator.resolveDecor(windowRef)
            UiAutomator.multiTap(decor, x.toFloat(), y.toFloat(), n, intervalMs ?: 80L)
        }
        "Tapped ($x, $y) × $n."
    }

    @AgentTool(
        name = "ui-long-press",
        description =
            "Hold a press at screen coordinates for `durationMs` ms then release. " +
                "Default duration is 600ms (threshold for long-press context menus).",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiLongPress(
        @AgentToolParam("Screen X coordinate") x: Double,
        @AgentToolParam("Screen Y coordinate") y: Double,
        @AgentToolParam("Hold duration in ms (default 600)") durationMs: Long?,
        @AgentToolParam("Handle to target window; null = top activity") windowRef: String?,
    ): String = guard {
        val dur = (durationMs ?: 600L).coerceAtLeast(100L)
        JvmValueBridge.onMain {
            val decor = UiAutomator.resolveDecor(windowRef)
            UiAutomator.longPress(decor, x.toFloat(), y.toFloat(), dur)
        }
        "Long-pressed ($x, $y) for ${dur}ms."
    }

    @AgentTool(
        name = "ui-swipe",
        description =
            "Swipe from (x1, y1) to (x2, y2) over `durationMs` ms. Emits smooth " +
                "MOVE events (`steps`, default ≈60fps) so velocity-based gestures work correctly. " +
                "Use for scrolling lists, pulling drawers, or dismissing cards.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiSwipe(
        @AgentToolParam("Start screen X (pixels)") x1: Double,
        @AgentToolParam("Start screen Y (pixels)") y1: Double,
        @AgentToolParam("End screen X (pixels)") x2: Double,
        @AgentToolParam("End screen Y (pixels)") y2: Double,
        @AgentToolParam("Swipe duration in ms (default 300)") durationMs: Long?,
        @AgentToolParam("Number of intermediate MOVE events (default max(10, durationMs/16))")
        steps: Int?,
        @AgentToolParam("Handle to target window; null = top activity") windowRef: String?,
    ): String = guard {
        val dur = (durationMs ?: 300L).coerceAtLeast(20L)
        val n = (steps ?: maxOf(10, (dur / 16).toInt())).coerceAtLeast(2)
        JvmValueBridge.onMain {
            val decor = UiAutomator.resolveDecor(windowRef)
            UiAutomator.swipe(decor, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), dur, n)
        }
        "Swiped ($x1,$y1)→($x2,$y2) in ${dur}ms with $n steps."
    }

    @AgentTool(
        name = "ui-touch-down",
        description =
            "Start a held touch gesture (ACTION_DOWN) at screen coordinates. " +
                "Follow with ui-touch-move and ui-touch-up to compose multi-step gestures. " +
                "Any previously-active gesture is cancelled first.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiTouchDown(
        @AgentToolParam("Screen X coordinate") x: Double,
        @AgentToolParam("Screen Y coordinate") y: Double,
        @AgentToolParam("Handle to target window; null = top activity") windowRef: String?,
    ): String = guard {
        JvmValueBridge.onMain {
            val decor = UiAutomator.resolveDecor(windowRef)
            UiAutomator.touchDown(decor, x.toFloat(), y.toFloat())
        }
        "Gesture started at ($x, $y)."
    }

    @AgentTool(
        name = "ui-touch-move",
        description =
            "Move a held touch gesture to screen coordinates (ACTION_MOVE). " +
                "Must follow ui-touch-down.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiTouchMove(
        @AgentToolParam("Target screen X coordinate") x: Double,
        @AgentToolParam("Target screen Y coordinate") y: Double,
    ): String = guard {
        JvmValueBridge.onMain { UiAutomator.touchMove(x.toFloat(), y.toFloat()) }
    }

    @AgentTool(
        name = "ui-touch-up",
        description =
            "Release a held touch gesture (ACTION_UP) at screen coordinates. " +
                "Must follow ui-touch-down. Coordinates may differ from the last move.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiTouchUp(
        @AgentToolParam("Release screen X coordinate") x: Double,
        @AgentToolParam("Release screen Y coordinate") y: Double,
    ): String = guard {
        JvmValueBridge.onMain { UiAutomator.touchUp(x.toFloat(), y.toFloat()) }
    }

    @AgentTool(
        name = "ui-press-key",
        description =
            "Send a KEY_DOWN + KEY_UP event to the top WeChat activity. Common key " +
                "codes: BACK=4, ENTER=66, DEL=67, HOME=3, VOLUME_UP=24, VOLUME_DOWN=25. Pass the " +
                "integer key code from android.view.KeyEvent.",
        sideEffect = true,
        group = BUILTIN_UI,
    )
    fun uiPressKey(
        @AgentToolParam("Android KeyEvent key code integer (e.g. 66 for ENTER, 4 for BACK)")
        keyCode: Int,
    ): String = guard {
        JvmValueBridge.onMain { UiAutomator.pressKey(keyCode) }
        "Key $keyCode dispatched."
    }

    // ------------------------------------------------------------------ helpers

    private fun bitmapToBase64Png(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private inline fun guard(block: () -> String): String =
        try {
            block()
        } catch (e: Throwable) {
            "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
}
