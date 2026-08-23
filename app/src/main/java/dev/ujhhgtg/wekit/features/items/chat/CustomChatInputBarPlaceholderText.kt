package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import java.time.LocalDate

object CustomChatInputBarPlaceholderText : ClickableFeature(), IResolveDex, WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "自定义输入框占位符文本"
    override val nameRes = R.string.feature_custom_chat_input_bar_placeholder_text_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_custom_chat_input_bar_placeholder_text_description

    private var lastDayOfMonth by prefOption("custom_pt_day", 0)
    private var totC by prefOption("custom_pt_tot_count", 0)
    private var textC by prefOption("custom_pt_text_count", 0)
    private var charC by prefOption("custom_pt_text_char_count", 0)
    private var emojiC by prefOption("custom_pt_emoji_count", 0)
    private var transferC by prefOption("custom_pt_transfer_count", 0)
    private var redPacketC by prefOption("custom_pt_red_packet_count", 0)
    private var fileC by prefOption("custom_pt_file_count", 0)
    private var text by prefOption("custom_pt_text", $$"今天总共发了 $totalCount 条消息喵")

    private val PLACEHOLDERS = listOf(
        $$"$totalCount",
        $$"$textCount",
        $$"$charCount",
        $$"$emojiCount",
        $$"$transferCount",
        $$"$redPacketCount",
        $$"$fileCount"
    )

    private val methodChatFooterCanSend by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "canSend true ! sendBtn is visible")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        val curDay = LocalDate.now().dayOfMonth
        if (lastDayOfMonth != curDay) {
            totC = 0
            textC = 0
            charC = 0
            emojiC = 0
            transferC = 0
            redPacketC = 0
            fileC = 0
            lastDayOfMonth = curDay
        }

        methodChatFooterCanSend.hookAfter {
            val canSend = args[0] as Boolean
            if (canSend) return@hookAfter

            thisObject!!.reflekt().invokeMethod(
                "setHint", text
                    .replace($$"$totalCount", totC.toString())
                    .replace($$"$textCount", textC.toString())
                    .replace($$"$charCount", charC.toString())
                    .replace($$"$emojiCount", emojiC.toString())
                    .replace($$"$transferCount", transferC.toString())
                    .replace($$"$redPacketCount", redPacketC.toString())
                    .replace($$"$fileCount", fileC.toString())
            )
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    @Suppress("DEPRECATION")
    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val isSend = values.getAsInteger("isSend")
        val type = values.getAsInteger("type")
        val msgInfo = MessageInfo.fromContentValues(values)
        if (isSend == 0) return

        if (type == MessageType.TEXT.code) {
            textC += 1
            charC += msgInfo.actualContent.length
            totC += 1
        }

        if (type == MessageType.QUOTE.code) {
            textC += 1
            charC += msgInfo.quoteMsgActualContent?.length ?: run {
                WeLogger.w("CustomChatInputBarPlaceholderText", "failed to get quote message content")
                0
            }
            totC += 1
        }

        if (type in setOf(MessageType.STICKER.code, MessageType.SO_GOU_EMOJI.code)) {
            emojiC += 1
            totC += 1
        }

        if (type == MessageType.TRANSFER.code) {
            transferC += 1
            totC += 1
        }

        if (type in setOf(MessageType.RED_PACKET.code, MessageType.SPECIAL_RED_PACKET.code)) {
            redPacketC += 1
            totC += 1
        }

        if (type == MessageType.FILE.code) {
            fileC += 1
            totC += 1
        }
    }

    override fun onClick(context: ComponentActivity) {
        showEditor(context)
    }

    private fun showEditor(context: ComponentActivity) {
        showComposeDialog(context) {
            var textInput by remember { mutableStateOf(TextFieldValue(text)) }
            var isFocused by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_custom_chat_input_bar_placeholder_text_name)) },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text(stringResource(R.string.chat_placeholder_content)) },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        )

                        Text(stringResource(R.string.chat_placeholder_insert_hint))

                        PlaceholderChips(
                            placeholders = PLACEHOLDERS,
                            value = textInput,
                            isFieldFocused = isFocused,
                            onValueChange = { textInput = it },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        text = textInput.text
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            )
        }
    }
}
