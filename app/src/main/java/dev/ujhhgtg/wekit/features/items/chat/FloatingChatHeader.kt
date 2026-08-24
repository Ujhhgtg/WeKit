package dev.ujhhgtg.wekit.features.items.chat

import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.reflection.int
import java.lang.reflect.Field
import java.util.WeakHashMap
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
object FloatingChatHeader : ClickableFeature(), IResolveDex {

    override val technicalId = "悬浮标题栏"
    override val nameRes = R.string.feature_floating_chat_header_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_floating_chat_header_description

    private const val TAG = "FloatingChatHeader"

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_TOP_GAP = 4
    private const val DEFAULT_EXTRA_GAP = 8
    private const val DEFAULT_ELEVATION = 4
    private const val DEFAULT_COMPONENT_GAP = 8

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
    private const val MIN_COMPONENT_GAP = 0
    private const val MAX_COMPONENT_GAP = 24

    private const val RECONCILE_LAYOUT = 1
    private const val RECONCILE_TIPS = 1 shl 1
    private const val RECONCILE_QUICK_SELECT = 1 shl 2

    /** 标题栏容器, 微信把 ViewStub(bkr) inflate 成这个 androidx 控件。 */
    private const val ACTION_BAR_CONTAINER_CLASS = "androidx.appcompat.widget.ActionBarContainer"

    /** 独立聊天 Activity 的公共基类; 这类页面使用窗口级标题栏。 */
    private const val CHATTING_UI_ACTIVITY_CLASS = "com.tencent.mm.ui.chatting.ChattingUI"

    private const val CONV_BOX_ACTIVITY_CLASS =
        "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"

    private const val BASE_CONVERSATION_ACTIVITY_CLASS =
        "com.tencent.mm.ui.conversation.BaseConversationUI"

    /** 消息列表所在的内容区宿主, 标题区挂件之外的直接子 View 才需要做成悬浮卡。 */
    private const val CHATTING_SCROLL_LAYOUT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout"

    /** 消息列表与多选快捷按钮所在的内容区 (layout ss 的 bki)。 */
    private const val CHATTING_CONTENT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingContent"

    private const val TOOLBAR_CLASS = "androidx.appcompat.widget.Toolbar"
    private const val ACTION_MENU_VIEW_CLASS = "com.tencent.mm.ui.widget.WXActionMenuView"

    /** 内容宿主里这些子 View 不是"标题下挂件", 排除在悬浮卡之外。 */
    private const val ME_HOLDER_VIEW_CLASS = "com.tencent.mm.magicbrush.plugin.emoji.ui.MEHolderView"
    private const val TALK_ROOM_POPUP_NAV_CLASS = "com.tencent.mm.ui.base.TalkRoomPopupNav"

    /** 置顶消息等提示条的宿主 (ViewStub p2f 展开, s3.xml)。 */
    private const val TIPS_BAR_GROUP_CLASS = "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup"

    private val methodTipsExpandAnimationUpdate by dexMethod {
        searchPackages("com.tencent.mm.ui.tipsbar")
        matcher {
            name = "onAnimationUpdate"
            paramTypes("android.animation.ValueAnimator")
            returnType = "void"
            declaredClass {
                addInterface {
                    className = "android.animation.ValueAnimator\$AnimatorUpdateListener"
                }
                addFieldForType(TIPS_BAR_GROUP_CLASS)
                addFieldForType(int)
                fieldCount(2)
            }
            addInvoke {
                declaredClass = "android.animation.ValueAnimator"
                name = "getAnimatedFraction"
                paramTypes()
            }
            addInvoke {
                declaredClass = "android.view.View"
                name = "setOutlineProvider"
                paramTypes("android.view.ViewOutlineProvider")
            }
            addInvoke {
                declaredClass = "android.view.View"
                name = "invalidate"
                paramTypes()
            }
        }
    }

    private val methodTipsFoldAnimationUpdate by dexMethod {
        searchPackages("com.tencent.mm.ui.tipsbar")
        matcher {
            name = "onAnimationUpdate"
            paramTypes("android.animation.ValueAnimator")
            returnType = "void"
            declaredClass {
                addInterface {
                    className = "android.animation.ValueAnimator\$AnimatorUpdateListener"
                }
                addFieldForType(TIPS_BAR_GROUP_CLASS)
                addFieldForType(int)
                addFieldForType(int)
                fieldCount(3)
            }
            addInvoke {
                declaredClass = "android.animation.ValueAnimator"
                name = "getAnimatedFraction"
                paramTypes()
            }
            addInvoke {
                declaredClass = "android.view.View"
                name = "setOutlineProvider"
                paramTypes("android.view.ViewOutlineProvider")
            }
            addInvoke {
                declaredClass = "android.view.View"
                name = "invalidate"
                paramTypes()
            }
        }
    }

    private var cornerRadiusDp by prefOption("floating_chat_header_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_header_side_margin", DEFAULT_SIDE_MARGIN)
    private var topGapDp by prefOption("floating_chat_header_top_gap", DEFAULT_TOP_GAP)
    private var extraGapDp by prefOption("floating_chat_header_extra_gap", DEFAULT_EXTRA_GAP)
    private var elevationDp by prefOption("floating_chat_header_elevation", DEFAULT_ELEVATION)
    private var componentGapDp by prefOption(
        "floating_chat_header_component_gap",
        DEFAULT_COMPONENT_GAP,
    )
    private var headerLayoutStyleName by prefOption(
        "floating_chat_header_layout_style",
        HeaderLayoutStyle.INTEGRATED.name,
    )

    private var headerLayoutStyle: HeaderLayoutStyle
        get() = runCatching { HeaderLayoutStyle.valueOf(headerLayoutStyleName) }
            .getOrDefault(HeaderLayoutStyle.INTEGRATED)
        set(value) {
            headerLayoutStyleName = value.name
        }

    private enum class HeaderLayoutStyle {
        INTEGRATED,
        SEPARATED,
    }

    /** 每个窗口是否已由本特性启用状态栏 edge-to-edge。 */
    private val edgeToEdgeApplied = WeakHashMap<Window, Boolean>()

    /** 每个会话页布局当前生效的状态栏偏移。 */
    private val statusBarOffsets = WeakHashMap<View, Int>()

    /** 每个会话页布局的状态栏偏移刷新监听。 */
    private val statusBarPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** 已把微信 EdgeToEdgeWrapperLayout 的状态栏 padding/色块压掉的窗口包装。 */
    private val statusBarWrappersNeutralized = WeakHashMap<View, Boolean>()

    /** 每个会话页布局 (ChattingUILayout) 对应的标题栏容器。 */
    private val headerViews = WeakHashMap<View, View>()

    /** 标题栏重挂前在根布局里的原始顶部偏移 (含状态栏 inset), 重挂后当 topMargin 的基准。 */
    private val headerTopOffsets = WeakHashMap<View, Int>()

    /** 每个会话页布局对应的消息列表 RecyclerView, 避免每次高度刷新都做整树 DFS。 */
    private val chatListRecyclers = WeakHashMap<View, View>()

    /** 动画帧直接验证消息列表归属, 普通 reconciliation 负责维护。 */
    private val chatListRecyclerLayouts = WeakHashMap<View, View>()

    /** 消息列表相对 ChattingUILayout 的顶部偏移, 避免动画帧沿父链计算。 */
    private val chatListTopOffsets = WeakHashMap<View, Int>()

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

    /** ChatTipsBarGroup 到所属布局的直接映射, 动画回调只读这份缓存。 */
    private val tipsBarGroupLayouts = WeakHashMap<View, View>()

    /** 半屏路径下使用窗口级 ActionBarContainer 时, 已把它所在 ActionBarOverlayLayout 切成 overlay。 */
    private val windowBarOverlays = WeakHashMap<View, Boolean>()

    /** 标题栏来自窗口级 ActionBarContainer 的布局, 边距按 overlay 坐标算, 不再叠加 layout.paddingTop。 */
    private val windowBarHeaders = WeakHashMap<View, Boolean>()

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

    /** 置顶消息挂件的卡片体 (s3.xml 的 hyi, RelativeLayout), 重排用。 */
    private val tipsBarCardBodies = WeakHashMap<View, View>()

    /** 已报过"找不到置顶消息卡片体"的组, 避免每帧刷日志。 */
    private val tipsBarBodyWarned = WeakHashMap<View, Boolean>()

    /** 置顶消息"多条"样式的重叠圆角矩形 (s3.xml 的 ovt), 找到后永久压掉。 */
    private val tipsBarOverlapRects = WeakHashMap<View, View>()

    /** 置顶消息挂件的原生分割线 (s3.xml 的 ovu), 颜色借给每行底部的自定义分割线。 */
    private val tipsBarDividers = WeakHashMap<View, View>()

    /** 置顶消息挂件的折叠 handle (s3.xml 的 b1n), 只读可见性判断展开态。 */
    private val tipsBarHandles = WeakHashMap<View, View>()

    /** 每个提示条组取到的分割线颜色 (原生 ovu 的 ColorDrawable)。 */
    private val tipsBarDividerColors = WeakHashMap<View, Int>()

    /** 已装过 adapter 数量 Hook 的提示条列表。 */
    private val tipsBarRowHooks = WeakHashMap<View, Boolean>()

    /** 每个提示条组折叠态首行右侧的“其余 +N 条”角标。 */
    private val tipsBarBadges = WeakHashMap<View, TextView>()

    /** 每个提示条组的角标当前挂在哪个首行 overlay。 */
    private val tipsBarBadgeRows = WeakHashMap<View, ViewGroup>()

    /** 每个提示条组当前为角标让位的主要文本。 */
    private val tipsBarBadgeTexts = WeakHashMap<View, TextView>()

    /** 主要文本为角标让位前的原始相对 padding。 */
    private val tipsBarBadgeTextPaddings = WeakHashMap<View, IntArray>()

    /** 每个注入行装饰所属的提示条 RecyclerView, 用于精确回收旧列表。 */
    private val tipsBarRowRecyclers = WeakHashMap<View, View>()

    /** 每个置顶消息行内横向布局被本特性调整前的原始上下边距。 */
    private val tipsBarRowOriginalLineMargins = WeakHashMap<View, IntArray>()

    /** 每个提示条行底部的横向分割线，通过 overlay 绘制以兼容不同根布局。 */
    private val tipsBarRowDividers = WeakHashMap<View, View>()

    /** 每个提示条组的折叠动画占位层 (s3.xml 的 ovv)。 */
    private val tipsBarPlaceholders = WeakHashMap<View, View>()

    /** 已摘掉微信 item offset 装饰的置顶消息列表。 */
    private val tipsBarOffsetsRemoved = WeakHashMap<View, Boolean>()

    /** adapter 类 → 指向 ChatTipsBarGroup 的字段, getItemCount hook 里反查组用。 */
    private val tipsBarAdapterGroupFields = HashMap<Class<*>, Field>()

    /** 已 hook 过 getItemCount 的 adapter 类, 避免重复 hook。 */
    private val tipsBarAdapterHooked = HashSet<Class<*>>()

    /** 动画监听器类 → 唯一的 ChatTipsBarGroup 字段, 每类只解析一次。 */
    private val animationGroupFields = HashMap<Class<*>, Field>()

    /** 完整叠放计算中使用的提示条有效高度, 动画帧只按差量更新。 */
    private val tipsBarEffectiveHeights = WeakHashMap<View, Int>()

    /** 折叠时 hyi (内容卡) 上次套用的裁剪轮廓高度。 */
    private val tipsBarBodyOutlineHeights = WeakHashMap<View, Int>()

    /** 每个提示条组上次套用的卡片轮廓高度。 */
    private val tipsBarCardOutlineHeights = WeakHashMap<View, Int>()

    /** 主线程轮廓读取复用的 scratch, 动画帧不分配临时 Outline/Rect。 */
    private val outlineScratch = Outline()
    private val outlineRectScratch = Rect()

    /** 折叠稳态的卡片高, 展开动画的起点/折叠动画的终点。 */
    private val tipsBarFoldHeights = WeakHashMap<View, Int>()

    /** 提示条组的动画轮廓: 高度跟随展开/折叠进度, 阴影和行裁剪共用。 */
    private class TipsBarCardOutline : ViewOutlineProvider() {
        var height: Int = 0

        override fun getOutline(view: View, outline: Outline) {
            val r = view.resources.displayMetrics.density * cornerRadiusDp
            outline.setRoundRect(0, 0, view.width, height.coerceAtLeast(1), r)
        }
    }

