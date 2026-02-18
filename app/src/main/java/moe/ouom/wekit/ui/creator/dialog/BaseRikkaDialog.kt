package moe.ouom.wekit.ui.creator.dialog

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isEmpty
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.google.android.material.materialswitch.MaterialSwitch
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.utils.common.ModuleRes
import moe.ouom.wekit.utils.log.WeLogger

abstract class BaseRikkaDialog(
    context: Context,
    private val title: String
) : AppCompatDialog(context, getThemeId()) {

    private var isDismissing = false

    companion object {
        private fun getThemeId(): Int {
            val themeId = ModuleRes.getId("Theme.WeKit", "style")
            return if (themeId != 0) themeId else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        }
    }

    // 提升 rootView 为类成员变量，因为动画要作用于它
    protected lateinit var rootView: View
    protected lateinit var contentContainer: LinearLayout

    // 依赖管理：key -> 依赖它的 View 列表
    private val dependencies = mutableMapOf<String, MutableList<DependencyInfo>>()

    data class DependencyInfo(
        val view: View,
        val enableWhen: Boolean, // true: 当依赖项为 true 时启用，false: 当依赖项为 false 时启用
        val hideWhenDisabled: Boolean // true: 禁用时隐藏，false: 禁用时只是 disable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Window 基础设置
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.5f) // 遮罩浓度
        }

        val layoutId = ModuleRes.getId("module_dialog_frame", "layout")
        if (layoutId == 0) return

        // 只加载一次，赋值给成员变量 rootView
        rootView = layoutInflater.inflate(layoutId, null)

        // 设置背景
        val bgDrawableId = ModuleRes.getId("bg_dialog_surface", "drawable")
        if (bgDrawableId != 0) {
            rootView.background = ModuleRes.getDrawable("bg_dialog_surface")
        } else {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            rootView.setBackgroundColor(typedValue.data)
        }

        // 将设置好背景的 view 设置为内容
        setContentView(rootView)

        // 初始化控件
        val idAppBar = ModuleRes.getId("topAppBar", "id")
        val toolbar = rootView.findViewById<Toolbar>(idAppBar)
        toolbar.title = title
        toolbar.setNavigationIcon(ModuleRes.getId("ic_outline_arrow_back_ios_new_24", "drawable"))
        toolbar.setNavigationOnClickListener { dismiss() }

        val idSettingsFrame = ModuleRes.getId("settings", "id")
        val settingsFrame = rootView.findViewById<FrameLayout>(idSettingsFrame)

        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }

        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 100)
        }

        scrollView.addView(contentContainer)
        settingsFrame.addView(scrollView)

        initPreferences()
    }

    abstract fun initPreferences()

    // ----------------------------------------------------------------
    //  UI 构建辅助方法
    // ----------------------------------------------------------------

    /**
     * 添加一个设置分类标题
     *
     * @param title 分类标题文本
     */
    protected fun addCategory(title: String) {
        val layoutId = ModuleRes.getId("module_item_entry_category", "layout")
        if (layoutId == 0) return

        val view = layoutInflater.inflate(layoutId, contentContainer, false)
        val idTitle = ModuleRes.getId("title", "id")
        val tvTitle = view.findViewById<TextView>(idTitle)

        // 隐藏不需要的元素
        val idIcon = ModuleRes.getId("icon", "id")
        val idArrow = ModuleRes.getId("arrow", "id")
        view.findViewById<View>(idIcon)?.visibility = View.GONE
        view.findViewById<View>(idArrow)?.visibility = View.GONE

        // 样式调整
        view.minimumHeight = 0
        view.background = null

        val density = context.resources.displayMetrics.density
        val paddingHorizontal = (16 * density).toInt()
        val paddingBottom = (8 * density).toInt()
        val isFirstItem = contentContainer.isEmpty()
        val paddingTop = if (isFirstItem) (10 * density).toInt() else (24 * density).toInt()

        view.setPadding(paddingHorizontal, paddingTop, paddingHorizontal, paddingBottom)

        tvTitle.text = title
        tvTitle.textSize = 14f
        val accentColor = ModuleRes.getColor("colorAccent") ?: -16776961
        tvTitle.setTextColor(accentColor)

        contentContainer.addView(view)
    }

    /**
     * 添加开关选项 (Switch Preference)
     *
     * @param key 配置存储的 Key
     * @param title 选项显示的标题
     * @param summary 选项显示的摘要/说明
     * @param iconName 图标资源名称 (可选，默认为 null)
     * @param useFullKey 是否使用完整 Key。true: 直接使用传入的 key; false: 自动拼接 Constants.PrekXXX 前缀
     * @return 返回该选项的根 View，通常用于后续建立依赖关系 (setDependency)
     */
    protected fun addSwitchPreference(
        key: String,
        title: String,
        summary: String,
        iconName: String? = null,
        useFullKey: Boolean = false
    ): View {
        val layoutId = ModuleRes.getId("module_item_switch", "layout")
        if (layoutId == 0) return View(context)

        val view = layoutInflater.inflate(layoutId, contentContainer, false)

        val tvTitle = view.findViewById<TextView>(ModuleRes.getId("title", "id"))
        val tvSummary = view.findViewById<TextView>(ModuleRes.getId("summary", "id"))
        val switchWidget = view.findViewById<MaterialSwitch>(ModuleRes.getId("widget_switch", "id"))
        val root = view.findViewById<View>(ModuleRes.getId("item_root", "id"))

        tvTitle.text = title
        tvSummary.text = summary

        // 图标处理
        if (iconName != null) {
            val ivIcon = view.findViewById<ImageView>(ModuleRes.getId("icon", "id"))
            val drawable = ModuleRes.getDrawable(iconName)
            if (drawable != null) {
                ivIcon.setImageDrawable(drawable)
                ivIcon.visibility = View.VISIBLE
            }
        }

        // Key 处理逻辑
        val configKey = if (useFullKey) key else "${Constants.PrekXXX}$key"
        val isChecked = WeConfig.getDefaultConfig().getBooleanOrFalse(configKey)
        switchWidget.isChecked = isChecked

        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            WeConfig.getDefaultConfig().edit().putBoolean(configKey, checked).apply()
            WeLogger.d("BaseRikkaDialog: Config changed [$configKey] -> $checked")
            updateDependencies(configKey, checked)
        }

        switchWidget.setOnCheckedChangeListener(listener)
        root.setOnClickListener { switchWidget.toggle() }

        contentContainer.addView(view)

        // 初始化依赖状态
        updateDependencies(configKey, isChecked)

        return view
    }

    /**
     * 添加输入框选项 (EditText Preference)
     *
     * @param key 配置存储的 Key
     * @param title 选项标题
     * @param summary 选项摘要
     * @param defaultValue 默认值 (如果未设置过)
     * @param hint 输入框内的提示文本
     * @param inputType 输入类型 (例如 InputType.TYPE_CLASS_TEXT, InputType.TYPE_CLASS_NUMBER)
     * @param maxLength 最大输入长度，0 表示不限制
     * @param singleLine 是否强制单行输入
     * @param iconName 图标资源名称 (可选)
     * @param useFullKey 是否使用完整 Key
     * @param summaryFormatter 自定义摘要格式化函数。参数为当前值，返回显示的文本。如果为 null，则使用默认格式 "$summary: $value"
     * @return 返回该选项的根 View
     */
    protected fun addEditTextPreference(
        key: String,
        title: String,
        summary: String,
        defaultValue: String = "",
        hint: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        maxLength: Int = 0,
        singleLine: Boolean = true,
        iconName: String? = null,
        useFullKey: Boolean = false,
        summaryFormatter: ((String) -> String)? = null
    ): View {
        val configKey = if (useFullKey) key else "${Constants.PrekXXX}$key"

        val initialValue =
            WeConfig.getDefaultConfig().getString(configKey, defaultValue) ?: defaultValue

        val displaySummary = if (summaryFormatter != null) {
            summaryFormatter(initialValue)
        } else {
            if (initialValue.isEmpty()) summary else "$summary: $initialValue"
        }

        val summaryTextView = addPreference(
            title = title,
            summary = displaySummary,
            iconName = iconName,
            onClick = { anchor, summaryView ->
                val latestValue =
                    WeConfig.getDefaultConfig().getString(configKey, defaultValue) ?: defaultValue

                showInputDialog(
                    key = configKey,
                    title = title,
                    currentValue = latestValue,
                    hint = hint,
                    inputType = inputType,
                    maxLength = maxLength,
                    singleLine = singleLine,
                    summaryView = summaryView,
                    baseSummary = summary,
                    summaryFormatter = summaryFormatter
                )
            }
        )

        // 使用 findItemContainer 找到最外层的 View
        return findItemContainer(summaryTextView)
    }

    /**
     * 添加选择器选项 (List Preference)
     *
     * @param key 配置存储的 Key
     * @param title 选项标题
     * @param summary 选项摘要
     * @param options 选项映射表 (Int 值 -> 显示文本)
     * @param defaultValue 默认选中的 Int 值
     * @param iconName 图标资源名称 (可选)
     * @param useFullKey 是否使用完整 Key
     * @return 返回该选项的根 View
     */
    protected fun addSelectPreference(
        key: String,
        title: String,
        summary: String,
        options: Map<Int, String>,
        defaultValue: Int,
        iconName: String? = null,
        useFullKey: Boolean = false
    ): View {
        val configKey = if (useFullKey) key else "${Constants.PrekXXX}$key"
        val currentValue = WeConfig.getDefaultConfig().getInt(configKey, defaultValue)
        val displaySummary = options[currentValue] ?: "$summary: $currentValue"

        val summaryTextView = addPreference(
            title = title,
            summary = displaySummary,
            iconName = iconName,
            onClick = { anchor, summaryView ->
                showSelectPopup(anchor, configKey, options, summaryView)
            }
        )

        // 使用 findItemContainer 找到最外层的 View
        return findItemContainer(summaryTextView)
    }

    /**
     * 设置依赖关系，当依赖项的状态改变时，控制目标 View 的启用/禁用或显示/隐藏状态
     *
     * @param dependentView 受控制的 View (通常是 addXXXPreference 返回的 View)
     * @param dependencyKey 依赖项的配置 Key (通常是 Switch 的 key)
     * @param enableWhen 当依赖项的值为 true 时启用目标 View，否则禁用 (默认为 true)
     * @param hideWhenDisabled 当目标 View 被禁用时，是否同时将其隐藏 (GONE)
     * @param useFullKey dependencyKey 是否为完整 Key
     */
    protected fun setDependency(
        dependentView: View,
        dependencyKey: String,
        enableWhen: Boolean = true,
        hideWhenDisabled: Boolean = false,
        useFullKey: Boolean = false
    ) {
        val configKey = if (useFullKey) dependencyKey else "${Constants.PrekXXX}$dependencyKey"
        val list = dependencies.getOrPut(configKey) { mutableListOf() }

        // 注册依赖信息
        val info = DependencyInfo(dependentView, enableWhen, hideWhenDisabled)
        list.add(info)

        // 立即读取当前配置并应用状态
        // 防止 addSwitchPreference 先执行完了，导致这里错过了初始化的机会
        val currentValue = WeConfig.getDefaultConfig().getBooleanOrFalse(configKey)

        val shouldEnable = if (enableWhen) currentValue else !currentValue

        if (hideWhenDisabled) {
            dependentView.visibility = if (shouldEnable) View.VISIBLE else View.GONE
        } else {
            // 使用之前修复过的递归方法，确保点击事件也被禁用
            setViewEnabledRecursive(dependentView, shouldEnable)
        }
    }

    /**
     * [内部方法] 递归设置 View 及其子 View 的启用状态
     */
    private fun setViewEnabledRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.8f

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setViewEnabledRecursive(view.getChildAt(i), enabled)
            }
        }
    }

    /**
     * [内部方法] 向上查找，直到找到 contentContainer 的直接子 View (即整行 Item 的根布局)
     */
    private fun findItemContainer(view: View?): View {
        var current = view
        // 只要 current 的父布局不是 contentContainer，就继续往上找
        while (current?.parent != null && current.parent != contentContainer) {
            current = current.parent as View
        }
        return current ?: View(context)
    }

    /**
     * [内部方法] 更新所有依赖项的状态
     */
    private fun updateDependencies(key: String, value: Boolean) {
        dependencies[key]?.forEach { info ->
            val shouldEnable = if (info.enableWhen) value else !value

            if (info.hideWhenDisabled) {
                info.view.visibility = if (shouldEnable) View.VISIBLE else View.GONE
            } else {
                // 使用递归方法替代直接 setEnabled
                setViewEnabledRecursive(info.view, shouldEnable)
            }
        }
    }

    /**
     * [内部方法] 显示输入对话框
     *
     * @param key 配置 Key
     * @param title 对话框标题
     * @param currentValue 当前值
     * @param hint 输入提示
     * @param inputType 输入类型
     * @param maxLength 最大长度
     * @param singleLine 单行模式
     * @param summaryView 需要更新摘要的 TextView
     * @param baseSummary 基础摘要文本
     * @param summaryFormatter 摘要格式化器
     */
    private fun showInputDialog(
        key: String,
        title: String,
        currentValue: String,
        hint: String?,
        inputType: Int,
        maxLength: Int,
        singleLine: Boolean,
        summaryView: TextView?,
        baseSummary: String,
        summaryFormatter: ((String) -> String)?
    ) {
        // 修复 NPE：必须使用 Activity Context 作为 Wrapper 的基础，不能使用 Module Context
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        // 使用 MaterialDialog 的 input 扩展函数
        MaterialDialog(wrappedContext)
            .title(text = title)
            .input(
                hint = hint,
                prefill = currentValue,
                inputType = inputType,
                maxLength = if (maxLength > 0) maxLength else null,
                allowEmpty = true,
                waitForPositiveButton = true
            ) { dialog, text ->
                // 用户点击确定后的回调
                val newValue = text.toString()
                WeConfig.getDefaultConfig().edit().putString(key, newValue).apply()

                val displayText = if (summaryFormatter != null) {
                    summaryFormatter(newValue)
                } else {
                    if (newValue.isEmpty()) baseSummary else "$baseSummary: $newValue"
                }
                summaryView?.text = displayText

                WeLogger.d("BaseRikkaDialog: Config changed [$key] -> $newValue")
            }
            .negativeButton(text = "取消") { dialog ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * [内部方法] 显示下拉选择菜单 (PopupMenu)
     *
     * @param anchor 锚点 View (菜单将显示在此 View 附近)
     * @param key 配置 Key
     * @param options 选项 Map
     * @param summaryView 需要更新摘要的 TextView
     */
    private fun showSelectPopup(
        anchor: View,
        key: String,
        options: Map<Int, String>,
        summaryView: TextView?
    ) {
        val popup = PopupMenu(context, anchor)
        options.forEach { (value, displayText) ->
            popup.menu.add(displayText).setOnMenuItemClickListener {
                WeConfig.getDefaultConfig().edit().putInt(key, value).apply()
                summaryView?.text = displayText
                WeLogger.d("BaseRikkaDialog: Config changed [$key] -> $value")
                true
            }
        }
        popup.show()
    }

    /**
     * 添加普通点击项 (类似 Preference)
     * 用于打开二级菜单、显示版本信息、链接跳转等
     *
     * @param title 选项标题
     * @param summary 选项摘要 (可选)
     * @param iconName 图标资源名称 (可选)
     * @param onClick 点击回调 (View: 点击的条目, TextView: 摘要 View)
     * @return 返回 Summary TextView，用于后续动态更新文本
     */
    protected fun addPreference(
        title: String,
        summary: String? = null,
        iconName: String? = null,
        onClick: ((View, TextView?) -> Unit)? = null
    ): TextView? { // 返回 Summary TextView
        val layoutId = ModuleRes.getId("module_item", "layout")
        if (layoutId == 0) return null

        val view = layoutInflater.inflate(layoutId, contentContainer, false)

        val tvTitle = view.findViewById<TextView>(ModuleRes.getId("title", "id"))
        val tvSummary = view.findViewById<TextView>(ModuleRes.getId("summary", "id"))
        val ivIcon = view.findViewById<ImageView>(ModuleRes.getId("icon", "id"))
        val ivArrow = view.findViewById<View>(ModuleRes.getId("arrow", "id"))
        val root = view.findViewById<View>(ModuleRes.getId("item_root", "id"))

        tvTitle.text = title

        if (!summary.isNullOrEmpty()) {
            tvSummary.text = summary
            tvSummary.visibility = View.VISIBLE
        } else {
            tvSummary.visibility = if (summary == null) View.GONE else View.VISIBLE
        }

        if (iconName != null) {
            val drawable = ModuleRes.getDrawable(iconName)
            if (drawable != null) {
                ivIcon.setImageDrawable(drawable)
            } else {
                ivIcon.setImageResource(android.R.drawable.ic_menu_agenda)
            }
            ivIcon.visibility = View.VISIBLE
        } else {
            ivIcon.visibility = View.GONE
        }

        if (onClick != null) {
            root.setOnClickListener {
                // 如果当前 View (item_root) 处于禁用状态，直接 return，不执行 onClick
                if (!it.isEnabled) return@setOnClickListener

                onClick.invoke(root, tvSummary)
            }
            ivArrow?.visibility = View.VISIBLE
        } else {
            root.isClickable = false
            root.background = null
            ivArrow?.visibility = View.GONE
        }

        contentContainer.addView(view)

        // 返回 summary view
        return tvSummary
    }

    override fun show() {
        super.show()
        // 重置状态
        rootView.alpha = 1f
        rootView.scaleX = 1f
        rootView.scaleY = 1f
        rootView.translationY = 0f

        // 动画作用于 rootView
        val animId = ModuleRes.getId("sheet_enter", "anim")
        if (animId != 0) {
            try {
                val anim = AnimationUtils.loadAnimation(ModuleRes.getContext(), animId)
                rootView.startAnimation(anim)
            } catch (e: Exception) {
                WeLogger.e("Enter anim error", e)
            }
        }
    }

    override fun dismiss() {
        if (isDismissing) return
        isDismissing = true

        val animId = ModuleRes.getId("sheet_exit", "anim")
        if (animId == 0) {
            super.dismiss()
            return
        }

        try {
            // 动画作用于 rootView
            val anim = AnimationUtils.loadAnimation(ModuleRes.getContext(), animId)
            anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(a: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
                override fun onAnimationEnd(a: android.view.animation.Animation?) {
                    rootView.post {
                        try {
                            super@BaseRikkaDialog.dismiss()
                        } catch (_: Exception) {
                        }
                    }
                }
            })
            rootView.startAnimation(anim)
        } catch (e: Exception) {
            super.dismiss()
        }
    }
}