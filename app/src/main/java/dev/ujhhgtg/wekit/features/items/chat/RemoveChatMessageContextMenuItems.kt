package dev.ujhhgtg.wekit.features.items.chat

import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

object RemoveChatMessageContextMenuItems : ClickableFeature() {

    override val technicalId = "移除消息菜单项"
    override val nameRes = R.string.feature_remove_chat_message_context_menu_items_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_chat_message_context_menu_items_description

    // this is the method that builds the whole context menu (m0.a). we can't reliably hook the
    // individual menu.add(...) calls because wechat also inserts items by constructing MenuItem
    // objects directly into the backing list (during its reorder passes), bypassing add()/c()
    // entirely. so instead we hook after the menu is fully built and sweep the backing list by
    // title, which catches every item regardless of how it was added.
    private var removedItemNames by prefOption(
        "removed_menu_item_names",
        "收藏,总结,提醒,翻译,搜一搜,打开,相关表情,合拍,查看专辑,静音播放,听筒播放,背景播放,从当前听"
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.methodCreateMenu.hookAfter {
            val removedNames = removedItemNames.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (removedNames.isEmpty()) return@hookAfter

            // args[0] is the menu (db5.g4); its single List field is the backing ArrayList of items
            val list = args[0]!!.reflekt()
                .firstField { type = List::class }
                .get() as? MutableList<*> ?: return@hookAfter

            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Any?>).removeAll { item ->
                // WeKit's own injected items carry a " [K]" suffix so they never match here
                val title = (item as? MenuItem)?.title?.toString()?.trim()
                title != null && removedNames.contains(title)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var removedNames by remember { mutableStateOf(removedItemNames) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_remove_chat_message_context_menu_items_name)) },
                text = {
                    TextField(
                        value = removedNames,
                        onValueChange = { removedNames = it },
                        label = { Text(stringResource(R.string.chat_remove_menu_items_label)) })
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        removedItemNames = removedNames
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }
}