    /** hyi 的裁剪轮廓: 折叠时由我们驱动, 把行从底部逐行裁掉。 */
    private class TipsBarBodyOutline : ViewOutlineProvider() {
        var height: Int = 0

        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, height.coerceAtLeast(1), 0f)
        }
    }

    /** 消息列表 RecyclerView 由微信自己设的原始 top padding。 */
    private val chatListBasePaddings = WeakHashMap<View, Int>()

    private class TrackedViewListeners(
        val attachListener: View.OnAttachStateChangeListener,
        val layoutListener: View.OnLayoutChangeListener,
    )

    private class LayoutTracker(val layout: View) {
        var active = true
        var pendingFlags = 0
        var observer: ViewTreeObserver? = null
        var oneShotPreDraw: ViewTreeObserver.OnPreDrawListener? = null
        var reparentRunnable: Runnable? = null
        val observedViews = WeakHashMap<View, TrackedViewListeners>()
    }

    private val layoutTrackers = WeakHashMap<View, LayoutTracker>()
    private val layoutAttachListeners = WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val tipsGroupAttachListeners = WeakHashMap<View, View.OnAttachStateChangeListener>()

    /** 根布局不是 RelativeLayout 而放弃重挂的布局, 不再每帧重试。 */
    private val reparentBlocked = WeakHashMap<View, Boolean>()

    /** 已报过"找不到标题栏/消息列表"的布局, 避免每帧刷日志。 */
    private val lookupWarned = WeakHashMap<View, Boolean>()

    /** 上次实际套用的样式, 配置变化后下一帧自动重刷。 */
    private val headerStyles = WeakHashMap<View, HeaderStyle>()

    /** 每个会话布局当前使用的分体式装饰层。 */
    private val separatedHeaderStates = WeakHashMap<View, SeparatedHeaderState>()

    /** 已报过“找不到标题栏三段结构”的会话布局。 */
    private val separatedHeaderWarned = WeakHashMap<View, Boolean>()

    private data class HeaderStyle(val cornerRadiusDp: Int, val elevationDp: Int)

    private data class HeaderVisualState(
        val view: View,
        val background: Drawable?,
        val outlineProvider: ViewOutlineProvider?,
        val clipToOutline: Boolean,
        val elevation: Float,
        val translationZ: Float,
        val stateListAnimator: StateListAnimator?,
    )

    private data class HeaderClipState(
        val view: ViewGroup,
        val clipChildren: Boolean,
        val clipToPadding: Boolean,
    )

    private class SeparatedHeaderState(
        val layout: View,
        val header: View,
        val toolbar: ViewGroup,
        val root: RelativeLayout,
        val left: View,
        val title: View,
        val right: View,
        var menuHost: ViewGroup?,
        val decorationLayer: FrameLayout,
        val decoration: SeparatedHeaderDecorationView,
        val surfaceColor: Int,
        val visualStates: List<HeaderVisualState>,
        val clipStates: List<HeaderClipState>,
    ) {
        val layoutListeners = HashMap<View, View.OnLayoutChangeListener>()
        val menuActionViews = HashSet<View>()
        lateinit var attachListener: View.OnAttachStateChangeListener
        lateinit var menuAttachListener: View.OnAttachStateChangeListener
        lateinit var menuActionAttachListener: View.OnAttachStateChangeListener
        var menuRetryObserver: ViewTreeObserver? = null
        var menuRetryListener: ViewTreeObserver.OnGlobalLayoutListener? = null
        var updatePosted = false
    }

    private class SeparatedHeaderDecorationView(context: Context) : View(context) {
        private val leftRect = RectF()
        private val titleRect = RectF()
        private val rightRect = RectF()
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private var titleRadius = 0f
        private var buttonRadius = 0f
        private var surfaceColor = Color.TRANSPARENT
        private var shadowRadius = 0f
        private var shadowOffsetY = 0f
        private var shadowColor = Color.TRANSPARENT
        private var strokeWidth = 0f
        private var strokeColor = Color.TRANSPARENT

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }

        fun shadowPadding(elevationPx: Float): Int {
            val radius = elevationPx * 1.5f
            val offset = elevationPx * 0.5f
            return (radius + offset + 2f).roundToInt().coerceAtLeast(1)
        }

        fun update(
            left: RectF,
            title: RectF,
            right: RectF,
            offset: Float,
            titleRadius: Float,
            elevationPx: Float,
            surfaceColor: Int,
            darkMode: Boolean,
            density: Float,
        ) {
            val leftLeft = left.left + offset
            val leftTop = left.top + offset
            val leftRight = left.right + offset
            val leftBottom = left.bottom + offset
            val titleLeft = title.left + offset
            val titleTop = title.top + offset
            val titleRight = title.right + offset
            val titleBottom = title.bottom + offset
            val rightLeft = right.left + offset
            val rightTop = right.top + offset
            val rightRight = right.right + offset
            val rightBottom = right.bottom + offset
            val nextShadowRadius = elevationPx * 1.5f
            val nextShadowOffsetY = elevationPx * 0.5f
            val nextShadowColor = if (darkMode) 0x99000000.toInt() else 0x52000000
            val nextStrokeWidth = if (darkMode) density.coerceAtLeast(1f) else 0f
            if (leftRect.left == leftLeft && leftRect.top == leftTop &&
                leftRect.right == leftRight && leftRect.bottom == leftBottom &&
                titleRect.left == titleLeft && titleRect.top == titleTop &&
                titleRect.right == titleRight && titleRect.bottom == titleBottom &&
                rightRect.left == rightLeft && rightRect.top == rightTop &&
                rightRect.right == rightRight && rightRect.bottom == rightBottom &&
                this.titleRadius == titleRadius && this.surfaceColor == surfaceColor &&
                shadowRadius == nextShadowRadius && shadowOffsetY == nextShadowOffsetY &&
                shadowColor == nextShadowColor && strokeWidth == nextStrokeWidth
            ) {
                return
            }
            leftRect.set(left)
            titleRect.set(title)
            rightRect.set(right)
            leftRect.offset(offset, offset)
            titleRect.offset(offset, offset)
            rightRect.offset(offset, offset)
            this.titleRadius = titleRadius
            buttonRadius = left.height() / 2f
            this.surfaceColor = surfaceColor
            shadowRadius = nextShadowRadius
            shadowOffsetY = nextShadowOffsetY
            shadowColor = nextShadowColor
            strokeWidth = nextStrokeWidth
            strokeColor = 0x24FFFFFF
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (shadowRadius > 0f) {
                shadowPaint.color = surfaceColor
                shadowPaint.setShadowLayer(shadowRadius, 0f, shadowOffsetY, shadowColor)
                drawCards(canvas, shadowPaint)
                shadowPaint.clearShadowLayer()
            }
            fillPaint.color = surfaceColor
            drawCards(canvas, fillPaint)
            if (strokeWidth > 0f) {
                strokePaint.strokeWidth = strokeWidth
                strokePaint.color = strokeColor
                drawCards(canvas, strokePaint)
            }
        }

        private fun drawCards(canvas: Canvas, paint: Paint) {
            if (!leftRect.isEmpty) canvas.drawRoundRect(leftRect, buttonRadius, buttonRadius, paint)
            if (!titleRect.isEmpty) canvas.drawRoundRect(titleRect, titleRadius, titleRadius, paint)
            if (!rightRect.isEmpty) {
                val radius = rightRect.height() / 2f
                canvas.drawRoundRect(rightRect, radius, radius, paint)
            }
        }
    }

    override fun onEnable() {
        cacheAnimationGroupFields()
        methodTipsExpandAnimationUpdate.hookAfter {
            val group = animationGroup(thisObject!!) ?: return@hookAfter
            onTipsAnimationFrame(group)
        }
        methodTipsFoldAnimationUpdate.hookAfter {
            val group = animationGroup(thisObject!!) ?: return@hookAfter
            onTipsAnimationFrame(group)
        }

        ChattingUILayout::class.reflekt().firstConstructorOrNull {
            parameters(Context::class, AttributeSet::class)
        }?.hookAfter {
            val layout = thisObject as? ChattingUILayout ?: return@hookAfter
            ensureLayoutAttachListener(layout)
        } ?: WeLogger.w(TAG, "ChattingUILayout constructor hook target not found")

        // 通知半屏/全屏路径下 ChatFooter 一定存在且稳定 attach, 借它兜底追踪 ChattingUILayout:
        // 这些路径的 ChattingUILayout 可能由微信布局预取线程提前 inflate, 构造 hook/attach 监听会漏。
        ChatFooter::class.reflekt().firstMethodOrNull { name = "onAttachedToWindow" }?.hookAfter {
            val footer = thisObject as? ChatFooter ?: return@hookAfter
            footer.findAncestorChattingUILayout()?.let { layout ->
                ensureLayoutAttachListener(layout)
                trackLayout(layout)
            }
        } ?: WeLogger.w(TAG, "ChatFooter.onAttachedToWindow hook target not found")

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
            trackLayout(layout)
            scheduleReconcile(layout, RECONCILE_LAYOUT)
        } ?: WeLogger.w(TAG, "onLayout hook target not found")

        // 状态栏沉浸后，微信仍会把状态栏 inset 吃进 ChattingUILayout.paddingTop。
        // 只清顶部；导航栏相关的底部 padding 由 FloatingChatFooter 独立处理。
        ChattingUILayout::class.reflekt().firstMethodOrNull { name = "fitSystemWindows" }
            ?.hookAfter {
                zeroChatLayoutTopPadding(thisObject as View)
            } ?: WeLogger.w(TAG, "ChattingUILayout.fitSystemWindows hook target not found")

        // ConvBox.onCreate 尾部的 FullScreenHelper 会 post 把 actionBarSize 写入
        // layout nn 根布局的 paddingTop。edge-to-edge 下清掉这份根补偿，
        // 再把会话容器 jmc 对齐到标题栏最终下沿，不影响并列的 bjy。
        CONV_BOX_ACTIVITY_CLASS.toClass().reflekt().firstMethodOrNull {
            name = "onCreate"
            parameters(Bundle::class)
        }?.hookAfter {
            val activity = thisObject as Activity
            val decor = activity.window.decorView
            decor.post {
                if (!isActive) return@post
                applyConvBoxEdgeToEdge(activity)
            }
        } ?: WeLogger.w(TAG, "ConvBoxServiceConversationUI.onCreate hook target not found")

        // IdleHandler 首次预加载聊天容器会重包原窗口树；退出聊天后也要
        // 重新收敛列表布局。两条路径最后都调用 resumeMainFragment。
        BASE_CONVERSATION_ACTIVITY_CLASS.toClass().reflekt().firstMethodOrNull {
            name = "resumeMainFragment"
            parameters()
        }?.hookAfter {
            val activity = thisObject as Activity
            if (activity.javaClass.name != CONV_BOX_ACTIVITY_CLASS) return@hookAfter
            activity.window.decorView.post {
                if (isActive) applyConvBoxEdgeToEdge(activity)
            }
        } ?: WeLogger.w(TAG, "BaseConversationUI.resumeMainFragment hook target not found")

        // 置顶消息卡展开/收起时, 微信通过 ChatTipsBarGroup.setListViewPaddingTop 自己给消息
        // 列表补 recycler 高度。它与我们算的悬浮 padding 叠加会重复, 直接关掉这个补偿,
        // 顶部 padding 完全由本特性统一计算。
        "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup".toClass().reflekt().firstMethodOrNull {
            name = "setListViewPaddingTop"
        }?.hookBefore {
            val group = thisObject as? View
            val layout = group?.let(::ownerLayoutForTipsGroup)
            result = null
            if (layout != null) scheduleReconcile(layout, RECONCILE_TIPS)
        } ?: WeLogger.w(TAG, "ChatTipsBarGroup.setListViewPaddingTop hook target not found")

        // ChatTipsBarGroup 在树里的实际父容器不猜了: 构造时拿到实例, attach 后反查所属
        // ChattingUILayout 登记。悬浮与 dim 压制都直接走这份登记, 版本差异也能兜住。
        "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup".toClass().reflekt().firstConstructorOrNull {
            parameters(Context::class, AttributeSet::class)
        }?.hookAfter {
            val group = thisObject as? View ?: return@hookAfter
            ensureTipsGroupAttachListener(group)
        } ?: WeLogger.w(TAG, "ChatTipsBarGroup constructor hook target not found")

    }

    private fun cacheAnimationGroupFields() {
        listOf(
            methodTipsExpandAnimationUpdate.method.declaringClass,
            methodTipsFoldAnimationUpdate.method.declaringClass,
        ).forEach { clazz ->
            animationGroupFields[clazz] = clazz.declaredFields.single {
                it.type.name == TIPS_BAR_GROUP_CLASS
            }.apply {
                isAccessible = true
            }
        }
    }

    private fun animationGroup(listener: Any): View? {
        return animationGroupFields[listener.javaClass]?.get(listener) as? View
    }

    private fun ensureLayoutAttachListener(layout: View) {
        if (layoutAttachListeners[layout] != null) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                trackLayout(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                disposeTracker(v)
            }
        }
        layoutAttachListeners[layout] = listener
        layout.addOnAttachStateChangeListener(listener)
        if (layout.isAttachedToWindow) trackLayout(layout)
    }

    private fun ensureTipsGroupAttachListener(group: View) {
        if (tipsGroupAttachListeners[group] != null) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val layout = v.findAncestorChattingUILayout() ?: return
                trackLayout(layout)
                registerTipsGroupOwnership(layout, v)
                scheduleReconcile(layout, RECONCILE_LAYOUT)
            }

            override fun onViewDetachedFromWindow(v: View) {
                val layout = ownerLayoutForTipsGroup(v) ?: return
                val tracker = layoutTrackers[layout]
                val recycler = tipsBarRecyclers[v]
                if (tipsBarGroups[layout] === v) tipsBarGroups.remove(layout)
                if (tracker != null) {
                    unobserveTrackedView(tracker, v)
                }
                clearTipsGroupCaches(v, recycler)
                tipsBarGroupLayouts.remove(v)
                scheduleReconcile(layout, RECONCILE_LAYOUT)
            }
        }
        tipsGroupAttachListeners[group] = listener
        group.addOnAttachStateChangeListener(listener)
        if (group.isAttachedToWindow) listener.onViewAttachedToWindow(group)
    }

    private fun ownerLayoutForTipsGroup(group: View): View? {
        return tipsBarGroupLayouts[group]
            ?: tipsBarGroups.entries.firstOrNull { it.value === group }?.key
            ?: group.findAncestorChattingUILayout()
    }

    private fun trackLayout(layout: View) {
        applyStatusBarEdgeToEdge(layout)
        trackStatusBarOffset(layout)
        if (animationGroupFields.isEmpty()) cacheAnimationGroupFields()
        val existing = layoutTrackers[layout]
        if (existing?.active == true) return
        val tracker = LayoutTracker(layout)
        layoutTrackers[layout] = tracker
        observeTrackedView(tracker, layout, RECONCILE_LAYOUT)
        scheduleReconcile(layout, RECONCILE_LAYOUT)
    }

    private fun registerTipsGroupOwnership(layout: View, group: View) {
        val tracker = layoutTrackers[layout] ?: return
        tipsBarGroups[layout]?.takeIf { it !== group }?.let { previous ->
            val recycler = tipsBarRecyclers[previous]
            unobserveTrackedView(tracker, previous)
            clearTipsGroupCaches(previous, recycler)
            tipsBarGroupLayouts.remove(previous)
        }
        tipsBarGroups[layout] = group
        tipsBarGroupLayouts[group] = layout
        observeTrackedView(tracker, group, RECONCILE_TIPS)
        tipsBarRecycler(group)?.let { observeTrackedView(tracker, it, RECONCILE_TIPS) }
    }

    private fun observeTrackedView(tracker: LayoutTracker, view: View, flags: Int) {
        if (tracker.observedViews[view] != null) return
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                scheduleReconcile(tracker.layout, flags)
            }

            override fun onViewDetachedFromWindow(v: View) {
                unobserveTrackedView(tracker, v)
                if (v === tracker.layout) {
                    disposeTracker(tracker.layout)
                } else {
                    scheduleReconcile(tracker.layout, flags)
                }
            }
        }
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
                                                           oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                scheduleReconcile(tracker.layout, flags)
            }
        }
        tracker.observedViews[view] = TrackedViewListeners(attachListener, layoutListener)
        view.addOnAttachStateChangeListener(attachListener)
        view.addOnLayoutChangeListener(layoutListener)
    }

    private fun unobserveTrackedView(tracker: LayoutTracker, view: View) {
        val listeners = tracker.observedViews.remove(view) ?: return
        view.removeOnAttachStateChangeListener(listeners.attachListener)
        view.removeOnLayoutChangeListener(listeners.layoutListener)
    }

    private fun scheduleReconcile(layout: View, flags: Int = RECONCILE_LAYOUT) {
        val tracker = layoutTrackers[layout] ?: return
        if (!tracker.active || !layout.isAttachedToWindow) return
        tracker.pendingFlags = tracker.pendingFlags or flags
        if (tracker.oneShotPreDraw != null) return

        val observer = layout.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (tracker.oneShotPreDraw === this) {
                    tracker.oneShotPreDraw = null
                    tracker.observer = null
                }
                val pending = tracker.pendingFlags
                tracker.pendingFlags = 0
                if (tracker.active && layout.isAttachedToWindow) {
                    runReconciliation(layout, pending)
                }
                return true
            }
        }
        tracker.observer = observer
        tracker.oneShotPreDraw = listener
        observer.addOnPreDrawListener(listener)
    }

    private fun runReconciliation(layout: View, flags: Int) {
        val covered = if (flags and RECONCILE_LAYOUT != 0) applyIfReady(layout) else 0
        if (flags and RECONCILE_TIPS != 0 && covered and RECONCILE_TIPS == 0) reconcileTips(layout)
        if (flags and RECONCILE_QUICK_SELECT != 0 && covered and RECONCILE_QUICK_SELECT == 0) {
            reconcileQuickSelect(layout)
        }
    }

    private fun applyIfReady(layout: View): Int {
        val header = currentHeader(layout) ?: return 0
        reparentIfNeeded(layout, header)
        applyHeaderStyle(layout, header)
        applyMargins(layout, header)
        applyHeaderZoneCards(layout, header)
        val tipsCovered = applyHeaderZoneOverlays(layout, header)
        suppressTipsBarDimFor(layout)
        applyChatListPadding(layout, header)
        val quickSelectCovered = applyQuickSelectOffset(layout, header)
        val covered = (if (quickSelectCovered) RECONCILE_QUICK_SELECT else 0) or
            if (tipsCovered) RECONCILE_TIPS else 0
        val tracker = layoutTrackers[layout] ?: return covered
        tipsBarGroups[layout]?.takeIf { it.isAttachedToWindow }?.let { group ->
            registerTipsGroupOwnership(layout, group)
        }
        trackQuickSelectInputs(tracker, layout)
        return covered
    }

    private fun isCachedHeaderValid(layout: View, header: View): Boolean {
        return header.isAttachedToWindow && header.rootView === layout.rootView
    }

    private fun currentHeader(layout: View): View? {
        headerViews[layout]?.let { header ->
            if (isCachedHeaderValid(layout, header)) return header
            headerViews.remove(layout)
        }
        return findHeader(layout)
    }

    private fun reconcileTips(layout: View) {
        val header = currentHeader(layout) ?: return
        val group = tipsBarGroups[layout]?.takeIf { it.isAttachedToWindow } ?: return
        registerTipsGroupOwnership(layout, group)
        suppressTipsBarDim(group)
        applyTipsBarCardStyle(group)
        applyPinnedTipsBarLayout(group)
        applyHeaderZoneOverlays(layout, header)
        applyChatListPadding(layout, header)
        val tracker = layoutTrackers[layout] ?: return
        tipsBarRecycler(group)?.let { observeTrackedView(tracker, it, RECONCILE_TIPS) }
    }

    private fun reconcileQuickSelect(layout: View) {
        val header = currentHeader(layout) ?: return
        applyQuickSelectOffset(layout, header)
    }

    private fun findHeader(layout: View): View? {
        layout.allViews.firstOrNull { it.javaClass.name == ACTION_BAR_CONTAINER_CLASS }?.let {
            headerViews[layout] = it
            return it
        }
        // 独立 ChattingUI 路径中 ChattingUIFragment(true) 的 isCurrentActivity=true,
        // 所以布局内的 bkr ViewStub 不会 inflate; 无论是通知半屏还是全屏
        // ChattingMainUI, 标题栏都来自窗口级 ActionBarContainer。LauncherUI 中的
        // 聊天 Fragment 仍走上面的布局内标题栏, 避免误选主窗口标题栏。
        if (layout.isInStandaloneChattingUi()) {
            layout.rootView?.allViews?.firstOrNull {
                it.javaClass.name == ACTION_BAR_CONTAINER_CLASS
            }?.let {
                headerViews[layout] = it
                windowBarHeaders[layout] = true
                return it
            }
        }
        if (lookupWarned.put(layout, true) == null) {
            WeLogger.w(TAG, "ActionBarContainer not found, retrying on next layout event")
        }
        return null
    }

    /**
     * 把标题栏从 ChattingUILayout 摘出来, 重挂到会话页根 RelativeLayout (layout ss 的根)。
     * 标题栏因此脱离消息流的测量, 成为铺在整页之上的覆盖物; 微信对它的 findViewById /
     * setLayoutParams(height) 仍照常工作。pre-draw 里改层级不安全, 延迟到 post 里做。
     */
    private fun reparentIfNeeded(layout: View, header: View) {
        if (reparentBlocked[layout] != null) return
        val parent = header.parent as? ViewGroup ?: return
        if (parent !== layout) {
            // 窗口级 ActionBarContainer 不能重挂 (AppCompat 的 ActionBarOverlayLayout
            // 会继续用它的 LayoutParams, 重挂会类型崩溃), 改为原位 overlay 悬浮。
            // overlay 悬浮依赖本特性维护的状态栏偏移；偏移尚未就绪时只做卡片样式。
            if (windowBarHeaders[layout] == true && statusBarOffset(layout) > 0) {
                headerTopOffsets[layout] = layout.top + layout.paddingTop
                ensureWindowBarOverlay(header)
            }
            return
        }
        val tracker = layoutTrackers[layout] ?: return
        if (tracker.reparentRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                if (!tracker.active) {
                    if (tracker.reparentRunnable === this) tracker.reparentRunnable = null
                    return
                }
                try {
                    performReparent(layout, header)
                } finally {
                    if (tracker.reparentRunnable === this) tracker.reparentRunnable = null
                }
                scheduleReconcile(layout, RECONCILE_LAYOUT)
            }
        }
        tracker.reparentRunnable = runnable
        if (!layout.post(runnable)) tracker.reparentRunnable = null
    }

    /** 把窗口级标题栏所在的 ActionBarOverlayLayout 切成 overlay, 让消息内容延伸到标题卡背后。 */
    private fun ensureWindowBarOverlay(header: View) {
        if (windowBarOverlays[header] != null) return
        var parent = header.parent
        while (parent != null) {
            if (parent.javaClass.name == "androidx.appcompat.widget.ActionBarOverlayLayout") {
                val applied = runCatching {
                    parent.javaClass.getMethod("setOverlayMode", Boolean::class.javaPrimitiveType)
                        .invoke(parent, true)
                    true
                }.getOrDefault(false)
                if (applied) {
                    windowBarOverlays[header] = true
                    WeLogger.d(TAG, "floating standalone chatting window action bar in place")
                }
                break
            }
            parent = parent.parent
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

    /** 圆角 / 裁剪 / 阴影 / 暗色浮层, 与悬浮输入框同一套绘制属性; 标题栏和标题区挂件共用。 */
    private fun applyCardStyle(view: View) {
        val style = HeaderStyle(cornerRadiusDp, elevationDp)
        val density = view.resources.displayMetrics.density
        val expectedElevation = elevationDp * density
        FloatingChatCardVisuals.applyDarkSurface(view, cornerRadiusDp)
        // 半屏路径微信会在展开动画结束时清掉 ActionBarContainer 的 outline (m.a()),
        // 只按样式缓存判断会漏掉这次恢复, 所以 outline/elevation 被微信改掉时也要重刷。
        if (headerStyles[view] == style &&
            view.outlineProvider != null &&
            view.elevation == expectedElevation
        ) {
            return
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = view.resources.displayMetrics.density * cornerRadiusDp
                // 高度为 0 时取 1, 避免空 outline 把整卡裁没
                outline.setRoundRect(0, 0, view.width, view.height.coerceAtLeast(1), r)
            }
        }
        view.clipToOutline = true
        view.elevation = expectedElevation
        headerStyles[view] = style
        WeLogger.d(TAG, "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
    }

    private fun applyHeaderStyle(layout: View, header: View) {
        if (headerLayoutStyle == HeaderLayoutStyle.INTEGRATED) {
            removeSeparatedHeader(layout)
            applyCardStyle(header)
        } else {
            applySeparatedHeaderStyle(layout, header)
        }
    }

    private fun applySeparatedHeaderStyle(layout: View, header: View) {
        if (header.parent === layout && windowBarHeaders[layout] != true) {
            removeSeparatedHeader(layout)
            return
        }
        val current = separatedHeaderStates[layout]
        val state = if (current != null &&
            current.header === header &&
            current.root.isAttachedToWindow &&
            current.root.parent === current.toolbar &&
            current.root.rootView === header.rootView
        ) {
            current
        } else {
            if (current != null) disposeSeparatedHeader(current, restoreVisuals = true)
            createSeparatedHeaderState(layout, header)?.also {
                separatedHeaderStates[layout] = it
                separatedHeaderWarned.remove(layout)
            }
        }
        if (state == null) {
            if (separatedHeaderWarned.put(layout, true) == null) {
                WeLogger.w(TAG, "split header skipped: title bar components not found")
            }
            applyCardStyle(header)
            return
        }
        neutralizeSeparatedHeader(state)
        refreshSeparatedHeader(state)
    }

    private fun createSeparatedHeaderState(layout: View, header: View): SeparatedHeaderState? {
        val toolbar = header.allViews.firstOrNull { it.javaClass.name == TOOLBAR_CLASS } as? ViewGroup
            ?: return null
        val parts = (0 until toolbar.childCount)
            .map(toolbar::getChildAt)
            .filterIsInstance<RelativeLayout>()
            .firstNotNullOfOrNull(::findSeparatedParts)
            ?: return null
        val menuHost = findMenuHost(parts.right)
        val surfaceColor = sampleHeaderSurfaceColor(header)
        val visualStates = listOf(header, toolbar, parts.root).distinct().map { view ->
            HeaderVisualState(
                view = view,
                background = view.background,
                outlineProvider = view.outlineProvider,
                clipToOutline = view.clipToOutline,
                elevation = view.elevation,
                translationZ = view.translationZ,
                stateListAnimator = view.stateListAnimator,
            )
        }
        val clipStates = captureHeaderClipStates(parts.root, header)
        val decorationLayer = FrameLayout(header.context).apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }
        val decoration = SeparatedHeaderDecorationView(header.context)
        decorationLayer.addView(
            decoration,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        parts.root.addView(
            decorationLayer,
            0,
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val state = SeparatedHeaderState(
            layout = layout,
            header = header,
            toolbar = toolbar,
            root = parts.root,
            left = parts.left,
            title = parts.title,
            right = parts.right,
            menuHost = menuHost,
            decorationLayer = decorationLayer,
            decoration = decoration,
            surfaceColor = surfaceColor,
            visualStates = visualStates,
            clipStates = clipStates,
        )
        state.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                postSeparatedHeaderUpdate(state)
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (separatedHeaderStates[layout] === state) {
                    disposeSeparatedHeader(state, restoreVisuals = true)
                    scheduleReconcile(layout, RECONCILE_LAYOUT)
                }
            }
        }
        state.root.addOnAttachStateChangeListener(state.attachListener)
        state.menuAttachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                postSeparatedHeaderUpdate(state)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (state.menuHost === v) state.menuHost = null
                startMenuHostRetry(state)
                postSeparatedHeaderUpdate(state)
            }
        }
        state.menuActionAttachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                postSeparatedHeaderUpdate(state)
            }

            override fun onViewDetachedFromWindow(v: View) {
                postSeparatedHeaderUpdate(state)
            }
        }
        if (menuHost != null) {
            menuHost.addOnAttachStateChangeListener(state.menuAttachListener)
        } else {
            startMenuHostRetry(state)
        }
        neutralizeSeparatedHeader(state)
        syncSeparatedHeaderObservers(state)
        headerStyles.remove(header)
        return state
    }

    private data class SeparatedParts(
        val root: RelativeLayout,
        val left: View,
        val title: View,
        val right: View,
    )

    private fun findSeparatedParts(root: RelativeLayout): SeparatedParts? {
        val children = (0 until root.childCount).map(root::getChildAt)
        val title = children.firstOrNull { child ->
            val lp = child.layoutParams as? RelativeLayout.LayoutParams ?: return@firstOrNull false
            lp.getRule(RelativeLayout.CENTER_IN_PARENT) != 0
        } ?: return null
        val right = children.firstOrNull { child ->
            val lp = child.layoutParams as? RelativeLayout.LayoutParams ?: return@firstOrNull false
            lp.getRule(RelativeLayout.ALIGN_PARENT_RIGHT) != 0 ||
                lp.getRule(RelativeLayout.ALIGN_PARENT_END) != 0
        } ?: return null
        val left = children.firstOrNull { it !== title && it !== right } ?: return null
        return SeparatedParts(root, left, title, right)
    }

    private fun findMenuHost(right: View): ViewGroup? {
        val group = right as ViewGroup
        return (0 until group.childCount)
            .map(group::getChildAt)
            .filterIsInstance<ViewGroup>()
            .firstOrNull()
    }

    private fun isVisibleMenuAction(view: View): Boolean {
        if (!view.isVisible || view.width <= 0 || view.height <= 0) return false
        if (view.javaClass.name != ACTION_MENU_VIEW_CLASS) return true
        // 半屏切全屏时微信会保留一个外层可见、但内部图标和文字均为空的 action 槽位。
        return view.allViews.drop(1).any { content ->
            content.isShown && when (content) {
                is ImageView -> content.drawable != null
                is TextView -> content.text.isNotEmpty()
                else -> false
            }
        }
    }

    private fun syncMenuHost(state: SeparatedHeaderState) {
        val current = findMenuHost(state.right)?.takeIf { it.isAttachedToWindow }
        if (state.menuHost === current) {
            if (current == null) startMenuHostRetry(state)
            return
        }
        state.menuHost?.removeOnAttachStateChangeListener(state.menuAttachListener)
        state.menuHost = current
        if (current != null) {
            current.addOnAttachStateChangeListener(state.menuAttachListener)
            stopMenuHostRetry(state)
        } else {
            startMenuHostRetry(state)
        }
    }

    private fun startMenuHostRetry(state: SeparatedHeaderState) {
        if (state.menuRetryListener != null || !state.root.isAttachedToWindow) return
        val observer = state.root.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (separatedHeaderStates[state.layout] !== state || !state.root.isAttachedToWindow) {
                stopMenuHostRetry(state)
                return@OnGlobalLayoutListener
            }
            val replacement = findMenuHost(state.right)?.takeIf { it.isAttachedToWindow }
                ?: return@OnGlobalLayoutListener
            state.menuHost = replacement
            replacement.addOnAttachStateChangeListener(state.menuAttachListener)
            stopMenuHostRetry(state)
            postSeparatedHeaderUpdate(state)
        }
        state.menuRetryObserver = observer
        state.menuRetryListener = listener
        observer.addOnGlobalLayoutListener(listener)
    }

    private fun stopMenuHostRetry(state: SeparatedHeaderState) {
        val listener = state.menuRetryListener ?: return
        state.menuRetryObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        state.menuRetryObserver = null
        state.menuRetryListener = null
    }

    private fun captureHeaderClipStates(root: ViewGroup, header: View): List<HeaderClipState> {
        val states = ArrayList<HeaderClipState>()
        var current: ViewGroup? = root
        while (current != null) {
            states += HeaderClipState(current, current.clipChildren, current.clipToPadding)
            if (current !== root && current.height > header.height * 2) break
            current = current.parent as? ViewGroup
        }
        return states
    }

    private fun neutralizeSeparatedHeader(state: SeparatedHeaderState) {
        state.visualStates.forEach { original ->
            val view = original.view
            val background = view.background
            if (background !is ColorDrawable || background.color != Color.TRANSPARENT) {
                view.background = ColorDrawable(Color.TRANSPARENT)
            }
            if (view.outlineProvider != null) view.outlineProvider = null
            if (view.clipToOutline) view.clipToOutline = false
            if (view.elevation != 0f) view.elevation = 0f
            if (view.translationZ != 0f) view.translationZ = 0f
            if (view.stateListAnimator != null) view.stateListAnimator = null
        }
        state.clipStates.forEach { clip ->
            if (clip.view.clipChildren) clip.view.clipChildren = false
            if (clip.view.clipToPadding) clip.view.clipToPadding = false
        }
    }

    private fun refreshSeparatedHeader(state: SeparatedHeaderState) {
        if (!state.root.isAttachedToWindow || state.root.width <= 0 || state.root.height <= 0) return
        syncMenuHost(state)
        syncSeparatedHeaderObservers(state)
        val density = state.root.resources.displayMetrics.density
        val elevationPx = elevationDp * density
        val shadowPadding = state.decoration.shadowPadding(elevationPx)
        val targetWidth = state.root.width + shadowPadding * 2
        val targetHeight = state.root.height + shadowPadding * 2
        val params = state.decoration.layoutParams as FrameLayout.LayoutParams
        if (params.width != targetWidth || params.height != targetHeight ||
            params.leftMargin != -shadowPadding || params.topMargin != -shadowPadding
        ) {
            params.width = targetWidth
            params.height = targetHeight
            params.leftMargin = -shadowPadding
            params.topMargin = -shadowPadding
            state.decoration.layoutParams = params
        }

        val rootHeight = state.root.height.toFloat()
        val buttonDiameter = rootHeight
        val backIcon = state.left.allViews.firstOrNull { it is ImageView && it.isVisible }
        val backBounds = RectF()
        val leftBounds = RectF()
        boundsInHeaderRoot(state.left, state.root, leftBounds)
        val leftCenterX = if (backIcon != null) {
            boundsInHeaderRoot(backIcon, state.root, backBounds)
            backBounds.left + backIcon.paddingLeft +
                (backIcon.width - backIcon.paddingLeft - backIcon.paddingRight) / 2f
        } else {
            leftBounds.centerX()
        }
        val leftCard = RectF(
            leftCenterX - buttonDiameter / 2f,
            0f,
            leftCenterX + buttonDiameter / 2f,
            rootHeight,
        )

        val rightCard = RectF()
        state.menuHost?.let { menuHost ->
            for (index in 0 until menuHost.childCount) {
                val child = menuHost.getChildAt(index)
                if (!isVisibleMenuAction(child)) continue
                val bounds = RectF()
                boundsInHeaderRoot(child, state.root, bounds)
                val actionCard = RectF(
                    bounds.centerX() - buttonDiameter / 2f,
                    0f,
                    bounds.centerX() + buttonDiameter / 2f,
                    rootHeight,
                )
                if (rightCard.isEmpty) rightCard.set(actionCard) else rightCard.union(actionCard)
            }
        }

        val gapPx = componentGapDp * density
        val leftReserve = leftCard.right + gapPx
        val rightReserve = if (rightCard.isEmpty) {
            leftReserve
        } else {
            state.root.width - rightCard.left + gapPx
        }
        // 多 action 半屏右区比左侧宽；名称卡仍须以屏幕为中心，因此配置值是两侧
        // 可见间距的下限，较宽一侧严格命中，另一侧可能留出更多空间。
        val sideReserve = maxOf(leftReserve, rightReserve)
        val titleCard = RectF(
            sideReserve,
            0f,
            state.root.width - sideReserve,
            rootHeight,
        )
        if (titleCard.right <= titleCard.left) titleCard.setEmpty()

        state.decoration.update(
            left = leftCard,
            title = titleCard,
            right = rightCard,
            offset = shadowPadding.toFloat(),
            titleRadius = cornerRadiusDp * density,
            elevationPx = elevationPx,
            surfaceColor = state.surfaceColor,
            darkMode = state.root.context.isDarkMode,
            density = density,
        )
    }

    private fun syncSeparatedHeaderObservers(state: SeparatedHeaderState) {
        val observed = LinkedHashSet<View>()
        val menuActions = LinkedHashSet<View>()
        observed += state.root
        observed += state.left
        observed += state.title
        observed += state.right
        state.menuHost?.let(observed::add)
        state.left.allViews.firstOrNull { it is ImageView && it.isVisible }?.let(observed::add)
        state.menuHost?.let { menuHost ->
            for (index in 0 until menuHost.childCount) {
                val action = menuHost.getChildAt(index)
                menuActions += action
                observed += action
                action.allViews.drop(1).forEach(observed::add)
            }
        }
        state.menuActionViews.filterNot(menuActions::contains).forEach { action ->
            action.removeOnAttachStateChangeListener(state.menuActionAttachListener)
            state.menuActionViews.remove(action)
        }
        menuActions.filterNot(state.menuActionViews::contains).forEach { action ->
            action.addOnAttachStateChangeListener(state.menuActionAttachListener)
            state.menuActionViews += action
        }
        state.layoutListeners.keys.toList().filterNot(observed::contains).forEach { view ->
            view.removeOnLayoutChangeListener(state.layoutListeners.remove(view))
        }
        observed.filterNot(state.layoutListeners::containsKey).forEach { view ->
            val listener = View.OnLayoutChangeListener {
                    _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    postSeparatedHeaderUpdate(state)
                }
            }
            state.layoutListeners[view] = listener
            view.addOnLayoutChangeListener(listener)
        }
    }

    private fun postSeparatedHeaderUpdate(state: SeparatedHeaderState) {
        if (state.updatePosted) return
        state.updatePosted = true
        state.root.post {
            state.updatePosted = false
            if (separatedHeaderStates[state.layout] !== state) return@post
            if (!state.root.isAttachedToWindow) {
                scheduleReconcile(state.layout, RECONCILE_LAYOUT)
                return@post
            }
            neutralizeSeparatedHeader(state)
            refreshSeparatedHeader(state)
        }
    }

    private fun boundsInHeaderRoot(view: View, root: View, out: RectF) {
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        view.getLocationInWindow(viewLocation)
        val left = (viewLocation[0] - rootLocation[0]).toFloat()
        val top = (viewLocation[1] - rootLocation[1]).toFloat()
        out.set(left, top, left + view.width, top + view.height)
    }

    private fun sampleHeaderSurfaceColor(header: View): Int {
        val fallback = if (header.context.isDarkMode) 0xFF242424.toInt() else 0xFFF7F7F7.toInt()
        if (header.width <= 0 || header.height <= 0) return fallback
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-(header.width / 2f), -1f)
        header.draw(canvas)
        val sampled = bitmap.getPixel(0, 0)
        bitmap.recycle()
        return if (Color.alpha(sampled) >= 0x80) sampled else fallback
    }

    private fun removeSeparatedHeader(layout: View) {
        separatedHeaderStates[layout]?.let { disposeSeparatedHeader(it, restoreVisuals = true) }
    }

    private fun disposeSeparatedHeader(state: SeparatedHeaderState, restoreVisuals: Boolean) {
        if (separatedHeaderStates[state.layout] === state) separatedHeaderStates.remove(state.layout)
        state.layoutListeners.forEach { (view, listener) ->
            view.removeOnLayoutChangeListener(listener)
        }
        state.layoutListeners.clear()
        state.menuActionViews.forEach { action ->
            action.removeOnAttachStateChangeListener(state.menuActionAttachListener)
        }
        state.menuActionViews.clear()
        state.root.removeOnAttachStateChangeListener(state.attachListener)
        state.menuHost?.removeOnAttachStateChangeListener(state.menuAttachListener)
        stopMenuHostRetry(state)
        if (state.decorationLayer.parent === state.root) {
            state.root.removeView(state.decorationLayer)
        }
        if (restoreVisuals) {
            state.visualStates.forEach { original ->
                original.view.background = original.background
                original.view.outlineProvider = original.outlineProvider
                original.view.clipToOutline = original.clipToOutline
                original.view.stateListAnimator = original.stateListAnimator
                original.view.elevation = original.elevation
                original.view.translationZ = original.translationZ
            }
            state.clipStates.forEach { original ->
                original.view.clipChildren = original.clipChildren
                original.view.clipToPadding = original.clipToPadding
            }
        }
    }

    /**
     * 提示条卡自己的样式: 投影/裁剪的轮廓只覆盖卡片区域, 而不是整组。
     *
     * 轮廓由一个可变高度的 TipsBarCardOutline 驱动: 展开/折叠动画期间高度跟着动画走,
     * 展开时镜像 hyi 的 outline、折叠时由动画驱动, 阴影实时跟随卡片而不是框住整张
     * 全高卡; 稳态时高度 = 组高 (真实卡片高)。elevation 用用户设置的固定值。
     */
    private fun applyTipsBarCardStyle(group: View) {
        val style = HeaderStyle(cornerRadiusDp, elevationDp)
        FloatingChatCardVisuals.applyDarkSurface(group, cornerRadiusDp)
        if (tipsBarStyles[group] != style) {
            val density = group.resources.displayMetrics.density
            group.outlineProvider = group.outlineProvider as? TipsBarCardOutline
                ?: TipsBarCardOutline().also {
                    it.height = group.height
                    group.outlineProvider = it
                    tipsBarCardOutlineHeights[group] = group.height
                }
            group.clipToOutline = true
            group.elevation = elevationDp * density
            tipsBarStyles[group] = style
            WeLogger.d(TAG, "applied tips bar card style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
        }
    }

    /** 左右留白 + 顶部间距; topMargin 以微信原本的位置为基准, 自动适配状态栏。 */
    private fun applyMargins(layout: View, header: View) {
        val lp = header.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = header.resources.displayMetrics.density
        val sidePx = (sideMarginDp * density).toInt()
        // 实时算而不是用重挂时的快照: 聊天页 edge-to-edge 后 paddingTop 会被清零,
        // 标题卡需要落在 状态栏 inset + 顶部间距 的位置。
        val topPx = if (windowBarHeaders[layout] == true) {
            // 窗口级 ActionBarContainer 的坐标原点已经包含系统栏偏移, 直接加状态栏 inset 即可
            statusBarOffset(layout) + (topGapDp * density).toInt()
        } else {
            layout.top + layout.paddingTop +
                statusBarOffset(layout) + (topGapDp * density).toInt()
        }
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
        val baseOffsetPx = statusBarOffset(layout) +
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
    private fun applyHeaderZoneOverlays(layout: View, header: View): Boolean {
        if (headerTopOffsets[layout] == null) return false
        val host = contentHost(layout) ?: return false
        val hostGroup = host as? ViewGroup ?: return false
        val density = layout.resources.displayMetrics.density
        val sidePx = (sideMarginDp * density).toInt()
        val gapPx = (extraGapDp * density).toInt()
        val titleBottomPx = header.height + (topGapDp * density).toInt()
        // 下一张卡的期望顶部 (ChattingUILayout 坐标系)
        val hostTopPx = hostGroup.offsetTopIn(layout)
        // 流内挂件已把内容宿主推下去时, 期望位置不会高于宿主顶部
        var nextTopPx = (statusBarOffset(layout) + layout.paddingTop +
            titleBottomPx + gapPx).coerceAtLeast(hostTopPx)
        var bottomPx: Int? = null
        var pinnedTipsApplied = false
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
                registerTipsGroupOwnership(layout, child)
                // 展开态的 ChatTipsBarGroup 会被 dim 撑满整个内容区, 必须先摘掉 dim,
                // 否则后面的高度兜底检查会把它当成全屏覆盖层跳过。
                suppressTipsBarDim(child)
                applyTipsBarCardStyle(child)
                pinnedTipsApplied = applyPinnedTipsBarLayout(child) || pinnedTipsApplied
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
            if (isTipsGroup) tipsBarEffectiveHeights[child] = cardHeight
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
            pinnedTipsApplied = applyPinnedTipsBarLayout(tracked) || pinnedTipsApplied
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
            val cardHeight = effectiveTipsBarHeight(tracked)
            tipsBarEffectiveHeights[tracked] = cardHeight
            nextTopPx += cardHeight + gapPx
            bottomPx = nextTopPx - gapPx
        }
        if (bottomPx != null) {
            overlayCardBottoms[layout] = bottomPx
        } else {
            overlayCardBottoms.remove(layout)
        }
        return pinnedTipsApplied
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
            // 兜底 1: 即使某帧微信把它重新点亮, alpha=0 也保证画不出来
            if (dim.alpha != 0f) dim.alpha = 0f
            // 兜底 2: 背景清空, 无论微信怎么改可见性/透明度都画不出那层全屏灰色
            if (dim.background != null) dim.background = null
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
        val cached = tipsBarRecyclers[group]
        cached?.takeIf { it.isAttachedToWindow && it.isDescendantOf(group) }?.let { return it }
        val found = group.findViewWhich {
            it.javaClass.name == "com.tencent.mm.view.recyclerview.MaxHeightWxRecyclerView"
        }
        if (cached !== found) {
            cached?.let { retireTipsRecycler(group, it) }
            if (found != null) tipsBarRecyclers[group] = found
        }
        return found
    }

    /**
     * 返回"实际卡片"高度: 折叠动画期间轮廓随动画逐帧收缩、展开时镜像 hyi 的 reveal,
     * 列表 padding 跟着它平滑变化。之前按占位层撑起的组高算, 折叠结束瞬间 padding 会
     * 一次性掉几千像素, RecyclerView 锚定失效, 表现为列表跳到一条旧消息。
     */
    private fun effectiveTipsBarHeight(group: View): Int {
        return tipsBarCardOutlineHeights[group] ?: group.height
    }

    /** 折叠过渡占位层 (s3.xml 的 ovv): 纯 View, 宽 match_parent, 高 0 或动画期被微信
     * 撑到卡片全高; 和 dim (ow1, 全尺寸) 靠高度参数区分。 */
    private fun tipsBarPlaceholder(group: View): View? {
        tipsBarPlaceholders[group]?.takeIf { it.parent != null }?.let { return it }
        val found = group.findViewWhich { view ->
            view.javaClass.name == "android.view.View" &&
                view.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                view.layoutParams?.height != ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (found != null) tipsBarPlaceholders[group] = found
        return found
    }

    /**
     * 摘掉微信给置顶消息列表加的 item offset 装饰 (每行底部 8dp): 折叠态这 8dp 造成
     * 内容偏上、展开态造成第二条起每行上空隙, 统一改成行内上下 4dp, 让行结构一致。
     * 微信的 androidx 方法名被混淆 (构造器里 addItemDecoration 实际叫 N), 不能按名字
     * 找; 装饰列表是 RecyclerView 上唯一的"元素声明了 getItemOffsets"的 public
     * ArrayList 字段, 直接清空, 不依赖任何混淆名。
     */
    private fun removePinnedItemOffsets(recycler: View) {
        if (tipsBarOffsetsRemoved[recycler] != null) return
        runCatching {
            // 装饰列表是 RecyclerView 上唯一的"元素声明了 getItemOffsets"的 public
            // ArrayList 字段 (getItemDecorations/removeItemDecoration 被微信瘦身删掉了),
            // 直接清空
            var removed = 0
            var current: Class<*>? = recycler.javaClass
            while (current != null) {
                for (field in current.declaredFields) {
                    if (field.type != ArrayList::class.java) continue
                    val list = field.get(recycler) as? ArrayList<*> ?: continue
                    val sample = list.firstOrNull() ?: continue
                    if (sample.javaClass.declaredMethods.any { it.name == "getItemOffsets" }) {
                        removed = list.size
                        list.clear()
                        recycler.requestLayout()
                        break
                    }
                }
                if (removed > 0) break
                current = current.superclass
            }
            if (removed == 0) error("no item decoration list found")
            tipsBarOffsetsRemoved[recycler] = true
            WeLogger.d(TAG, "pinned tips bar native item offsets removed ($removed)")
        }.onFailure {
            WeLogger.w(TAG, "pinned tips bar offset decoration removal failed, keeping native spacing", it)
        }
    }

    /**
     * ChatTipsBarGroup 的完整重排:
     *
     * - 所有 Tips 统一使用展开态分割线；非置顶 Tips 保留微信自己的语义背景和内部行距，
     *   只有置顶消息行去掉 bce 背景并补内部行距，直接融入悬浮卡片；
     * - 微信的 item offset 装饰摘掉，列表边距归零，行铺满整卡；
     * - 折叠态在当前首行右侧显示 +(总 Tips 数 - 1)，展开态隐藏；
     * - 各类 Tips 原有的图标、按钮和点击行为均不修改。
     */
    private fun applyPinnedTipsBarLayout(group: View): Boolean {
        // 早退 1: 找不到卡片体 (s3.xml 的 hyi)。注意 s3 的根 FrameLayout 才是组的直接子
        // View, hyi 在根 FrameLayout 下面, 必须递归找, 不能用 parent === group。
        val body = tipsBarCardBody(group)
        if (body == null) {
            if (tipsBarBodyWarned.put(group, true) == null) {
                val tree = group.allViews.take(24).joinToString(", ") { v ->
                    "${v.javaClass.simpleName}[p=${v.parent?.javaClass?.simpleName}]"
                }
                WeLogger.w(TAG, "pinned tips bar card body not found, group tree: $tree")
            }
            return false
        }
        FloatingChatCardVisuals.applyDarkSurface(body, cornerRadiusDp)
        // 早退 2: 找不到内容列表 (MaxHeightWxRecyclerView), 保留原生布局。
        val recycler = tipsBarRecycler(group) ?: return false
        val expanded = tipsBarHandle(group)?.isVisible == true
        // 折叠动画中: handle 已藏、微信的 ovv 占位层还撑着全高
        val collapsing = !expanded && tipsBarPlaceholder(group)?.height ?: 0 > 0
        // 行 2+ 是否被 getItemCount hook 保住; 没保住就不做轮廓动画, 阴影直接贴内容卡
        val rowsKept = (recycler as ViewGroup).childCount > 1
        // 灰色占位层只是微信用来撑折叠动画的, 会连同阴影呈现成全屏灰色边框, 背景清掉
        tipsBarPlaceholder(group)?.background = null
        // 行没保住时把占位层高度也压掉: 组高、列表 padding 立即回到折叠卡, 不出现全屏边框
        if (collapsing && !rowsKept) {
            val placeholder = tipsBarPlaceholder(group)
            val placeholderLp = placeholder?.layoutParams
            if (placeholderLp != null && placeholderLp.height != 0) {
                placeholderLp.height = 0
                placeholder.requestLayout()
            }
        }
        // 摘掉微信每行底部 8dp 的 item offset；置顶消息行距继续由内部布局边距承担
        removePinnedItemOffsets(recycler)
        installPinnedAdapterHook(recycler)
        refreshPinnedRows(recycler, group, expanded || collapsing)
        updateSteadyTipsBarOutline(group, expanded, collapsing, rowsKept)
        // 行铺满整卡: 去掉 s3.xml 里列表的 8dp 左右边距, 折叠单条时点击不会落在行外
        val lp = recycler.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null && (lp.leftMargin != 0 || lp.rightMargin != 0 ||
            lp.topMargin != 0 || lp.bottomMargin != 0)
        ) {
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.topMargin = 0
            lp.bottomMargin = 0
            recycler.requestLayout()
        }
        // 多条重叠矩形和原生分割线都不再需要, 分割线统一画在每行底部
        tipsBarOverlapRect(group)?.visibility = View.GONE
        tipsBarDivider(group)?.visibility = View.GONE
        // 展开态若残留折叠动画的占位层 (动画被打断时微信可能没把它归零), 会变成卡片
        // 背后一块灰色, 直接压掉
        if (expanded) {
            val placeholder = tipsBarPlaceholder(group)
            val placeholderLp = placeholder?.layoutParams
            if (placeholderLp != null && placeholderLp.height != 0) {
                placeholderLp.height = 0
                placeholder.requestLayout()
            }
        }
        return true
    }

    /**
     * 卡片轮廓: 阴影和行裁剪共用一个可变高轮廓。
     *
     * - 折叠: getItemCount hook 保住行 2+, 轮廓从全高收缩到折叠稳态高, 行被升起的
     *   卡片下沿逐行盖住; 灰色占位层背景已清空, 不会出现全屏灰色边框; 行没保住时不做
     *   动画, 轮廓直接贴内容卡;
     * - 展开: 不跑自己的动画, 直接读 hyi 当前 outline 的实际高度 (微信自己的 reveal
     *   动画在驱动它), 阴影和内容来自同一个数值, 比例严格 1:1。
     * elevation 是用户设置的固定值; 全屏灰色边框来自微信 dim, 已由 suppressTipsBarDim
     * 清空背景处理。
     */
    private fun updateSteadyTipsBarOutline(
        group: View,
        expanded: Boolean,
        collapsing: Boolean,
        rowsKept: Boolean
    ) {
        val body = tipsBarCardBody(group) ?: return
        if (collapsing) {
            // 保留行时由微信折叠动画回调逐帧拥有轮廓; 行没保住则立即贴内容卡兜底。
            if (rowsKept) return
            setTipsBarBodyOutline(body, body.height)
            setTipsBarCardOutline(group, body.height)
            return
        }
        if (expanded) {
            setTipsBarCardOutline(group, outlineHeight(body) ?: body.height.takeIf { it > 0 } ?: group.height)
            return
        } else {
            // 行 2+ 还在时组高是展开高; 只有列表真正只剩 1 行 (行已被摘掉) 才记录折叠
            // 稳态高, 否则下一次展开动画会从全高起跳, 轮廓一帧就占满全屏
            val recycler = tipsBarRecycler(group) as? ViewGroup
            if (recycler == null || recycler.childCount <= 1) {
                tipsBarFoldHeights[group] = group.height
            }
            setTipsBarBodyOutline(body, body.height)
            setTipsBarCardOutline(group, group.height)
            return
        }
    }

    /** 读取 View 当前 outline 的实际高度。 */
    private fun outlineHeight(view: View): Int? {
        val provider = view.outlineProvider ?: return null
        outlineScratch.setEmpty()
        outlineRectScratch.setEmpty()
        provider.getOutline(view, outlineScratch)
        if (!outlineScratch.canClip()) return null
        outlineScratch.getRect(outlineRectScratch)
        return if (outlineRectScratch.height() > 0) outlineRectScratch.height() else null
    }

    private fun onTipsAnimationFrame(group: View) {
        val layout = tipsBarGroupLayouts[group] ?: return
        val tracker = layoutTrackers[layout] ?: return
        if (!tracker.active || tipsBarGroups[layout] !== group || !group.isAttachedToWindow) return
        val body = tipsBarCardBodies[group]?.takeIf { it.parent != null } ?: return
        val handle = tipsBarHandles[group]?.takeIf { it.parent != null } ?: return
        val placeholder = tipsBarPlaceholders[group]?.takeIf { it.parent != null } ?: return
        val header = headerViews[layout]?.takeIf { it.isAttachedToWindow } ?: return
        val recycler = chatListRecyclers[layout]?.takeIf {
            it.isAttachedToWindow && chatListRecyclerLayouts[it] === layout
        } ?: return
        val source = if (handle.isVisible) body else placeholder
        val height = outlineHeight(source) ?: return
        setTipsBarBodyOutline(body, height)
        setTipsBarCardOutline(group, height)
        updateAnimatedTipsGeometry(layout, group, header, recycler, height)
    }

    private fun updateAnimatedTipsGeometry(
        layout: View,
        group: View,
        header: View,
        recycler: View,
        height: Int,
    ) {
        val previous = tipsBarEffectiveHeights.put(group, height)
        if (previous == null) {
            scheduleReconcile(layout, RECONCILE_TIPS)
            return
        }
        overlayCardBottoms[layout]?.let { bottom ->
            overlayCardBottoms[layout] = bottom + height - previous
        }
        applyAnimatedChatListPadding(layout, recycler)
    }

    /** 折叠时驱动 hyi 的裁剪轮廓, 把行从底部逐行裁掉。 */
    private fun setTipsBarBodyOutline(body: View, height: Int) {
        if (tipsBarBodyOutlineHeights[body] == height) return
        tipsBarBodyOutlineHeights[body] = height
        val provider = body.outlineProvider as? TipsBarBodyOutline
            ?: TipsBarBodyOutline().also { body.outlineProvider = it }
        provider.height = height
        body.clipToOutline = true
        body.invalidateOutline()
    }

    private fun setTipsBarCardOutline(group: View, height: Int) {
        if (tipsBarCardOutlineHeights[group] == height) return
        tipsBarCardOutlineHeights[group] = height
        val provider = group.outlineProvider as? TipsBarCardOutline
            ?: TipsBarCardOutline().also { group.outlineProvider = it }
        provider.height = height
        group.invalidateOutline()
    }

    private fun installPinnedAdapterHook(recycler: View) {
        if (tipsBarRowHooks[recycler] != null) return
        installTipsBarAdapterCountHook(recycler)
        tipsBarRowHooks[recycler] = true
        WeLogger.d(TAG, "tips bar adapter hook installed")
    }

    /**
     * 折叠动画期间保住行 2+: 微信的 adapter getItemCount 在折叠态返回 1, 会立刻把多余
     * 行摘掉, 只留灰色占位层撑全高 (阴影跟着它就成了全屏边框)。占位层 ovv 还撑着全高时
     * 按完整数量返回, 行留着被升起的卡片下沿逐行盖住。方法名被混淆, 但 getItemCount 是
     * adapter 自身声明里唯一的"无参返回 int"。
     */
    private fun installTipsBarAdapterCountHook(recycler: View) {
        val adapter = tipsBarAdapter(recycler) ?: return
        val adapterClass = adapter.javaClass
        if (!tipsBarAdapterHooked.add(adapterClass)) return
        val getItemCount = adapterClass.declaredMethods.firstOrNull {
            !it.isSynthetic && it.parameterCount == 0 && it.returnType == Integer.TYPE
        }
        if (getItemCount == null) {
            WeLogger.w(TAG, "tips bar adapter getItemCount not found, collapse animation degrades")
            return
        }
        getItemCount.hookBefore {
            val hookAdapter = thisObject ?: return@hookBefore
            val group = tipsBarAdapterGroup(hookAdapter) ?: return@hookBefore
            if (tipsBarPlaceholder(group)?.height ?: 0 > 0) {
                val count = tipsCount(group)
                if (count > 1) result = count
            }
        }
        WeLogger.d(TAG, "tips bar adapter getItemCount hooked (${adapterClass.name})")
    }

    /**
     * 从列表上反查置顶消息 adapter: 遍历无参公开方法, 返回对象声明了 ChatTipsBarGroup
     * 类型字段的就是它 (getAdapter 等 androidx 方法名被混淆, 按返回对象结构识别)。
     */
    private fun tipsBarAdapter(recycler: View): Any? {
        for (method in recycler.javaClass.methods) {
            if (method.parameterCount != 0) continue
            val result = runCatching { method.invoke(recycler) }.getOrNull() ?: continue
            if (result.javaClass.declaredFields.any {
                    it.type.name == "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup"
                }
            ) {
                return result
            }
        }
        return null
    }

    /** adapter 里指向 ChatTipsBarGroup 的字段 (按字段类型找, 不依赖混淆名)。 */
    private fun tipsBarAdapterGroup(adapter: Any): View? {
        val clazz = adapter.javaClass
        val field = tipsBarAdapterGroupFields[clazz] ?: clazz.declaredFields.firstOrNull {
            it.type.name == "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup"
        }?.apply { isAccessible = true }?.also { tipsBarAdapterGroupFields[clazz] = it }
        return field?.get(adapter) as? View
    }

    /** 每帧把已挂载的 Tips 行刷成统一布局。
     * [showExpandedStyle] 在折叠动画期间也保持 true, 行距/分割线不提前切换。 */
    @SuppressLint("SetTextI18n")
    private fun refreshPinnedRows(recycler: View, group: View, showExpandedStyle: Boolean) {
        val dividerColor = tipsBarDividerColor(group)
        val remainingCount = if (showExpandedStyle) 0 else tipsCount(group) - 1
        val list = recycler as ViewGroup
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            tipsBarRowRecyclers[row] = recycler
            // 置顶消息的 bce 背景清掉后融入整卡；警告等其它 Tips 保留微信自己的语义背景。
            if (row.isPinnedTipsRow()) row.background = null
            val rowDivider = tipsBarRowDividers[row] ?: ensureTipsRowDivider(row)
            rowDivider.setBackgroundColor(dividerColor)
            rowDivider.visibility = if (showExpandedStyle) View.VISIBLE else View.GONE
            if (row.width > 0 && row.height > 0) {
                rowDivider.layout(0, row.height - 1, row.width, row.height)
            }
            // 置顶消息继续用原先验证过的内部行距；其它 Tips 保留微信自己的内部 spacing，
            // 不能给 item 根补 padding，否则警告的内层红色背景周围会露出卡片底色。
            if (row.isPinnedTipsRow()) {
                val line = (row as FrameLayout).getChildAt(0) as LinearLayout
                val lineLp = line.layoutParams as ViewGroup.MarginLayoutParams
                val original = tipsBarRowOriginalLineMargins.getOrPut(row) {
                    intArrayOf(lineLp.topMargin, lineLp.bottomMargin)
                }
                val halfGapPx =
                    if (showExpandedStyle) (4 * row.resources.displayMetrics.density).toInt() else 0
                val top = original[0] + halfGapPx
                val bottom = original[1] + halfGapPx
                if (lineLp.topMargin != top || lineLp.bottomMargin != bottom) {
                    lineLp.topMargin = top
                    lineLp.bottomMargin = bottom
                    line.requestLayout()
                }
            }
        }
        updateTipsBarBadge(group, recycler, remainingCount)
    }

    /** ChatTipsBarGroup 唯一的 ArrayList 字段就是包含全部类型 Tips 的 dataList。 */
    private fun tipsCount(group: View): Int {
        return (group.reflekt().firstField { type = ArrayList::class }.get() as ArrayList<*>).size
    }

    /** 折叠态把 +(总数 - 1) 画在当前首行 overlay，背景仍由该 Tips 自己完整铺满。 */
    private fun updateTipsBarBadge(group: View, recycler: View, remainingCount: Int) {
        val badge = tipsBarBadges[group] ?: TextView(group.context).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            id = View.generateViewId()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }.also { tipsBarBadges[group] = it }
        val show = remainingCount > 0
        if (!show) {
            hideTipsBarBadge(group, badge)
            return
        }
        val firstRow = (recycler as ViewGroup).getChildAt(0)
        if (firstRow == null) {
            // 退出聊天时微信会先 setAdapter(null)，此前排队的 pre-draw 仍可能到这里；
            // dataList 尚未清空但 RecyclerView 已无 child，是合法的生命周期瞬时状态。
            hideTipsBarBadge(group, badge)
            return
        }
        val row = firstRow as ViewGroup
        val previousRow = tipsBarBadgeRows[group]
        if (previousRow !== row) {
            previousRow?.overlay?.remove(badge)
            row.overlay.add(badge)
            tipsBarBadgeRows[group] = row
        }
        val text = row.allViews
            .filterIsInstance<TextView>()
            .maxBy { it.width }
        tipsBarBadgeTexts[group]?.takeIf { it !== text }?.let(::restoreTipsBadgeTextPadding)
        tipsBarBadgeTexts[group] = text
        val original = tipsBarBadgeTextPaddings.getOrPut(text) {
            intArrayOf(text.paddingStart, text.paddingTop, text.paddingEnd, text.paddingBottom)
        }
        val reservedEnd = original[2] + (56 * group.resources.displayMetrics.density).toInt()
        if (text.paddingStart != original[0] || text.paddingTop != original[1] ||
            text.paddingEnd != reservedEnd || text.paddingBottom != original[3]
        ) {
            text.setPaddingRelative(original[0], original[1], reservedEnd, original[3])
        }
        badge.text = "+$remainingCount"
        val color = text.currentTextColor
        badge.setTextColor(color and 0x00FFFFFF or (0x99 shl 24))
        badge.visibility = View.VISIBLE
        badge.measure(
            View.MeasureSpec.makeMeasureSpec(row.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(row.height, View.MeasureSpec.AT_MOST),
        )
        val marginEnd = (16 * group.resources.displayMetrics.density).toInt()
        val left = row.width - marginEnd - badge.measuredWidth
        val top = (row.height - badge.measuredHeight) / 2
        badge.layout(left, top, left + badge.measuredWidth, top + badge.measuredHeight)
    }

    private fun hideTipsBarBadge(group: View, badge: TextView) {
        badge.visibility = View.GONE
        tipsBarBadgeRows.remove(group)?.overlay?.remove(badge)
        tipsBarBadgeTexts.remove(group)?.let(::restoreTipsBadgeTextPadding)
    }

    private fun restoreTipsBadgeTextPadding(text: TextView) {
        tipsBarBadgeTextPaddings.remove(text)?.let { padding ->
            text.setPaddingRelative(padding[0], padding[1], padding[2], padding[3])
        }
    }

    /** 用 ViewOverlay 给任意根布局的 Tips 行补一条底部分割线。 */
    private fun ensureTipsRowDivider(row: View): View {
        val rowGroup = row as ViewGroup
        val divider = View(row.context).apply {
            visibility = View.GONE
        }
        rowGroup.overlay.add(divider)
        tipsBarRowDividers[row] = divider
        return divider
    }

    /** 置顶消息行的结构特征: s4.xml 的根 FrameLayout + 横向 LinearLayout + 带 weight 的
     * TextView。其他提示条 (直播等) 的行根是 RelativeLayout 之类, 不会被误伤。 */
    private fun View.isPinnedTipsRow(): Boolean {
        if (javaClass.name != "android.widget.FrameLayout") return false
        val line = (this as FrameLayout).getChildAt(0) as? LinearLayout ?: return false
        if (line.orientation != LinearLayout.HORIZONTAL) return false
        return line.allViews.any { view ->
            view is TextView &&
                (view.layoutParams as? LinearLayout.LayoutParams)?.weight?.let { it > 0f } == true
        }
    }

    /** 提示条组的卡片体: 组内唯一的 RelativeLayout (s3.xml 的 hyi, 挂在 s3 根 FrameLayout
     * 下, 不是组的直接子 View, 必须整树找)。 */
    private fun tipsBarCardBody(group: View): View? {
        tipsBarCardBodies[group]?.takeIf { it.parent != null }?.let { return it }
        val found = group.findViewWhich { it is RelativeLayout }
        if (found != null) tipsBarCardBodies[group] = found
        return found
    }

    /** 多条样式的重叠矩形: 卡片体里带负 topMargin 的子 View (s3.xml 的 ovt)。 */
    private fun tipsBarOverlapRect(group: View): View? {
        tipsBarOverlapRects[group]?.takeIf { it.parent != null }?.let { return it }
        val body = tipsBarCardBody(group) ?: return null
        val found = body.allViews.firstOrNull { view ->
            view.parent === body &&
                (view.layoutParams as? ViewGroup.MarginLayoutParams)
                    ?.topMargin?.let { it < 0 } == true
        }
        if (found != null) tipsBarOverlapRects[group] = found
        return found
    }

    /** 原生分割线: 卡片体里 1px 高的子 View (s3.xml 的 ovu)。 */
    private fun tipsBarDivider(group: View): View? {
        tipsBarDividers[group]?.takeIf { it.parent != null }?.let { return it }
        val body = tipsBarCardBody(group) ?: return null
        val found = body.allViews.firstOrNull { view ->
            view.parent === body && view.layoutParams?.height == 1
        }
        if (found != null) tipsBarDividers[group] = found
        return found
    }

    /** 折叠 handle: 卡片体里唯一的 FrameLayout 子 View (s3.xml 的 b1n), 可见即展开态。 */
    private fun tipsBarHandle(group: View): View? {
        tipsBarHandles[group]?.takeIf { it.parent != null }?.let { return it }
        val body = tipsBarCardBody(group) ?: return null
        val found = body.allViews.firstOrNull { view ->
            view.parent === body && view is FrameLayout
        }
        if (found != null) tipsBarHandles[group] = found
        return found
    }

    /** 分割线颜色取原生 ovu 的背景, 深浅色主题自动跟随。 */
    private fun tipsBarDividerColor(group: View): Int {
        tipsBarDividerColors[group]?.let { return it }
        val color = tipsBarDivider(group)?.background
            ?.let { (it as? ColorDrawable)?.color } ?: 0
        if (color != 0) {
            tipsBarDividerColors[group] = color
            return color
        }
        // 兜底: 用任一行内文本的前景色压 10% 透明度, 深浅色都能用
        val text = group.allViews.firstOrNull { it is TextView && it.isAttachedToWindow }
            as? TextView ?: return 0
        return text.currentTextColor and 0x00FFFFFF or (0x1A shl 24)
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

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var parent = parent
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    /** 独立 ChattingUI 的 Fragment 不使用布局内标题栏, 必须走窗口级 ActionBarContainer。 */
    private fun View.isInStandaloneChattingUi(): Boolean {
        val activity = context.activityOrNull() ?: return false
        return CHATTING_UI_ACTIVITY_CLASS.toClass().isInstance(activity)
    }

    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }

    private fun applyConvBoxEdgeToEdge(activity: Activity) {
        val window = activity.window
        val decor = window.decorView
        val contentParent = activity.findViewById<ViewGroup>(android.R.id.content)
        val contentRoot = contentParent.getChildAt(0)

        // FullScreenHelper 或聊天容器重包后都可能重新派发 legacy insets。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        edgeToEdgeApplied[window] = true
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        zeroChatLayoutTopPadding(contentRoot)
        settleConvBoxLayout(activity, contentRoot)
        decor.requestApplyInsets()
    }

    /** ConvBox 的标题栏和列表位于 ActionBarOverlayLayout 的两个容器分支。 */
    private fun settleConvBoxLayout(activity: Activity, contentRoot: View) {
        val decor = activity.window.decorView
        val conversationHost = (contentRoot as ViewGroup).getChildAt(0)
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!isActive || !contentRoot.isAttachedToWindow) {
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    return true
                }
                val titleBar = decor.findViewWhich {
                    it.javaClass.name == ACTION_BAR_CONTAINER_CLASS
                } ?: return true
                if (titleBar.height <= 0 || contentRoot.height <= 0) return true

                // 保留 AppCompat 对标题栏的正常 inset 处理，只把 jmc 移到最终下沿。
                val titleLocation = IntArray(2)
                val rootLocation = IntArray(2)
                titleBar.getLocationOnScreen(titleLocation)
                contentRoot.getLocationOnScreen(rootLocation)
                val targetTop =
                    (titleLocation[1] + titleBar.height - rootLocation[1]).coerceAtLeast(0)
                val conversationParams = conversationHost.layoutParams as FrameLayout.LayoutParams
                if (conversationParams.topMargin != targetTop) {
                    conversationParams.topMargin = targetTop
                    conversationHost.layoutParams = conversationParams
                    return false
                }

                decor.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        }
        decor.viewTreeObserver.addOnPreDrawListener(listener)
    }

    // ---- 状态栏 edge-to-edge ----

    /** 当前会话页需要补偿给悬浮标题栏与消息列表的状态栏高度。 */
    private fun statusBarOffset(layout: View): Int = statusBarOffsets[layout] ?: 0

    /**
     * 本特性启用时让聊天内容延伸到状态栏背后。窗口级开关只应用一次，顶部布局修正保持幂等。
     */
    private fun applyStatusBarEdgeToEdge(layout: View) {
        val activity = layout.context.activityOrNull() ?: return
        val window = activity.window
        if (edgeToEdgeApplied[window] != true) {
            edgeToEdgeApplied[window] = true
            // decorFits 是窗口级总开关；本特性只消费顶部 inset，未启用 Footer 时底部仍由微信保留。
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WeLogger.d(TAG, "chat status bar edge-to-edge applied")
        }
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        zeroChatLayoutTopPadding(layout)
        zeroStandaloneChatBodyTopPadding(layout)
        neutralizeStatusBarWrapper(layout)
    }

    private fun currentStatusBarOffset(layout: View): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return layout.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    }

    private fun zeroChatLayoutTopPadding(layout: View) {
        if (layout.paddingTop != 0) {
            layout.setPadding(layout.paddingLeft, 0, layout.paddingRight, layout.paddingBottom)
        }
    }

    /** 每帧刷新状态栏偏移，并兜底微信对状态栏颜色和顶部包装 padding 的重设。 */
    private fun trackStatusBarOffset(layout: View) {
        if (statusBarPreDraws[layout] != null) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            val offset = currentStatusBarOffset(layout)
            val previous = statusBarOffsets.put(layout, offset)
            reassertEdgeToEdgeStatusBar(layout)
            zeroStandaloneChatBodyTopPadding(layout)
            neutralizeStatusBarWrapper(layout)
            // 通知入口的独立 ChattingUI 首次 pre-draw 时 insets 可能尚未就绪；
            // 后续拿到有效状态栏高度后必须重跑完整布局，才能切换窗口标题栏 overlay，
            // 并同步标题卡、提示卡和消息列表的位置与样式。
            if (previous != null && previous != offset) {
                scheduleReconcile(layout, RECONCILE_LAYOUT)
            }
            true
        }
        statusBarPreDraws[layout] = listener
        layout.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun reassertEdgeToEdgeStatusBar(layout: View) {
        val activity = layout.context.activityOrNull() ?: return
        val window = activity.window
        if (edgeToEdgeApplied[window] != true) return
        runCatching {
            if (window.statusBarColor != Color.TRANSPARENT) {
                window.statusBarColor = Color.TRANSPARENT
            }
        }
    }

    /**
     * 独立 ChattingUI 的 FullScreenHelper 会给 Fragment body 写入一个 ActionBar 高度的
     * paddingTop。标题栏已经切成 overlay 后，这份 173px 左右的补偿会让整个消息背景仍从
     * 标题栏下方开始；清掉它后由标题卡和列表 padding 各自处理遮挡关系。
     */
    private fun zeroStandaloneChatBodyTopPadding(layout: View) {
        if (!layout.isInStandaloneChattingUi()) return
        val body = layout.parent as RelativeLayout
        if (body.paddingTop != 0) {
            body.setPadding(body.paddingLeft, 0, body.paddingRight, body.paddingBottom)
        }
    }

    /** 只处理 EdgeToEdgeWrapperLayout 的顶部，底部由 FloatingChatFooter 负责。 */
    private fun neutralizeStatusBarWrapper(layout: View) {
        val wrapper = layout.findEdgeToEdgeWrapper() ?: return
        if (statusBarWrappersNeutralized[wrapper] != true) {
            // 高版本 ChattingUI 的 wrapper 默认 ALWAYS_AVOID，ADB 显示它会把整个内容子树
            // 下移一个状态栏高度。先把策略切到 ALWAYS_HIDE，使后续 insets 回调保持 top=0。
            val strategySetter = wrapper.reflekt().firstMethod {
                name = "setStatusBarStrategy"
                parameterCount = 1
            }
            val strategyClass = strategySetter.parameterTypes.single()
            val alwaysHide = strategyClass.enumConstants!!.single {
                (it as Enum<*>).name == "ALWAYS_HIDE"
            }
            strategySetter.invoke(alwaysHide)
            runCatching {
                wrapper.javaClass.getMethod("setStatusBarColor", Int::class.javaPrimitiveType)
                    .invoke(wrapper, Color.TRANSPARENT)
            }
            statusBarWrappersNeutralized[wrapper] = true
            wrapper.requestApplyInsets()
        }
        // setter 只更新策略字段，不会同步重算已经收到的 inset；当前帧立即移除 160px。
        if (wrapper.paddingTop != 0) {
            wrapper.setPadding(wrapper.paddingLeft, 0, wrapper.paddingRight, wrapper.paddingBottom)
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
            else -> statusBarOffset(layout) + header.height + (topGapDp * density).toInt()
        }
        val target = base + extra
        if (recycler.paddingTop == target) return
        recycler.setPadding(recycler.paddingLeft, target, recycler.paddingRight, recycler.paddingBottom)
        WeLogger.d(TAG, "chat list top padding: ${recycler.paddingTop} -> $target (extra=$extra)")
    }

    private fun applyAnimatedChatListPadding(layout: View, recycler: View) {
        val overlayBottom = overlayCardBottoms[layout] ?: return
        val base = chatListBasePaddings[recycler] ?: return
        val recyclerTop = chatListTopOffsets[recycler] ?: return
        val extra = (overlayBottom - recyclerTop).coerceAtLeast(0)
        val target = base + extra
        if (recycler.paddingTop == target) return
        recycler.setPadding(recycler.paddingLeft, target, recycler.paddingRight, recycler.paddingBottom)
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
    private fun applyQuickSelectOffset(layout: View, header: View): Boolean {
        if (headerTopOffsets[layout] == null) return false
        val content = chatContent(layout) as? ViewGroup ?: return false
        val quickSelect = quickSelectUpView(content, layout) ?: return false
        val density = layout.resources.displayMetrics.density
        val gapPx = (extraGapDp * density).toInt()
        // 标题卡下沿 (ChattingUILayout 坐标系): statusBarOffset + 卡高 + 顶部间距
        val titleBottomPx = statusBarOffset(layout) +
            header.height + (topGapDp * density).toInt()
        // ChattingScrollLayout 滚动时用 translationY 移动内容区, 要一起算进按钮的屏幕位置
        val contentTopPx = content.offsetTopIn(layout) + content.translationY.roundToInt()
        val marginTop = (titleBottomPx + gapPx - contentTopPx).coerceAtLeast(0)
        val lp = quickSelect.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        if (lp.topMargin != marginTop) {
            lp.topMargin = marginTop
            quickSelect.requestLayout()
            WeLogger.d(TAG, "quick select up view top margin: ${lp.topMargin} -> $marginTop")
        }
        return true
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

    private fun trackQuickSelectInputs(tracker: LayoutTracker, layout: View) {
        val oldContent = chatContents[layout]
        val content = chatContent(layout)
        if (oldContent !== content) {
            oldContent?.let { unobserveTrackedView(tracker, it) }
            if (content == null) chatContents.remove(layout)
        }
        if (content == null) {
            quickSelectUpViews.remove(layout)?.let { unobserveTrackedView(tracker, it) }
            return
        }
        observeTrackedView(tracker, content, RECONCILE_QUICK_SELECT)

        val oldQuickSelect = quickSelectUpViews[layout]
        val quickSelect = (content as? ViewGroup)?.let { quickSelectUpView(it, layout) }
        if (oldQuickSelect !== quickSelect) {
            oldQuickSelect?.let { unobserveTrackedView(tracker, it) }
            if (quickSelect == null) quickSelectUpViews.remove(layout)
        }
        if (quickSelect != null) {
            observeTrackedView(tracker, quickSelect, RECONCILE_QUICK_SELECT)
        }
    }

    /** 结构特征: 内容区直接子 View 里 top|left 的 wrap_content 小浮层 (含未展开的 ViewStub)。 */
    @SuppressLint("RtlHardcoded")
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
        val cached = chatListRecyclers[this]
        cached?.takeIf {
            it.isAttachedToWindow && it.findAncestorChattingUILayout() === this
        }?.let {
            chatListRecyclerLayouts[it] = this
            chatListTopOffsets[it] = it.offsetTopIn(this)
            return it
        }
        val listHost = allViews.firstOrNull {
            it.javaClass.name == "com.tencent.mm.ui.chatting.view.MMChattingListView"
        }
        val found = listHost?.allViews?.firstOrNull { it.isChatRecycler() }
        if (found != null) {
            if (cached !== found) cached?.let(::retireChatListRecycler)
            chatListRecyclers[this] = found
            chatListRecyclerLayouts[found] = this
            chatListTopOffsets[found] = found.offsetTopIn(this)
        } else {
            cached?.let(::retireChatListRecycler)
            chatListRecyclers.remove(this)
            if (lookupWarned.put(this, true) == null) {
                WeLogger.w(TAG, "chat list recycler not found, top blank skipped")
            }
        }
        return found
    }

    private fun retireChatListRecycler(recycler: View) {
        chatListRecyclerLayouts.remove(recycler)
        chatListTopOffsets.remove(recycler)
        chatListBasePaddings.remove(recycler)
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

    private fun scheduleAllLayouts(flags: Int) {
        layoutTrackers.keys.toList().forEach { layout ->
            if (layoutTrackers[layout]?.active == true) scheduleReconcile(layout, flags)
        }
    }

    private fun disposeTracker(layout: View) {
        removeSeparatedHeader(layout)
        statusBarPreDraws.remove(layout)?.let { listener ->
            runCatching { layout.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        statusBarOffsets.remove(layout)
        val tracker = layoutTrackers.remove(layout) ?: return
        tracker.active = false
        tracker.oneShotPreDraw?.let { listener ->
            tracker.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        tracker.reparentRunnable?.let(layout::removeCallbacks)
        tracker.observedViews.keys.toList().forEach { unobserveTrackedView(tracker, it) }
        tracker.pendingFlags = 0
        tracker.observer = null
        tracker.oneShotPreDraw = null
        tracker.reparentRunnable = null

        val header = headerViews.remove(layout)
        val recycler = chatListRecyclers.remove(layout)
        val group = tipsBarGroups.remove(layout)
        group?.let { tipsBarGroupLayouts.remove(it) }
        headerTopOffsets.remove(layout)
        chatContents.remove(layout)
        quickSelectUpViews.remove(layout)
        contentHosts.remove(layout)
        overlayCardBottoms.remove(layout)
        windowBarHeaders.remove(layout)
        overlayDiagLogged.remove(layout)
        reparentBlocked.remove(layout)
        lookupWarned.remove(layout)
        header?.let {
            windowBarOverlays.remove(it)
            headerStyles.remove(it)
        }
        recycler?.let(::retireChatListRecycler)
        group?.let { clearTipsGroupCaches(it, tipsBarRecyclers[it]) }
    }

    private fun clearTipsGroupCaches(group: View, recycler: View?) {
        recycler?.let { retireTipsRecycler(group, it) }
        val body = tipsBarCardBodies.remove(group)
        tipsBarBadges.remove(group)?.let { hideTipsBarBadge(group, it) }
        dimWarned.remove(group)
        tipsBarDims.remove(group)
        tipsBarStyles.remove(group)
        tipsBarBodyWarned.remove(group)
        tipsBarOverlapRects.remove(group)
        tipsBarDividers.remove(group)
        tipsBarHandles.remove(group)
        tipsBarDividerColors.remove(group)
        tipsBarPlaceholders.remove(group)
        tipsBarEffectiveHeights.remove(group)
        body?.let { tipsBarBodyOutlineHeights.remove(it) }
        tipsBarCardOutlineHeights.remove(group)
        tipsBarFoldHeights.remove(group)
        animationGroupFields.clear()
        if (layoutTrackers.isNotEmpty()) cacheAnimationGroupFields()
    }

    private fun retireTipsRecycler(group: View, recycler: View) {
        if (tipsBarRecyclers[group] === recycler) tipsBarRecyclers.remove(group)
        tipsBarBadges[group]?.let { hideTipsBarBadge(group, it) }
        tipsBarGroupLayouts[group]?.let { layout ->
            layoutTrackers[layout]?.let { unobserveTrackedView(it, recycler) }
        }
        tipsBarRowHooks.remove(recycler)
        tipsBarOffsetsRemoved.remove(recycler)
        val rows = HashSet<View>()
        tipsBarRowRecyclers.entries
            .filter { it.value === recycler }
            .forEach { rows.add(it.key) }
        rows.forEach { row ->
            tipsBarRowDividers.remove(row)?.let((row as ViewGroup).overlay::remove)
            tipsBarRowOriginalLineMargins.remove(row)?.let { margins ->
                if (row.parent != null && row.isPinnedTipsRow()) {
                    val line = (row as FrameLayout).getChildAt(0) as LinearLayout
                    val lp = line.layoutParams as ViewGroup.MarginLayoutParams
                    lp.topMargin = margins[0]
                    lp.bottomMargin = margins[1]
                    line.layoutParams = lp
                }
            }
            tipsBarRowRecyclers.remove(row)
        }
    }

    override fun onDisable() {
        layoutTrackers.keys.toList().forEach(::disposeTracker)
        layoutAttachListeners.entries.toList().forEach { (layout, listener) ->
            layout.removeOnAttachStateChangeListener(listener)
        }
        tipsGroupAttachListeners.entries.toList().forEach { (group, listener) ->
            group.removeOnAttachStateChangeListener(listener)
        }
        layoutAttachListeners.clear()
        tipsGroupAttachListeners.clear()
        layoutTrackers.clear()
        headerViews.clear()
        headerTopOffsets.clear()
        chatListRecyclers.clear()
        chatListRecyclerLayouts.clear()
        chatListTopOffsets.clear()
        chatContents.clear()
        quickSelectUpViews.clear()
        contentHosts.clear()
        overlayCardBottoms.clear()
        tipsBarGroups.clear()
        tipsBarGroupLayouts.clear()
        windowBarOverlays.clear()
        windowBarHeaders.clear()
        overlayDiagLogged.clear()
        dimWarned.clear()
        tipsBarDims.clear()
        tipsBarRecyclers.clear()
        tipsBarStyles.clear()
        tipsBarCardBodies.clear()
        tipsBarBodyWarned.clear()
        tipsBarOverlapRects.clear()
        tipsBarDividers.clear()
        tipsBarHandles.clear()
        tipsBarDividerColors.clear()
        tipsBarRowHooks.clear()
        tipsBarBadges.clear()
        tipsBarBadgeRows.clear()
        tipsBarBadgeTexts.clear()
        tipsBarBadgeTextPaddings.clear()
        tipsBarRowDividers.clear()
        tipsBarRowRecyclers.clear()
        tipsBarRowOriginalLineMargins.clear()
        tipsBarPlaceholders.clear()
        tipsBarOffsetsRemoved.clear()
        tipsBarAdapterHooked.clear()
        tipsBarAdapterGroupFields.clear()
        animationGroupFields.clear()
        tipsBarEffectiveHeights.clear()
        tipsBarBodyOutlineHeights.clear()
        tipsBarCardOutlineHeights.clear()
        tipsBarFoldHeights.clear()
        chatListBasePaddings.clear()
        reparentBlocked.clear()
        lookupWarned.clear()
        headerStyles.clear()
        separatedHeaderStates.values.toList().forEach {
            disposeSeparatedHeader(it, restoreVisuals = true)
        }
        separatedHeaderStates.clear()
        separatedHeaderWarned.clear()
        statusBarPreDraws.clear()
        statusBarOffsets.clear()
        statusBarWrappersNeutralized.clear()
        edgeToEdgeApplied.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var layoutStyle by remember { mutableStateOf(headerLayoutStyle) }
            var corner by remember { mutableIntStateOf(cornerRadiusDp) }
            var side by remember { mutableIntStateOf(sideMarginDp) }
            var topGap by remember { mutableIntStateOf(topGapDp) }
            var extraGap by remember { mutableIntStateOf(extraGapDp) }
            var elevation by remember { mutableIntStateOf(elevationDp) }
            var componentGap by remember { mutableIntStateOf(componentGapDp) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_floating_header_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_floating_header_restart_hint),
                                    description = stringResource(R.string.chat_floating_header_summary),
                                )
                            }
                            item(key = "layout_style") {
                                DropDownMenuWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_floating_header_style_label),
                                    description = null,
                                    value = layoutStyle,
                                    options = listOf(
                                        DropdownOption(
                                            HeaderLayoutStyle.INTEGRATED,
                                            stringResource(R.string.chat_floating_header_style_integrated),
                                        ),
                                        DropdownOption(
                                            HeaderLayoutStyle.SEPARATED,
                                            stringResource(R.string.chat_floating_header_style_separated),
                                        ),
                                    ),
                                    onValueChange = {
                                        layoutStyle = it
                                        headerLayoutStyle = it
                                        scheduleAllLayouts(RECONCILE_LAYOUT)
                                    },
                                )
                            }
                            item(
                                key = "component_gap",
                                animatedVisibility = layoutStyle == HeaderLayoutStyle.SEPARATED,
                            ) {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_component_gap_label),
                                        value = componentGap,
                                        startInt = MIN_COMPONENT_GAP,
                                        endInt = MAX_COMPONENT_GAP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            componentGap = it
                                            componentGapDp = it
                                            scheduleAllLayouts(RECONCILE_LAYOUT)
                                        },
                                    )
                                }
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
                                            scheduleAllLayouts(RECONCILE_LAYOUT)
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
                                            scheduleAllLayouts(RECONCILE_LAYOUT)
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_top_gap_label),
                                        value = topGap,
                                        startInt = MIN_TOP_GAP,
                                        endInt = MAX_TOP_GAP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            topGap = it
                                            topGapDp = it
                                            scheduleAllLayouts(RECONCILE_LAYOUT)
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_card_gap_label),
                                        value = extraGap,
                                        startInt = MIN_EXTRA_GAP,
                                        endInt = MAX_EXTRA_GAP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            extraGap = it
                                            extraGapDp = it
                                            scheduleAllLayouts(RECONCILE_LAYOUT)
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_elevation_label),
                                        value = elevation,
                                        startInt = MIN_ELEVATION,
                                        endInt = MAX_ELEVATION,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            elevation = it
                                            elevationDp = it
                                            scheduleAllLayouts(RECONCILE_LAYOUT)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }
}
