package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.graphics.toColorInt
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.ExposurePlus1Icon
import dev.ujhhgtg.wekit.ui.utils.FormatQuoteIcon
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

@Feature(name = "滑动消息快捷操作", categories = ["聊天"], description = "在消息上滑动以引用, 并可选复读或编辑为次要操作")
object SwipeMessageOperations : ClickableFeature(), IResolveDex,
    WeChatMessageViewApi.ICreateViewListener {

    // Mutable per-view gesture state. RecyclerView recycles message views, so chattingContext is
    // refreshed on every onBindView (see onCreateView) rather than captured once.
    private class SwipeState(
        val touchSlop: Int,
        val triggerThreshold: Float,
        var chattingContext: Any? = null,
        var msgInfo: MessageInfo? = null,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var dragDirection: DragDirection? = null,
        var triggered: Boolean = false,
        var actionOverlay: SwipeActionOverlay? = null,
    )

    private enum class DragDirection { LEFT, RIGHT }

    // WeakHashMap: entries are removed automatically once the recycled row view is GC'd.
    private val states: MutableMap<View, SwipeState> =
        Collections.synchronizedMap(WeakHashMap())

    private val springInterpolator = OvershootInterpolator(1.3f)

    private val overlayInterpolator = OvershootInterpolator(0.7f)

    private var enableSecondary by prefOption("swipe_to_quote_or_repeat_right_repeat", false)
    private var swapDirections by prefOption("swipe_to_quote_or_repeat_swap_dir", false)
    // When true, the secondary (non-quote) swipe action is "edit" instead of "repeat".
    private var useEditInsteadOfRepeat by prefOption("swipe_secondary_action_edit", false)

    // com.tencent.mm.ui.chatting.viewitems.ChattingItemContainer (obfuscated: xg / li). This
    // RelativeLayout is the SHARED root of every message item type (each ChattingItem.F() wraps its
    // layout in `new <this>(inflater, layoutRes)`), and it is exactly the itemView handed to
    // onCreateView below. The clickable / long-pressable message bubble is a CHILD of it, so the
    // container only sees MOVE events after it intercepts them — which is why we must hook
    // onInterceptTouchEvent here rather than rely on an OnTouchListener alone. Scoping the hook to
    // this one class (instead of global ViewGroup) keeps the blast radius tiny.
    private val classChattingItemContainer by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingItemContainer",
                "warn!!! cacheSize:%s sysSize:%s"
            )
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)

        // Steal the horizontal-left drag from the child bubble before it becomes a click / long
        // press. onInterceptTouchEvent is the ONLY place the container can claim a MOVE stream that a
        // clickable child already owns; an OnTouchListener can't intercept. Once we return true here,
        // the tail (MOVE/UP/CANCEL) is delivered to the container itself and handled by the
        // OnTouchListener attached in onCreateView.
        classChattingItemContainer.reflekt()
            .firstMethod { name = "onInterceptTouchEvent" }
            .hookAfter {
                val v = thisObject as? ViewGroup ?: return@hookAfter
                val s = states[v] ?: return@hookAfter
                val event = args[0] as MotionEvent
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        s.startX = event.rawX
                        s.startY = event.rawY
                        s.dragDirection = null
                        s.triggered = false
                        s.actionOverlay?.dismiss(animated = false)
                        s.actionOverlay = null
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - s.startX
                        val dy = event.rawY - s.startY
                        if (s.dragDirection == null) {
                            detectDragDirection(dx, dy, s)?.let {
                                s.dragDirection = it
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        if (s.dragDirection != null) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            result = true
                        }
                    }
                }
            }
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        states.clear()
    }

    // ── row binding: register state + attach the swipe listener, keep context fresh ─

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)

        val state = states.getOrPut(view) {
            val ctx = view.context
            SwipeState(
                touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop,
                triggerThreshold = 60.dpToPx(ctx).toFloat(),
            )
        }
        state.chattingContext = chattingContext
        state.msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        // Re-install our wrapper every bind, delegating to whatever listener is currently attached
        // (unless it is already ours), mirroring SwipeToDeleteConversation.
        attachSwipeListener(view, state)
    }

    // Marks our wrapper so a re-bind can tell its own listener apart from WeChat's.
    private class SwipeTouchListener(
        val state: SwipeState,
        val delegate: View.OnTouchListener?,
    ) : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val consumed = handleSwipe(v, state, event)
            runCatching { delegate?.onTouch(v, event) }
            return consumed
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeListener(view: View, state: SwipeState) {
        val current = getAttachedTouchListener(view)
        if (current is SwipeTouchListener) return  // already wrapped for this stream
        view.setOnTouchListener(SwipeTouchListener(state, current))
    }

    // Reads the View's current OnTouchListener out of its ListenerInfo, so we can chain to WeChat's.
    private fun getAttachedTouchListener(view: View): View.OnTouchListener? = runCatching {
        val info = view.reflekt()
            .firstFieldOrNull { name = "mListenerInfo"; superclass() }
            ?.get() ?: return null
        info.reflekt()
            .firstFieldOrNull { name = "mOnTouchListener" }
            ?.get() as? View.OnTouchListener
    }.getOrNull()

    // ── gesture ──────────────────────────────────────────────────────────────
    //
    // This handles TWO entry paths into the same container:
    //   • Bubble (clickable child owns ACTION_DOWN): onInterceptTouchEvent above records the start
    //     and steals the stream once it is a left-drag, so the listener only sees MOVE onward with
    //     isDragging already set. The DOWN branch here never runs.
    //   • Blank area right of the bubble (no clickable descendant under the touch): ACTION_DOWN
    //     falls through to this listener. The container is a non-clickable RelativeLayout, so we must
    //     consume the DOWN (return true) or it receives no further MOVE events. We then detect the
    //     drag here ourselves, since onInterceptTouchEvent is no longer called once the container is
    //     the touch target.
    // requestDisallowInterceptTouchEvent(true) is deferred until a left-drag is confirmed, so a
    // vertical scroll starting on blank space still reaches the RecyclerView.
    private fun handleSwipe(v: View, s: SwipeState, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.startX = event.rawX
                s.startY = event.rawY
                s.dragDirection = null
                s.triggered = false
                s.actionOverlay?.dismiss(animated = false)
                s.actionOverlay = null
                // Claim so the non-clickable container keeps receiving the stream (blank-area path).
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (s.dragDirection == null) {
                    val dx = event.rawX - s.startX
                    val dy = event.rawY - s.startY
                    detectDragDirection(dx, dy, s)?.let {
                        s.dragDirection = it
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                val direction = s.dragDirection
                if (direction != null) {
                    val dx = event.rawX - s.startX
                    v.translationX = when (direction) {
                        DragDirection.LEFT -> dx.coerceIn(-s.triggerThreshold, 0f)
                        DragDirection.RIGHT -> dx.coerceIn(0f, s.triggerThreshold)
                    }
                    val overlayProgress = when (direction) {
                        DragDirection.LEFT -> (-dx / s.triggerThreshold).coerceIn(0f, 1f)
                        DragDirection.RIGHT -> (dx / s.triggerThreshold).coerceIn(0f, 1f)
                    }
                    updateActionOverlay(v, s, direction, overlayProgress)
                    // Haptic tick tracks the live fireable state: buzz when crossing INTO the fire
                    // zone, and re-arm when sliding back out so a re-cross buzzes again.
                    val past = when (direction) {
                        DragDirection.LEFT -> dx <= -s.triggerThreshold
                        DragDirection.RIGHT -> dx >= s.triggerThreshold
                    }
                    if (past && !s.triggered) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    s.triggered = past
                    true
                } else {
                    // Not (yet) a horizontal drag; don't consume, so a vertical scroll can still be
                    // intercepted by the RecyclerView.
                    false
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - s.startX
                // Decide by the FINAL position, not whether the threshold was ever crossed: sliding
                // past the threshold and then back is an intentional cancel, so it must NOT fire.
                val direction = s.dragDirection
                val fire = when (direction) {
                    DragDirection.LEFT -> dx <= -s.triggerThreshold
                    DragDirection.RIGHT -> dx >= s.triggerThreshold
                    null -> false
                }
                if (direction != null) {
                    v.animate()
                        .translationX(0f)
                        .setDuration(250)
                        .setInterpolator(springInterpolator)
                        .start()
                    s.actionOverlay?.dismiss(direction, animated = true)
                    s.actionOverlay = null
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.dragDirection = null
                    if (fire) {
                        if (isSecondaryDirection(direction)) {
                            if (useEditInsteadOfRepeat) onSwipeEdit(v, s) else onSwipeRepeat(v, s)
                        } else {
                            s.chattingContext?.let { onSwipeQuote(v, it) }
                        }
                    }
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (s.dragDirection != null) {
                    val direction = s.dragDirection!!
                    v.animate().translationX(0f).setDuration(150).start()
                    s.actionOverlay?.dismiss(direction, animated = true)
                    s.actionOverlay = null
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.dragDirection = null
                }
                false
            }

            else -> false
        }
    }

    private fun updateActionOverlay(
        row: View,
        s: SwipeState,
        direction: DragDirection,
        rawProgress: Float,
    ) {
        val overlay = s.actionOverlay ?: SwipeActionOverlay(
            row,
            isUseEdit = { useEditInsteadOfRepeat },
            isSecondaryDir = ::isSecondaryDirection,
        ).also { s.actionOverlay = it }
        val progress = overlayInterpolator.getInterpolation(rawProgress.coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
        overlay.update(row, direction, progress)
    }

    private class SwipeActionOverlay(
        row: View,
        private val isUseEdit: () -> Boolean,
        private val isSecondaryDir: (DragDirection) -> Boolean,
    ) {
        private val root = row.rootView as? ViewGroup
        private val size = 44.dpToPx(row.context)
        private val edgeMargin = 16.dpToPx(row.context)
        private val quoteIcon = SwipeActionIconView(row.context, FormatQuoteIcon, SwipeActionColor.GREEN)
        private val repeatIcon = SwipeActionIconView(row.context, ExposurePlus1Icon, SwipeActionColor.BLUE)
        private val editIcon = SwipeActionIconView(row.context, EditIcon, SwipeActionColor.ORANGE)
        private val rootLocation = IntArray(2)
        private val rowLocation = IntArray(2)

        init {
            root?.overlay?.add(quoteIcon)
            root?.overlay?.add(repeatIcon)
            root?.overlay?.add(editIcon)
            quoteIcon.layout(0, 0, size, size)
            repeatIcon.layout(0, 0, size, size)
            editIcon.layout(0, 0, size, size)
            quoteIcon.alpha = 0f
            repeatIcon.alpha = 0f
            editIcon.alpha = 0f
        }

        fun update(row: View, direction: DragDirection, progress: Float) {
            val root = root ?: return
            if (root.width <= 0) return

            root.getLocationOnScreen(rootLocation)
            row.getLocationOnScreen(rowLocation)
            val y = (rowLocation[1] - rootLocation[1] + (row.height - size) / 2f)
                .coerceIn(edgeMargin.toFloat(), (root.height - size - edgeMargin).toFloat())

            val leftHiddenX = -size - edgeMargin.toFloat()
            val leftVisibleX = edgeMargin.toFloat()
            val rightHiddenX = root.width + edgeMargin.toFloat()
            val rightVisibleX = root.width - size - edgeMargin.toFloat()

            // Secondary icon selection: read fresh from pref every frame so a settings change
            // mid-gesture (unlikely but possible) picks up immediately.
            val secondaryIcon = if (isUseEdit()) editIcon else repeatIcon
            val hiddenSecondary = if (isUseEdit()) repeatIcon else editIcon

            quoteIcon.y = y
            repeatIcon.y = y
            editIcon.y = y
            quoteIcon.translationX = 0f
            repeatIcon.translationX = 0f
            editIcon.translationX = 0f

            // Keep the unused secondary icon parked offscreen and invisible.
            hiddenSecondary.alpha = 0f
            hiddenSecondary.x = rightHiddenX

            // Which icon is active depends on the logical action bound to this physical direction,
            // not the direction itself — so both icons move correctly when directions are swapped.
            val (activeIcon, idleIcon) = if (isSecondaryDir(direction))
                Pair(secondaryIcon, quoteIcon) else Pair(quoteIcon, secondaryIcon)

            if (direction == DragDirection.LEFT) {
                activeIcon.x = lerp(rightHiddenX, rightVisibleX, progress)
                activeIcon.alpha = progress
                idleIcon.x = leftHiddenX
                idleIcon.alpha = 0f
            } else {
                activeIcon.x = lerp(leftHiddenX, leftVisibleX, progress)
                activeIcon.alpha = progress
                idleIcon.x = rightHiddenX
                idleIcon.alpha = 0f
            }
        }

        fun dismiss(direction: DragDirection = DragDirection.LEFT, animated: Boolean) {
            if (animated) {
                val root = root
                if (root == null || root.width <= 0) {
                    remove()
                    return
                }

                val secondaryIcon = if (isUseEdit()) editIcon else repeatIcon
                val targetIcon = if (isSecondaryDir(direction)) secondaryIcon else quoteIcon
                val offscreenX = when (direction) {
                    DragDirection.LEFT -> root.width + edgeMargin.toFloat()
                    DragDirection.RIGHT -> -size - edgeMargin.toFloat()
                }
                targetIcon.animate()
                    .x(offscreenX)
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction(::remove)
                    .start()
            } else {
                remove()
            }
        }

        private fun remove() {
            quoteIcon.animate().cancel()
            repeatIcon.animate().cancel()
            editIcon.animate().cancel()
            quoteIcon.translationX = 0f
            repeatIcon.translationX = 0f
            editIcon.translationX = 0f
            root?.overlay?.remove(quoteIcon)
            root?.overlay?.remove(repeatIcon)
            root?.overlay?.remove(editIcon)
        }

        private fun lerp(start: Float, end: Float, progress: Float): Float {
            return start + (end - start) * progress
        }
    }

    // Icon accent palette, split by light/dark mode. The circle is a soft-tinted backdrop; the icon
    // sits on top in a vivid variant of the same hue so it reads clearly against either theme.
    private enum class SwipeActionColor(
        val iconLight: Int,
        val circleLight: Int,
        val iconDark: Int,
        val circleDark: Int,
    ) {
        GREEN(
            iconLight = "#2E7D32".toColorInt(),
            circleLight = "#C8E6C9".toColorInt(),
            iconDark = "#A5D6A7".toColorInt(),
            circleDark = "#1B3A1E".toColorInt(),
        ),
        BLUE(
            iconLight = "#1565C0".toColorInt(),
            circleLight = "#BBDEFB".toColorInt(),
            iconDark = "#90CAF9".toColorInt(),
            circleDark = "#152A47".toColorInt(),
        ),
        ORANGE(
            iconLight = "#BF360C".toColorInt(),
            circleLight = "#FFCCBC".toColorInt(),
            iconDark = "#FFAB91".toColorInt(),
            circleDark = "#3E1000".toColorInt(),
        );

        fun iconColor(dark: Boolean) = if (dark) iconDark else iconLight
        fun circleColor(dark: Boolean) = if (dark) circleDark else circleLight
    }

    @SuppressLint("AppCompatCustomView")
    private class SwipeActionIconView(
        context: Context,
        iconDrawable: Drawable,
        color: SwipeActionColor,
    ) : ImageView(context) {
        init {
            val dark = context.isDarkMode
            scaleType = ScaleType.CENTER_INSIDE
            // Tint a fresh copy so the shared icon singleton keeps its default color.
            val drawable = iconDrawable.constantState?.newDrawable()?.mutate() ?: iconDrawable.mutate()
            drawable.colorFilter = PorterDuffColorFilter(color.iconColor(dark), PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            imageTintList = null
            // Circular backdrop behind the glyph.
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color.circleColor(dark))
            }
            val pad = 10.dpToPx(context)
            setPadding(pad, pad, pad, pad)
        }
    }

    // Returns whether a physical direction maps to the secondary action (repeat or edit, vs. quote).
    // secondary direction = RIGHT when not swapped, LEFT when swapped.
    private fun isSecondaryDirection(dir: DragDirection) = (dir == DragDirection.RIGHT) xor swapDirections

    private fun detectDragDirection(dx: Float, dy: Float, s: SwipeState): DragDirection? {
        if (abs(dx) <= s.touchSlop || abs(dx) <= abs(dy)) return null
        val dir = if (dx < 0) DragDirection.LEFT else DragDirection.RIGHT
        return if (isSecondaryDirection(dir)) {
            // Secondary direction: gated by the master toggle, then by per-action type support.
            if (!enableSecondary) null
            else if (useEditInsteadOfRepeat) {
                if (s.msgInfo?.type?.isText == true) dir else null
            } else {
                if (s.msgInfo?.let(RepeatMessages::isSupported) == true) dir else null
            }
        } else {
            dir // Quote side: always active.
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useEdit by remember { mutableStateOf(useEditInsteadOfRepeat) }
            var secondary by remember { mutableStateOf(enableSecondary) }
            var swap by remember { mutableStateOf(swapDirections) }
            val sec = if (useEdit) "编辑" else "复读"

            AlertDialogContent(
                title = { Text("滑动消息快捷操作") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                secondary = !secondary
                                enableSecondary = secondary
                            },
                            trailingContent = {
                                Switch(checked = secondary, onCheckedChange = null)
                            },
                            supportingContent = { Text("启用后, 在支持的消息上可调用次要操作以$sec") },
                            headlineContent = { Text("启用次要操作") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                useEdit = !useEdit
                                useEditInsteadOfRepeat = useEdit
                            },
                            trailingContent = {
                                Switch(checked = useEdit, onCheckedChange = null)
                            },
                            supportingContent = { Text("启用后, 次要划动操作变为「编辑」 (仅文字消息); 关闭时为「复读」") },
                            headlineContent = { Text("使用「编辑」而非「复读」作为次要操作") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                swap = !swap
                                swapDirections = swap
                            },
                            trailingContent = {
                                Switch(checked = swap, onCheckedChange = null)
                            },
                            supportingContent = { Text("启用后, 左划$sec, 右划引用") },
                            headlineContent = { Text("对调左右划") },
                        )
                    }
                }
            )
        }
    }

    // ── swipe actions ──────────────────────────────────────────────────────────

    private fun onSwipeQuote(originalView: View, chattingContext: Any) {
        val apiMan = chattingContext.reflekt()
            .firstField { type = WeServiceApi.apiManagerClass }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, classChattingUiFootComponent.clazz)
        val chatFooter = api.reflekt()
            .firstField { type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
            .get()!! as ChatFooter
        val chatHolder = originalView.tag.reflekt().getField("chatHolder", true)!!
        val msgInfo = methodGetMsgInfo.method.invoke(null, chatHolder, chattingContext)!!
        WeMessageApi.setReferringMessage(chatFooter, msgInfo)
    }

    private fun onSwipeRepeat(view: View, s: SwipeState) {
        val msgInfo = s.msgInfo ?: return
        if (!enableSecondary || !RepeatMessages.isSupported(msgInfo)) return

        val context = view.context
        CoroutineScope(Dispatchers.IO).launch {
            val sent = RepeatMessages.repeatMessage(msgInfo)
            showToastSuspend(context, if (sent) "已复读" else "复读失败! 可能为不支持的消息类型")
        }
    }

    private fun onSwipeEdit(view: View, s: SwipeState) {
        val msgInfo = s.msgInfo ?: return
        if (msgInfo.type?.isText != true) return
        QuickRevokeAndEdit.quickRevokeAndEdit(view.context, msgInfo)
    }

    private val classChattingUiFootComponent by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingUI.FootComponent",
                "onNotifyChange event %s talker %s"
            )
        }
    }

    private val methodGetMsgInfo by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher { usingEqStrings("ItemDataTag", "getCurrentMsg2 err") }
    }
}
