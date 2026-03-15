package moe.ouom.wekit.hooks.items.chat

import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.ui.WeChatMessageContextMenuApi
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.ModuleRes

@HookItem(
    path = "聊天/修改文本消息显示",
    desc = "向消息长按菜单添加菜单项, 可修改本地消息显示内容"
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
                lazy { ModuleRes.getDrawable("edit_24px") },
                { msgInfo -> msgInfo.isText }
            ) { view, _, _ ->
                showComposeDialog(view.context) {
                    var input by remember { mutableStateOf("") } // TODO: figure out how to find initial value

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