package moe.ouom.wekit.ui.content

import android.content.Context
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.HookItemFactory

class CategorySettingsDialog(
    context: Context,
    private val categoryName: String,
) : BaseSettingsDialog(context, categoryName) {

    override fun initList() {
        val allItems = HookItemFactory.getItems()

        val targetItems = allItems.filter { item ->
            item.path.startsWith("$categoryName/")
        }

        if (targetItems.isEmpty()) return

        targetItems.forEach { item ->
            val displayName = item.path.substringAfterLast("/")
            val desc = item.description

            when (item) {
                is SwitchHookItem -> addSwitchItem(item, displayName, desc)
                is ClickableHookItem -> addClickableItem(item, displayName, desc)
            }
        }
    }

    private fun addSwitchItem(
        item: SwitchHookItem,
        title: String,
        summary: String,
    ) {
        val configKey = item.path
        val initialChecked = WePrefs.getBoolOrFalse(configKey)

        rows += SettingsRow.SwitchRow(
            rowKey = nextKey("sw_${item.path}"),
            title = title,
            summary = summary,
            configKey = configKey,
            initialChecked = initialChecked,
            onBeforeToggle = { checked ->
                val allowed = item.onBeforeToggle(checked, context)
                if (allowed) {
                    WePrefs.putBool(configKey, checked)
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
        item: ClickableHookItem,
        title: String,
        summary: String,
    ) {
        val configKey = item.path
        val initialChecked = WePrefs.getBoolOrFalse(configKey)

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
                    WePrefs.putBool(configKey, checked)
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