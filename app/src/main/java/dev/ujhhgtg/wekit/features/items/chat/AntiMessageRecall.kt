package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeXmlParserApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch

@Feature(name = "防撤回", categories = ["聊天"], description = "阻止撤回消息")
object AntiMessageRecall : ClickableFeature(), WeXmlParserApi.IAfterParseListener {

    private const val TAG = "AntiMessageRecall"

    private var recallOutgoing by prefOption("recall_outgoing", false)
    private var pattern by prefOption("recall_pattern", $$"「$sender」尝试撤回上一条消息 (已阻止)")
    private var timeFormat by prefOption("recall_time_format", "yyyy/MM/dd HH:mm:ss")

    private val NAME_REGEX = Regex("([\"「])(.*?)([」\"])")

    override fun onEnable() {
        WeXmlParserApi.addListener(this)
    }

    override fun onDisable() {
        WeXmlParserApi.removeListener(this)
    }

    private const val TYPE_KEY = $$".sysmsg.$type"

    override fun onParse(param: HookParam, result: MutableMap<String, Any?>) {
        val args = param.args
        val xmlContent = args[0] as? String ?: ""
        val rootTag = args[1] as? String ?: ""

        if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
            return
        }

        if (result[TYPE_KEY] == "revokemsg") {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT type,content,talker,createTime,lvbuffer,msgId,msgSvrId,isSend FROM message WHERE msgSvrId = ?",
                arrayOf(result[".sysmsg.revokemsg.newmsgid"] as? String? ?: return)
            )

            cursor.use { cursor ->
                if (cursor.moveToFirst()) {
                    val msgInfo = MessageInfo(WeMessageApi.convertMsgInfoInstanceFromCursor(cursor))
                    val talker = msgInfo.talker
                    val createTime = msgInfo.createTime

                    if (msgInfo.isSelfSender && !recallOutgoing) {
                        WeLogger.i(TAG, "sender is self and not recall outgoing, skipping")
                        return
                    }

                    result[TYPE_KEY] = null

                    val replaceMsg = result[".sysmsg.revokemsg.replacemsg"] as? String?
                        ?: return
                    val match = NAME_REGEX.find(replaceMsg)
                    val senderName = match?.groupValues?.get(2) ?: if (recallOutgoing) "自己" else return

                    val interceptNotice = pattern
                        .replace($$"$sender", senderName)
                        .replace($$"$sendTime", formatEpoch(createTime, timeFormat))
                        .replace($$"$recallTime", formatEpoch(System.currentTimeMillis(), timeFormat))
                        .replace($$"$content", msgInfo.humanReadableRepr)

                    WeMessageApi.createSimpleMsgInfoAndInsert(
                        MessageType.SYSTEM.code,
                        talker,
                        interceptNotice,
                        createTime + 1
                    )

                    WeLogger.i(TAG, "blocked message revoke")
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var recallOutgoingInput by remember { mutableStateOf(recallOutgoing) }
            var patternInput by remember { mutableStateOf(pattern) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            AlertDialogContent(
                title = { Text("防撤回") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { recallOutgoingInput = !recallOutgoingInput },
                            trailingContent = {
                                Switch(checked = recallOutgoingInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("是否对自己发出的消息也生效 (这个功能现在是坏的, 别用)") },
                            headlineContent = { Text("防撤回自己的消息") },
                        )

                        TextField(
                            label = { Text("提示格式") },
                            supportingText = { Text($$"可使用占位符 $sender, $sendTime, $recallTime, $content") },
                            value = patternInput,
                            onValueChange = { patternInput = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        TextField(
                            value = timeFormatInput,
                            onValueChange = { timeFormatInput = it },
                            label = { Text("时间格式") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button({
                        recallOutgoing = recallOutgoingInput
                        pattern = patternInput
                        timeFormat = timeFormatInput
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}
