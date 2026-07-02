package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.compose.material3.Text
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeContactApi
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Feature(
    name = "批量删除好友",
    categories = ["批量操作"],
    description = "选择多个好友后一次性删除, 请求会自动间隔以规避服务器风控"
)
object BatchDeleteFriends : ClickableFeature() {

    private val TAG = This.Class.simpleName

    override val noSwitchWidget = true

    /** Space out deletions to avoid WeChat's server-side rate limiting. */
    private const val DELETE_INTERVAL_MS = 1500L

    override fun onClick(context: Context) {
        val friends = WeDatabaseApi.getFriends()

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要删除的好友",
                contacts = friends,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个好友")
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
                title = { Text("确认删除") },
                text = { Text("确定要删除选中的 ${wxIds.size} 位好友吗? 此操作不可逆! 可选择同时将其拉黑.") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        deleteFriends(wxIds, WeContactApi.DeleteMode.BLOCK_AND_DELETE)
                    }) { Text("拉黑并删除") }
                    Button(onClick = {
                        onDismiss()
                        deleteFriends(wxIds, WeContactApi.DeleteMode.DELETE_ONLY)
                    }) { Text("删除") }
                }
            )
        }
    }

    private fun deleteFriends(wxIds: Set<String>, mode: WeContactApi.DeleteMode) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在删除 ${wxIds.size} 位好友...")

            var success = 0
            wxIds.forEachIndexed { index, wxId ->
                if (WeContactApi.deleteContact(wxId, mode)) success++
                WeLogger.i(TAG, "deleted contact $wxId ($success/${wxIds.size})")
                if (index < wxIds.size - 1) delay(DELETE_INTERVAL_MS)
            }

            showToastSuspend(
                if (success == wxIds.size) "已删除 ${wxIds.size} 位好友"
                else "已删除 $success/${wxIds.size} 位好友"
            )
        }
    }
}
