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
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BatchHideConversations : ClickableFeature() {

    override val technicalId = "批量隐藏对话"
    override val nameRes = R.string.feature_batch_hide_conversations_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_hide_conversations_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedBatchString(R.string.batch_hide_conversations_select),
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.batch_select_at_least_one_conversation))
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
                title = { Text(stringResource(R.string.batch_hide_conversations_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.batch_hide_conversations_confirm,
                            wxIds.size,
                            wxIds.size,
                        ),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        hideConversations(wxIds)
                    }) { Text(stringResource(R.string.batch_hide_conversations_action)) }
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
                    if (WeConversationApi.hideConversation(wxId)) removed++
                }
            }
            showToastSuspend(
                localizedBatchQuantity(
                    R.plurals.batch_hide_conversations_done,
                    wxIds.size,
                    removed,
                    wxIds.size,
                ),
            )
        }
    }
}
