package moe.ouom.wekit.hooks.items.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeChatMessageContextMenuApi
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.common.ModuleRes

@HookItem(
    path = "聊天与消息/修改消息显示",
    desc = "修改本地消息显示内容"
)
object ModifyMessageDisplayHook : BaseSwitchFunctionHookItem(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun entry(classLoader: ClassLoader) {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun unload(classLoader: ClassLoader) {
        WeChatMessageContextMenuApi.removeProvider(this)
        super.unload(classLoader)
    }

    override fun getMenuItems(
        hookParam: XC_MethodHook.MethodHookParam,
        msgInfoBean: Any
    ): List<WeChatMessageContextMenuApi.MenuItem> {
        val type = msgInfoBean.asResolver()
            .firstField {
                name = "field_type"
                superclass()
            }
            .get() as Int

        if (!MsgType.isText(type)) {
            return emptyList()
        }

        return listOf(
            WeChatMessageContextMenuApi.MenuItem(777002, "修改内容", ModuleRes.getDrawable("edit_24px")) { view, _, _ ->
                showComposeDialog(view.context) { onDismiss ->
                    var input by remember { mutableStateOf("") } // TODO: figure out how to find initial value

                    AlertDialog(
                        onDismissRequest = onDismiss,
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