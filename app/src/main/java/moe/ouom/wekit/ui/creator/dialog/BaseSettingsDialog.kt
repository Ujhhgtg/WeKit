package moe.ouom.wekit.ui.creator.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.Toolbar // 这次可以放心引用 AndroidX 了
import moe.ouom.wekit.utils.common.ModuleRes
import moe.ouom.wekit.utils.log.WeLogger

abstract class BaseSettingsDialog(
    context: Context,
    private val title: String
) : Dialog(context, getThemeId()) {

    private var isDismissing = false
    protected lateinit var rootView: View

    companion object {
        private fun getThemeId(): Int {
            return ModuleRes.getId("Theme.WeKit", "style")
        }
    }

    protected lateinit var contentContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.5f)
        }

        val layoutId = ModuleRes.getId("module_dialog_frame", "layout")
        if (layoutId == 0) return // 异常处理

        // 只加载一次，赋值给成员变量
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

        // 设置内容
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

        initList()
    }

    abstract fun initList()

    // 给子类用的辅助方法：动态添加条目时，也必须用 context 的 inflater
    protected fun inflateItem(layoutName: String, parent: ViewGroup): View? {
        val id = ModuleRes.getId(layoutName, "layout")
        if (id == 0) return null
        return layoutInflater.inflate(id, parent, false)
    }

    override fun show() {
        super.show()
        rootView.alpha = 1f
        rootView.scaleX = 1f
        rootView.scaleY = 1f
        rootView.translationY = 0f

        // 动画 rootView
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
                            super@BaseSettingsDialog.dismiss()
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