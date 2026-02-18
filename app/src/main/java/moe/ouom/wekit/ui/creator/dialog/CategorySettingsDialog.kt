package moe.ouom.wekit.ui.creator.dialog

import android.content.Context
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.core.bridge.HookFactoryBridge
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.utils.common.ModuleRes

class CategorySettingsDialog(
    context: Context,
    private val categoryName: String
) : BaseSettingsDialog(context, categoryName) {

    override fun initList() {
        val allItems = HookFactoryBridge.getAllItemList()

        val targetItems = allItems.filter { item ->
            item.path.startsWith("$categoryName/")
        }

        if (targetItems.isEmpty()) return

        targetItems.forEach { item ->
            val displayName = item.path.substringAfterLast("/")
            val desc = item.desc

            if (item is BaseSwitchFunctionHookItem) {
                renderSwitchItem(item, displayName, desc)
            } else if (item is BaseClickableFunctionHookItem) {
                renderClickableItem(item, displayName, desc)
            }
        }
    }

    private fun renderSwitchItem(item: BaseSwitchFunctionHookItem, title: String, summary: String) {
        val view = inflateItem("module_item_switch", contentContainer) ?: return

        val tvTitle = view.findViewById<TextView>(ModuleRes.getId("title", "id"))
        val tvSummary = view.findViewById<TextView>(ModuleRes.getId("summary", "id"))
        val switchWidget = view.findViewById<MaterialSwitch>(ModuleRes.getId("widget_switch", "id"))
        val root = view.findViewById<View>(ModuleRes.getId("item_root", "id"))

        tvTitle.text = title
        tvSummary.text = summary

        val configKey = "${Constants.PrekXXX}${item.path}"
        val isChecked = WeConfig.getDefaultConfig().getBooleanOrFalse(configKey)

        switchWidget.isChecked = isChecked

        // 使用可变引用来避免在 lambda 内部引用自身导致的初始化问题
        var listenerRef: CompoundButton.OnCheckedChangeListener? = null
        val listener = CompoundButton.OnCheckedChangeListener { buttonView, checked ->
            // 在状态改变前调用 onBeforeToggle 确认是否允许切换
            val allowToggle = item.onBeforeToggle(checked, context)

            if (!allowToggle) {
                // 不允许切换,撤回开关状态
                // 使用 post 避免在 listener 中直接修改状态导致的递归调用
                buttonView.post {
                    buttonView.setOnCheckedChangeListener(null)
                    buttonView.isChecked = !checked
                    buttonView.setOnCheckedChangeListener(listenerRef)
                }
                return@OnCheckedChangeListener
            }

            // 允许切换,保存配置并更新状态
            WeConfig.getDefaultConfig().edit().putBoolean(configKey, checked).apply()
            item.isEnabled = checked
        }
        listenerRef = listener

        // 设置切换完成回调,用于异步确认后更新UI
        item.setToggleCompletionCallback {
            // 更新UI开关状态,不触发listener
            switchWidget.post {
                switchWidget.setOnCheckedChangeListener(null)
                switchWidget.isChecked = item.isEnabled
                switchWidget.setOnCheckedChangeListener(listener)
            }
        }

        switchWidget.setOnCheckedChangeListener(listener)

        // 设置点击监听器: 点击整个条目时切换开关
        root.setOnClickListener {
            switchWidget.toggle()
        }

        contentContainer.addView(view)
    }

    private fun renderClickableItem(
        item: BaseClickableFunctionHookItem,
        title: String,
        summary: String
    ) {
        val view = inflateItem("module_item_switch", contentContainer) ?: return

        val tvTitle = view.findViewById<TextView>(ModuleRes.getId("title", "id"))
        val tvSummary = view.findViewById<TextView>(ModuleRes.getId("summary", "id"))
        val switchWidget = view.findViewById<MaterialSwitch>(ModuleRes.getId("widget_switch", "id"))
        val root = view.findViewById<View>(ModuleRes.getId("item_root", "id"))

        tvTitle.text = title
        tvSummary.text = summary

        val configKey = "${Constants.PrekClickableXXX}${item.path}"
        val isChecked = WeConfig.getDefaultConfig().getBooleanOrFalse(configKey)

        switchWidget.isChecked = isChecked

        // 使用可变引用来避免在 lambda 内部引用自身导致的初始化问题
        var listenerRef: CompoundButton.OnCheckedChangeListener? = null
        val listener = CompoundButton.OnCheckedChangeListener { buttonView, checked ->
            // 在状态改变前调用 onBeforeToggle 确认是否允许切换
            val allowToggle = item.onBeforeToggle(checked, context)

            if (!allowToggle) {
                // 不允许切换,撤回开关状态
                // 使用 post 避免在 listener 中直接修改状态导致的递归调用
                buttonView.post {
                    buttonView.setOnCheckedChangeListener(null)
                    buttonView.isChecked = !checked
                    buttonView.setOnCheckedChangeListener(listenerRef)
                }
                return@OnCheckedChangeListener
            }

            // 允许切换,保存配置并更新状态
            WeConfig.getDefaultConfig().edit().putBoolean(configKey, checked).apply()
            item.isEnabled = checked
        }
        listenerRef = listener

        // 设置切换完成回调,用于异步确认后更新UI
        item.setToggleCompletionCallback {
            // 更新UI开关状态,不触发listener
            switchWidget.post {
                switchWidget.setOnCheckedChangeListener(null)
                switchWidget.isChecked = item.isEnabled
                switchWidget.setOnCheckedChangeListener(listener)
            }
        }

        switchWidget.setOnCheckedChangeListener(listener)

        if (item.noSwitchWidget()) {
            switchWidget.visibility = View.GONE
        }

        root.setOnClickListener {
            item.onClick(context)
        }

        contentContainer.addView(view)
    }
}