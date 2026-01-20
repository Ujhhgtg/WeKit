package moe.ouom.wekit.ui.creator.dialog

import android.content.Context
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import moe.ouom.wekit.config.ConfigManager
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.hooks._base.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks._base.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks._core.factory.HookItemFactory
import moe.ouom.wekit.util.common.ModuleRes
import moe.ouom.wekit.util.log.Logger

class CategorySettingsDialog(
    context: Context,
    private val categoryName: String
) : BaseSettingsDialog(context, categoryName) {

    override fun initList() {
        val allItems = HookItemFactory.getAllItemList()

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
        val isChecked = ConfigManager.getDefaultConfig().getBooleanOrFalse(configKey)

        switchWidget.isChecked = isChecked

        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            ConfigManager.getDefaultConfig().edit().putBoolean(configKey, checked).apply()
            item.isEnabled = checked
            if (checked) item.startLoad()
        }

        switchWidget.setOnCheckedChangeListener(listener)
        root.setOnClickListener { switchWidget.toggle() }

        contentContainer.addView(view)
    }

    private fun renderClickableItem(item: BaseClickableFunctionHookItem, title: String, summary: String) {
        val view = inflateItem("module_item_switch", contentContainer) ?: return

        val tvTitle = view.findViewById<TextView>(ModuleRes.getId("title", "id"))
        val tvSummary = view.findViewById<TextView>(ModuleRes.getId("summary", "id"))
        val switchWidget = view.findViewById<MaterialSwitch>(ModuleRes.getId("widget_switch", "id"))
        val root = view.findViewById<View>(ModuleRes.getId("item_root", "id"))

        tvTitle.text = title
        tvSummary.text = summary

        val configKey = "${Constants.PrekClickableXXX}${item.path}"
        val isChecked = ConfigManager.getDefaultConfig().getBooleanOrFalse(configKey)

        switchWidget.isChecked = isChecked

        switchWidget.setOnCheckedChangeListener { _, checked ->
            ConfigManager.getDefaultConfig().edit().putBoolean(configKey, checked).apply()
            item.isEnabled = checked
            if (checked) {
                Logger.i("[CategorySettings] Loading HookItem: ${item.path}")
                item.startLoad()
            } else {
                Logger.i("[CategorySettings] Unloading HookItem: ${item.path}")
                try {
                    item.unload(context.classLoader)
                } catch (e: Throwable) {
                    Logger.e("[CategorySettings] Unload HookItem Failed", e)
                }
            }
        }

        root.setOnClickListener {
            item.onClick(context)
        }

        contentContainer.addView(view)
    }
}