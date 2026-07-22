package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
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
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.DexClassDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

@Feature(
    name = "左划对话菜单",
    categories = ["聊天"],
    description = "在主页对话列表向左滑动展开菜单, 可对对话执行隐藏或删除等操作\n点击配置可选择启用「置顶」和「免打扰」快捷按钮"
)
object SwipeConversationOperations : ClickableFeature(), IResolveDex {

    // Layout / gesture tuning.
    private const val BUTTON_WIDTH_DP = 72  // width of each action button
    private const val FLY_OUT_THRESHOLD_DP = 220  // drag past this (left) on release => fly out + delete
    private const val COLOR_PIN = 0xFF007AFF.toInt()    // iOS-ish blue for 置顶
    private const val COLOR_MUTE = 0xFF8E8E93.toInt()   // iOS-ish grey for 免打扰
    private const val COLOR_HIDE = 0xFFF5A623.toInt()   // iOS-ish amber for 隐藏
    private const val COLOR_DELETE = 0xFFFF3B30.toInt() // iOS-ish red for 删除

    private var pinButtonEnabled by prefOption("swipe_pin_button", false)
    private var muteButtonEnabled by prefOption("swipe_mute_button", false)


    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var pinEnabled by remember { mutableStateOf(pinButtonEnabled) }
            var muteEnabled by remember { mutableStateOf(muteButtonEnabled) }

