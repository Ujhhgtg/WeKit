package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.widget.ListView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.view.WindowCompat
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Modifier
import java.util.WeakHashMap

@Suppress("DEPRECATION")
@Feature(
    name = "聊天界面沉浸",
    categories = ["聊天"],
    description = "聊天界面启用 edge-to-edge: 内容延伸到状态栏背后, 消息可以滚到状态栏下方; " +
        "同时适配相关页面 (如服务消息盒子) 在沉浸模式下的布局"
)
object ImmersiveChatUi : SwitchFeature() {

    private const val TAG = "ImmersiveChatUi"

    /** 每个窗口是否已应用聊天 edge-to-edge (只应用一次, 不恢复)。 */
    private val edgeToEdgeApplied = WeakHashMap<Window, Boolean>()

    /** ConvBox 页面激活的窗口, 期间拦截微信控制器对状态栏颜色的每帧重设。 */
    private val convBoxWindows = WeakHashMap<Window, Boolean>()

    /** ChattingUILayout.fitSystemWindows 入口时、还没被微信加料前的原始导航栏 inset。 */
    private val navBarInsetsBeforeFit = WeakHashMap<View, Int>()

    /** 我们自己写状态栏颜色时置位, 避免被上面的拦截误伤。 */
    private var settingConvBoxColor = false

    /** ConvBoxServiceConversationUI 页面各自的修复状态 (标题栏/列表缓存, 避免每帧整树扫描)。 */
    private val convBoxFixStates = WeakHashMap<View, ConvBoxFixState>()

    private class ConvBoxFixState {
        var titleBar: View? = null
        var toolbar: View? = null
        var list: View? = null
        var wrapper: View? = null
        var titleBarMissingWarned = false
        var applied = false
        var finished = false
        var color = 0
        var inset = 0
        var lastTitleBottom = Int.MIN_VALUE
        var lastListTop = Int.MIN_VALUE
        var stableFrames = 0
    }

    /** 每个会话页布局当前生效的状态栏偏移, 每帧刷新, 供悬浮标题栏读取。 */
    private val statusBarOffsets = WeakHashMap<View, Int>()

