package dev.ujhhgtg.wekit.hooks.items.chat

import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.ModuleRes
import dev.ujhhgtg.wekit.utils.reflection.asResolver

@HookItem(
    path = "聊天/修改文本消息显示",
    description = "向消息长按菜单添加菜单项, 可修改本地消息显示内容"
)
object ModifyTextMessageDisplay : SwitchHookItem(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777002,
                "修改内容",
                { ModuleRes.getDrawable(R.drawable.edit_24px)!! },
                { msgInfo -> msgInfo.type?.isText ?: false }
            ) { view, _, _ ->
                showComposeDialog(view.context) {
                    var input by remember { mutableStateOf(
                        view.asResolver()
                            .firstField {
                                type = CharSequence::class
                                superclass()
                            }.get<CharSequence>().toString()) }

                    AlertDialogContent(
                        title = { Text("修改消息显示") },
                        text = {
                            TextField(
                                value = input,
                                onValueChange = { input = it },
                                label = { Text("显示内容") })
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                view.asResolver()
                                    .firstMethod {
                                        parameters(CharSequence::class)
                                    }
                                    .invoke(input)
                                onDismiss()
                            }) {
                                Text("确定")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { onDismiss() }) {
                                Text("取消")
                            }
                        })
                }
            }
        )
    }
}
