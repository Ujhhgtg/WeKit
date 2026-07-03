package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

@Feature(name = "左划引用消息", categories = ["聊天"], description = "在消息上左划以引用")
object SwipeToQuote : SwitchFeature(), IResolveDex,
    WeChatMessageViewApi.ICreateViewListener {

    // Mutable per-view gesture state. RecyclerView recycles message views, so chattingContext is
    // refreshed on every onBindView (see onCreateView) rather than captured once.
    private class SwipeState(
        val touchSlop: Int,
        val triggerThreshold: Float,
        var chattingContext: Any? = null,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var isDragging: Boolean = false,
        var triggered: Boolean = false,
    )

    // WeakHashMap: entries are removed automatically once the recycled row view is GC'd.
    private val states: MutableMap<View, SwipeState> =
        Collections.synchronizedMap(WeakHashMap())

    private val springInterpolator = OvershootInterpolator(1.3f)

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        states.clear()
    }

    // ── row binding: attach the swipe listener + keep chattingContext fresh ─────

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

        // onBindView may (re)install WeChat's own OnTouchListener on the row on every bind. If we
        // only set ours once, a later re-bind would clobber it and the swipe silently stops working.
        // So we re-install our wrapper every bind, delegating to whatever listener is currently
        // attached (unless it is already ours).
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
            // Always let WeChat's listener observe the event too, but our return value decides
            // whether the row's click / long-press path proceeds.
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
    // The message bubble is clickable / long-pressable, so it consumes ACTION_DOWN in its own
    // onTouchEvent and its onInterceptTouchEvent is never called for subsequent MOVE events. An
    // OnTouchListener (which runs ahead of both onTouchEvent and the click) is the reliable place to
    // catch the swipe: we detect the horizontal drag at scaledTouchSlop and immediately call
    // requestDisallowInterceptTouchEvent(true) so the RecyclerView / list can't steal the gesture.
    private fun handleSwipe(v: View, s: SwipeState, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.startX = event.rawX
                s.startY = event.rawY
                s.isDragging = false
                s.triggered = false
                // Return false so the row still receives the click / long-press on this stream.
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - s.startX
                val dy = event.rawY - s.startY
                if (!s.isDragging && dx < 0 && abs(dx) > s.touchSlop && abs(dx) > abs(dy)) {
                    s.isDragging = true
                    // Win the gesture from the list before it crosses its slop.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    // Cancel the pending click / pressed state and the queued long-press callback:
                    // once we start consuming MOVE events they no longer reach onTouchEvent, so its
                    // own movement-cancel never runs and the long-press menu would still pop.
                    v.isPressed = false
                    v.cancelLongPress()
                }
                if (s.isDragging) {
                    v.translationX = dx.coerceIn(-s.triggerThreshold, 0f)
                    // Haptic tick tracks the live fireable state: buzz when crossing INTO the fire
                    // zone, and re-arm when sliding back out so a re-cross buzzes again.
                    val past = dx <= -s.triggerThreshold
                    if (past && !s.triggered) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    s.triggered = past
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - s.startX
                // Decide by the FINAL position, not whether the threshold was ever crossed: sliding
                // past the threshold and then back is an intentional cancel, so it must NOT fire.
                val fire = s.isDragging && dx <= -s.triggerThreshold
                if (s.isDragging) {
                    v.animate()
                        .translationX(0f)
                        .setDuration(250)
                        .setInterpolator(springInterpolator)
                        .start()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.isDragging = false
                    if (fire) s.chattingContext?.let { onSwipeLeft(v, it) }
                    // Consume so the row's click doesn't fire after a swipe.
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (s.isDragging) {
                    v.animate().translationX(0f).setDuration(150).start()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.isDragging = false
                }
                false
            }

            else -> false
        }
    }

    // ── quote on swipe ─────────────────────────────────────────────────────────

    private fun onSwipeLeft(originalView: View, chattingContext: Any) {
        val apiMan = chattingContext.reflekt()
            .firstField { type = WeServiceApi.apiManagerClass }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, classChattingUiFootComponent.clazz)
        val chatFooter = api.reflekt()
            .firstField { type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
            .get()!!
        val quoteMethod = chatFooter.reflekt()
            .firstMethod {
                parameters { params -> params[0] == WeMessageApi.classMsgInfo.clazz }
                returnType = Boolean::class
            }.self
        val chatHolder = originalView.tag.reflekt().getField("chatHolder", true)!!
        val msgInfo = methodGetMsgInfo.method.invoke(null, chatHolder, chattingContext)
        if (quoteMethod.parameterCount == 1) quoteMethod.invoke(chatFooter, msgInfo)
        else quoteMethod.invoke(chatFooter, msgInfo, null)
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
