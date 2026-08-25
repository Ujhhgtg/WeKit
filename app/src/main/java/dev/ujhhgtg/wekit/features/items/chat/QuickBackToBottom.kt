package dev.ujhhgtg.wekit.features.items.chat

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChattingContent
import com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout
import com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView
import com.tencent.mm.ui.chatting.view.MMChattingListView
import com.tencent.mm.ui.widget.imageview.WeImageView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.WeakHashMap

object QuickBackToBottom : SwitchFeature(), IResolveDex {

    override val technicalId = "快捷回底"
    override val nameRes = R.string.feature_quick_back_to_bottom_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_quick_back_to_bottom_description

    private const val TAG = "QuickBackToBottom"

    /** 距最新消息超过该值 (dp) 时显示气泡, 小于等于该值时隐藏。 */
    private const val TRIGGER_DISTANCE_DP = 240

    /** 微信原生「x条新消息」气泡的底边距 (H0 在 i16=5 状态写入 R.dimen.dd)。 */
    private const val BUBBLE_BOTTOM_MARGIN_DP = 44

    /**
     * 气泡图标是双上箭头, 微信显示「x条新消息」时 rotation=0 (gd.java:321);
     * 「回到最新」语义是向下, 与微信同图标的「回到引用位置」气泡 (XML rotation=180) 一致。
     */
    private const val BUBBLE_ICON_ROTATION = 180f

    private const val BUBBLE_TEXT = "回到最新"

    /** 点击后的原生加载链路完成窗口, 期间抑制「以下为新消息」分隔条。 */
    // TODO: rewrite this ai slop
    private const val SUPPRESS_HISTORY_MSG_TIP_WINDOW_MS = 5_000L

    /** 每个 footer 对应的消息列表 RecyclerView, 避免每帧做整树 DFS。 */
    private val chatListRecyclers = WeakHashMap<View, ChattingRecyclerView>()

    /** 每个 footer 对应的「x条新消息」气泡, 避免每帧做整树 DFS。 */
    private val newMessageBubbles = WeakHashMap<View, View>()

    /** 已注册的 pre-draw 监听, 重进会话时先摘掉旧的再挂新的, 避免监听失效。 */
    private val preDrawListeners = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** 已经报过"找不到列表/气泡"警告的 footer, 避免反复刷日志。 */
    private val lookupWarned = WeakHashMap<View, Boolean>()

    /** 每个会话的 HistoryMsgTongueComponent 实例, key 是它的气泡 View。 */
    private val components = WeakHashMap<View, Any>()

    /** 每个会话的 ChattingContext 实例, key 是它的气泡 View。 */
    private val contexts = WeakHashMap<View, Any>()

    /** 微信存储的全量消息数 (S7/Xa 结果), key 是 talker。 */
    private val msgCounts = WeakHashMap<String, Int>()

    /**
     * 我们的点击触发的原生加载链路是否还在进行中: 那条链路会在 presenter 里调
     * adapter.setShowHistoryMsgTipId (V0/U0), 给最新消息前面插"以下为新消息"分隔条,
     * 只对我们自己的点击生效时抑制, 微信原生点击不受影响。
     */
    @Volatile
    private var suppressHistoryMsgTipUntil = 0L

