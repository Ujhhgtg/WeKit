package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.WeakHashMap
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
@Feature(
    name = "悬浮标题栏",
    categories = ["聊天"],
    description = "将聊天界面顶部标题栏及标题下方挂件改为悬浮卡片形式, 带有圆角、阴影和侧边距\n" +
        "建议同时启用「聊天/聊天界面沉浸」"
)
object FloatingChatHeader : ClickableFeature() {

    private const val TAG = "FloatingChatHeader"

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_TOP_GAP = 4
    private const val DEFAULT_EXTRA_GAP = 8
    private const val DEFAULT_ELEVATION = 4

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_TOP_GAP = 0
    private const val MAX_TOP_GAP = 24
    private const val MIN_EXTRA_GAP = 0
    private const val MAX_EXTRA_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16

    /** 标题栏容器, 微信把 ViewStub(bkr) inflate 成这个 androidx 控件。 */
    private const val ACTION_BAR_CONTAINER_CLASS = "androidx.appcompat.widget.ActionBarContainer"

    /** 消息列表所在的内容区宿主, 标题区挂件之外的直接子 View 才需要做成悬浮卡。 */
    private const val CHATTING_SCROLL_LAYOUT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout"

    /** 消息列表与多选快捷按钮所在的内容区 (layout ss 的 bki)。 */
    private const val CHATTING_CONTENT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingContent"

    /** 内容宿主里这些子 View 不是"标题下挂件", 排除在悬浮卡之外。 */
    private const val ME_HOLDER_VIEW_CLASS = "com.tencent.mm.magicbrush.plugin.emoji.ui.MEHolderView"
    private const val TALK_ROOM_POPUP_NAV_CLASS = "com.tencent.mm.ui.base.TalkRoomPopupNav"

    /** 置顶消息等提示条的宿主 (ViewStub p2f 展开, s3.xml)。 */
    private const val TIPS_BAR_GROUP_CLASS = "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup"

    private var cornerRadiusDp by prefOption("floating_chat_header_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_header_side_margin", DEFAULT_SIDE_MARGIN)
    private var topGapDp by prefOption("floating_chat_header_top_gap", DEFAULT_TOP_GAP)
    private var extraGapDp by prefOption("floating_chat_header_extra_gap", DEFAULT_EXTRA_GAP)
    private var elevationDp by prefOption("floating_chat_header_elevation", DEFAULT_ELEVATION)

    /** 每个会话页布局 (ChattingUILayout) 对应的标题栏容器。 */
    private val headerViews = WeakHashMap<View, View>()

    /** 标题栏重挂前在根布局里的原始顶部偏移 (含状态栏 inset), 重挂后当 topMargin 的基准。 */
    private val headerTopOffsets = WeakHashMap<View, Int>()

    /** 每个会话页布局对应的消息列表 RecyclerView, 避免每次高度刷新都做整树 DFS。 */
    private val chatListRecyclers = WeakHashMap<View, View>()

    /** 每个会话页布局对应的聊天内容区 (ChattingContent), 多选快捷按钮的宿主。 */
    private val chatContents = WeakHashMap<View, View>()

    /** 每个会话页布局对应的顶部"选择到这里"按钮, inflate 后缓存, 失效则重找。 */
    private val quickSelectUpViews = WeakHashMap<View, View>()

    /** 每个会话页布局对应的内容区宿主 (包含 ChattingScrollLayout 的那个直接子 View)。 */
    private val contentHosts = WeakHashMap<View, View>()

    /** 内容宿主里的悬浮覆盖卡当前最下沿 (ChattingUILayout 坐标系), 列表 padding 用它对齐。 */
    private val overlayCardBottoms = WeakHashMap<View, Int>()

    /** 每个会话页布局对应的 ChatTipsBarGroup, 构造时登记, 不依赖它在树里的具体位置。 */
    private val tipsBarGroups = WeakHashMap<View, View>()

    /** 已输出过内容宿主子 View 诊断日志的布局。 */
    private val overlayDiagLogged = WeakHashMap<View, Boolean>()

    /** 已报过"组内找不到 dim"的 ChatTipsBarGroup。 */
    private val dimWarned = WeakHashMap<View, Boolean>()

    /** 每个 ChatTipsBarGroup 对应的 dim 子 View 列表 (递归找到后缓存, 避免每帧扫整树)。 */
    private val tipsBarDims = WeakHashMap<View, List<View>>()

    /** 每个 ChatTipsBarGroup 内容列表 (MaxHeightWxRecyclerView), 过渡动画期间用它算高度。 */
    private val tipsBarRecyclers = WeakHashMap<View, View>()