    /** 每个会话页布局的状态栏偏移刷新监听。 */
    private val offsetPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    override fun onEnable() {
        // 聊天页 attach 时把所在窗口切成 edge-to-edge: 内容延伸到状态栏背后。
        // 每个窗口只应用一次, 不做恢复 —— 其他页面 (如服务消息盒子) 因此也处于
        // edge-to-edge, 由各自的针对性布局修复来适配。
        ChattingUILayout::class.reflekt().firstConstructorOrNull {
            parameters(Context::class, AttributeSet::class)
        }?.hookAfter {
            val layout = thisObject as? ChattingUILayout ?: return@hookAfter
            layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    applyChatEdgeToEdge(layout)
                    trackStatusBarOffset(layout)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
        } ?: WeLogger.w(TAG, "ChattingUILayout constructor hook target not found")

        // 运行中才打开本特性时, 已有会话的布局早已构造完, attach 监听不会再触发;
        // 下一次布局 (切会话/键盘/旋转) 到来时补挂状态栏偏移追踪。
        ChattingUILayout::class.reflekt().firstMethodOrNull {
            name = "onLayout"
            superclass()
        }?.hookAfter {
            val layout = thisObject
            if (layout !is ChattingUILayout) return@hookAfter
            if (offsetPreDraws[layout] == null) trackStatusBarOffset(layout)
        } ?: WeLogger.w(TAG, "onLayout hook target not found")

        // 聊天页启用 edge-to-edge 后, ChattingUILayout 会把状态栏 inset 吃进 paddingTop,
        // 消息列表因此仍从状态栏下方开始。这里把顶部 padding 清零, 让列表真正延伸到
        // 状态栏背后。
        //
        // 底部: 微信 8.0.72+ 还会把导航栏 inset 吃进 bottom padding, 并在布局底部画一条
        // 全宽底条 (亮色=灰, 暗色=黑) 盖住消息。这里只从 padding 里减掉导航栏那部分
        // (微信额外加的 inset 保留), 并把底条画笔调成透明。
        ChattingUILayout::class.reflekt().firstMethodOrNull { name = "fitSystemWindows" }?.let { fit ->
            fit.hookBefore {
                val layout = thisObject as? View ?: return@hookBefore
                navBarInsetsBeforeFit[layout] = (args[0] as? Rect)?.bottom ?: 0
            }
            fit.hookAfter {
                val layout = thisObject as? View ?: return@hookAfter
                zeroChatLayoutTopPadding(layout)
                val originalBottom = navBarInsetsBeforeFit.remove(layout) ?: 0
                val keep = (layout.paddingBottom - originalBottom).coerceAtLeast(0)
                if (layout.paddingBottom != keep) {
                    layout.setPadding(layout.paddingLeft, layout.paddingTop, layout.paddingRight, keep)
                }
                suppressNavBarStrip(layout)
            }
        } ?: WeLogger.w(TAG, "ChattingUILayout.fitSystemWindows hook target not found")

        // ConvBoxServiceConversationUI 的对话列表页: 祖先容器会把状态栏 inset 吃进
        // paddingTop, 把标题栏+内容一起顶下去产生空白, 见 applyConvBoxLayoutFix。
        $$"com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI$ConvBoxServiceConversationFmUI"
            .toClass().reflekt().firstMethodOrNull {
                name = "onActivityCreated"
            }?.hookAfter {
                val fragment = thisObject ?: return@hookAfter
                val activity = runCatching {
                    fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                }.getOrNull() ?: return@hookAfter
                val root = runCatching {
                    fragment.javaClass.getMethod("getView").invoke(fragment) as? View
                }.getOrNull() ?: return@hookAfter
                fixConvBoxListLayout(activity, root)
            } ?: WeLogger.w(TAG, "ConvBoxServiceConversationFmUI hook target not found")

        // ConvBox 页面激活期间, 微信控制器每帧重设状态栏颜色, 会把状态栏区域涂成
        // 和页面不一致的颜色。把状态栏保持为透明, 让页面背景直接延伸到状态栏背后。
        // 注意 Window.setStatusBarColor 是抽象方法不能 hook, 要 hook 具体实现 PhoneWindow;
        // 我们自己写颜色时通过 settingConvBoxColor 放行。
        "com.android.internal.policy.PhoneWindow".toClass().reflekt().firstMethodOrNull {
            name = "setStatusBarColor"
        }?.hookBefore {
            val window = thisObject as? Window ?: return@hookBefore
            if (convBoxWindows[window] == true && !settingConvBoxColor) {
                result = null
            }
        } ?: WeLogger.w(TAG, "PhoneWindow.setStatusBarColor hook target not found")
    }

    /** 悬浮标题栏读取当前状态栏偏移 (本特性未启用时返回 0, 悬浮标题栏退化为非沉浸布局)。 */
    fun statusBarOffset(layout: View): Int = statusBarOffsets[layout] ?: 0

    // ---- 聊天页 edge-to-edge ----

    /**
     * 聊天页进入时把所在窗口切成 edge-to-edge: 内容延伸到状态栏背后, 消息可以滚到
     * 状态栏下方。每个窗口只应用一次, 不做恢复。
     */
    private fun applyChatEdgeToEdge(layout: View) {
        val activity = layout.context.activityOrNull() ?: return
        val window = activity.window ?: return
        if (edgeToEdgeApplied[window] == true) return
        edgeToEdgeApplied[window] = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        zeroChatLayoutTopPadding(layout)
        // 运行中才开启本特性时, 会话页可能已经吃下了导航栏 padding 并画上了底条,
        // 当场把导航栏那部分 padding 去掉, 并把底条画笔调成透明; 之后的每次
        // fitSystemWindows 由上面的 hook 兜底。
        val navInset = currentNavBarInset(layout)
        val keep = (layout.paddingBottom - navInset).coerceAtLeast(0)
        if (layout.paddingBottom != keep) {
            layout.setPadding(layout.paddingLeft, layout.paddingTop, layout.paddingRight, keep)
        }
        suppressNavBarStrip(layout)
        WeLogger.d(TAG, "chat edge-to-edge applied")
    }

    private fun isChatEdgeToEdge(layout: View): Boolean {
        val activity = layout.context.activityOrNull() ?: return false
        return edgeToEdgeApplied[activity.window] == true
    }