            AlertDialogContent(
                title = { Text("左划菜单配置") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                pinEnabled = !pinEnabled
                                pinButtonEnabled = pinEnabled
                            },
                            leadingContent = null,
                            trailingContent = {
                                Switch(
                                    checked = pinEnabled,
                                    onCheckedChange = null
                                )
                            },
                            supportingContent = { Text("在「隐藏」左侧显示置顶快捷按钮") },
                            headlineContent = { Text("置顶 / 取消置顶") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                muteEnabled = !muteEnabled
                                muteButtonEnabled = muteEnabled
                            },
                            leadingContent = null,
                            trailingContent = {
                                Switch(
                                    checked = muteEnabled,
                                    onCheckedChange = null
                                )
                            },
                            supportingContent = { Text("在「隐藏」左侧显示免打扰快捷按钮") },
                            headlineContent = { Text("免打扰 / 取消免打扰") },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = onDismiss) { Text("关闭") }
                }
            )
        }
    }

    // Per-row gesture + reveal state. The list recycles row views, so talker / conversation are
    // refreshed on every getView bind; the parked-open offset persists across binds of the SAME
    // view object (WeakHashMap key) which is what we want — but we reset it on rebind because a
    // recycled view now represents a different conversation.
    private class SwipeState(
        val touchSlop: Int,
        // Drag past this (leftwards) on release => fly the row out and delete.
        val flyOutThreshold: Float,
        var talker: String? = null,
        var conversation: Any? = null,
        // The FrameLayout we insert to host content+panel; non-null once this row is set up (guard).
        var wrapper: View? = null,
        // The content view we translate (cj0), the action panel behind it, and its buttons.
        var content: View? = null,
        var panel: View? = null,
        // All possible buttons (slots); a null slot means not created yet.
        var pinBtn: View? = null,
        var muteBtn: View? = null,
        var hideBtn: View? = null,
        var delBtn: View? = null,
        // Active buttons in left-to-right order; rebuilt on each bind from current settings.
        // applyTranslation uses this list to position buttons evenly across the revealed strip.
        var activeButtons: List<View> = emptyList(),
        // Reveal width = activeButtons.size * BUTTON_WIDTH_DP; refreshed on each bind.
        var revealWidth: Float = 0f,
        var startX: Float = 0f,
        var startY: Float = 0f,
        // translationX of the content at gesture start (0 when closed, -revealWidth when parked open).
        var dragBase: Float = 0f,
        var isDragging: Boolean = false,
        var isOpen: Boolean = false,
        // Whether the row was already parked-open when THIS gesture started.
        var startedOpen: Boolean = false,
        var flungOut: Boolean = false,
        // Cached state for button labels; refreshed on ACTION_DOWN so labels are always current.
        var isPinned: Boolean = false,
        var isDnd: Boolean = false,
    )

    // WeakHashMap: entries are removed automatically once the recycled row view is GC'd.
    private val states: MutableMap<View, SwipeState> =
        Collections.synchronizedMap(WeakHashMap())

    // At most one row is open at a time (iOS behavior). Weak so it can't leak a row view.
    @SuppressLint("StaticFieldLeak")
    private var openState: SwipeState? = null

    private val settleInterpolator = DecelerateInterpolator()

    private const val TAG = "SwipeToDeleteConversation"

    // WeChat has TWO home conversation-list adapters and picks one at runtime in MainUI.onCreate
    // (o75.s.f347101a.b()): the legacy ListView adapter com.tencent.mm.ui.conversation.p3
    // (ConversationWithCacheAdapter) and the newer MVVM adapter o75.v0
    // (ConversationAdapter.MvvmConversationAdapter). Both expose getView(int,View,ViewGroup) that
    // returns the clickable row root and getItem(position) -> com.tencent.mm.storage.m3, and both
    // re-install their own row OnTouchListener on every bind — so we hook getView on whichever is
    // present. allowFailure so a build that only ships one of them still resolves the other.
    private val classConversationAdapter by dexClass(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingStrings(
                "MicroMsg.ConversationWithCacheAdapter",
                "[getView] position="
            )
        }
    }

    private val classMvvmConversationAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter")
        }
    }

    override fun onEnable() {
        hookAdapter(classConversationAdapter)
        hookAdapter(classMvvmConversationAdapter)
    }

    override fun onDisable() {
        states.clear()
    }

    //── row binding: attach the swipe listener + keep talker / conversation fresh ─

    private fun hookAdapter(adapter: DexClassDelegate) {
        if (adapter.isPlaceholder) return
        adapter.reflekt().firstMethod { name = "getView"; parameterCount = 3 }
            .hookAfter {
                val view = result as? View ?: return@hookAfter
                val position = args[0] as? Int ?: return@hookAfter

                // getItem(position) -> com.tencent.mm.storage.m3(rconversation model).
                val conversation = runCatching {
                    thisObject!!.reflekt()
                        .firstMethod { name = "getItem"; parameterCount = 1 }
                        .invoke(position)
                }.getOrNull() ?: return@hookAfter

                val talker = runCatching {
                    conversation.reflekt()
                        .firstFieldOrNull { name = "field_username"; superclass() }
                        ?.get() as? String
                }.getOrNull()

                val ctx = view.context
                val state = states.getOrPut(view) {
                    SwipeState(
                        touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop,
                        flyOutThreshold = FLY_OUT_THRESHOLD_DP.dpToPx(ctx).toFloat(),
                    )
                }
                state.talker = talker
                state.conversation = conversation

                // Resolve the content view (cj0 = the row's first child) and (once) build the action
                // panel behind it. The recycled row now represents a different conversation, so any
                // leftover open/translation from its previous use must be reset to closed.
                setUpRow(view, state)
                // Refresh active buttons and reveal width from current settings every bind.
                rebindState(state, ctx)
                resetRow(state)

                // p3.getView (re)installs WeChat's own OnTouchListener on every bind — re-install
                // our wrapper every bind, delegating to whatever listener is currently attached.
                attachSwipeListener(view, state)
            }
    }

    // The row root (cj1) is a horizontal LinearLayout whose first child (cj0) is the full-width
    // content. Adding a sibling to that LinearLayout would reflow cj0, so instead we WRAP cj0 in a
    // FrameLayout (inserted at cj0's original index, inheriting its LayoutParams): the action panel
    // is pinned to the wrapper's right edge, cj0 sits on top and slides left to reveal it. Done once
    // per row and tagged so re-binds don't wrap twice.
    private fun setUpRow(row: View, s: SwipeState) {
        // Already wrapped this row (state is keyed by the row view, stable across recycles).
        if (s.wrapper != null) return

        val group = row as? ViewGroup ?: return

        // cj0 = first child of the row root.
        val content = group.getChildAt(0) ?: return
        val index = group.indexOfChild(content)
        val lp = content.layoutParams

        val wrapper = FrameLayout(group.context)
        val panel = buildActionPanel(group.context, s)

        group.removeViewAt(index)
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Panel spans the whole wrapper; buttons are positioned per-frame in applyTranslation.
        wrapper.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        wrapper.addView(content) // content on top
        wrapper.layoutParams = lp // take cj0's slot in the row LinearLayout
        wrapper.clipChildren = true
        group.addView(wrapper, index)

        s.wrapper = wrapper
        s.content = content
        s.panel = panel
        // Park the panel closed immediately.
        applyTranslation(s, 0f)
    }

    // Creates the action panel with ALL possible button slots. Buttons that are not currently
    // enabled are still created here (once, panel is never rebuilt) but excluded from
    // s.activeButtons, so they are never measured, positioned, or tappable when disabled.
    private fun buildActionPanel(context: Context, s: SwipeState): View {
        val panel = FrameLayout(context)

        fun button(label: String, bg: Int, onTap: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            maxLines = 1
            // Start at width 0: real width comes from applyTranslation after the first layout pass.
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            setOnClickListener { onTap() }
        }

        // Create all buttons. Order in panel: pin, mute, hide, delete (left to right when revealed).
        // 删除 is last and drawn on top so it can expand to cover others on over-drag.
        val pin = button("置顶", COLOR_PIN) { onAction(s, Action.PIN) }
        val mute = button("免打扰", COLOR_MUTE) { onAction(s, Action.MUTE) }
        val hide = button("隐藏", COLOR_HIDE) { onAction(s, Action.HIDE) }
        val del = button("删除", COLOR_DELETE) { onAction(s, Action.DELETE, context) }

        panel.addView(pin)
        panel.addView(mute)
        panel.addView(hide)
        panel.addView(del) // del on top so it can cover others when expanded

        s.pinBtn = pin
        s.muteBtn = mute
        s.hideBtn = hide
        s.delBtn = del
        return panel
    }

    // Called on every getView bind: rebuilds activeButtons from current settings and recomputes
    // revealWidth. This makes the reveal geometry correct even if the user toggles settings between
    // swipes, without re-wrapping the row.
    private fun rebindState(s: SwipeState, ctx: Context) {
        val active = mutableListOf<View>()
        if (pinButtonEnabled) s.pinBtn?.let { active += it }
        if (muteButtonEnabled) s.muteBtn?.let { active += it }
        s.hideBtn?.let { active += it }
        s.delBtn?.let { active += it }
        s.activeButtons = active
        s.revealWidth = (BUTTON_WIDTH_DP * active.size).dpToPx(ctx).toFloat()
    }

    // Positions the content and all action buttons for a given content offset (tx, <= 0).
    // reveal = -tx is how far the content has been pulled left. All buttons grow from width 0 to
    // fill the revealed strip [rowW - reveal, rowW], split evenly (reveal / n each).
    // On a second swipe (started open), dragging past the threshold smoothly widens 删除 LEFTWARD
    // to cover the other buttons — driven by factor t (0 at threshold → 1 at fly-out). gravity=CENTER
    // keeps labels centred as buttons grow.
    private fun applyTranslation(s: SwipeState, tx: Float) {
        val content = s.content ?: return
        content.translationX = tx

        val buttons = s.activeButtons
        if (buttons.isEmpty()) return

        val reveal = (-tx).coerceAtLeast(0f)
        s.panel?.visibility = if (reveal <= 0f) View.GONE else View.VISIBLE
        if (reveal <= 0f) return

        val rowW = (s.panel?.width?.takeIf { it > 0 } ?: content.width).toFloat()
        if (rowW <= 0f) return

        val n = buttons.size
        val stripLeft = rowW - reveal
        val btnW = reveal / n

        // Over-drag transition: only on a second swipe (started open), ramps 0→1 as drag passes
        // the reveal threshold toward the fly-out threshold. 删除 expands leftward to cover all.
        val t = if (s.startedOpen && s.flyOutThreshold > s.revealWidth) {
            ((reveal - s.revealWidth) / (s.flyOutThreshold - s.revealWidth)).coerceIn(0f, 1f)
        } else 0f

        // All buttons except 删除: evenly spaced, stay fixed as t grows.
        for (i in 0 until n - 1) {
            setButtonWidth(buttons[i], btnW.toInt())
            buttons[i].translationX = stripLeft + i * btnW
        }

        // 删除 (last button): left edge lerps from even-split position to stripLeft as t → 1,
        // so it expands from (1/n) of the strip to the full strip continuously.
        val delEvenLeft = stripLeft + (n - 1) * btnW
        val delLeft = delEvenLeft + (stripLeft - delEvenLeft) * t
        setButtonWidth(buttons[n - 1], (rowW - delLeft).toInt())
        buttons[n - 1].translationX = delLeft
    }

    // Rubber-band resistance: linear up to [limit], then heavily damped beyond.
    private fun rubberBand(tx: Float, limit: Float): Float {
        val over = -tx - limit
        return if (over <= 0f) tx else -(limit + over * 0.15f)
    }

    private fun setButtonWidth(v: View, w: Int) {
        val width = w.coerceAtLeast(0)
        if (v.layoutParams.width != width) {
            v.layoutParams = v.layoutParams.also { it.width = width }
            v.requestLayout()
        }
    }

    private fun resetRow(s: SwipeState) {
        s.isDragging = false
        s.isOpen = false
        s.flungOut = false
        s.dragBase = 0f
        s.content?.animate()?.cancel()
        applyTranslation(s, 0f)
        if (openState === s) openState = null
    }

    // Marks our wrapper so a re-bind can tell its own listener apart from WeChat's v3.
    private class SwipeTouchListener(
        val state: SwipeState,
        val delegate: View.OnTouchListener?,
    ) : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val consumed = handleSwipe(v, state, event)
            // Always let WeChat's listener observe the event too (it only sets a ripple hotspot and
            // returns false), but our return value decides whether the row's click path proceeds.
            runCatching { delegate?.onTouch(v, event) }
            return consumed
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeListener(view: View, state: SwipeState) {
        val current = getAttachedTouchListener(view)
        if (current is SwipeTouchListener) return // already wrapped for this stream
        view.setOnTouchListener(SwipeTouchListener(state, current))
    }

    // Reads the View's current OnTouchListener out of its ListenerInfo.
    private fun getAttachedTouchListener(view: View): View.OnTouchListener? = runCatching {
        val info = view.reflekt()
            .firstFieldOrNull { name = "mListenerInfo"; superclass() }
            ?.get() ?: return null
        info.reflekt()
            .firstFieldOrNull { name = "mOnTouchListener" }
            ?.get() as? View.OnTouchListener
    }.getOrNull()

    // ── gesture ──────────────────────────────────────────────────────────────
    private fun handleSwipe(v: View, s: SwipeState, event: MotionEvent): Boolean {
        val content = s.content ?: return false
        if (s.activeButtons.isEmpty()) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.startX = event.rawX
                s.startY = event.rawY
                s.isDragging = false
                s.dragBase = content.translationX
                s.startedOpen = s.isOpen
                // Refresh pin/mute state and update button labels now, before the gesture is
                // visible. Doing it here (not on every bind) avoids a DB read per list scroll.
                val talker = s.talker
                if (talker != null) {
                    runCatching {
                        s.isPinned = WeConversationApi.isPinned(talker)
                        s.isDnd = WeConversationApi.isDnd(talker)
                        (s.pinBtn as? TextView)?.text = if (s.isPinned) "取消置顶" else "置顶"
                        (s.muteBtn as? TextView)?.text = if (s.isDnd) "取消免打扰" else "免打扰"
                    }
                }
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - s.startX
                val dy = event.rawY - s.startY
                if (!s.isDragging && abs(dx) > s.touchSlop && abs(dx) > abs(dy)) {
                    if (dx < 0 || s.isOpen) {
                        s.isDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = false
                        v.cancelLongPress()
                    }
                }
                if (s.isDragging) {
                    val raw = (s.dragBase + dx).coerceAtMost(0f)
                    val tx = if (s.startedOpen) raw else rubberBand(raw, s.revealWidth)
                    applyTranslation(s, tx)
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                if (!s.isDragging) {
                    if (s.isOpen) {
                        settleClosed(s)
                        return true
                    }
                    return false
                }
                s.isDragging = false
                val tx = content.translationX
                when {
                    !s.startedOpen -> {
                        if (tx <= -s.revealWidth * 0.25f) settleOpen(s) else settleClosed(s)
                    }

                    tx <= -s.flyOutThreshold -> flyOutAndDelete(v, s)
                    tx <= -s.revealWidth * 0.5f -> settleOpen(s)
                    else -> settleClosed(s)
                }
                true
            }

            else -> false
        }
    }

    private fun settleOpen(s: SwipeState) {
        openState?.takeIf { it !== s }?.let { settleClosed(it) }
        animateTo(s, -s.revealWidth)
        s.isOpen = true
        openState = s
    }

    private fun settleClosed(s: SwipeState) {
        animateTo(s, 0f)
        s.isOpen = false
        if (openState === s) openState = null
    }

    private fun flyOutAndDelete(v: View, s: SwipeState) {
        if (s.flungOut) return
        s.flungOut = true
        if (openState === s) openState = null
        animateTo(s, -v.width.toFloat()) { onAction(s, Action.DELETE, v.context) }
    }

    private fun animateTo(s: SwipeState, targetTx: Float, onEnd: (() -> Unit)? = null) {
        val content = s.content ?: return
        content.animate()
            .translationX(targetTx)
            .setDuration(200)
            .setInterpolator(settleInterpolator)
            .setUpdateListener { applyTranslation(s, content.translationX) }
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    // Actions the swipe buttons can trigger.
    private enum class Action { PIN, MUTE, HIDE, DELETE }

    // Executes the action for a row (button tap or fly-out).
    // [context] must be provided for DELETE so the confirmation dialog can attach to the Activity.
    private fun onAction(s: SwipeState, action: Action, context: Context? = null) {
        val talker = s.talker
        val conversation = s.conversation
        if (talker.isNullOrBlank()) return
        runOnUiThread {
            when (action) {
                Action.DELETE -> {
                    settleClosed(s)
                    if (openState === s) openState = null
                    val ctx = context ?: run {
                        WeLogger.w(TAG, "DELETE action missing context for $talker")
                        return@runOnUiThread
                    }
                    showComposeDialog(ctx) {
                        AlertDialogContent(
                            title = { Text("删除对话") },
                            text = { Text("确定删除该对话? 此操作将同时删除所有消息记录") },
                            confirmButton = {
                                Button(onClick = {
                                    onDismiss()
                                    WeConversationApi.deleteConversation(talker, conversation)
                                    showToast("已删除")
                                }) { Text("删除") }
                            },
                            dismissButton = {
                                TextButton(onDismiss) { Text("取消") }
                            }
                        )
                    }
                }

                Action.HIDE -> {
                    WeConversationApi.hideConversation(talker)
                    showToast("已隐藏")
                    settleClosed(s)
                    if (openState === s) openState = null
                }

                Action.PIN -> {
                    val newTop = !s.isPinned
                    WeConversationApi.setPinned(talker, newTop)
                    showToast(if (newTop) "已置顶" else "已取消置顶")
                    s.isPinned = newTop
                    (s.pinBtn as? TextView)?.text = if (newTop) "取消置顶" else "置顶"
                    settleClosed(s)
                    if (openState === s) openState = null
                }

                Action.MUTE -> {
                    val newMute = !s.isDnd
                    WeConversationApi.setDnd(talker, newMute)
                    showToast(if (newMute) "已开启免打扰" else "已关闭免打扰")
                    s.isDnd = newMute
                    (s.muteBtn as? TextView)?.text = if (newMute) "取消免打扰" else "免打扰"
                    settleClosed(s)
                    if (openState === s) openState = null
                }
            }
        }
    }
}