    /** 每个 ChatTipsBarGroup 上次生效的样式 (圆角/阴影值), 变化才重建 outline。 */
    private val tipsBarStyles = WeakHashMap<View, HeaderStyle>()

    /** 每个 ChatTipsBarGroup 上次刷新的卡片轮廓高度, 变化时 invalidateOutline。 */
    private val tipsBarOutlineHeights = WeakHashMap<View, Int>()

    /** 消息列表 RecyclerView 由微信自己设的原始 top padding。 */
    private val chatListBasePaddings = WeakHashMap<View, Int>()

    /** 已注册的 pre-draw 监听, 重挂前先摘旧监听, 避免旧 observer 失效或重复触发。 */
    private val headerPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** 已排期重挂的布局, 防止 pre-draw 每帧重复 post。 */
    private val reparentScheduled = WeakHashMap<View, Boolean>()

    /** 根布局不是 RelativeLayout 而放弃重挂的布局, 不再每帧重试。 */
    private val reparentBlocked = WeakHashMap<View, Boolean>()

    /** 已报过"找不到标题栏/消息列表"的布局, 避免每帧刷日志。 */
    private val lookupWarned = WeakHashMap<View, Boolean>()

    /** 上次实际套用的样式, 配置变化后下一帧自动重刷。 */
    private val headerStyles = WeakHashMap<View, HeaderStyle>()

    private data class HeaderStyle(val cornerRadiusDp: Int, val elevationDp: Int)

