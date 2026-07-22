package dev.ujhhgtg.wekit.features.items.chat

import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

@Feature(name = "移除消息菜单项", categories = ["聊天"], description = "从消息的长按菜单中移除指定名称的菜单项")
object RemoveChatMessageContextMenuItems : ClickableFeature(), IResolveDex {

    // this is the method that builds the whole context menu (m0.a). we can't reliably hook the
    // individual menu.add(...) calls because wechat also inserts items by constructing MenuItem
    // objects directly into the backing list (during its reorder passes), bypassing add()/c()
    // entirely. so instead we hook after the menu is fully built and sweep the backing list by
    // title, which catches every item regardless of how it was added.
    private val methodCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
        }
    }

    private var removedItemNames by prefOption(
        "removed_menu_item_names",
        "收藏,总结,提醒,翻译,搜一搜,打开,相关表情,合拍,查看专辑,静音播放,听筒播放,背景播放,从当前听"
    )

    override fun onEnable() {
        methodCreateMenu.hookAfter {
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
                title = { Text("移除消息菜单项") },
                text = {
                    TextField(
                        value = removedNames,
                        onValueChange = { removedNames = it },
                        label = { Text("要移除的菜单项名称 (以逗号分割):") })
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        removedItemNames = removedNames
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}
