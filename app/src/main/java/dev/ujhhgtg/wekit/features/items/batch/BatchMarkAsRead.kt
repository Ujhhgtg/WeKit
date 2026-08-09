package dev.ujhhgtg.wekit.features.items.batch

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(
    id = "批量标为已读",
    nameRes = "feature_batch_mark_as_read_name",
    categoryIds = [FeatureCategoryIds.BATCH],
    descriptionRes = "feature_batch_mark_as_read_description",
)
object BatchMarkAsRead : ClickableFeature() {

    private const val TAG = "BatchMarkAsRead"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要标为已读的对话",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对话")
                        return@ContactsSelector
                    }

                    onDismiss()
                    markAsRead(selectedWxIds)
                }
            )
        }
    }

    private fun markAsRead(wxIds: Set<String>) {
        // These are local DB writes (no server CGI), so no rate-limit pacing is needed.
        CoroutineScope(Dispatchers.IO).launch {
            wxIds.forEach { wxId ->
                runCatching { WeConversationApi.markAsRead(wxId) }
                    .onFailure { WeLogger.e(TAG, "failed to mark $wxId as read", it) }
            }
            WeConversationApi.reloadConversations()
            showToastSuspend("已将 ${wxIds.size} 个对话标为已读")
        }
    }
}
