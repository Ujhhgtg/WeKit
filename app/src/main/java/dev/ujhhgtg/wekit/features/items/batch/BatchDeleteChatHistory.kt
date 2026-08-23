package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
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

object BatchDeleteChatHistory : ClickableFeature() {

    override val technicalId = "批量删除聊天记录"
    override val nameRes = R.string.feature_batch_delete_chat_history_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_delete_chat_history_description

    private const val TAG = "BatchDeleteChatHistory"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedBatchString(R.string.batch_delete_history_select),
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.batch_select_at_least_one_conversation))
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
                title = { Text(stringResource(R.string.batch_delete_history_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.batch_delete_history_confirm,
                            wxIds.size,
                            wxIds.size,
                        ),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        deleteChatHistory(wxIds)
                    }) { Text(stringResource(R.string.action_delete)) }
                }
            )
        }
    }

    private fun deleteChatHistory(wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend(
                localizedBatchQuantity(
                    R.plurals.batch_delete_history_progress,
                    wxIds.size,
                    wxIds.size,
                ),
            )

            // Wipe the message rows first so a mid-way failure doesn't leave an empty conversation.
            val messagesDeleted = deleteMessageRows(wxIds) {
                WeLogger.e(TAG, "failed to delete messages", it)
            }

            // Delete the conversations the way WeChat's "删除该聊天" does (after confirm): remove the
            // rconversation row + sync the deletion to the server, not just hide the row. This is the
            // fix for the old behavior, which called deleteConversation (the "不显示该聊天" hide path).
            // It notifies list observers synchronously on the calling thread, so it must run on the
            // main thread (see WeConversationApi.reloadConversations).
            withContext(Dispatchers.Main) {
                wxIds.forEach { wxId -> WeConversationApi.deleteConversation(wxId) }
            }

            showToastSuspend(
                localizedBatchQuantity(
                    R.plurals.batch_delete_history_done,
                    wxIds.size,
                    wxIds.size,
                    messagesDeleted,
                ),
            )
        }
    }
}
