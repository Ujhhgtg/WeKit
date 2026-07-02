package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Feature(
    name = "群发消息",
    categories = ["批量操作"],
    description = "编写一条文本或卡片消息, 选择多个好友或群聊后批量发送, 请求会自动间隔以规避服务器风控"
)
object MassSendMessage : ClickableFeature() {

    private val TAG = This.Class.simpleName

    override val noSwitchWidget = true

    /** Space out sends to avoid WeChat's server-side rate limiting. */
    private const val SEND_INTERVAL_MS = 800L

    private enum class SendMode(val displayName: String, val hint: String, val label: String) {
        TEXT("文本消息", "编写要群发的文本消息, 点击「选择对象」挑选发送目标", "消息内容"),
        CARD("卡片消息 (XML)", "粘贴卡片消息的 appmsg XML, 点击「选择对象」挑选发送目标", "卡片 XML")
    }

    override fun onClick(context: Context) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            MassSendMessageDialog(
                context = context,
                contacts = contacts,
                onDismiss = onDismiss
            )
        }
    }

    @Composable
    private fun MassSendMessageDialog(
        context: Context,
        contacts: List<IWeContact>,
        onDismiss: () -> Unit
    ) {
        var text by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(SendMode.TEXT) }

        AlertDialogContent(
            title = { Text("群发消息") },
            text = {
                DefaultColumn {
                    SendMode.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { mode = option }
                        ) {
                            RadioButton(
                                selected = mode == option,
                                onClick = { mode = option }
                            )
                            Text(option.displayName)
                        }
                    }
                    Text(mode.hint)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(mode.label) },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 8
                    )
                }
            },
            dismissButton = { TextButton(onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    if (text.isBlank()) {
                        showToast("请输入消息内容")
                        return@Button
                    }

                    onDismiss()
                    pickRecipientsAndSend(context, contacts, mode, text)
                }) { Text("选择对象") }
            }
        )
    }

    private fun pickRecipientsAndSend(
        context: Context,
        contacts: List<IWeContact>,
        mode: SendMode,
        text: String
    ) {
        showComposeDialog(context) {
            ContactsSelector(
                title = "选择群发对象",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对象")
                        return@ContactsSelector
                    }

                    onDismiss()
                    sendToAll(selectedWxIds, mode, text)
                }
            )
        }
    }

    private fun sendToAll(wxIds: Set<String>, mode: SendMode, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在群发到 ${wxIds.size} 个对象...")

            var success = 0
            wxIds.forEachIndexed { index, wxId ->
                val sent = runCatching {
                    when (mode) {
                        SendMode.TEXT -> WeMessageApi.sendText(wxId, text)
                        SendMode.CARD -> WeMessageApi.sendXmlAppMsg(wxId, text)
                    }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to send message to $wxId", it)
                    false
                }
                if (sent) success++
                if (index < wxIds.size - 1) delay(SEND_INTERVAL_MS.milliseconds)
            }

            showToastSuspend(
                if (success == wxIds.size) "已群发到 ${wxIds.size} 个对象"
                else "已群发到 $success/${wxIds.size} 个对象"
            )
        }
    }
}
