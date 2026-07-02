package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.compose.material3.Text
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(
    name = "批量删除聊天记录",
    categories = ["批量操作"],
    description = "彻底清除选中对话的聊天记录 (删除 rconversation 与 message 记录), 此操作不可逆!"
)
object BatchDeleteChatHistory : ClickableFeature() {

    private val TAG = This.Class.simpleName

    override val noSwitchWidget = true

    override fun onClick(context: Context) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要删除聊天记录的对话",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对话")
                        return@ContactsSelector
                    }

                    onDismiss()
                    confirmAndDelete(context, selectedWxIds)
                }
            )
        }
    }

    private fun confirmAndDelete(context: Context, wxIds: Set<String>) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("删除聊天记录") },
                text = { Text("确定要删除选中的 ${wxIds.size} 个对话的全部聊天记录吗? 此操作不可逆!") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        deleteChatHistory(wxIds)
                    }) { Text("删除") }
                }
            )
        }
    }

    private fun deleteChatHistory(wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在删除 ${wxIds.size} 个对话的聊天记录...")

            // Wipe the message rows first so a mid-way failure doesn't leave an empty conversation.
            val messagesDeleted = deleteMessageRows(wxIds) {
                WeLogger.e(TAG, "failed to delete messages", it)
            }

            // Remove the conversation rows via WeChat's native cache-aware delete (same path as the
            // "删除该聊天" menu item), so the list refreshes immediately without a restart. That
            // delete notifies list observers synchronously on the calling thread, so it must run on
            // the main thread (see WeConversationApi.reloadConversations).
            withContext(Dispatchers.Main) {
                wxIds.forEach { wxId -> WeConversationApi.deleteConversation(wxId) }
            }

            showToastSuspend("已删除 ${wxIds.size} 个对话的聊天记录 (共 $messagesDeleted 条消息)")
        }
    }
}
