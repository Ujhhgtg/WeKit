package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeContactApi
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object BatchDeleteFriends : ClickableFeature() {

    override val technicalId = "批量删除好友"
    override val nameRes = R.string.feature_batch_delete_friends_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_delete_friends_description

    private const val TAG = "BatchDeleteFriends"

    override val noSwitchWidget = true

    /** Space out deletions to avoid WeChat's server-side rate limiting. */
    private const val DELETE_INTERVAL_MS = 1500L

    override fun onClick(context: ComponentActivity) {
        val friends = WeDatabaseApi.getFriends()

        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedBatchString(R.string.batch_delete_friends_select),
                contacts = friends,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.batch_select_at_least_one_friend))
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
                title = { Text(stringResource(R.string.batch_delete_friends_confirm_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.batch_delete_friends_confirm_message,
                            wxIds.size,
                            wxIds.size,
                        ),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        deleteFriends(wxIds, WeContactApi.DeleteMode.BLOCK_AND_DELETE)
                    }) { Text(stringResource(R.string.batch_delete_friends_block_and_delete)) }
                    Button(onClick = {
                        onDismiss()
                        deleteFriends(wxIds, WeContactApi.DeleteMode.DELETE_ONLY)
                    }) { Text(stringResource(R.string.action_delete)) }
                }
            )
        }
    }

    private fun deleteFriends(wxIds: Set<String>, mode: WeContactApi.DeleteMode) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend(
                localizedBatchQuantity(
                    R.plurals.batch_delete_friends_progress,
                    wxIds.size,
                    wxIds.size,
                ),
            )

            var success = 0
            wxIds.forEachIndexed { index, wxId ->
                if (WeContactApi.deleteContact(wxId, mode)) success++
                WeLogger.i(TAG, "deleted contact $wxId ($success/${wxIds.size})")
                if (index < wxIds.size - 1) delay(DELETE_INTERVAL_MS)
            }

            showToastSuspend(
                localizedBatchQuantity(
                    if (success == wxIds.size) R.plurals.batch_delete_friends_done
                    else R.plurals.batch_delete_friends_partial,
                    wxIds.size,
                    success,
                    wxIds.size,
                )
            )
        }
    }
}
