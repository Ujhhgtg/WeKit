package dev.ujhhgtg.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.WeServiceApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import org.luckypray.dexkit.DexKitBridge
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

@SuppressLint("StaticFieldLeak")
@HookItem(path = "聊天/左划引用消息", desc = "在消息上左划以引用")
object SwipeToQuote : SwitchHookItem(), IResolvesDex,
    WeChatMessageViewApi.ICreateViewListener {

    // Mutable per-view gesture state, kept off the heap as long as the view lives
    private class SwipeState(
        val chattingContext: Any,
        val triggerThreshold: Float,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var isDragging: Boolean = false,
        var triggered: Boolean = false,
    )

    // WeakHashMap: entries are automatically removed when the View is GC'd
    // (RecyclerView recycles views, so this stays small in practice)
    private val states: MutableMap<View, SwipeState> =
        Collections.synchronizedMap(WeakHashMap())

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        ViewGroup::class.asResolver()
            .firstMethod { name = "onInterceptTouchEvent" }
            .hookAfter { param ->
                val v = param.thisObject as ViewGroup
                val s = states[v] ?: return@hookAfter
                val event = param.args[0] as MotionEvent

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        s.startX = event.x
                        s.startY = event.y
                        s.isDragging = false
                        s.triggered = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - s.startX
                        val dy = event.y - s.startY
                        if (!s.isDragging && abs(dx) > abs(dy) && dx < 0) {
                            s.isDragging = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (s.isDragging) param.result = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        s.isDragging = false
                    }
                }
            }

        View::class.asResolver()
            .firstMethod { name = "onTouchEvent"; superclass() }
            .hookAfter { param ->
                val v = param.thisObject as View
                val s = states[v] ?: return@hookAfter
                val event = param.args[0] as MotionEvent

                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        if (s.isDragging) {
                            val rawDx = event.x - s.startX
                            v.translationX = rawDx.coerceIn(-s.triggerThreshold, 0f)
                            if (!s.triggered && rawDx < -s.triggerThreshold) {
                                s.triggered = true
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                            param.result = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (s.isDragging) {
                            v.animate()
                                .translationX(0f)
                                .setDuration(250)
                                .setInterpolator(SpringInterpolator())
                                .start()
                            if (s.triggered) onSwipeLeft(v, s.chattingContext)
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            s.isDragging = false
                            param.result = true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (s.isDragging) {
                            v.animate().translationX(0f).setDuration(150).start()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            s.isDragging = false
                        }
                    }
                }
            }
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        states.clear() // hooks still exist but map is empty → zero work per event
    }

    // ── ICreateViewListener ──────────────────────────────────────────────────

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        if (states.containsKey(view)) return
        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
        states[view] = SwipeState(
            chattingContext = chattingContext,
            triggerThreshold = dpToPx(view.context, 60).toFloat(),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private class SpringInterpolator : Interpolator {
        override fun getInterpolation(t: Float): Float =
            (1f - (cos(t * PI * 2.5) * exp(-t * 5f))).toFloat()
    }

    private fun onSwipeLeft(originalView: View, chattingContext: Any) {
        val apiMan = chattingContext.asResolver()
            .firstField { type = WeServiceApi.methodApiManagerGetApi.method.declaringClass }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, classChattingUiFootComponent.clazz)
        val chatFooter = api.asResolver()
            .firstField { type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
            .get()!! as FrameLayout
        val quoteMethod = chatFooter.asResolver()
            .firstMethod {
                parameters { params -> params[0] == WeMessageApi.classMsgInfo.clazz }
                returnType = Boolean::class
            }.self
        val chatHolder = originalView.tag.asResolver()
            .firstField { name = "chatHolder"; superclass() }.get()!!
        val msgInfo = methodGetMsgInfo.method.invoke(null, chatHolder, chattingContext)
        if (quoteMethod.parameterCount == 1) quoteMethod.invoke(chatFooter, msgInfo)
        else quoteMethod.invoke(chatFooter, msgInfo, null)
    }

    private fun dpToPx(context: Context, dp: Int) =
        (dp * context.resources.displayMetrics.density).toInt()

    private val classChattingUiFootComponent by dexClass()
    private val methodGetMsgInfo by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        classChattingUiFootComponent.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                usingEqStrings(
                    "MicroMsg.ChattingUI.FootComponent",
                    "onNotifyChange event %s talker %s"
                )
            }
        }

        methodGetMsgInfo.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher { usingEqStrings("ItemDataTag", "getCurrentMsg2 err") }
        }
    }
}
