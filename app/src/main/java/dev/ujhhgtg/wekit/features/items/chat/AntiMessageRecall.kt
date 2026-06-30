package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
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
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeXmlParserApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.strings.stripWxId

@Feature(name = "防撤回", categories = ["聊天"], description = "阻止撤回消息")
object AntiMessageRecall : ClickableFeature(), WeXmlParserApi.IAfterParseListener {

    private val TAG = This.Class.simpleName

    private var recallOutgoing by prefOption("recall_outgoing", false)
    private var pattern by prefOption("recall_pattern", $$"$sender 尝试撤回上一条消息 (已阻止)")
    private var timeFormat by prefOption("recall_time_format", "yyyy/MM/dd HH:mm:ss")

    private val NAME_REGEX = Regex("([\"「])(.*?)([」\"])")

    override fun onEnable() {
        WeXmlParserApi.addListener(this)
    }

    override fun onDisable() {
        WeXmlParserApi.removeListener(this)
    }

    override fun onParse(param: XC_MethodHook.MethodHookParam, result: MutableMap<String, Any?>) {
        val args = param.args
        val xmlContent = args[0] as? String ?: ""
        val rootTag = args[1] as? String ?: ""

        if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
            return
        }

        val typeKey = $$".sysmsg.$type"

        if (result[typeKey] == "revokemsg") {
            val talker = result[".sysmsg.revokemsg.session"] as? String?
                ?: return
            val replaceMsg = result[".sysmsg.revokemsg.replacemsg"] as? String?
                ?: return
            val msgSvrId = result[".sysmsg.revokemsg.newmsgid"] as? String?
                ?: return

            if (!replaceMsg.contains("\"") && !replaceMsg.contains("「")) {
                WeLogger.i(TAG, "outgoing message, skipping")
                return
            }

            result[typeKey] = null

            val cursor = WeDatabaseApi.rawQuery(
                "SELECT content,createTime FROM message WHERE msgSvrId = ?",
                arrayOf(msgSvrId)
            )

            cursor.use { cursor ->
                if (cursor.moveToFirst()) {
                    val createTime =
                        cursor.getLong(cursor.getColumnIndexOrThrow("createTime"))
                    val content =
                        cursor.getString(cursor.getColumnIndexOrThrow("content"))
                    val match = NAME_REGEX.find(replaceMsg)
                    val senderName = match?.groupValues?.get(2) ?: "未知"
                    val interceptNotice = pattern
                        .replace($$"$sender", senderName)
                        .replace($$"$sendTime", formatEpoch(createTime, timeFormat))
                        .replace($$"$recallTime", formatEpoch(System.currentTimeMillis(), timeFormat))
                        .format($$"$content", content.stripWxId())
                    WeMessageApi.createSimpleMsgInfoAndInsert(
                        MessageType.SYSTEM.code,
                        talker,
                        interceptNotice,
                        createTime + 1
                    )
                    WeLogger.d(TAG, "blocked message revoke")
                }
            }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var recallOutgoingInput by remember { mutableStateOf(recallOutgoing) }
            var patternInput by remember { mutableStateOf(pattern) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            AlertDialogContent(
                title = { Text("阻止消息撤回 3") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("防撤回自己的消息") },
                            supportingContent = { Text("是否对自己发出的消息也生效") },
                            trailingContent = {
                                Switch(checked = recallOutgoingInput, onCheckedChange = null)
                            },
                            modifier = Modifier.clickable { recallOutgoingInput = !recallOutgoingInput }
                        )

                        TextField(
                            label = { Text("提示格式") },
                            supportingText = { Text($$"可使用占位符 $sender, $sendTime, $recallTime, $content") },
                            value = patternInput,
                            onValueChange = { patternInput = it },
                            modifier = Modifier.fillMaxWidth())

                        TextField(
                            value = timeFormatInput,
                            onValueChange = { timeFormatInput = it },
                            label = { Text("时间格式") },
                            modifier = Modifier.fillMaxWidth())
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = { Button({
                    recallOutgoing = recallOutgoingInput
                    pattern = patternInput
                    timeFormat = timeFormatInput
                    onDismiss()
                }) { Text("确定") } })
        }
    }
}
