package moe.ouom.wekit.ui.creator.dialog

import android.content.Context
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.core.bridge.HookFactoryBridge
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem

class CategorySettingsDialogCompose(
    context: Context,
    private val categoryName: String,
) : BaseSettingsDialogCompose(context, categoryName) {

    override fun initList() {
        val allItems = HookFactoryBridge.getAllItemList()

        val targetItems = allItems.filter { item ->
            item.path.startsWith("$categoryName/")
        }

        if (targetItems.isEmpty()) return

        targetItems.forEach { item ->
            val displayName = item.path.substringAfterLast("/")
            val desc = item.desc

            when (item) {
                is BaseSwitchFunctionHookItem -> addSwitchItem(item, displayName, desc)
                is BaseClickableFunctionHookItem -> addClickableItem(item, displayName, desc)
            }
        }
    }

    private fun addSwitchItem(
        item: BaseSwitchFunctionHookItem,
        title: String,
        summary: String,
    ) {
        val configKey = "${Constants.PrekXXX}${item.path}"
        val initialChecked = WeConfig.getDefaultConfig().getBooleanOrFalse(configKey)

        rows += SettingsRow.SwitchRow(
            rowKey = nextKey("sw_${item.path}"),
            title = title,
            summary = summary,
            configKey = configKey,
            initialChecked = initialChecked,
            onBeforeToggle = { checked ->
                val allowed = item.onBeforeToggle(checked, context)
                if (allowed) {
                    WeConfig.getDefaultConfig().edit().putBoolean(configKey, checked).apply()
                    item.isEnabled = checked
                }
                allowed
            },
            bindCompletionCallback = { callback ->
                // item.setToggleCompletionCallback fires after async confirmation;
                // we read item.isEnabled as the authoritative post-toggle value.
                item.setToggleCompletionCallback {
                    callback(item.isEnabled)
                }
            },
        )
    }

    private fun addClickableItem(
        item: BaseClickableFunctionHookItem,
        title: String,
        summary: String,
    ) {
        val configKey = "${Constants.PrekClickableXXX}${item.path}"
        val initialChecked = WeConfig.getDefaultConfig().getBooleanOrFalse(configKey)

        rows += SettingsRow.ClickableRow(
            rowKey = nextKey("cl_${item.path}"),
            title = title,
            summary = summary,
            showSwitch = !item.noSwitchWidget(),
            configKey = configKey,
            initialChecked = initialChecked,
            onBeforeToggle = { checked ->
                val allowed = item.onBeforeToggle(checked, context)
                if (allowed) {
                    WeConfig.getDefaultConfig().edit().putBoolean(configKey, checked).apply()
                    item.isEnabled = checked
                }
                allowed
            },
            bindCompletionCallback = { callback ->
                item.setToggleCompletionCallback {
                    callback(item.isEnabled)
                }
            },
            onClick = { item.onClick(context) },
        )
    }
}