    /** 聊天页当前应补偿的状态栏偏移: edge-to-edge 生效时消息列表从屏幕顶开始, 卡片/间距要加回 inset。 */
    private fun currentStatusBarOffset(layout: View): Int {
        if (!isChatEdgeToEdge(layout)) return 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return layout.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    }

    private fun zeroChatLayoutTopPadding(layout: View) {
        if (layout.paddingTop != 0) {
            layout.setPadding(layout.paddingLeft, 0, layout.paddingRight, layout.paddingBottom)
        }
    }

    private fun currentNavBarInset(layout: View): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return layout.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    }

    /**
     * 8.0.72+ 的 ChattingUILayout 有一个 private final Paint 字段, 专门画那条全宽底条。
     * 按类型找字段 (不碰混淆名), 把 alpha 置 0; fitSystemWindows 每次 setColor 之后
     * hookAfter 会再把它归零。老版本没有这个字段, 直接跳过。
     */
    private fun suppressNavBarStrip(layout: View) {
        val paintField = layout.javaClass.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == Paint::class.java
        } ?: return
        runCatching {
            paintField.isAccessible = true
            (paintField.get(layout) as? Paint)?.alpha = 0
        }
    }

    /** 微信会在聊天页里自己设置状态栏颜色, 会盖住背后的消息; 聊天页在台上时压回透明。 */
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

    /** 每个聊天页布局挂 pre-draw: 每帧刷新状态栏偏移, 供悬浮标题栏/列表 padding 使用。 */
    private fun trackStatusBarOffset(layout: View) {
        val old = offsetPreDraws.remove(layout)
        old?.let { listener ->
            runCatching { layout.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            statusBarOffsets[layout] = currentStatusBarOffset(layout)
            reassertEdgeToEdgeStatusBar(layout)
            true
        }
        offsetPreDraws[layout] = listener
        layout.viewTreeObserver.addOnPreDrawListener(listener)
    }

    // ---- ConvBoxServiceConversationUI 对话列表的 edge-to-edge 适配 ----

    /**
     * 根因 (bar chain dump 证实): 标题栏的祖先容器里有一个 LinearLayout, 微信把状态栏
     * inset 吃进它的 paddingTop, 把整个标题栏+内容一起顶到状态栏下方, 标题栏上方
     * 于是露出一条空白。标题栏自身高度被微信锁死 (actionBarSize), 改它的高度/padding
     * 都会被抢回去。
     *
     * 修复:
     * 1. 每帧清掉祖先链 (含标题栏自身) 的 paddingTop 并关掉 fitsSystemWindows ——
     *    标题栏自然落到 y=0, 空白区域被标题栏背景盖住;
     * 2. Toolbar 单独下移 inset, 内容保持在状态栏下方;
     * 3. 标题栏和 Toolbar 强制铺不透明背景 (同一采样色), 消除"文字悬空"与滚动透底;
     * 4. 列表 padding 按当前实际几何逐帧校准, 第一项贴齐标题内容 (Toolbar) 下沿,
     *    不加任何额外间距;
     * 5. 停掉 DrawStatusBarFrameLayout 系列自绘的状态栏色块 (fixStatusbar 机型)。
     */
    private fun fixConvBoxListLayout(activity: Activity, root: View) {
        // ConvBox 页面可能在没有先进过聊天页的情况下直接打开, 窗口还没被标记为
        // edge-to-edge —— 这里直接对它的窗口应用, 修复不依赖"先进聊天页"。
        ensureConvBoxWindowEdgeToEdge(activity)
        // 收敛后立即摘掉监听: 之后滚动路径上零写入、零 requestLayout, 不会抖动。
        val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (applyConvBoxLayoutFix(activity, root)) {
                    runCatching { root.viewTreeObserver.removeOnGlobalLayoutListener(this) }
                }
            }
        }
        val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val done = applyConvBoxLayoutFix(activity, root)
                if (done) {
                    runCatching { root.viewTreeObserver.removeOnPreDrawListener(this) }
                }
                return true
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        // 布局事件还没来之前先 post 一次, 尽早开始收敛
        root.post { applyConvBoxLayoutFix(activity, root) }
    }

    /**
     * 返回 true 表示处理完成: 视图就绪、几何连续两次观察一致、列表 padding 已应用,
     * 调用方应摘掉监听。完成之后不再有任何逐帧写入。视图暂时缺失时返回 false 继续等。
     */
    private fun applyConvBoxLayoutFix(activity: Activity, root: View): Boolean {
        ensureConvBoxWindowEdgeToEdge(activity)
        if (edgeToEdgeApplied[activity.window] != true) return false
        val state = convBoxFixStates.getOrPut(root) { ConvBoxFixState() }
        if (state.finished) return true
        var titleBar = state.titleBar?.takeIf { it.isAttachedToWindow }
        if (titleBar == null && !state.titleBarMissingWarned) {
            titleBar = activity.window.decorView.findViewWhich {
                it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
            }
            if (titleBar == null) {
                state.titleBarMissingWarned = true
                WeLogger.w(TAG, "conv box title bar not found, layout fix keeps retrying")
                return false
            }
            state.titleBar = titleBar
        }
        if (titleBar == null) return false
        var list = state.list?.takeIf { it.isAttachedToWindow }
        if (list == null) {
            list = root.findViewWhich { it is ListView }
            if (list == null) return false
            state.list = list
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (titleBar.height <= 0) return false

        val barGroup = titleBar as? ViewGroup ?: return false
        val toolbar = barGroup.findViewWhich {
            it.javaClass.name == "androidx.appcompat.widget.Toolbar"
        } ?: barGroup.getChildAt(0) ?: return false
        val inset = root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        state.toolbar = toolbar
        state.inset = inset

        // 收敛阶段几何写入 (目标值已达成时全部是 no-op, 不会触发 requestLayout):
        // 清掉吃进 inset 的 paddingTop 并关掉 fitsSystemWindows, 防止微信在后续布局里
        // 重新把标题栏顶下去。
        var ancestor: View? = titleBar
        while (ancestor != null && ancestor !== activity.window.decorView) {
            if (ancestor.paddingTop != 0) {
                ancestor.setPadding(ancestor.paddingLeft, 0, ancestor.paddingRight, ancestor.paddingBottom)
            }
            if (ancestor.fitsSystemWindows) ancestor.fitsSystemWindows = false
            ancestor = ancestor.parent as? View
        }
        if (titleBar.translationY != 0f) titleBar.translationY = 0f
        if (toolbar.translationY != inset.toFloat()) toolbar.translationY = inset.toFloat()
        barGroup.clipChildren = false
        (titleBar.parent as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
        if (titleBar.elevation != 2f) titleBar.elevation = 2f
        if (toolbar.elevation != 2f) toolbar.elevation = 2f

        // 停掉微信自己画的状态栏色块 (DrawStatusBarFrameLayout / EdgeToEdgeWrapperLayout
        // 机型; 本机链条里没有, 但 8.0.65-8.0.74 的 fixStatusbar 页面会用到)
        val wrapper = state.wrapper?.takeIf { it.isAttachedToWindow }
            ?: findStatusBarStripWrapper(activity).also { state.wrapper = it }
        if (wrapper != null && !wrapper.willNotDraw()) wrapper.setWillNotDraw(true)

        if (!state.applied) {
            // 一次性: 采样颜色、铺不透明背景、状态栏透明
            val color = sampleOpaqueBackground(titleBar)
                ?: sampleOpaqueBackground(list)
                ?: Color.WHITE
            state.color = color
            titleBar.background = color.toDrawable()
            toolbar.background = color.toDrawable()
            runCatching { activity.window.setBackgroundDrawable(color.toDrawable()) }
            if (wrapper != null) wrapper.background = color.toDrawable()
            activity.window.decorView.findViewById<View>(android.R.id.statusBarBackground)?.visibility = View.GONE

            convBoxWindows[activity.window] = true
            settingConvBoxColor = true
            try {
                activity.window.statusBarColor = Color.TRANSPARENT
            } finally {
                settingConvBoxColor = false
            }
            state.applied = true
        }

        // 收敛阶段兜底: 背景/状态栏被微信改回去时纠正 (值没变则 no-op)
        runCatching {
            val barBg = titleBar.background
            if (barBg !is ColorDrawable || barBg.color != state.color) {
                titleBar.background = state.color.toDrawable()
            }
            val toolBg = toolbar.background
            if (toolBg !is ColorDrawable || toolBg.color != state.color) {
                toolbar.background = state.color.toDrawable()
            }
            val decorBg = activity.window.decorView.background
            if (decorBg !is ColorDrawable || decorBg.color != state.color) {
                activity.window.setBackgroundDrawable(state.color.toDrawable())
            }
            if (wrapper != null) {
                if (!wrapper.willNotDraw()) wrapper.setWillNotDraw(true)
                val wrapperBg = wrapper.background
                if (wrapperBg !is ColorDrawable || wrapperBg.color != state.color) {
                    wrapper.background = state.color.toDrawable()
                }
            }
            activity.window.decorView.findViewById<View>(android.R.id.statusBarBackground)?.let {
                if (it.visibility != View.GONE) it.visibility = View.GONE
            }
            if (activity.window.statusBarColor != Color.TRANSPARENT) {
                settingConvBoxColor = true
                try {
                    activity.window.statusBarColor = Color.TRANSPARENT
                } finally {
                    settingConvBoxColor = false
                }
            }
        }

        // 列表 padding: 连续两次观察几何一致才算稳定, 稳定后才写一次 —— 不会在滚动
        // 过程中反复 setPadding 触发 requestLayout。
        val toolbarLoc = IntArray(2)
        val listLoc = IntArray(2)
        toolbar.getLocationOnScreen(toolbarLoc)
        list.getLocationOnScreen(listLoc)
        val titleBottom = toolbarLoc[1] + toolbar.height
        val listTop = listLoc[1]
        if (state.lastTitleBottom != titleBottom || state.lastListTop != listTop) {
            state.lastTitleBottom = titleBottom
            state.lastListTop = listTop
            state.stableFrames = 0
            return false
        }
        state.stableFrames++
        if (state.stableFrames < 2) {
            // 保证第二次观察一定发生: 即使没有新的布局/绘制事件, post 也会补一次
            root.post { applyConvBoxLayoutFix(activity, root) }
            return false
        }

        val needed = (titleBottom - listTop).coerceAtLeast(0)
        if (list.paddingTop != needed) {
            list.setPadding(list.paddingLeft, needed, list.paddingRight, list.paddingBottom)
            (list as? ViewGroup)?.clipToPadding = false
            WeLogger.d(
                TAG,
                "conv box list top padding: ${list.paddingTop} -> $needed (titleBottom=$titleBottom listTop=$listTop)"
            )
        }
        state.finished = true
        WeLogger.d(TAG, "conv box layout fix finished: inset=$inset color=${state.color}")
        return true
    }

    /** ConvBox 页面自己的窗口应用 edge-to-edge (可能先于任何聊天页打开, 不能依赖聊天页的 attach)。 */
    private fun ensureConvBoxWindowEdgeToEdge(activity: Activity) {
        val window = activity.window ?: return
        if (edgeToEdgeApplied[window] == true) return
        edgeToEdgeApplied[window] = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        WeLogger.d(TAG, "conv box edge-to-edge applied")
    }

    /** 定位微信自绘状态栏色块的容器 (DrawStatusBarFrameLayout 及继承它的 EdgeToEdgeWrapperLayout)。 */
    private fun findStatusBarStripWrapper(activity: Activity): View? {
        val cls = runCatching {
            "com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout".toClass(activity.classLoader)
        }.getOrNull() ?: return null
        return activity.window.decorView.findViewWhich { cls.isInstance(it) }
    }

    /** 按面积从大到小取第一个不透明背景的颜色, 画到 1x1 位图采样, 兼容任意 drawable 类型。 */
    private fun sampleOpaqueBackground(view: View): Int? {
        // 视图自身的纯色背景直接读, 颜色精确无位图误差
        (view.background as? ColorDrawable)?.let {
            if (Color.alpha(it.color) >= 0xCC) return it.color
        }
        val candidates = listOf(view) + view.allViews
            .filter { it !== view && it.background != null }
            .sortedByDescending { it.width * it.height }
        for (candidate in candidates) {
            val drawable = candidate.background ?: continue
            val color = runCatching {
                val bitmap = createBitmap(1, 1)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, 1, 1)
                drawable.draw(canvas)
                bitmap[0, 0]
            }.getOrNull() ?: continue
            if (Color.alpha(color) >= 0xCC) return color
        }
        return null
    }

    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }
}
