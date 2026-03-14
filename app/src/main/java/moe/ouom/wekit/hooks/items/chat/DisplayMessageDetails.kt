package moe.ouom.wekit.hooks.items.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeChatMessageContextMenuApi
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.common.ModuleRes
import moe.ouom.wekit.utils.common.ToastUtils

@HookItem(path = "聊天/显示消息详情", desc = "向消息长按菜单添加菜单项, 可查看消息详情")
object DisplayMessageDetails : SwitchHookItem(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onLoad() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onUnload() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777005, "查看详情",
                lazy { ModuleRes.getDrawable("chat_info_24px") }, { _ -> true })
            { view, _, msgInfo ->
                val displayItems = mutableListOf<Pair<String, String>>()
                displayItems += "类型" to msgInfo.type.toString()
                displayItems += "ID" to msgInfo.id.toString()
                displayItems += "对方/群聊 ID" to msgInfo.talker
                displayItems += "真实发送者 ID" to msgInfo.sender
                displayItems += "内容" to msgInfo.content

                showComposeDialog(view.context) {
                    AlertDialogContent(
                        title = { Text("消息详情") },
                        text = {
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                            ) {
                                items(displayItems) { (key, value) ->
                                    ListItem(
                                        headlineContent = { Text(key) },
                                        supportingContent = { Text(value) },
                                        modifier = Modifier.clickable {
                                            val clipboard =
                                                view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Value", value)
                                            clipboard.setPrimaryClip(clip)
                                            ToastUtils.showToast("已复制")
                                        })
                                }
                            }
                        })
                }
            }
        )
    }
}