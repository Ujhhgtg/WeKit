package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.ColorPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch


/** View tags shared with [ReadReceipts] while keeping its bind state on the time view. */
internal const val READ_RECEIPTS_MESSAGE_ID_TAG = 0x7E000010
internal const val READ_RECEIPTS_BINDING_GENERATION_TAG = 0x7E000011
internal const val READ_RECEIPTS_COUNT_TAG = 0x7E000012
internal const val READ_RECEIPTS_NATIVE_TEXT_TAG = 0x7E000013

internal data class ReadReceiptCountState(val count: Int?)

object MessageTimeEnhancements : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "消息时间增强"
    override val nameRes = R.string.feature_message_time_enhancements_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_time_enhancements_description

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private var timeFormat by prefOption("msg_time_pattern", "yyyy/MM/dd HH:mm:ss")
    private var textSize by prefOption("msg_time_text_size", 11)
    private var displayFormat by prefOption("msg_time_display_format", $$"$time | $type")
    private var isAlwaysCentered by prefOption("msg_time_always_centered", false)
    private var isAlwaysVisible by prefOption("msg_time_always_visible", false)
    private var textColorLight by prefOption("msg_time_color_light", "gray")
    private var textColorDark by prefOption("msg_time_color_dark", "gray")

    private fun getFormattedText(msgInfo: MessageInfo): String {
        var result = displayFormat

        if (result.contains($$"$time")) {
            val timeStr = formatEpoch(msgInfo.createTime, timeFormat)
            result = result.replace($$"$time", timeStr)
        }

        if (result.contains($$"$relativeTime")) {
            val createTime = msgInfo.createTime
            val zoneId = java.time.ZoneId.systemDefault()
            val epochDay = java.time.LocalDate.now(zoneId).toEpochDay() -
                    java.time.Instant.ofEpochMilli(createTime).atZone(zoneId).toLocalDate().toEpochDay()
            val relTimeStr = when {
                epochDay > 1 -> localizedChatQuantity(
                    R.plurals.chat_message_time_days_ago,
                    epochDay.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    epochDay,
                )
                epochDay == 1L -> localizedChatString(R.string.chat_message_time_yesterday)
                else -> {
                    val diff = System.currentTimeMillis() - createTime
                    when {
                        diff <= 0 -> localizedChatString(R.string.chat_message_time_just_now)
                        else -> {
                            val mins = diff / 60000
                            val hours = diff / 3600000
                            when {
                                mins < 1 -> localizedChatString(R.string.chat_message_time_just_now)
                                hours < 1 -> localizedChatQuantity(
                                    R.plurals.chat_message_time_minutes_ago,
                                    mins.toInt(),
                                    mins,
                                )
                                else -> {
                                    val displayedHours = maxOf(hours, 1L)
                                    localizedChatQuantity(
                                        R.plurals.chat_message_time_hours_ago,
                                        displayedHours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        displayedHours,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            result = result.replace($$"$relativeTime", relTimeStr)
        }

        if (result.contains($$"$type")) {
//            val typeStr = "0x${msgInfo.typeCode.toString(16).uppercase(Locale.ROOT)}"
//            result = result.replace($$"$type", typeStr)
            result = result.replace($$"$type", msgInfo.typeCode.toString())
        }

        if (result.contains($$"$msgId")) {
            result = result.replace($$"$msgId", msgInfo.id.toString())
        }

        if (result.contains($$"$msgSvrId")) {
            result = result.replace($$"$msgSvrId", msgInfo.serverId.toString())
        }

        if (result.contains($$"$mentionedUsers")) {
            val atStr = when {
                msgInfo.mentionedUsers.isEmpty() -> ""
                msgInfo.isAnnounceAll -> localizedChatString(R.string.chat_message_time_group_announcement)
                msgInfo.isNotifyAll -> localizedChatString(R.string.chat_message_time_everyone)
                msgInfo.isAtMe -> localizedChatString(R.string.chat_message_time_me)
                else -> localizedChatQuantity(
                    R.plurals.chat_message_time_mentioned_people,
                    msgInfo.mentionedUsers.size,
                    msgInfo.mentionedUsers.size,
                )
            }
            result = result.replace($$"$mentionedUsers", atStr)
        }

        return result
    }

    /**
     * Re-renders the message-time view, optionally including a read-receipt count.
     *
     * Read-receipt updates use this entry point instead of reproducing the time formatting and
     * styling rules. A null count is meaningful: it clears an active template placeholder but
     * never adds a suffix to native text.
     */
    @SuppressLint("SetTextI18n")
    internal fun renderMessageTime(
        msgInfo: MessageInfo,
        time: TextView,
        forceVisible: Boolean = false,
        readReceiptCount: Int? = null,
    ) {
        val enhancementActive = isActive
        if (!enhancementActive && !forceVisible) return
        if (!forceVisible && !isAlwaysVisible && !time.isVisible) return

        val context = time.context
        val baseText = if (enhancementActive) {
            getFormattedText(msgInfo)
        } else {
            readReceiptNativeText(
                time.text?.toString().orEmpty(),
                time.getTag(READ_RECEIPTS_NATIVE_TEXT_TAG) as? String,
            )
        }
        val localizedReadText = readReceiptCount?.let {
            localizedChatString(R.string.chat_read_receipts_count, it)
        }
        time.text = renderReadReceiptText(baseText, localizedReadText, enhancementActive)
        if (forceVisible || isAlwaysVisible) {
            time.visibility = View.VISIBLE
        }

        if (!enhancementActive) return

        // Dynamic text color configuration based on system theme
        val rawColor = if (context.isDarkMode) textColorDark else textColorLight
        val parsedColor = runCatching { rawColor.toColorInt() }.getOrElse { Color.GRAY }
        time.setTextColor(parsedColor)

        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())

        // 1. Convert 12dp to pixels dynamically so it matches standard screen-edge spacing
        val edgeMarginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            12f,
            context.resources.displayMetrics
        ).toInt()

        // 2. Make the paddings above and below the time smaller (2dp)
        val verticalPaddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        time.setPadding(time.paddingLeft, verticalPaddingPx, time.paddingRight, verticalPaddingPx)

        val lp = time.layoutParams as? RelativeLayout.LayoutParams
        if (lp != null) {
            // System messages are always centered, regardless of user config or sender
            if (isAlwaysCentered || msgInfo.type?.isSystem == true) {
                lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
                lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
                lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                lp.marginStart = 0
                lp.marginEnd = 0
                time.gravity = Gravity.CENTER_HORIZONTAL
            } else {
                lp.removeRule(RelativeLayout.CENTER_HORIZONTAL)

                // 3. Conditional alignment based on who sent the message
                if (msgInfo.isSelfSender) {
                    // Align to the Right (End)
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_END)

                    lp.marginEnd = edgeMarginPx
                    lp.marginStart = 0 // Clear opposing margin to prevent bugs on view recycling

                    time.gravity = Gravity.END
                } else {
                    // Align to the Left (Start)
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_START)

                    lp.marginStart = edgeMarginPx

                    lp.marginEnd = 0
                    time.gravity = Gravity.START
                }
            }

            time.layoutParams = lp
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        val tag = view.tag ?: return
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        val time = tag.reflekt()
            .firstField {
                name = "timeTV"
                superclass()
            }
            .get() as? TextView? ?: return

        val nextGeneration = ((time.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) as? Long) ?: 0L) + 1L
        time.setTag(READ_RECEIPTS_BINDING_GENERATION_TAG, nextGeneration)

        val trackedMessageId = time.getTag(READ_RECEIPTS_MESSAGE_ID_TAG) as? Long
        val tracked = trackedMessageId == msgInfo.id
        if (!tracked) {
            time.setTag(READ_RECEIPTS_MESSAGE_ID_TAG, null)
            time.setTag(READ_RECEIPTS_COUNT_TAG, null)
            time.setTag(READ_RECEIPTS_NATIVE_TEXT_TAG, null)
        }
        val count = if (tracked) {
            (time.getTag(READ_RECEIPTS_COUNT_TAG) as? ReadReceiptCountState)?.count
        } else {
            null
        }
        renderMessageTime(msgInfo, time, forceVisible = tracked, readReceiptCount = count)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext = LocalWeKitLocalizedContext.current
            var displayFormatInput by remember { mutableStateOf(TextFieldValue(displayFormat)) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            var textSizeInputRaw by remember { mutableStateOf(textSize.toString()) }
            var isAlwaysCenteredInput by remember { mutableStateOf(isAlwaysCentered) }
            var isAlwaysVisibleInput by remember { mutableStateOf(isAlwaysVisible) }
            var textColorLightInput by remember { mutableStateOf(textColorLight) }
            var textColorDarkInput by remember { mutableStateOf(textColorDark) }
            var isFocused by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_message_time_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_message_time_display_template),
                                ) {
                                    Column {
                                        OutlinedTextField(
                                            value = displayFormatInput,
                                            onValueChange = { displayFormatInput = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .onFocusChanged { isFocused = it.isFocused }
                                        )

                                        Text(
                                            stringResource(R.string.chat_message_time_insert_placeholder),
                                            modifier = Modifier
                                                .padding(start = 16.dp, top = 8.dp)
                                        )

                                        val placeholders = listOf(
                                            $$"$time",
                                            $$"$relativeTime",
                                            $$"$type",
                                            $$"$msgId",
                                            $$"$msgSvrId",
                                            $$"$mentionedUsers",
                                            READ_RECEIPTS_PLACEHOLDER,
                                        )
                                        PlaceholderChips(
                                            placeholders = placeholders,
                                            value = displayFormatInput,
                                            isFieldFocused = isFocused,
                                            onValueChange = { displayFormatInput = it },
                                            modifier = Modifier
                                                .padding(horizontal = 16.dp),
                                        )
                                    }
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_message_time_format),
                                ) {
                                    OutlinedTextField(
                                        value = timeFormatInput,
                                        onValueChange = { timeFormatInput = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_message_time_font_size),
                                ) {
                                    OutlinedTextField(
                                        value = textSizeInputRaw,
                                        onValueChange = { textSizeInputRaw = it.filter { c -> c.isDigit() } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.chat_message_time_color_light),
                                    value = textColorLightInput,
                                    onValueChange = { textColorLightInput = it },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.chat_message_time_color_dark),
                                    value = textColorDarkInput,
                                    onValueChange = { textColorDarkInput = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_center),
                                    description = stringResource(R.string.chat_message_time_center_summary),
                                    checked = isAlwaysCenteredInput,
                                    onCheckedChange = { isAlwaysCenteredInput = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_always_show),
                                    description = stringResource(R.string.chat_message_time_always_show_summary),
                                    checked = isAlwaysVisibleInput,
                                    onCheckedChange = { isAlwaysVisibleInput = it },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val textSizeInput = textSizeInputRaw.toIntOrNull()
                        if (textSizeInput == null || textSizeInput <= 0) {
                            showToast(localizedContext.getString(R.string.chat_message_time_invalid_number))
                            return@Button
                        }

                        displayFormat = displayFormatInput.text
                        timeFormat = timeFormatInput
                        textSize = textSizeInput
                        isAlwaysCentered = isAlwaysCenteredInput
                        isAlwaysVisible = isAlwaysVisibleInput
                        textColorLight = textColorLightInput
                        textColorDark = textColorDarkInput
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
    }
}