    /**
     * HistoryMsgTongueComponent 的初始化方法 (8.0.65 叫 a(), 8.0.76 叫 b()):
     * 在这里把组件实例与它的气泡 View 关联起来, 用稳定日志/追踪字符串锚定。
     */
    private val methodComponentInit by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            usingStrings("HistoryMsgTongueComponent", "onChattingInit")
        }
    }

    /**
     * 微信存储的全量消息数: 8.0.65 是 f8.Xa, 8.0.76 是 f9.S7, 共用同一日志字符串
     * ("select msgCount from rconversation")。滚进历史后 adapter 只保留已加载窗口,
     * 只有这个存储计数能给出"最后一条消息"的全量位置; 微信每次加载/更新舌头都会调它。
     */
    private val methodGetMsgCount by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.MsgInfoStorage", "getMsgCount conversationStorage.getMsgCountByUsername count:%d")
        }
    }

    /**
     * HistoryMsgTongueComponent.m0/p0 (locationByMsgId) —— 「x条新消息」点击的完整加载链路:
     * provider → loader 按 SCENE=2 重新加载消息尾部 (深历史时最新消息不在当前窗口, 必须靠
     * 它加载), presenter 再滚动到目标。两个版本的日志字符串略有差异, 用 usingStrings 取公共子串。
     */
    private val methodLocationByMsgId by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            usingStrings("MicroMsg.ChattingDataAdapterV3", "[locationByMsgId] position:%s mode:%s")
        }
    }

    /** ChattingDataAdapterV3.setShowHistoryMsgTipId (8.0.65 U0 / 8.0.76 V0)。 */
    private val methodSetShowHistoryMsgTipId by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.adapter")
        matcher {
            usingStrings("[setShowHistoryMsgTipId] pos:%s")
        }
    }

    override fun onEnable() {
        methodSetShowHistoryMsgTipId.hookBefore {
            if (SystemClock.elapsedRealtime() < suppressHistoryMsgTipUntil) {
                suppressHistoryMsgTipUntil = 0
                result = null
            }
        }
        methodGetMsgCount.hookAfter {
            msgCounts[args[0] as String] = result as Int
        }
        methodComponentInit.hookAfter {
            val component = thisObject!!
            val fieldValues = component.reflekt().fields { superclass = true }.map { it.get() }
            val bubble = fieldValues
                .filterIsInstance<View>()
                .firstOrNull { it.isNewMessageBubble() } ?: return@hookAfter
            components[bubble] = component
            contexts[bubble] = fieldValues.firstOrNull {
                WeMessageApi.classChattingContext.clazz.isInstance(it)
            } ?: return@hookAfter
        }
        ChatFooter::class.reflekt().firstMethod { name = "onAttachedToWindow" }.hookAfter {
            attachToChat(thisObject as ChatFooter)
        }
    }

    override fun onDisable() {
        // 摘掉 pre-draw 监听; 已写过的气泡文本/可见性按 best-effort 处理, 重启微信即复原
        preDrawListeners.forEach { (footer, listener) ->
            runCatching { footer.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        preDrawListeners.clear()
    }

    /**
     * 会话页会复用同一个 footer 实例: 每次 attach 都先摘旧监听再挂新监听, 否则旧 observer
     * 失效后新会话里不会再触发。列表/气泡晚于 footer attach 也没关系, pre-draw 每帧都会
     * 重新查找, 找到后立刻接管。
     */
    private fun attachToChat(footer: ChatFooter) {
        val old = preDrawListeners.remove(footer)
        if (old != null) {
            runCatching { footer.viewTreeObserver.removeOnPreDrawListener(old) }
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            val bubble = footer.newMessageBubble() ?: return@OnPreDrawListener true
            updateBubble(footer.chatRecycler(), bubble)
            true
        }
        preDrawListeners[footer] = listener
        footer.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * 每帧对齐气泡状态: 距最新消息超过阈值时显示并接管文字/点击, 否则隐藏。
     * 微信自己改写文本、替换点击监听或改可见性后, 下一帧会被纠正回来。
     */
    private fun updateBubble(recycler: ChattingRecyclerView, bubble: View) {
        val remaining = distanceToBottom(recycler)
        val threshold = (TRIGGER_DISTANCE_DP * bubble.resources.displayMetrics.density).toInt()
        if (remaining <= threshold) {
            if (bubble.visibility != View.GONE) bubble.visibility = View.GONE
            return
        }
        if (bubble.visibility != View.VISIBLE) bubble.visibility = View.VISIBLE
        // 微信只在它自己显示气泡时才会把 layout_gravity 从 XML 的 top|right 改成 bottom|end;
        // 我们强制显示时它没走这套逻辑, 气泡会停在屏幕右上角, 这里主动钉回右下角。
        ensureBottomRight(bubble)
        val icon = bubble.findViewWhich { it is WeImageView }!! as WeImageView
        if (icon.rotation != BUBBLE_ICON_ROTATION) icon.rotation = BUBBLE_ICON_ROTATION
        // 每帧都接管点击: 微信 H0/update 随时会重挂 zf/eg 等监听, 只在文本变化时替换会漏掉,
        // 点到旧的 zf (D 为空) 会 NPE, 点到旧的 eg 会用过期位置跳到错误消息。
        bubble.setOnClickListener { scrollToLatest(bubble) }
        val textView = bubble.findViewWhich { it is TextView }!! as TextView
        if (textView.text.toString() != BUBBLE_TEXT) {
            textView.text = BUBBLE_TEXT
            WeLogger.d(TAG, "bubble taken over: remaining=${remaining}px threshold=${threshold}px")
        }
    }

    /** 把气泡定位到右下角, 与微信 H0(i16=5) 的 gravity (BOTTOM|END) 一致。 */
    private fun ensureBottomRight(bubble: View) {
        val lp = bubble.layoutParams as FrameLayout.LayoutParams
        if (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.BOTTOM) return
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.topMargin = 0
        if (lp.bottomMargin <= 0) {
            lp.bottomMargin = (BUBBLE_BOTTOM_MARGIN_DP * bubble.resources.displayMetrics.density).toInt()
        }
        bubble.layoutParams = lp
    }

    /** 距最新消息还剩多少像素可滚。RecyclerView 桩 (HOST. 前缀) 直接提供这三个公开方法。 */
    private fun distanceToBottom(recycler: ChattingRecyclerView): Int =
        (recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollOffset() -
            recycler.computeVerticalScrollExtent()).coerceAtLeast(0)

    /**
     * 完整复用「x条新消息」的点击链路 (m0/p0 → loader SCENE=2)。位置 = 全量消息数 - 1
     * (最后一条消息的全量位置): 深历史时最新消息不在当前 adapter 窗口, 直接按窗口 count
     * 滚动只会停在历史窗口末尾; loader 会按 S7 - position 重新加载消息尾部再滚动。
     * 不能用 Int.MAX_VALUE: S7 - MAX 是负数, SQLite LIMIT 负数 = 不限, 外层再按 ASC
     * 排序, 取到的是最老消息。
     */
    private fun scrollToLatest(bubble: View) {
        val component = components[bubble]
        val context = contexts[bubble]
        if (component == null || context == null) {
            WeLogger.w(TAG, "component/context not captured, click ignored")
            return
        }
        val talker = WeMessageApi.methodChattingContextGetTalker.method.invoke(context) as String
        val totalCount = msgCounts[talker] ?: run {
            WeLogger.w(TAG, "total message count not captured, click ignored")
            return
        }
        // 原生舌头链路会在加载完成后给最新消息前插"以下为新消息"分隔条, 这里挂起抑制开关,
        // 下一次 setShowHistoryMsgTipId 调用 (异步到达) 会被跳过并自动消费掉。
        suppressHistoryMsgTipUntil = SystemClock.elapsedRealtime() + SUPPRESS_HISTORY_MSG_TIP_WINDOW_MS
        // m0/p0 是静态方法: receiver 传 null, 组件实例和位置都进参数数组
        methodLocationByMsgId.method.invoke(null, component, totalCount - 1)
    }

    /**
     * 从 footer 所在的 ChattingScrollLayout 里定位消息列表 RecyclerView。
     */
    private fun ChatFooter.chatRecycler(): ChattingRecyclerView {
        val cached = chatListRecyclers[this]
        if (cached != null && cached.isAttachedToWindow) return cached
        val found = ((parent as ChattingScrollLayout)
            .findViewWhich { it is MMChattingListView }!! as MMChattingListView)
            .findViewWhich { it is ChattingRecyclerView }!! as ChattingRecyclerView
        chatListRecyclers[this] = found
        return found
    }

    /**
     * 从 ChattingContent 里定位「x条新消息」气泡 (bm4): 直接子 LinearLayout, 内容区居中,
     * 由 WeImageView + TextView 组成。同屏的「翻到顶部/底部」提示条用普通 ImageView,
     * 引用气泡嵌在更深层, 只查直接子节点即可排除。
     */
    private fun ChatFooter.newMessageBubble(): View? {
        val cached = newMessageBubbles[this]
        if (cached != null && cached.isAttachedToWindow && (cached.parent as ViewGroup).parent === parent) {
            return cached
        }
        val content = (parent as ChattingScrollLayout)
            .findViewWhich { it is ChattingContent }!! as ChattingContent
        for (i in 0 until content.childCount) {
            val candidate = content.getChildAt(i)
            if (candidate.isNewMessageBubble()) {
                newMessageBubbles[this] = candidate
                return candidate
            }
        }
        if (lookupWarned.put(this, true) == null) {
            WeLogger.w(TAG, "new message bubble not found, 快捷回底 skipped")
        }
        return null
    }

    private fun View.isNewMessageBubble(): Boolean {
        if (this !is LinearLayout) return false
        val g = gravity
        if (g and Gravity.VERTICAL_GRAVITY_MASK != Gravity.CENTER_VERTICAL) return false
        if (g and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.CENTER_HORIZONTAL) return false
        var hasIcon = false
        var hasText = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (!hasIcon && child is WeImageView) hasIcon = true
            if (!hasText && child is TextView) hasText = true
            if (hasIcon && hasText) return true
        }
        return false
    }
}