    override fun onEnable() {
        // pre-draw 里幂等处理"找到容器 → 重挂 → 样式 → 列表 padding", ViewStub 还没 inflate
        // 或重进会话复用旧视图等时序都能兜住。
        ChattingUILayout::class.reflekt().firstConstructorOrNull {
            parameters(Context::class, AttributeSet::class)
        }?.hookAfter {
            val layout = thisObject as? ChattingUILayout ?: return@hookAfter
            layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    trackHeader(layout)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
        } ?: WeLogger.w(TAG, "ChattingUILayout constructor hook target not found")

        // 运行中才打开本特性时, 已有会话的布局早已构造完, attach 监听不会再触发;
        // 下一次布局 (切会话/键盘/旋转) 到来时补挂追踪, 之后的 pre-draw 完成全部改造。
        // onLayout 沿继承链命中 KeyboardLinearLayout.onLayout —— ChattingUILayout 运行时
        // 实际派发的 override, 只影响这一小簇布局类, 不会波及全进程。
        ChattingUILayout::class.reflekt().firstMethodOrNull {
            name = "onLayout"
            superclass()
        }?.hookAfter {
            val layout = thisObject
            if (layout !is ChattingUILayout) return@hookAfter
            if (headerPreDraws[layout] == null) trackHeader(layout)
        } ?: WeLogger.w(TAG, "onLayout hook target not found")

        // 置顶消息卡展开/收起时, 微信通过 ChatTipsBarGroup.setListViewPaddingTop 自己给消息
        // 列表补 recycler 高度。它与我们算的悬浮 padding 叠加会重复, 直接关掉这个补偿,
        // 顶部 padding 完全由本特性统一计算。
        "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup".toClass().reflekt().firstMethodOrNull {
            name = "setListViewPaddingTop"
        }?.hookBefore {
            result = null
        } ?: WeLogger.w(TAG, "ChatTipsBarGroup.setListViewPaddingTop hook target not found")

        // ChatTipsBarGroup 在树里的实际父容器不猜了: 构造时拿到实例, attach 后反查所属
        // ChattingUILayout 登记。悬浮与 dim 压制都直接走这份登记, 版本差异也能兜住。
        "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup".toClass().reflekt().firstConstructorOrNull {
            parameters(Context::class, AttributeSet::class)
        }?.hookAfter {
            val group = thisObject as? View ?: return@hookAfter
            group.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    group.findAncestorChattingUILayout()?.let { layout ->
                        tipsBarGroups[layout] = group
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
        } ?: WeLogger.w(TAG, "ChatTipsBarGroup constructor hook target not found")

    }

    private fun trackHeader(layout: View) {
        val old = headerPreDraws.remove(layout)
        old?.let { listener ->
            runCatching { layout.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            applyIfReady(layout)
            true
        }
        headerPreDraws[layout] = listener
        layout.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun applyIfReady(layout: View) {
        val header = headerViews[layout]?.takeIf { it.isAttachedToWindow }
            ?: findHeader(layout)
            ?: return
        reparentIfNeeded(layout, header)
        applyCardStyle(header)
        applyMargins(layout, header)
        applyHeaderZoneCards(layout, header)
        applyHeaderZoneOverlays(layout, header)
        suppressTipsBarDimFor(layout)
        applyChatListPadding(layout, header)
        applyQuickSelectOffset(layout, header)
    }

    private fun findHeader(layout: View): View? {
        val found = layout.allViews.firstOrNull { it.javaClass.name == ACTION_BAR_CONTAINER_CLASS }
        if (found == null) {
            if (lookupWarned.put(layout, true) == null) {
                WeLogger.w(TAG, "ActionBarContainer not found, retrying on next pre-draw")
            }
            return null
        }
        headerViews[layout] = found
        return found
    }

    /**
     * 把标题栏从 ChattingUILayout 摘出来, 重挂到会话页根 RelativeLayout (layout ss 的根)。
     * 标题栏因此脱离消息流的测量, 成为铺在整页之上的覆盖物; 微信对它的 findViewById /
     * setLayoutParams(height) 仍照常工作。pre-draw 里改层级不安全, 延迟到 post 里做。
     */
    private fun reparentIfNeeded(layout: View, header: View) {
        if (reparentBlocked[layout] != null) return
        val parent = header.parent as? ViewGroup ?: return
        if (parent !== layout) return
        if (reparentScheduled.put(layout, true) != null) return
        layout.post {
            try {
                performReparent(layout, header)
            } finally {
                reparentScheduled.remove(layout)
            }
        }
    }

    private fun performReparent(layout: View, header: View) {
        val parent = header.parent as? ViewGroup
        if (parent !== layout) return
        val root = layout.parent as? RelativeLayout
        if (root == null) {
            if (reparentBlocked.put(layout, true) == null) {
                WeLogger.w(
                    TAG,
                    "reparent skipped: expected RelativeLayout root, got ${layout.parent?.javaClass?.name}"
                )
            }
            return
        }
        // 重挂前捕获原位置: 标题栏是 ChattingUILayout 的第一个子 View, 它的原生 top 恒等于
        // layout.paddingTop (状态栏 inset 也吃在这里)。用 paddingTop 而不是 header.top,
        // 标题栏 GONE/尚未布局时也能拿到正确基准; 重挂后 LayoutParams 里拿不到这个偏移了。
        headerTopOffsets[layout] = layout.top + layout.paddingTop
        val height = header.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        parent.removeView(header)
        val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        root.addView(header, lp)
        WeLogger.d(TAG, "reparented title bar onto chat root (topOffset=${headerTopOffsets[layout]})")
    }

    /** 圆角 / 裁剪 / 阴影, 与悬浮输入框同一套绘制属性; 标题栏和标题区挂件共用。 */
    private fun applyCardStyle(view: View) {
        val style = HeaderStyle(cornerRadiusDp, elevationDp)
        if (headerStyles[view] == style) return
        val density = view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = view.resources.displayMetrics.density * cornerRadiusDp
                // 高度为 0 时取 1, 避免空 outline 把整卡裁没
                outline.setRoundRect(0, 0, view.width, view.height.coerceAtLeast(1), r)
            }
        }
        view.clipToOutline = true
        view.elevation = elevationDp * density
        headerStyles[view] = style
        WeLogger.d(TAG, "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
    }

    /**
     * 提示条卡自己的样式: 投影/裁剪的轮廓只覆盖卡片区域, 而不是整组。
     *
     * 展开/收起过渡期微信用占位层把整组瞬时撑到几乎全高, 若轮廓跟整组走, 阴影会闪出
     * 巨大矩形边框; 这里在占位态把轮廓高度收敛为「内容列表高 + 固定余量」(卡片本体),
     * 稳态则用整组高度 (此时整组就是卡片)。轮廓高度变化时手动 invalidateOutline,
     * 阴影实时跟随。
     */
    private fun applyTipsBarCardStyle(group: View) {
        val style = HeaderStyle(cornerRadiusDp, elevationDp)
        if (tipsBarStyles[group] != style) {
            val density = group.resources.displayMetrics.density
            group.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = view.resources.displayMetrics.density * cornerRadiusDp
                    val recyclerHeight = tipsBarRecycler(view)?.height ?: 0
                    val epsilon = (32 * view.resources.displayMetrics.density).toInt()
                    val delta = (64 * view.resources.displayMetrics.density).toInt()
                    val cardHeight = if (recyclerHeight > 0 &&
                        view.height > recyclerHeight + epsilon
                    ) {
                        // 占位态: 卡片 = 内容列表 + 底部箭头/分隔线余量
                        recyclerHeight + delta
                    } else {
                        view.height
                    }
                    outline.setRoundRect(0, 0, view.width, cardHeight.coerceAtLeast(1), r)
                }
            }
            group.clipToOutline = true
            group.elevation = elevationDp * density
            tipsBarStyles[group] = style
            WeLogger.d(TAG, "applied tips bar card style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
        }
        val cardHeight = effectiveTipsBarHeight(group)
        if (tipsBarOutlineHeights[group] != cardHeight) {
            tipsBarOutlineHeights[group] = cardHeight
            group.invalidateOutline()
        }
    }

    /** 左右留白 + 顶部间距; topMargin 以微信原本的位置为基准, 自动适配状态栏。 */
    private fun applyMargins(layout: View, header: View) {
        val lp = header.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = header.resources.displayMetrics.density
        val sidePx = (sideMarginDp * density).toInt()
        // 实时算而不是用重挂时的快照: 聊天页 edge-to-edge 后 paddingTop 会被清零,
        // 标题卡需要落在 状态栏 inset + 顶部间距 的位置。
        val topPx = layout.top + layout.paddingTop +
            ImmersiveChatUi.statusBarOffset(layout) + (topGapDp * density).toInt()
        if (lp.leftMargin != sidePx || lp.rightMargin != sidePx || lp.topMargin != topPx) {
            lp.leftMargin = sidePx
            lp.rightMargin = sidePx
            lp.topMargin = topPx
            header.requestLayout()
        }
    }

    /**
     * 标题栏下方还会挂其他东西 (置顶消息卡、服务通知条等): 微信把它们塞进 ChattingUILayout
     * 里、内容区宿主之前的直接子 View (典型是 ViewStub p2p 展开后的 g7j 容器)。这些挂件
     * 保持留在流内, 只给它们同样的侧边距 / 圆角 / 阴影, 并整体下移到悬浮标题卡下方。
     *
     * 边距算法 (LinearLayout 纵向流): 第一个可见挂件的 topMargin = 标题卡高 + 顶部间距 +
     * 卡片间距, 之后的每个可见挂件只需 topMargin = 卡片间距 —— 因为流式布局会把前面挂件的
     * 高度和边距都计入后续位置, 高度项互相抵消。
     */
    private fun applyHeaderZoneCards(layout: View, header: View) {
        // 标题栏还没重挂出去时它也是直接子 View, 此时不算挂件
        if (headerTopOffsets[layout] == null) return
        val host = contentHost(layout) ?: return
        val group = layout as? ViewGroup ?: return
        val density = layout.resources.displayMetrics.density
        val sidePx = (sideMarginDp * density).toInt()
        val gapPx = (extraGapDp * density).toInt()
        val baseOffsetPx = ImmersiveChatUi.statusBarOffset(layout) +
            header.height + (topGapDp * density).toInt() + gapPx
        var firstVisible = true
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child === host || child.isGone) continue
            if (child is ViewStub) continue
            applyCardStyle(child)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val topPx = if (firstVisible) baseOffsetPx else gapPx
            firstVisible = false
            if (lp.leftMargin != sidePx || lp.rightMargin != sidePx || lp.topMargin != topPx) {
                lp.leftMargin = sidePx
                lp.rightMargin = sidePx
                lp.topMargin = topPx
                child.requestLayout()
                WeLogger.d(
                    TAG,
                    "styled header-zone card ${child.javaClass.simpleName}: " +
                        "top=${topPx}px side=${sidePx}px height=${child.height}px"
                )
            }
        }
    }

    /** 定位内容区宿主: 直接子 View 里包含 ChattingScrollLayout 的那个 (layout ss 的 bqh)。 */
    private fun contentHost(layout: View): View? {
        contentHosts[layout]?.takeIf { it.isAttachedToWindow }?.let { return it }
        val group = layout as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.name == "android.view.ViewStub") continue
            val found = child.allViews.any { it.javaClass.name == CHATTING_SCROLL_LAYOUT_CLASS }
            if (found) {
                contentHosts[layout] = child
                return child
            }
        }
        return null
    }

    /**
     * 置顶消息卡等提示条并不在 ChattingUILayout 的流里: 微信把它们作为内容宿主 (bqh,
     * FrameLayout) 的直接子 View 盖在列表上方, 典型是 ViewStub p2f 展开的
     * ChatTipsBarGroup (com.tencent.mm.ui.tipsbar.ChatTipsBarGroup) 和 s7o 系列提示卡,
     * 并自行通过 setListViewPaddingTop 给消息列表补它们的高度。
     *
     * 这里把内容宿主里"可见、非滚动区、非全屏/特殊覆盖层"的子 View 都当悬浮卡处理:
     * 同样的侧边距/圆角/阴影, 并用 topMargin 把它们整体推到标题卡下方, 多张卡按顺序堆叠,
     * 互相之间间隔 extraGap。它们自己的入场动画走 translationY, 与 topMargin 互不干扰。
     */
    private fun applyHeaderZoneOverlays(layout: View, header: View) {
        if (headerTopOffsets[layout] == null) return
        val host = contentHost(layout) ?: return
        val hostGroup = host as? ViewGroup ?: return
        val density = layout.resources.displayMetrics.density
        val sidePx = (sideMarginDp * density).toInt()
        val gapPx = (extraGapDp * density).toInt()
        val titleBottomPx = header.height + (topGapDp * density).toInt()
        // 下一张卡的期望顶部 (ChattingUILayout 坐标系)
        val hostTopPx = hostGroup.offsetTopIn(layout)
        // 流内挂件已把内容宿主推下去时, 期望位置不会高于宿主顶部
        var nextTopPx = (ImmersiveChatUi.statusBarOffset(layout) + layout.paddingTop +
            titleBottomPx + gapPx).coerceAtLeast(hostTopPx)
        var bottomPx: Int? = null
        if (overlayDiagLogged.put(layout, true) == null) {
            val children = (0 until hostGroup.childCount).joinToString(", ") { i ->
                val c = hostGroup.getChildAt(i)
                "${c.javaClass.name}[v=${c.visibility} h=${c.height}]"
            }
            WeLogger.d(TAG, "content host children: $children")
        }
        for (i in 0 until hostGroup.childCount) {
            val child = hostGroup.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val isTipsGroup = child.javaClass.name == TIPS_BAR_GROUP_CLASS
            if (isTipsGroup) {
                // 展开态的 ChatTipsBarGroup 会被 dim 撑满整个内容区, 必须先摘掉 dim,
                // 否则后面的高度兜底检查会把它当成全屏覆盖层跳过。
                suppressTipsBarDim(child)
                applyTipsBarCardStyle(child)
            }
            if (!isHeaderZoneOverlay(child, hostGroup)) continue
            if (!isTipsGroup) applyCardStyle(child)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val topPx = (nextTopPx - hostTopPx).coerceAtLeast(0)
            if (lp.leftMargin != sidePx || lp.rightMargin != sidePx || lp.topMargin != topPx) {
                lp.leftMargin = sidePx
                lp.rightMargin = sidePx
                lp.topMargin = topPx
                child.requestLayout()
                WeLogger.d(
                    TAG,
                    "floated header-zone overlay ${child.javaClass.simpleName}: " +
                        "top=${topPx}px side=${sidePx}px height=${child.height}px"
                )
            }
            val cardHeight = if (isTipsGroup) effectiveTipsBarHeight(child) else child.height
            nextTopPx += cardHeight + gapPx
            bottomPx = nextTopPx - gapPx
        }
        // 构造登记的 ChatTipsBarGroup 不在内容宿主下时 (版本差异), 单独悬浮它
        val tracked = tipsBarGroups[layout]?.takeIf {
            it.isAttachedToWindow && it.isVisible && it.parent !== hostGroup
        }
        if (tracked != null) {
            suppressTipsBarDim(tracked)
            applyTipsBarCardStyle(tracked)
            val parentTopPx = (tracked.parent as? View)?.offsetTopIn(layout) ?: hostTopPx
            val topPx = (nextTopPx - parentTopPx).coerceAtLeast(0)
            val lp = tracked.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && (lp.leftMargin != sidePx || lp.rightMargin != sidePx || lp.topMargin != topPx)) {
                lp.leftMargin = sidePx
                lp.rightMargin = sidePx
                lp.topMargin = topPx
                tracked.requestLayout()
                WeLogger.d(
                    TAG,
                    "floated tracked ChatTipsBarGroup: top=${topPx}px " +
                        "parent=${tracked.parent?.javaClass?.name} height=${tracked.height}px"
                )
            }
            nextTopPx += effectiveTipsBarHeight(tracked) + gapPx
            bottomPx = nextTopPx - gapPx
        }
        if (bottomPx != null) {
            overlayCardBottoms[layout] = bottomPx
        } else {
            overlayCardBottoms.remove(layout)
        }
    }

    /** 用构造登记的实例压制 dim, 组在哪个父容器下都能生效。 */
    private fun suppressTipsBarDimFor(layout: View) {
        val group = tipsBarGroups[layout]?.takeIf {
            it.isAttachedToWindow && it.isVisible
        } ?: return
        suppressTipsBarDim(group)
    }

    /**
     * ChatTipsBarGroup 展开置顶消息列表时, 会在组内放一张全尺寸深色 View (s3.xml 的 ow1,
     * match_parent × match_parent, 背景 #80000000) 当 dim: 盖住列表背景, 点击它 (冒泡到
     * 组件的点击监听) 会折叠回卡片。改成悬浮后这张 dim 不可能再盖满整屏, 语义上应当整个
     * 去掉 —— 每帧把它压成 GONE: 视觉消失, 且 GONE 不参与命中测试, 点击原 dim 区域会落到
     * 消息列表而不是触发折叠。
     */
    private fun suppressTipsBarDim(group: View) {
        // 不同微信版本里组的内部结构不一样 (有的 s3.xml 直接挂 ow1, 有的套一层 FrameLayout),
        // 所以按特征递归找: 纯 View + 全尺寸参数。
        val cached = tipsBarDims[group]
        val dims = cached?.takeIf { list -> list.all { it.parent !== null } }
            ?: group.findViewsWhich { it.isTipsBarDim() }.toList()
        if (dims.isEmpty()) {
            if (dimWarned.put(group, true) == null) {
                val tree = group.allViews.take(30).joinToString(", ") { v ->
                    val lp = v.layoutParams
                    "${v.javaClass.simpleName}[w=${lp?.width} h=${lp?.height} v=${v.visibility}]"
                }
                WeLogger.w(TAG, "tips bar dim not found, group tree: $tree")
            }
            return
        }
        tipsBarDims[group] = dims
        for (dim in dims) {
            if (dim.visibility != View.GONE) {
                dim.visibility = View.GONE
                WeLogger.d(TAG, "suppressed ChatTipsBarGroup dim layer")
            }
            // 兜底: 即使某帧微信把它重新点亮, alpha=0 也保证画不出来
            if (dim.alpha != 0f) dim.alpha = 0f
        }
    }

    private fun View.isTipsBarDim(): Boolean {
        if (javaClass.name != "android.view.View") return false
        val lp = layoutParams ?: return false
        // 只按结构特征匹配, 不查资源表、不依赖混淆 id: 全尺寸的纯 View 就是 dim 层
        return lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.height == ViewGroup.LayoutParams.MATCH_PARENT
    }

    /** 提示条组内容列表 (MaxHeightWxRecyclerView), 找不到时返回 null。 */
    private fun tipsBarRecycler(group: View): View? {
        tipsBarRecyclers[group]?.takeIf { it.isAttachedToWindow }?.let { return it }
        val found = group.findViewWhich {
            it.javaClass.name == "com.tencent.mm.view.recyclerview.MaxHeightWxRecyclerView"
        }
        if (found != null) tipsBarRecyclers[group] = found
        return found
    }

    /**
     * 过渡动画期间微信用占位层把整组瞬时撑到几乎全高, 但可见卡片只到内容列表为止。
     * 组高明显大于列表高时按列表高算, 列表 padding 就不会跟着占位层一起跳。
     */
    private fun effectiveTipsBarHeight(group: View): Int {
        val recyclerHeight = tipsBarRecycler(group)?.height ?: 0
        if (recyclerHeight <= 0) return group.height
        val density = group.resources.displayMetrics.density
        val epsilon = (32 * density).toInt()
        return if (group.height > recyclerHeight + epsilon) recyclerHeight else group.height
    }

    /** 内容宿主里值得做成悬浮卡的子 View: 排除滚动区、ViewStub、裸 View 和已知全屏/特殊覆盖层。 */
    private fun isHeaderZoneOverlay(child: View, host: ViewGroup): Boolean {
        val name = child.javaClass.name
        if (name == CHATTING_SCROLL_LAYOUT_CLASS) return false
        if (name == "android.view.ViewStub") return false
        if (name == "android.view.View") return false
        if (name == ME_HOLDER_VIEW_CLASS) return false
        if (name == TALK_ROOM_POPUP_NAV_CLASS) return false
        // 提示条组本身就是要悬浮的卡 (展开态高度可能很大), 不走高度兜底
        if (name == TIPS_BAR_GROUP_CLASS) return true
        // 兜底: 全屏覆盖层不可能是标题下挂件
        if (host.height > 0 && child.height > host.height * 0.9) return false
        return true
    }

    /** this 相对 [layout] 的顶部偏移 (沿父链累加 top)。 */
    private fun View.offsetTopIn(layout: View): Int {
        var offset = 0
        var current: View? = this
        while (current != null && current !== layout) {
            offset += current.top
            current = current.parent as? View
        }
        return offset
    }

    private fun View.findAncestorChattingUILayout(): ChattingUILayout? {
        var parent = parent
        while (parent != null) {
            if (parent is ChattingUILayout) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * 标题栏盖在整页之上后, 给消息列表补 [header.height + 顶部间距] 的 top padding,
     * 让第一条消息停在卡片下沿而不是藏在卡片后面。RecyclerView 本身 clipToPadding=false,
     * 滚动时消息会正常从卡片背后穿过; 顶部 padding 在 scrollY=0 时自动把首条消息放到
     * padding 之下, 不需要额外调整滚动位置 (与底部 padding 的语义不同)。
     *
     * 标题区挂件可见时, 它们在流内把消息列表整体推到卡片下方, 列表不会与任何悬浮卡重叠,
     * 此时不再补 padding; 挂件全部收起时才需要补标题卡那部分。
     */
    private fun applyChatListPadding(layout: View, header: View) {
        if (headerTopOffsets[layout] == null) return
        if (header.height <= 0) return
        val recycler = layout.chatRecycler() ?: return
        val base = chatListBasePaddings.getOrPut(recycler) { recycler.paddingTop }
        val density = layout.resources.displayMetrics.density
        val overlayBottom = overlayCardBottoms[layout]
        val extra = when {
            // 内容宿主里的覆盖卡: 微信只补它自身高度, 我们补它相对列表顶部的下移量
            overlayBottom != null -> (overlayBottom - recycler.offsetTopIn(layout)).coerceAtLeast(0)
            // 流内挂件已把列表整体推到卡片下方
            hasVisibleHeaderExtras(layout, header) -> 0
            else -> ImmersiveChatUi.statusBarOffset(layout) + header.height + (topGapDp * density).toInt()
        }
        val target = base + extra
        if (recycler.paddingTop == target) return
        recycler.setPadding(recycler.paddingLeft, target, recycler.paddingRight, recycler.paddingBottom)
        WeLogger.d(TAG, "chat list top padding: ${recycler.paddingTop} -> $target (extra=$extra)")
    }

    private fun hasVisibleHeaderExtras(layout: View, header: View): Boolean {
        val host = contentHost(layout) ?: return false
        val group = layout as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child === host || child === header) continue
            if (child.isGone) continue
            if (child.javaClass.name == "android.view.ViewStub") continue
            return true
        }
        return false
    }

    /**
     * 多选模式顶部"选择到这里"按钮 (ChattingContent 里 top|left 的 wrap_content 小浮层)
     * 原生位于标题栏下方; 标题栏重挂成悬浮卡后内容区顶到屏幕上方, 按钮会被标题卡盖住。
     * 这里把它下推到标题卡下沿 + 卡片间距, 几何与列表 top padding 同一套。
     */
    private fun applyQuickSelectOffset(layout: View, header: View) {
        if (headerTopOffsets[layout] == null) return
        val content = chatContent(layout) as? ViewGroup ?: return
        val quickSelect = quickSelectUpView(content, layout) ?: return
        val density = layout.resources.displayMetrics.density
        val gapPx = (extraGapDp * density).toInt()
        // 标题卡下沿 (ChattingUILayout 坐标系): statusBarOffset + 卡高 + 顶部间距
        val titleBottomPx = ImmersiveChatUi.statusBarOffset(layout) +
            header.height + (topGapDp * density).toInt()
        // ChattingScrollLayout 滚动时用 translationY 移动内容区, 要一起算进按钮的屏幕位置
        val contentTopPx = content.offsetTopIn(layout) + content.translationY.roundToInt()
        val marginTop = (titleBottomPx + gapPx - contentTopPx).coerceAtLeast(0)
        val lp = quickSelect.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != marginTop) {
            lp.topMargin = marginTop
            quickSelect.requestLayout()
            WeLogger.d(TAG, "quick select up view top margin: ${lp.topMargin} -> $marginTop")
        }
    }

    private fun chatContent(layout: View): View? {
        chatContents[layout]?.takeIf { it.isAttachedToWindow }?.let { return it }
        val found = layout.allViews.firstOrNull {
            it.javaClass.name == CHATTING_CONTENT_CLASS
        }
        if (found != null) chatContents[layout] = found
        return found
    }

    private fun quickSelectUpView(content: ViewGroup, layout: View): View? {
        quickSelectUpViews[layout]?.takeIf { it.parent === content }?.let { return it }
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child.isQuickSelectUp()) {
                quickSelectUpViews[layout] = child
                return child
            }
        }
        return null
    }

    /** 结构特征: 内容区直接子 View 里 top|left 的 wrap_content 小浮层 (含未展开的 ViewStub)。 */
    private fun View.isQuickSelectUp(): Boolean {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return false
        val topLeft = Gravity.TOP or Gravity.LEFT
        val topStart = Gravity.TOP or Gravity.START
        if (lp.gravity != topLeft && lp.gravity != topStart) return false
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
            lp.height != ViewGroup.LayoutParams.WRAP_CONTENT
        ) return false
        return lp.topMargin > 0
    }

    private fun View.chatRecycler(): View? {
        chatListRecyclers[this]?.takeIf { it.isAttachedToWindow }?.let { return it }
        val listHost = allViews.firstOrNull {
            it.javaClass.name == "com.tencent.mm.ui.chatting.view.MMChattingListView"
        }
        val found = listHost?.allViews?.firstOrNull { it.isChatRecycler() }
        if (found != null) {
            chatListRecyclers[this] = found
        } else if (lookupWarned.put(this, true) == null) {
            WeLogger.w(TAG, "chat list recycler not found, top blank skipped")
        }
        return found
    }

    private fun View.isChatRecycler(): Boolean {
        val name = javaClass.name
        if (name == "com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView" ||
            name == "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView"
        ) {
            return true
        }
        // 兜底: 用视图自己的 classloader 判定宿主 RecyclerView 子类
        val hostRecycler = runCatching {
            "androidx.recyclerview.widget.RecyclerView".toClass(javaClass.classLoader)
        }.getOrNull() ?: return false
        return hostRecycler.isInstance(this)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var cornerInput by remember { mutableFloatStateOf(cornerRadiusDp.toFloat()) }
            var sideInput by remember { mutableFloatStateOf(sideMarginDp.toFloat()) }
            var gapInput by remember { mutableFloatStateOf(topGapDp.toFloat()) }
            var extraGapInput by remember { mutableFloatStateOf(extraGapDp.toFloat()) }
            var elevInput by remember { mutableFloatStateOf(elevationDp.toFloat()) }

            AlertDialogContent(
                title = { Text("悬浮标题栏") },
                text = {
                    DefaultColumn {
                        ListItem(
                            content = { Text("改动在重新进入聊天后生效") },
                            supportingContent = {
                                Text("标题栏及标题下方的置顶消息等卡片均以悬浮卡片显示")
                            }
                        )
                        ListItem(
                            content = { Text("圆角半径: ${cornerInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = cornerInput,
                                    onValueChange = { cornerInput = it },
                                    valueRange = MIN_CORNER_RADIUS.toFloat()..MAX_CORNER_RADIUS.toFloat(),
                                    steps = MAX_CORNER_RADIUS - MIN_CORNER_RADIUS - 1
                                )
                            }
                        )
                        ListItem(
                            content = { Text("侧边距: ${sideInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = sideInput,
                                    onValueChange = { sideInput = it },
                                    valueRange = MIN_SIDE_MARGIN.toFloat()..MAX_SIDE_MARGIN.toFloat(),
                                    steps = MAX_SIDE_MARGIN - MIN_SIDE_MARGIN - 1
                                )
                            }
                        )
                        ListItem(
                            content = { Text("顶部间距: ${gapInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = gapInput,
                                    onValueChange = { gapInput = it },
                                    valueRange = MIN_TOP_GAP.toFloat()..MAX_TOP_GAP.toFloat(),
                                    steps = MAX_TOP_GAP - MIN_TOP_GAP - 1
                                )
                            }
                        )
                        ListItem(
                            content = { Text("下方卡片间距: ${extraGapInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = extraGapInput,
                                    onValueChange = { extraGapInput = it },
                                    valueRange = MIN_EXTRA_GAP.toFloat()..MAX_EXTRA_GAP.toFloat(),
                                    steps = MAX_EXTRA_GAP - MIN_EXTRA_GAP - 1
                                )
                            }
                        )
                        ListItem(
                            content = { Text("阴影强度: ${elevInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = elevInput,
                                    onValueChange = { elevInput = it },
                                    valueRange = MIN_ELEVATION.toFloat()..MAX_ELEVATION.toFloat(),
                                    steps = MAX_ELEVATION - MIN_ELEVATION - 1
                                )
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        cornerRadiusDp = cornerInput.roundToInt()
                        sideMarginDp = sideInput.roundToInt()
                        topGapDp = gapInput.roundToInt()
                        extraGapDp = extraGapInput.roundToInt()
                        elevationDp = elevInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
