package dev.ujhhgtg.wekit.hooks.items.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.hooks.items.contacts.DisplayGroupMemberRealName
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast

@HookItem(name = "显示消息详情", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可查看消息详情")
object DisplayMessageDetails : SwitchHookItem(),
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
                777005, "查看详情",
                ChatInfoIcon, { _ -> true })
            { view, _, msgInfo ->
                val displayItems = mutableListOf<Pair<String, String>>()
                displayItems += "类型" to msgInfo.typeCode.toString()
                displayItems += "ID" to msgInfo.id.toString()
                displayItems += "对方/群聊 ID" to msgInfo.talker
                displayItems += "真实发送者 ID" to msgInfo.sender
                displayItems += "内容" to msgInfo.content
                val realNameItem = mutableStateOf<Pair<String, String>?>(null)
                val realNameTarget = when {
                    msgInfo.isInGroupChat && !msgInfo.isSelfSender -> {
                        DisplayGroupMemberRealName.parseGroupSender(msgInfo.content)
                            ?.let { RealNameTarget("发送者实名", it, msgInfo.talker) }
                    }

                    !msgInfo.isInGroupChat &&
                            !msgInfo.isOfficialAccount -> {
                        RealNameTarget("好友实名", msgInfo.talker, "")
                    }

                    else -> null
                }
                if (realNameTarget != null) {
                    val cachedName = DisplayGroupMemberRealName.getCachedDisplayName(
                        realNameTarget.wxId
                    )
                    realNameItem.value = realNameTarget.title to when {
                        cachedName != null -> DisplayGroupMemberRealName.formatDisplayName(cachedName)
                        DisplayGroupMemberRealName.hasEnabled -> "查询中"
                        else -> "实名显示未启用"
                    }
                    if (cachedName == null && DisplayGroupMemberRealName.hasEnabled) {
                        val requested = DisplayGroupMemberRealName.requestDisplayName(
                            realNameTarget.wxId,
                            realNameTarget.chatroomId
                        ) { displayName ->
                            realNameItem.value = realNameTarget.title to DisplayGroupMemberRealName
                                .formatDisplayName(displayName)
                        }
                        if (!requested) {
                            realNameItem.value = realNameTarget.title to "查询失败"
                        }
                    }
                }

                showComposeDialog(view.context) {
                    AlertDialogContent(
                        title = { Text("消息详情") },
                        text = {
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                            ) {
                                items(displayItems + listOfNotNull(realNameItem.value)) { (key, value) ->
                                    ListItem(
                                        headlineContent = { Text(key) },
                                        supportingContent = { Text(value) },
                                        modifier = Modifier.clickable {
                                            copyToClipboard(value)
                                            showToast("已复制")
                                        })
                                }
                            }
                        },
                        confirmButton = { Button(onDismiss) { Text("关闭") } })
                }
            }
        )
    }

    private data class RealNameTarget(
        val title: String,
        val wxId: String,
        val chatroomId: String
    )
}
