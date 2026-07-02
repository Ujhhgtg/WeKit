package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.compose.material3.Text
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(
    name = "批量隐藏对话",
    categories = ["批量操作"],
    description = "从对话列表移除选中的对话 (仅删除 rconversation 记录, 保留聊天记录), 重新收到消息时对话会再次出现"
)
object BatchHideConversations : ClickableFeature() {

    override val noSwitchWidget = true

    override fun onClick(context: Context) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要隐藏的对话",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对话")
                        return@ContactsSelector
                    }

                    onDismiss()
                    confirmAndHide(context, selectedWxIds)
                }
            )
        }
    }

    private fun confirmAndHide(context: Context, wxIds: Set<String>) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("隐藏对话") },
                text = { Text("确定要从对话列表移除选中的 ${wxIds.size} 个对话吗? 聊天记录将保留, 重新收到消息时对话会再次出现.") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        hideConversations(wxIds)
                    }) { Text("隐藏") }
                }
            )
        }
    }

    private fun hideConversations(wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            // WeChat's native "不显示该聊天" deletes the rconversation row through its cache-aware
            // storage wrapper and notifies list observers, so the change shows immediately. That
            // notify runs synchronously on the calling thread and mutates the list adapters, so it
            // must happen on the main thread.
            var removed = 0
            withContext(Dispatchers.Main) {
                wxIds.forEach { wxId ->
                    if (WeConversationApi.deleteConversation(wxId)) removed++
                }
            }
            showToastSuspend("已隐藏 $removed/${wxIds.size} 个对话")
        }
    }
}
