package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tencent.mm.pluginsdk.ui.chat.AppPanel
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooterBottom
import com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.fastJavaMethod
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.FloatingChatFooter.PANEL_TOP_RESERVE_DP
import dev.ujhhgtg.wekit.features.items.chat.FloatingChatFooter.maxPanelHeight
import dev.ujhhgtg.wekit.features.items.chat.FloatingChatFooter.movePanelAbove
import dev.ujhhgtg.wekit.features.items.chat.FloatingChatFooter.offscreenHeight
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.constructor
import java.lang.reflect.Modifier
import java.util.WeakHashMap

object FloatingChatFooter : ClickableFeature(), IResolveDex {

    override val technicalId = "悬浮输入框"
    override val nameRes = R.string.feature_floating_chat_footer_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_floating_chat_footer_description

    private const val TAG = "FloatingChatFooter"

    // ChatFooter.switchPanel(state, ...) 的状态取值
    private const val PANEL_STATE_NONE = 0
    private const val PANEL_STATE_SMILEY = 2
    private const val PANEL_STATE_APP = 3

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_BOTTOM_GAP = 8
    private const val DEFAULT_ELEVATION = 4

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_BOTTOM_GAP = 0
    private const val MAX_BOTTOM_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16

    /** 微信原版「x条新消息」气泡与输入行之间的留白 (支持版本恒为 44dp)。 */
    private const val NEW_MSG_BUBBLE_GAP_DP = 44

    /** 气泡图标的宿主类名 (布局 XML 直接引用, 不会被混淆)。 */
    private const val WE_CHAT_ICON_VIEW = "com.tencent.mm.ui.widget.imageview.WeImageView"

    /** 键盘与面板同时展开时, 至少给会话内容留出的高度。 */
    private const val PANEL_TOP_RESERVE_DP = 120

    /** 压缩后的面板下限, 免得在小屏上被压没。 */
    private const val MIN_PANEL_HEIGHT_DP = 160

    /** AppPanel 的自然高度 (微信通过 setPortHeighPx 告知)。key 是 AppPanel 实例。 */
    private val naturalPanelHeights = WeakHashMap<View, Int>()

    /** AppPanel 内部那个承载全部内容、被 setPortHeighPx 撑开的子 View。 */
    private val appPanelBodies = WeakHashMap<View, View>()

    /** 键盘态实际用过的滚动量, 面板态复用它以避免位移抖动。key 是 ChattingScrollLayout。 */
    private val keyboardScrolls = WeakHashMap<View, Int>()

    /** 表情面板把手当前展开出来的额外高度。key 是 ChatFooterBottom。 */
    private val dragExtents = WeakHashMap<View, Int>()

    /** 已经装过 outline 追踪器的 footer, 防止重复注册 OnPreDrawListener。 */
    private val outlineTrackers = WeakHashMap<View, Boolean>()

    /** 临时挪动面板期间保存的原 translationY。key 是 ChatFooterBottom。 */
    private val savedPanelTranslations = WeakHashMap<View, Float>()

    /** 消息列表 RecyclerView 由微信自己设的原始 bottom padding (一般是 6dp)。 */
    private val chatListBasePaddings = WeakHashMap<View, Int>()

    /** 每个 footer 对应的消息列表 RecyclerView, 避免每次高度刷新都做整树 DFS。 */
    private val chatListRecyclers = WeakHashMap<View, View>()

    /** 已经报过"找不到列表"警告的 footer, 避免每帧刷日志。 */
    private val chatListLookupWarned = WeakHashMap<View, Boolean>()

    /** 每个 footer 对应的「x条新消息」气泡, 避免每次高度刷新都做整树 DFS。 */
    private val newMessageBubbles = WeakHashMap<View, View>()

    /** 已经报过"找不到气泡"警告的 footer, 避免每帧刷日志。 */
    private val newMessageBubbleLookupWarned = WeakHashMap<View, Boolean>()

    /** 已注册的 pre-draw 监听, 重进会话时先摘掉旧的再挂新的, 避免监听失效。 */
    private val navInsetPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** 每个窗口是否已由本特性启用导航栏 edge-to-edge。 */
    private val edgeToEdgeApplied = WeakHashMap<Window, Boolean>()

    /** ChattingUILayout.fitSystemWindows 入口时的原始导航栏 inset。 */
    private val navBarInsetsBeforeFit = WeakHashMap<View, Int>()

    /** 已当场清理过底部 padding 的会话布局。 */
    private val navBarLayoutsApplied = WeakHashMap<View, Boolean>()

    /** 已把微信 EdgeToEdgeWrapperLayout 的导航栏色块压成透明的窗口包装。 */
    private val navBarWrappersNeutralized = WeakHashMap<View, Boolean>()

    /** 重入保护: 我们自己调 setPortHeighPx 时不要把压缩后的值当成自然高度记下来。 */
    private var resizingAppPanel = false

    /**
     * 是否把表情/工具面板移到输入行上方。关掉就是微信原生的"面板在下方、输入行随之上移",
     * 此时输入框下半部分仍会延伸到屏幕外, 拿不到下方两个圆角 —— 那是面板在下方的固有结果。
     *
     * 改动只在 ChatFooter attach 时生效, 切换后需要重进会话。
     */
    private var movePanelAbove by prefOption("floating_chat_footer_panel_above", true)

    private var cornerRadiusDp by prefOption("floating_chat_footer_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_footer_side_margin", DEFAULT_SIDE_MARGIN)
    private var bottomGapDp by prefOption("floating_chat_footer_bottom_gap", DEFAULT_BOTTOM_GAP)
    private var elevationDp by prefOption("floating_chat_footer_elevation", DEFAULT_ELEVATION)

