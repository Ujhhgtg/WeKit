package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.foundation.clickable
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Alternate_email
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.NewSendMsgItemProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.NewSendMsgReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.UserNameProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.WeProto
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

@Feature(
    name = "@所有人",
    categories = ["聊天"],
    description = "在群聊输入栏长按菜单中添加「@所有人」功能, 支持选择接收成员; 长按此项可配置发送设置"
)
object MentionMembers : SwitchFeature() {

    private var stealthMentionAll by WePrefs.prefOption("mention_members_stealth_all", false)

    private fun showSettingsDialog(context: Context) {
        showComposeDialog(context) {
            var stealthState by remember { mutableStateOf(stealthMentionAll) }
            AlertDialogContent(
                title = { Text("@所有人设置") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                stealthState = !stealthState
                                stealthMentionAll = stealthState
                            },
                            content = { Text("隐蔽@所有人") },
                            supportingContent = { Text("开启时点击直接隐蔽发送@所有人消息 (不显示成员选择弹窗且消息不附带@昵称前缀); 关闭时弹出成员选择弹窗并在消息头部附带@昵称文本") },
                            trailingContent = {
                                Switch(checked = stealthState, onCheckedChange = null)
                            }
                        )
                    }
                },
                confirmButton = { Button(onDismiss) { Text("确定") } }
            )
        }
    }

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "mention_members",
                icon = MaterialSymbols.Outlined.Alternate_email,
                label = "@所有人 (长按配置)",
                isSupported = { _, _ ->
                    WeCurrentConversationApi.value.isGroupChatWxId
                },
                onClick = { context, chatFooter ->
                    val currentConv = WeCurrentConversationApi.value
                    if (!currentConv.isGroupChatWxId) {
                        showToast("只能在群组里使用!")
                        return@ActionItem
                    }

                    if (stealthMentionAll) {
                        val content = chatFooter.lastText
                        val item = NewSendMsgItemProto(
                            toUser = UserNameProto(currentConv),
                            content = content,
                            type = 1,
                            msgSource = """<msgsource><atuserlist><![CDATA[notify@all]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>"""
                        )
                        val reqProto = NewSendMsgReqProto(
                            count = 1,
                            items = listOf(item)
                        )
                        val reqBytes = WeProto.encodeWithDefaults(reqProto)

                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/newsendmsg",
                            522,
                            0,
                            0,
                            reqBytes = reqBytes
                        ) {
                            onSuccess { _ ->
                                showToast("已发送 (自己无法看到该消息)")
                                val now = System.currentTimeMillis()
                                WeMessageApi.createSimpleMsgInfoAndInsert(
                                    10000,
                                    currentConv,
                                    "你隐蔽@了所有人",
                                    now
                                )
                                chatFooter.lastText = ""
                            }
                        }
                        return@ActionItem
                    }

                    val allMembers = WeDatabaseApi
                        .getGroupMembers(currentConv)
                        .filter { c -> c.wxId != WeApi.selfWxId }

                    if (allMembers.isEmpty()) {
                        showToast("群成员列表为空!")
                        return@ActionItem
                    }

                    showComposeDialog(context) {
                        ContactsSelector(
                            title = "@所有人",
                            contacts = allMembers,
                            initialSelectedWxIds = allMembers.map { it.wxId }.toSet(),
                            onDismiss = onDismiss,
                            onConfirm = { selectedWxIds ->
                                if (selectedWxIds.isEmpty()) {
                                    showToast("请选择至少一个好友!")
                                    return@ContactsSelector
                                }

                                onDismiss()

                                val selectedContacts = allMembers.filter { it.wxId in selectedWxIds }
                                val content = chatFooter.lastText
                                val atNicknames = selectedContacts.joinToString("") { "@${it.nickname} " }
                                val isAllSelected = selectedContacts.size == allMembers.size
                                val atWxIds = if (isAllSelected) {
                                    "notify@all"
                                } else {
                                    selectedContacts.joinToString(",") { it.wxId }
                                }

                                val item = NewSendMsgItemProto(
                                    toUser = UserNameProto(currentConv),
                                    content = atNicknames + content,
                                    type = 1,
                                    msgSource = """<msgsource><atuserlist><![CDATA[$atWxIds]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>"""
                                )
                                val reqProto = NewSendMsgReqProto(
                                    count = 1,
                                    items = listOf(item)
                                )
                                val reqBytes = WeProto.encodeWithDefaults(reqProto)

                                WePacketHelper.sendCgi(
                                    "/cgi-bin/micromsg-bin/newsendmsg",
                                    522,
                                    0,
                                    0,
                                    reqBytes = reqBytes
                                ) {
                                    onSuccess { _ ->
                                        showToast("已发送 (自己无法看到该消息)")
                                        val now = System.currentTimeMillis()
                                        WeMessageApi.createSimpleMsgInfoAndInsert(
                                            10000,
                                            currentConv,
                                            "你@了 ${selectedContacts.size} 个人",
                                            now
                                        )
                                        chatFooter.lastText = ""
                                    }
                                }
                            }
                        )
                    }
                },
                onLongClick = { context, _ ->
                    runOnUiThread {
                        showSettingsDialog(context)
                    }
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
