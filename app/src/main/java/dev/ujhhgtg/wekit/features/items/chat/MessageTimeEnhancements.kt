package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import java.lang.reflect.Field


@Feature(name = "消息时间增强", categories = ["聊天"], description = "显示精确消息发送时间并允许显示更多详情")
object MessageTimeEnhancements : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private lateinit var avatarField: Field

    private var timeFormat by prefOption("msg_time_pattern", "yyyy/MM/dd HH:mm:ss")
    private var textSize by prefOption("msg_time_text_size", 10)
    private var displayFormat by prefOption("msg_time_display_format", $$"$time | $type")
    private var isAlwaysCentered by prefOption("msg_time_always_centered", false)
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
                epochDay > 1 -> "$epochDay 天前"
                epochDay == 1L -> "昨天"
                else -> {
                    val diff = System.currentTimeMillis() - createTime
                    when {
                        diff <= 0 -> "刚刚"
                        else -> {
                            val mins = diff / 60000
                            val hours = diff / 3600000
                            when {
                                mins < 1 -> "刚刚"
                                hours < 1 -> "$mins 分钟前"
                                else -> "${maxOf(hours, 1L)} 小时前"
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
                msgInfo.isAnnounceAll -> "群公告"
                msgInfo.isNotifyAll -> "@所有人"
                msgInfo.isAtMe -> "@我"
                else -> "@${msgInfo.mentionedUsers.size}人"
            }
            result = result.replace($$"$mentionedUsers", atStr)
        }

        return result
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        val tag = view.tag ?: return
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val text = getFormattedText(msgInfo)

        val time = tag.reflekt()
            .firstField {
                name = "timeTV"
                superclass()
            }
            .get() as? TextView? ?: return

        val context = time.context

        time.visibility = View.VISIBLE
        time.text = text

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
            if (isAlwaysCentered) {
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

                    // Resolve avatar to check if it's currently hidden
                    if (!::avatarField.isInitialized) {
                        avatarField = tag.reflekt()
                            .firstField { name = "avatarIV"; superclass() }.self
                    }
                    val avatar = avatarField.get(tag) as View?
                    val avatarContainer = avatar?.parent as? View ?: avatar

                    if (avatarContainer != null && avatarContainer.visibility != View.VISIBLE) {
                        // If the avatar is hidden, shift the timestamp right to align under the bubble.
                        // Uses measured width if available; otherwise falls back to 52dp (40dp avatar + 12dp spacing).
                        val avatarWidthPx = if (avatarContainer.width > 0) {
                            avatarContainer.width
                        } else {
                            TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                52f,
                                context.resources.displayMetrics
                            ).toInt()
                        }
                        lp.marginStart = edgeMarginPx + avatarWidthPx
                    } else {
                        // Default edge spacing when avatar is visible
                        lp.marginStart = edgeMarginPx
                    }

                    lp.marginEnd = 0 // Clear opposing margin to prevent bugs on view recycling
                    time.gravity = Gravity.START
                }
            }

            time.layoutParams = lp
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var displayFormatInput by remember { mutableStateOf(TextFieldValue(displayFormat)) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            var textSizeInputRaw by remember { mutableStateOf(textSize.toString()) }
            var isAlwaysCenteredInput by remember { mutableStateOf(isAlwaysCentered) }
            var textColorLightInput by remember { mutableStateOf(textColorLight) }
            var textColorDarkInput by remember { mutableStateOf(textColorDark) }
            var isFocused by remember { mutableStateOf(false) }

            val insertPlaceholder = { placeholder: String ->
                val selection = displayFormatInput.selection
                val text = displayFormatInput.text
                if (isFocused) {
                    val newText = text.substring(0, selection.start) + placeholder + text.substring(selection.end)
                    val newSelection = TextRange(selection.start + placeholder.length)
                    displayFormatInput = TextFieldValue(newText, newSelection)
                } else {
                    val newText = text + placeholder
                    val newSelection = TextRange(newText.length)
                    displayFormatInput = TextFieldValue(newText, newSelection)
                }
            }

            AlertDialogContent(
                title = { Text("消息时间增强") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        TextField(
                            value = displayFormatInput,
                            onValueChange = { displayFormatInput = it },
                            label = { Text("显示格式模板") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        )

                        Text("点击插入占位符:")

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            val placeholders = listOf(
                                $$"$time",
                                $$"$relativeTime",
                                $$"$type",
                                $$"$msgId",
                                $$"$msgSvrId",
                                $$"$mentionedUsers"
                            )
                            placeholders.forEach { ph ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clickable { insertPlaceholder(ph) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = ph,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        TextField(
                            value = timeFormatInput,
                            onValueChange = { timeFormatInput = it },
                            label = { Text("时间格式") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        TextField(
                            value = textSizeInputRaw,
                            onValueChange = { textSizeInputRaw = it.filter { c -> c.isDigit() } },
                            label = { Text("字体大小") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        TextField(
                            value = textColorLightInput,
                            onValueChange = { textColorLightInput = it },
                            label = { Text("字体颜色 (亮色模式)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        TextField(
                            value = textColorDarkInput,
                            onValueChange = { textColorDarkInput = it },
                            label = { Text("字体颜色 (暗色模式)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        ListItem(
                            headlineContent = { Text("时间居中显示") },
                            supportingContent = { Text("时间是否始终居中, 不根据发送方居左居右") },
                            modifier = Modifier.clickable {
                                isAlwaysCenteredInput = !isAlwaysCentered
                            },
                            trailingContent = {
                                Switch(
                                    checked = isAlwaysCenteredInput,
                                    onCheckedChange = null
                                )
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val textSizeInput = textSizeInputRaw.toIntOrNull()
                        if (textSizeInput == null || textSizeInput <= 0) {
                            showToast(context, "数字格式不正确!")
                            return@Button
                        }

                        displayFormat = displayFormatInput.text
                        timeFormat = timeFormatInput
                        textSize = textSizeInput
                        isAlwaysCentered = isAlwaysCenteredInput // 保存配置
                        textColorLight = textColorLightInput
                        textColorDark = textColorDarkInput
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
    }
}
