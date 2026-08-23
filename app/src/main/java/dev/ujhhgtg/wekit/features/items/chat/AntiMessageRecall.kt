package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeXmlParserApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch

object AntiMessageRecall : ClickableFeature(), WeXmlParserApi.IAfterParseListener {

    override val technicalId = "防撤回"
    override val nameRes = R.string.feature_anti_message_recall_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_anti_message_recall_description

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
            var patternValue by remember { mutableStateOf(TextFieldValue(pattern)) }
            var timeFormatValue by remember { mutableStateOf(timeFormat) }
            var isPatternFocused by remember { mutableStateOf(false) }

            AlertDialogContent(
                    title = { Text(stringResource(R.string.feature_anti_message_recall_name)) },
                    text = {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_anti_recall_outgoing),
                                    description = stringResource(R.string.chat_anti_recall_outgoing_description),
                                    checked = recallOutgoingInput,
                                    onCheckedChange = { recallOutgoingInput = it },
                                )
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_anti_recall_pattern),
                                ) {
                                    Column {
                                        OutlinedTextField(
                                            value = patternValue,
                                            onValueChange = { patternValue = it },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .onFocusChanged { isPatternFocused = it.isFocused },
                                        )
                                        Text(
                                            stringResource(R.string.chat_message_time_insert_placeholder),
                                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                        )
                                        PlaceholderChips(
                                            placeholders = listOf(
                                                $$"$sender",
                                                $$"$sendTime",
                                                $$"$recallTime",
                                                $$"$content",
                                            ),
                                            value = patternValue,
                                            isFieldFocused = isPatternFocused,
                                            onValueChange = { patternValue = it },
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                    }
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_anti_recall_time_format),
                                ) {
                                    OutlinedTextField(
                                        value = timeFormatValue,
                                        onValueChange = { timeFormatValue = it },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            recallOutgoing = recallOutgoingInput
                            pattern = patternValue.text
                            timeFormat = timeFormatValue
                            onDismiss()
                        }) { Text(stringResource(R.string.action_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                    },
            )
        }
    }
}