    /**
     * Locates ChatFooter.refreshBottomHeight() by the unique log string WeChat emits at the
     * start of the method. The intentional typo "keyborPx" is WeChat's own, copied faithfully.
     */
    private val methodRefreshBottomHeight by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "[refreshBottomHeight] keyborPx:%d")
        }
    }

    /**
     * ChatFooter.switchPanel(int state, boolean, int) —— 底部面板状态机。
     * state: 0=收起 1=键盘 2=表情面板 3=工具面板 4=语音。
     */
    private val methodSwitchPanel by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "switchPanel: %s, %s")
        }
    }

    /**
     * ChattingScrollLayout.scrollContentTo(int y, boolean, int, int) —— 微信展开面板的方式:
     * 对除消息列表宿主外的所有子 View 做 translationY(-y)。
     */
    private val methodScrollContentTo by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingScrollLayout",
                "scrollContentTo: y:%s, targetScroll:%s, alwaysScroll:%s"
            )
        }
    }

    /**
     * ChatFooter.configPanel(int state, boolean, int) —— switchPanel 的外层包装。
     * 负责在切换前后开关软键盘: 切到 state 1 且键盘未开时 post 一个 showSoftInput,
     * 切到非 1 且键盘已开时 hideVKB。竖屏下它还会把 switchPanel 推迟到键盘收起回调里补跑。
     */
    private val methodConfigPanel by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "configPanel: %s, %s, %s, %s, %s")
        }
    }

    /**
     * ChatFooter.enterKeyboardState() —— 无参, 内部就是 configPanel(1, true, -1)。
     * 表情键与「+」键在面板已展开时都调它来"关面板", 顺带把键盘弹出来。
     */
    private val methodEnterKeyboardState by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "isScrolling!! pass this event!")
        }
    }

    /**
     * FullScreenEditHelper.updateFullScreenEdtLayoutHeight() —— 全屏编辑器的高度
     * = `ChatFooterBottom 的屏幕 Y` 减去顶部锚点的屏幕 Y。它拿面板当下边界。
     */
    private val methodUpdateFullScreenEditHeight by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("updateFullScreenEdtLayoutHeight:")
        }
    }

    /**
     * EmojiPanelDragIndicator.determineExtent(parentHeight, collapsedHeight, inputHeight)
     * —— 算出表情面板把手最多能往上拖出多少 (maxExtendedHeight)。
     */
    private val methodDetermineExtent by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.EmojiPanelDragIndicator", "determineExtent: parentHeight=")
        }
    }

    override fun onEnable() {
        val reflekt = ChatFooter::class.reflekt()

        // 绘制属性不依赖 LayoutParams, 构造完就能设
        ChatFooter::class.constructor.hookAfter {
            applyDrawingStyle(thisObject as ChatFooter)
        }

        // 结构改造与边距必须等 LayoutParams 就位 —— 它由父容器 (ChattingScrollLayout)
        // 在 addView() 时写入, 构造函数返回时还没有, 到 onAttachedToWindow 保证非空。
        //
        // applyDrawingStyle 在这里再来一次 (幂等): 运行时才打开本特性的话, 当前会话的
        // ChatFooter 早已构造完毕, 构造函数 hook 会整个错过。
        reflekt.firstMethod { name = "onAttachedToWindow" }.hookAfter {
            val footer = thisObject as ChatFooter
            applyNavigationBarEdgeToEdge(footer)
            applyDrawingStyle(footer)
            applySideMargins(footer)
            if (movePanelAbove) {
                reparentBottomPanel(footer)
                applyBottomMargin(footer)
            } else {
                applyBottomGap(footer)
            }
            trackNavBarInset(footer)
        }

        // 导航栏沉浸后，微信仍会把导航栏 inset 吃进 ChattingUILayout.paddingBottom，
        // 8.0.72+ 还会在底部画一条不透明色块。只处理底部，顶部由 FloatingChatHeader 负责。
        ChattingUILayout::class.reflekt().firstMethodOrNull { name = "fitSystemWindows" }?.let { fit ->
            fit.hookBefore {
                val layout = thisObject as View
                navBarInsetsBeforeFit[layout] = (args[0] as Rect).bottom
            }
            fit.hookAfter {
                val layout = thisObject as View
                val originalBottom = navBarInsetsBeforeFit.remove(layout) ?: 0
                val keep = (layout.paddingBottom - originalBottom).coerceAtLeast(0)
                if (layout.paddingBottom != keep) {
                    layout.setPadding(layout.paddingLeft, layout.paddingTop, layout.paddingRight, keep)
                }
                suppressNavBarStrip(layout)
                neutralizeNavigationBarWrapper(layout)
            }
        } ?: WeLogger.w(TAG, "ChattingUILayout.fitSystemWindows hook target not found")

        // AppPanel 的自然高度只有微信自己知道 (它把 f207332x2 喂给 setPortHeighPx),
        // 记下来给 applyPanelHeight 当基准。setPortHeighPx 是 View 子类的 set* 方法,
        // 被默认 proguard 规则保留, 按名字反射是安全的。
        AppPanel::class.reflekt().firstMethod { name = "setPortHeighPx" }.hookBefore {
            if (resizingAppPanel) return@hookBefore
            val px = args[0] as? Int ?: return@hookBefore
            if (px > 0) naturalPanelHeights[thisObject as View] = px
        }

        // 微信在这里写入 bottomMargin = -面板高, 我们在它之后覆盖掉;
        // 顺便重算面板高度 —— 这里同样会把容器高度改回微信那套值。
        methodRefreshBottomHeight.hookAfter {
            val footer = thisObject as ChatFooter
            if (!movePanelAbove) {
                applyBottomGap(footer)
                return@hookAfter
            }
            applyBottomMargin(footer)
            // 面板收起时也要重算: 微信在这里把容器高度写回它那套值, 留着不管的话
            // 下一次展开会先用一帧错误的高度。
            footer.bottomPanel?.let { applyPanelHeight(footer, it, "refreshBottomHeight") }
        }

        // 表情面板顶部那个把手的拖拽机制是"先把面板撑到全高, 再用 translationY 把多出来的
        // 部分压到屏幕外, 拖动时收回 translation" —— 整套都建立在面板位于输入行下方的前提上。
        // 面板移到上方后, 一按住把手 footer 就会被撑爆。把可拖出的量强制为 0 让把手失效:
        // 传 parentHeight=0 会让 determineExtent 走 else 分支直接得到 0, 不用碰混淆字段。
        methodDetermineExtent.hookBefore {
            if (movePanelAbove) args[0] = 0
        }

        // 全屏编辑器拿 ChatFooterBottom 的屏幕坐标当下边界 —— 面板原本在输入行下方,
        // 那个位置≈屏幕底部, 所以编辑器能铺满全屏。面板搬到上方后锚点跟着上移, 编辑器
        // 就在输入行上边截断了。这里在它测算期间把面板临时挪回原位 (输入区底边),
        // 算完立刻还原。面板此刻是 GONE 不参与绘制, 且不跨帧, 不会闪。
        methodUpdateFullScreenEditHeight.hookBefore {
            if (!movePanelAbove) return@hookBefore
            val footer = thisObject?.ownerChatFooter ?: return@hookBefore
            val panel = footer.bottomPanel ?: return@hookBefore
            val panelLoc = IntArray(2).also { panel.getLocationOnScreen(it) }
            val footerLoc = IntArray(2).also { footer.getLocationOnScreen(it) }
            val desiredY = footerLoc[1] + footerHeightExcludingPanel(panel)
            savedPanelTranslations[panel] = panel.translationY
            panel.translationY += (desiredY - panelLoc[1]).toFloat()
        }

        methodUpdateFullScreenEditHeight.hookAfter {
            if (!movePanelAbove) return@hookAfter
            val panel = thisObject?.ownerChatFooter?.bottomPanel ?: return@hookAfter
            savedPanelTranslations.remove(panel)?.let { panel.translationY = it }
        }

        // 微信只在 state 2/3 显示面板, 从不隐藏它 —— 收起态由我们兜底设 GONE。
        // 必须是 before: 方法体里会调到 scrollContentTo, 那里要读这个可见性。
        methodSwitchPanel.hookBefore {
            if (!movePanelAbove) return@hookBefore
            val state = args[0] as? Int ?: return@hookBefore
            val footer = thisObject as ChatFooter
            val panel = footer.bottomPanel ?: return@hookBefore
            if (state != PANEL_STATE_SMILEY && state != PANEL_STATE_APP) {
                panel.visibility = View.GONE
                dragExtents.remove(panel)
                return@hookBefore
            }
            applyPanelHeight(footer, panel, "switchPanel:before:$state")
        }

        // 再来一次: switchPanel 的方法体里 (setVisibility / F1 / G1 以及它们触发的
        // refreshBottomHeight) 有机会把容器高度改回微信那套值, 收尾时覆盖掉。
        methodSwitchPanel.hookAfter {
            if (!movePanelAbove) return@hookAfter
            val state = args[0] as? Int ?: return@hookAfter
            if (state != PANEL_STATE_SMILEY && state != PANEL_STATE_APP) return@hookAfter
            val footer = thisObject as ChatFooter
            val panel = footer.bottomPanel ?: return@hookAfter
            applyPanelHeight(footer, panel, "switchPanel:after:$state")
        }

        // 面板可见 = 它已经作为布局的一部分向上撑开了 footer, 此时再做 translationY
        // 位移会把输入行顶飞。读实际可见性而不是记状态标志: switchPanel 有一条短路
        // 分支不会走到这里, 标志位会残留过期值。
        //
        // 键盘同时开着时仍然需要位移, 把输入行抬到 IME 之上。但不能直接放行微信给的 y:
        // 表情面板用的是 max(推荐高, 键盘高), 比键盘态本身多出几十像素, 会让整个 footer
        // 轻微上移一下再弹回。改用键盘态实际用过的那个滚动量, 从构造上保证零位移。
        methodScrollContentTo.hookBefore {
            if (!movePanelAbove) return@hookBefore
            val scrollLayout = thisObject as ChattingScrollLayout
            val panel = scrollLayout.footerBottomPanel ?: return@hookBefore
            if (panel.visibility != View.VISIBLE) {
                // 面板收起时的滚动量就是键盘高度, 记下来给面板态复用
                (args[0] as? Int)
                    ?.takeIf { it > 0 && scrollLayout.isImeVisible }
                    ?.let { keyboardScrolls[scrollLayout] = it }
                return@hookBefore
            }
            val imeHeight = scrollLayout.imeHeight
            // IME inset 与微信自己那套键盘高度理论上一致, 但以微信实际用过的值优先, 保证零位移
            args[0] = if (imeHeight > 0) keyboardScrolls[scrollLayout] ?: imeHeight else 0
        }

        // 面板原本在输入行下方, 所以微信开面板前必须先收键盘给它腾地方 (configPanel 里的
        // hideVKB), 而且竖屏下还会把 switchPanel 推迟到键盘收起回调里补跑。面板现在在
        // 输入行上方, 键盘不需要让位 —— 跳过整个 configPanel, 自己直接跑 switchPanel。
        // 只跳过这一步而不是单独屏蔽 hideVKB: 否则键盘不收, 回调不来, switchPanel 永远
        // 补不上, 面板根本不会出现。
        methodConfigPanel.hookBefore {
            if (!movePanelAbove) return@hookBefore
            val state = args[0] as? Int ?: return@hookBefore
            if (state != PANEL_STATE_SMILEY && state != PANEL_STATE_APP) return@hookBefore
            val footer = thisObject as ChatFooter
            if (!footer.isImeVisible) return@hookBefore
            methodSwitchPanel.method.invoke(footer, state, args[1], args[2])
            result = null
        }

        // 表情键/「+」键在面板已展开时都调 enterKeyboardState 来关面板, 顺带弹键盘 ——
        // 面板在下方时这是合理的 (面板腾出的位置正好给键盘), 现在则纯属多余。
        // 键盘当前已开的话保留原行为: 那时 configPanel 只是切状态, 不会重复弹键盘。
        methodEnterKeyboardState.hookBefore {
            if (!movePanelAbove) return@hookBefore
            val footer = thisObject as ChatFooter
            val panel = footer.bottomPanel ?: return@hookBefore
            if (panel.visibility != View.VISIBLE) return@hookBefore
            if (footer.isImeVisible) return@hookBefore
            methodConfigPanel.method.invoke(footer, PANEL_STATE_NONE, true, -1)
            result = null
        }

        // RecentImageBubble 通过 ChatFooter.getYFromBottom() 把 popup 放在输入行上方。
        // 原布局中面板在输入行下方且挤在屏幕外，footer 的总高度正好能代表这个偏移；面板
        // 重排到上方后，它也被算进总高度，导致 popup 被额外抬过整个面板。只在面板实际
        // 可见时扣除其当前高度，键盘压缩后的面板也会使用同一个真实高度。
        ChatFooter::getYFromBottom.fastJavaMethod!!.hookAfter {
            if (!movePanelAbove) return@hookAfter
            val footer = thisObject as ChatFooter
            val panel = footer.bottomPanel ?: return@hookAfter
            if (panel.visibility != View.VISIBLE) return@hookAfter
            val panelHeight = panel.height.takeIf { it > 0 }
                ?: panel.layoutParams?.height?.takeIf { it > 0 }
                ?: return@hookAfter
            val yFromBottom = result as? Int ?: return@hookAfter
            result = (yFromBottom - panelHeight).coerceAtLeast(0)
        }
    }

    /**
     * 软键盘当前占据的高度, 未显示时为 0。
     *
     * API 30 以下拿不到可靠的 IME inset, 一律按"未显示"处理 —— 那些设备退化为微信原生
     * 行为 (面板与键盘互斥), 不会走到需要两者共存的分支, 因此退化是安全的。
     */
    private val View.imeHeight: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        } else {
            0
        }

    private val View.isImeVisible: Boolean
        get() = imeHeight > 0

    /**
     * 决定面板容器该有多高, 并把 AppPanel 的内容一起调整到位。
     *
     * 两件事:
     *
     * 1. 微信把容器高度设为 `max(表情面板推荐高, 键盘高)`, 而 AppPanel 只撑到键盘高 ——
     *    多出来的那几十像素在「+」面板底部留下一条空白 (表情面板是 MATCH_PARENT 加进
     *    容器的, 所以没有)。改用 AppPanel 的自然高度当基准, 两种面板都刚好贴住输入行。
     * 2. 键盘和面板现在可以同时开着, 两者叠加会把面板顶到聊天页顶栏后面。键盘开着时
     *    按剩余空间压缩面板, 并给会话内容留出 [PANEL_TOP_RESERVE_DP]。
     */
    private fun applyPanelHeight(footer: ChatFooter, panel: ChatFooterBottom, from: String) {
        val appPanel = footer.findViewWhich { it is AppPanel }
        val lp = panel.layoutParams ?: return
        // AppPanel 是懒创建的 (第一次点「+」才走 G0), 在那之前微信从没调过 setPortHeighPx,
        // 自然高度无从得知。退回 getKeyBordHeightPX() —— 它正是 G0 给 AppPanel 的初值,
        // 随时可读且不依赖任何时序。少了这一层, 第一次开表情面板会用容器原值
        // max(表情面板推荐高, 键盘高) 而偏高;「+」面板则因为被我们拉伸到同一高度而看不出来。
        val recorded = naturalPanelHeights[appPanel]
        val natural = recorded
            ?: footer.keyBordHeightPX.takeIf { it > 0 }
            ?: lp.height
        if (natural <= 0) return

        // 把手展开的增量 (见 installPanelDragHooks); 面板关掉时清零, 不跨次保留展开状态
        val extent = dragExtents[panel] ?: 0
        val cap = maxPanelHeight(footer, panel)
        val target = minOf(natural + extent, cap)

        if (lp.height != target) {
            WeLogger.d(
                TAG,
                "panel height [$from]: ${lp.height} -> $target " +
                    "(natural=$natural extent=$extent ime=${footer.imeHeight} cap=$cap)"
            )
            lp.height = target
            panel.layoutParams = lp
        }
        if (appPanel != null) resizeAppPanelBody(appPanel, natural, target)
    }

    /**
     * 面板容器的高度上限: 宿主高度扣掉软键盘、footer 的非面板部分, 再给会话内容留出
     * [PANEL_TOP_RESERVE_DP]。键盘没开时这个值通常远大于自然高度, 不会产生影响;
     * 键盘开着时它就是防止面板顶到聊天页顶栏后面的那道闸。
     */
    private fun maxPanelHeight(footer: ChatFooter, panel: ChatFooterBottom): Int {
        val hostHeight = (footer.parent as? View)?.height ?: 0
        val inputHeight = footerHeightExcludingPanel(panel)
        // 还没走过布局, 量不到真实尺寸 —— 此时不设限, 否则会把面板钉在下限上。
        if (hostHeight <= 0 || inputHeight <= 0) return Int.MAX_VALUE
        val density = footer.resources.displayMetrics.density
        val room = hostHeight - footer.imeHeight - inputHeight -
            (PANEL_TOP_RESERVE_DP * density).toInt()
        return room.coerceAtLeast((MIN_PANEL_HEIGHT_DP * density).toInt())
    }

    /**
     * footer 里除面板之外的内容有多高 (引用条 + 输入列)。
     *
     * 不能写成 `footer.height - panel.height`: 这两个实测值不同步 —— 面板刚被设为可见时
     * `panel.height` 已是新值而 `footer.height` 还是上一帧的旧值, 相减会得到负数, 让
     * [maxPanelHeight] 凭空多出上千像素, 压缩就失效了。面板的兄弟节点与面板开合无关,
     * 直接把它们加起来才是稳定的。
     */
    private fun footerHeightExcludingPanel(panel: ChatFooterBottom): Int {
        val root = panel.parent as? ViewGroup ?: return 0
        var sum = 0
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child !== panel && child.visibility != View.GONE) sum += child.height
        }
        return sum
    }

    /**
     * 把 AppPanel 的内容撑到 [target]。
     *
     * `setPortHeighPx` 只是打个标记, 真正应用它的 `forceRefreshSize` 方法名被混淆了,
     * 不能按名字反射。改为直接找到被它撑开的那个子 View —— 首次查找时它的高度恰好等于
     * 自然高度 [natural] —— 之后缓存起来, 否则压缩过一次就再也认不出它了。
     */
    private fun resizeAppPanelBody(appPanel: View, natural: Int, target: Int) {
        resizingAppPanel = true
        try {
            (appPanel as AppPanel).setPortHeighPx(target)
        } catch (e: Throwable) {
            WeLogger.w(TAG, "setPortHeighPx failed", e)
        } finally {
            resizingAppPanel = false
        }
        val body = appPanelBodies.getOrPut(appPanel) {
            appPanel.findViewWhich { it !== appPanel && it.layoutParams?.height == natural }
                ?: return
        }
        val blp = body.layoutParams ?: return
        if (blp.height == target) return
        blp.height = target
        body.layoutParams = blp
    }

    /** ChatFooter 子树里的表情/工具面板容器。 */
    private val ChatFooter.bottomPanel: ChatFooterBottom?
        get() = findViewWhich { it is ChatFooterBottom } as ChatFooterBottom?

    /** 从 ChatFooter 的辅助类实例 (如 FullScreenEditHelper) 反查它服务的 ChatFooter。 */
    private val Any.ownerChatFooter: ChatFooter?
        get() = runCatching {
            reflekt().firstField { type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
                .get() as? ChatFooter
        }.getOrNull()

    /**
     * 从 ChattingScrollLayout 定位面板。只遍历 ChatFooter 子树 —— 直接对整个
     * ChattingScrollLayout 做 DFS 会先把整条消息列表走一遍, 白白付出代价。
     */
    private val ChattingScrollLayout.footerBottomPanel: ChatFooterBottom?
        get() {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child is ChatFooter) return child.bottomPanel
            }
            return null
        }

    /** 设置 outline / 圆角裁剪 / 阴影 / 暗色浮层 —— 全是不依赖 LayoutParams 的绘制属性, 可重复调用。 */
    private fun applyDrawingStyle(footer: ChatFooter) {
        val density = footer.resources.displayMetrics.density
        footer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = view.resources.displayMetrics.density * cornerRadiusDp
                val bottom = (view.height - offscreenHeight(footer)).coerceAtLeast(1)
                outline.setRoundRect(0, 0, view.width, bottom, r)
            }
        }
        footer.clipToOutline = true
        footer.elevation = elevationDp * density
        FloatingChatCardVisuals.applyDarkSurface(footer, cornerRadiusDp)
        if (!movePanelAbove) trackOutlineWhileScrolling(footer)
        WeLogger.d(TAG, "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
    }

    /**
     * footer 有多少高度悬在屏幕外, 不该被算进圆角矩形。
     *
     * 面板在上方时恒为 0 —— bottomMargin 非负, footer 的实际高度就是可视高度。
     *
     * 面板在下方 (开关关闭) 时不然: `bottomMargin = -面板高` 把面板那段挤到 LinearLayout
     * 底边之外, 微信靠 translationY 把整个 footer 上移来"展开"面板。于是悬在外面的高度
     * 是 `面板高 + translationY` (translationY ∈ [-面板高, 0]): 收起时等于面板高, 圆角
     * 落在输入行下沿; 完全展开时归零, 圆角落在 footer 真正的底边。中间过程连续。
     */
    private fun offscreenHeight(footer: ChatFooter): Int {
        if (movePanelAbove) return 0
        val panelHeight = footer.bottomPanel?.height ?: return 0
        return (panelHeight + footer.translationY).toInt().coerceAtLeast(0)
    }

    /**
     * translationY 变了不会自动重算 outline, 得手动 invalidate。只在面板位于下方时需要 ——
     * 面板在上方时 [offscreenHeight] 恒为 0, outline 不随位移变化。
     *
     * 不会自激: 下一帧 translationY 没变就不再 invalidate。
     */
    private fun trackOutlineWhileScrolling(footer: ChatFooter) {
        if (outlineTrackers.put(footer, true) != null) return
        var last = Float.NaN
        footer.viewTreeObserver.addOnPreDrawListener {
            if (footer.translationY != last) {
                last = footer.translationY
                footer.invalidateOutline()
            }
            true
        }
    }

    /**
     * 导航栏 inset 变化后重算 footer 底边距。键盘弹出/收起、旋转、手势模式切换都会触发
     * 一帧 pre-draw。apply* 内部都有"目标值没变就不动"的判断, 所以每帧调用是幂等的,
     * 还能兜底纠正面板开关瞬间 refreshBottomHeight 用旧高度算出的瞬时错误值。
     */
    private fun trackNavBarInset(footer: ChatFooter) {
        val listener = ViewTreeObserver.OnPreDrawListener {
            applyNavigationBarEdgeToEdge(footer)
            if (movePanelAbove) {
                applyBottomMargin(footer)
                liftNewMessageBubble(footer)
            } else {
                applyBottomGap(footer)
            }
            true
        }
        // 会话页会复用同一个 footer 实例: 重挂前先摘旧监听, 防止旧 observer 已失效或重复触发
        navInsetPreDraws.remove(footer)?.let { old ->
            runCatching { footer.viewTreeObserver.removeOnPreDrawListener(old) }
        }
        navInsetPreDraws[footer] = listener
        footer.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * 「x条新消息」气泡 (HistoryMsgTongueComponent 的 mGoBackToHistoryMsgLayout, 布局 id
     * bm4) 是 ChattingContent 的直接子 View, 始终贴着内容底边。面板搬到输入行上方后,
     * 悬浮卡片用负 topMargin 盖住了内容底部一截, 微信自己的 44dp 底边距就不够用了,
     * 气泡下半截会被卡片挡住。这里按 footer 实际绘制位置算出卡片盖住的高度, 在微信
     * 设定的 margin 之上补足, 保留原版的气泡与输入框间距。
     */
    private fun liftNewMessageBubble(footer: ChatFooter) {
        if (!movePanelAbove) return
        val bubble = footer.newMessageBubble() ?: return
        val lp = bubble.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK != Gravity.BOTTOM) return
        val content = bubble.parent as? View ?: return
        val contentBottom = content.bottom + content.translationY
        val cardTop = footer.top + footer.translationY
        val overlap = (contentBottom - cardTop).toInt().coerceAtLeast(0)
        if (overlap <= 0) return
        val gap = (NEW_MSG_BUBBLE_GAP_DP * footer.resources.displayMetrics.density).toInt()
        val target = maxOf(lp.bottomMargin, overlap + gap)
        if (lp.bottomMargin == target) return
        lp.bottomMargin = target
        bubble.layoutParams = lp
    }

    /**
     * 从 ChattingScrollLayout 里定位「x条新消息」气泡。不碰资源表, 也不依赖逐版本混淆的
     * 资源名/类名: 气泡是 ChattingContent (footer 的兄弟节点) 的直接子 LinearLayout,
     * 内容区居中, 由 WeImageView + TextView 组成。微信同屏的「翻到顶部/底部」提示条虽然
     * 也是 WeImageView + TextView, 但内容是 center_vertical 且带 elevation, 不会命中;
     * 引用气泡用普通 ImageView; 其余提示视图都嵌在更深层, 只查直接子节点即可排除。
     */
    private fun ChatFooter.newMessageBubble(): View? {
        newMessageBubbles[this]?.takeIf {
            it.isAttachedToWindow && (it.parent as? ViewGroup)?.parent === parent
        }?.let { return it }
        val scrollLayout = parent as? ViewGroup ?: return null
        for (i in 0 until scrollLayout.childCount) {
            val sibling = scrollLayout.getChildAt(i) as? ViewGroup ?: continue
            if (sibling === this) continue
            for (j in 0 until sibling.childCount) {
                val candidate = sibling.getChildAt(j)
                if (candidate.isNewMessageBubble()) {
                    newMessageBubbles[this] = candidate
                    return candidate
                }
            }
        }
        if (newMessageBubbleLookupWarned.put(this, true) == null) {
            WeLogger.w(TAG, "new message bubble not found, lift skipped")
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
            if (!hasIcon && child.javaClass.name == WE_CHAT_ICON_VIEW) hasIcon = true
            if (!hasText && child is TextView) hasText = true
            if (hasIcon && hasText) return true
        }
        return false
    }

    /**
     * 面板在上方时, 卡片需要悬浮出列表的高度 = footer 总高去掉面板那一段
     * (即面板在 footer 根布局里的那些可见兄弟节点: 输入列、引用条等)。
     *
     * 不用 `footer.height - panel.height`: 面板开/关的瞬间 footer 还没重排, 高度里仍带着
     * 上一帧的面板, 会算出瞬时错误值; 兄弟节点自己的高度不受面板开关影响, 恒稳定。
     */
    private fun overlayAmount(footer: ChatFooter): Int {
        val panel = footer.bottomPanel ?: return footer.height
        return footerHeightExcludingPanel(panel)
    }

    // ---- 导航栏 edge-to-edge ----

    private fun applyNavigationBarEdgeToEdge(footer: ChatFooter) {
        val layout = footer.findAncestorChattingUILayout() ?: return
        val activity = footer.context.activityOrNull() ?: return
        val window = activity.window
        if (edgeToEdgeApplied[window] != true) {
            edgeToEdgeApplied[window] = true
            // decorFits 是窗口级总开关；本特性只消费底部 inset，未启用 Header 时顶部仍由微信保留。
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WeLogger.d(TAG, "chat navigation bar edge-to-edge applied")
        }
        if (navBarLayoutsApplied.put(layout, true) == null) {
            val navInset = currentNavBarInset(layout)
            val keep = (layout.paddingBottom - navInset).coerceAtLeast(0)
            if (layout.paddingBottom != keep) {
                layout.setPadding(layout.paddingLeft, layout.paddingTop, layout.paddingRight, keep)
            }
            suppressNavBarStrip(layout)
        }
        neutralizeNavigationBarWrapper(layout)
    }

    private fun currentNavBarInset(layout: View): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return layout.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    }

    /** 只处理 EdgeToEdgeWrapperLayout 的底部，顶部由 FloatingChatHeader 负责。 */
    private fun neutralizeNavigationBarWrapper(layout: View) {
        val wrapper = layout.findEdgeToEdgeWrapper() ?: return
        if (wrapper.paddingBottom != 0) {
            wrapper.setPadding(wrapper.paddingLeft, wrapper.paddingTop, wrapper.paddingRight, 0)
        }
        if (navBarWrappersNeutralized.put(wrapper, true) != null) return
        runCatching {
            wrapper.javaClass.getMethod(
                "setNavigationBarBackgroundColor",
                Int::class.javaPrimitiveType,
            ).invoke(wrapper, Color.TRANSPARENT)
        }
    }

    private fun View.findEdgeToEdgeWrapper(): View? {
        var current: View? = this
        while (current != null) {
            if (current.javaClass.name == "com.tencent.mm.ui.widget.EdgeToEdgeWrapperLayout") {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun View.findAncestorChattingUILayout(): ChattingUILayout? {
        var parent = parent
        while (parent != null) {
            if (parent is ChattingUILayout) return parent
            parent = parent.parent
        }
        return null
    }

    /** 8.0.72+ ChattingUILayout 底部自绘色块。 */
    private fun suppressNavBarStrip(layout: View) {
        val paintField = layout.javaClass.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == Paint::class.java
        } ?: return
        runCatching {
            paintField.isAccessible = true
            (paintField.get(layout) as Paint).alpha = 0
        }
    }

    /**
     * 需要补到 footer 底边的导航栏 inset。
     *
     * 只有窗口真的铺到导航栏后面 (微信 edge-to-edge 开关打开) 才补 —— 否则内容区本身就
     * 在导航栏上方结束, 再补会把输入框顶高。键盘弹出时导航栏被 IME 盖住,
     * getInsets(navigationBars()) 自然返回 0, 不用单独判断键盘状态。
     */
    private fun bottomNavBarInsetToAdd(footer: ChatFooter): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        if (!footer.isWindowBehindNavBar()) return 0
        return footer.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    }

    private fun View.isWindowBehindNavBar(): Boolean {
        val activity = context.activityOrNull() ?: return false
        return edgeToEdgeApplied[activity.window] == true
    }

    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }

    /** 从 footer 所在的 ChattingScrollLayout 里定位消息列表的 RecyclerView。 */
    private fun ChatFooter.chatRecycler(): View? {
        chatListRecyclers[this]?.takeIf { it.isAttachedToWindow }?.let { return it }
        val found = findChatRecycler(this)
        if (found != null) chatListRecyclers[this] = found
        return found
    }

    private fun View.findChatRecycler(footer: ChatFooter): View? {
        val scrollLayout = parent as? View
        if (scrollLayout?.javaClass?.name != "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout") {
            footer.logChatListLookup("parent=${scrollLayout?.javaClass?.name}")
            return null
        }
        // 只用类名字符串定位, 不用 `is` 跨类加载器判断: 模块自带的 androidx 类
        // 与微信进程里的类不是同一个 Class 对象, `is RecyclerView` 会静默失败。
        val listHost = scrollLayout.allViews.firstOrNull {
            it.javaClass.name == "com.tencent.mm.ui.chatting.view.MMChattingListView"
        }
        if (listHost == null) {
            footer.logChatListLookup("MMChattingListView missing")
            return null
        }
        val recycler = listHost.allViews.firstOrNull { it.isChatRecycler() }
        if (recycler == null) footer.logChatListLookup("chat recycler missing")
        return recycler
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
            Class.forName(
                "androidx.recyclerview.widget.RecyclerView",
                false,
                javaClass.classLoader
            )
        }.getOrNull() ?: return false
        return hostRecycler.isInstance(this)
    }

    private fun ChatFooter.logChatListLookup(reason: String) {
        if (chatListLookupWarned.put(this, true) == null) {
            WeLogger.w(TAG, "chat list recycler not found ($reason), bottom blank skipped")
        }
    }

    /**
     * 卡片悬浮在列表之上后, 给消息列表底部补 [extra] 的 padding, 让最后一条消息在滚到底时
     * 停在卡片上沿而不是藏在卡片后面。RecyclerView 本身 clipToPadding=false, 滚动时消息
     * 会正常从卡片和小白条背后穿过。
     *
     * 补 padding 本身不会移动现有滚动位置: 如果列表此刻正停在旧的底端, 还要顺着新 padding
     * 再往下滚一段, 最新消息才会立刻从卡片后面露出。
     */
    private fun applyChatListPadding(footer: ChatFooter, extra: Int) {
        val recycler = footer.chatRecycler()
        if (recycler == null) {
            footer.logChatListLookup("lookup failed")
            return
        }
        val base = chatListBasePaddings.getOrPut(recycler) { recycler.paddingBottom }
        val target = base + extra
        val old = recycler.paddingBottom
        if (old == target) return
        val wasAtBottom = !recycler.canScrollVertically(1)
        recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, target)
        WeLogger.d(
            TAG,
            "chat list bottom padding: $old -> $target (extra=$extra atBottom=$wasAtBottom)"
        )
        if (wasAtBottom && target > old) {
            // 滚动到新的 padding 底端, 让最新消息从卡片后露出; 用户正翻旧消息时不打扰
            recycler.scrollBy(0, target - old)
        }
    }

    /** 左右留白, 让 footer 看起来是一张与屏幕边缘脱开的悬浮卡。 */
    private fun applySideMargins(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val sideMarginPx = (sideMarginDp * footer.resources.displayMetrics.density).toInt()
        lp.leftMargin = sideMarginPx
        lp.rightMargin = sideMarginPx
        footer.requestLayout()
        WeLogger.d(TAG, "applied side margins: side=${sideMarginDp}dp")
    }

    /**
     * 微信的 refreshBottomHeight 会写 bottomMargin = -面板高, 把 footer 下半段 (面板)
     * 挤到屏幕外。面板重排到输入行上方之后这个负值必须消失, 否则输入行本身会被推出屏幕。
     * 这里改为绝对赋值 —— 直接写用户配置的底部间距。
     *
     * 悬浮用负 topMargin 实现: footer 在 LinearLayout 流里只占底间距的高度, 卡片本体
     * 被 -topMargin 拉回列表之上, 消息列表因此能一直铺到屏幕底边 (edge-to-edge), 滚动时
     * 从卡片和小白条背后穿过。
     */
    private fun applyBottomMargin(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = footer.resources.displayMetrics.density
        val gapPx = (bottomGapDp * density).toInt()
        val targetBottom = gapPx + bottomNavBarInsetToAdd(footer)
        val overlay = overlayAmount(footer)
        val targetTop = -(overlay + targetBottom)
        if (lp.bottomMargin != targetBottom || lp.topMargin != targetTop) {
            lp.bottomMargin = targetBottom
            lp.topMargin = targetTop
            footer.requestLayout()
        }
        applyChatListPadding(footer, overlay + targetBottom)
    }

    /**
     * [movePanelAbove] 关闭时的底部间距。面板仍在输入行下方, 微信写入的
     * `bottomMargin = -面板高` 必须保留 (否则输入行会被推出屏幕), 只能在它之上叠加。
     *
     * 直接从面板的 LayoutParams 取那个"面板高"重算, 而不是在现值上做加法 —— 加法在
     * onAttachedToWindow 与 refreshBottomHeight 都会调到时会重复累加。
     */
    private fun applyBottomGap(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != 0) {
            lp.topMargin = 0
        }
        val panelHeight = footer.bottomPanel?.layoutParams?.height ?: return
        if (panelHeight <= 0) return
        val gapPx = (bottomGapDp * footer.resources.displayMetrics.density).toInt()
        val target = -panelHeight + gapPx + bottomNavBarInsetToAdd(footer)
        if (lp.bottomMargin != target) {
            lp.bottomMargin = target
            footer.requestLayout()
        }
    }

    /**
     * 把 ChatFooterBottom (表情/工具面板容器) 从输入列移到 footer 根 RelativeLayout
     * 的首位, 使面板出现在输入行**上方**并向上延伸。
     *
     * 不依赖任何微信 R.id: 面板按类型找, 输入列 = 面板的父容器, 根布局 = 输入列的父容器。
     * 重排后把根布局中原本顶部对齐 (没有 BELOW 规则) 的兄弟节点重新锚到面板下方,
     * 保持 面板 → 引用条 → 输入行 的纵向顺序。
     *
     * 幂等: onAttachedToWindow 会重入 (切换会话复用 footer), 面板已在根布局时直接返回。
     */
    private fun reparentBottomPanel(footer: ChatFooter) {
        val panel = footer.bottomPanel
        if (panel == null) {
            WeLogger.w(TAG, "reparent: ChatFooterBottom not found, layout left untouched")
            return
        }
        val inputColumn = panel.parent as? ViewGroup ?: return
        // 已经重排过: 面板的父容器就是 footer 的直接子 View (根布局)
        if (inputColumn.parent === footer) return

        val root = inputColumn.parent as? RelativeLayout
        if (root == null) {
            WeLogger.w(
                TAG,
                "reparent: expected RelativeLayout root, got ${inputColumn.parent?.javaClass?.name}"
            )
            return
        }
        if (panel.id == View.NO_ID) panel.id = View.generateViewId()

        val height = panel.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        inputColumn.removeView(panel)
        // 收起态: 微信从不隐藏面板, 负 margin 去掉后必须由我们兜底, 否则常驻一片空白
        panel.visibility = View.GONE
        root.addView(
            panel,
            0,
            RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        )

        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === panel) continue
            val childLp = child.layoutParams as? RelativeLayout.LayoutParams ?: continue
            if (childLp.rules[RelativeLayout.BELOW] == 0) {
                childLp.addRule(RelativeLayout.BELOW, panel.id)
                child.layoutParams = childLp
            }
        }
        WeLogger.d(TAG, "reparented ChatFooterBottom above input row (panelHeight=$height)")
    }

    override fun onDisable() {
        navInsetPreDraws.entries.toList().forEach { (footer, listener) ->
            runCatching { footer.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        navInsetPreDraws.clear()
        navBarInsetsBeforeFit.clear()
        navBarLayoutsApplied.clear()
        navBarWrappersNeutralized.clear()
        edgeToEdgeApplied.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var corner by remember { mutableIntStateOf(cornerRadiusDp) }
            var side by remember { mutableIntStateOf(sideMarginDp) }
            var gap by remember { mutableIntStateOf(bottomGapDp) }
            var elev by remember { mutableIntStateOf(elevationDp) }
            var panelAbove by remember { mutableStateOf(movePanelAbove) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_floating_footer_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_floating_footer_panel_above),
                                description = stringResource(R.string.chat_floating_footer_panel_above_summary),
                                checked = panelAbove,
                                onCheckedChange = {
                                    panelAbove = it
                                    movePanelAbove = it
                                },
                            )
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.chat_floating_corner_radius_label),
                                    value = corner,
                                    startInt = MIN_CORNER_RADIUS,
                                    endInt = MAX_CORNER_RADIUS,
                                    stepSize = 1,
                                    valueSuffix = "dp",
                                    onValueChange = {
                                        corner = it
                                        cornerRadiusDp = it
                                    },
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.chat_floating_side_margin_label),
                                    value = side,
                                    startInt = MIN_SIDE_MARGIN,
                                    endInt = MAX_SIDE_MARGIN,
                                    stepSize = 1,
                                    valueSuffix = "dp",
                                    onValueChange = {
                                        side = it
                                        sideMarginDp = it
                                    },
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.chat_floating_bottom_gap_label),
                                    value = gap,
                                    startInt = MIN_BOTTOM_GAP,
                                    endInt = MAX_BOTTOM_GAP,
                                    stepSize = 1,
                                    valueSuffix = "dp",
                                    onValueChange = {
                                        gap = it
                                        bottomGapDp = it
                                    },
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.chat_floating_elevation_label),
                                    value = elev,
                                    startInt = MIN_ELEVATION,
                                    endInt = MAX_ELEVATION,
                                    stepSize = 1,
                                    valueSuffix = "dp",
                                    onValueChange = {
                                        elev = it
                                        elevationDp = it
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }
}
