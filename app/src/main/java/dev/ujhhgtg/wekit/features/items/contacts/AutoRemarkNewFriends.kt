package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch

@Feature(
    name = "添加自动备注",
    categories = ["联系人与群组"],
    description = "添加好友时自动备注"
)
object AutoRemarkNewFriends : ClickableFeature() {

    private val TAG = This.Class.simpleName

    private const val DEFAULT_TEXT_FORMAT = $$"$nickname ($time)"
    private const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd"

    private var textFormat by WePrefs.prefOption("auto_remark_text_format", DEFAULT_TEXT_FORMAT)
    private var timeFormat by WePrefs.prefOption("auto_remark_time_format", DEFAULT_TIME_FORMAT)

    override fun onEnable() {
        "com.tencent.mm.plugin.profile.ui.SayHiWithSnsPermissionUI".toClass().reflekt().firstMethod("initView").hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            val intent = activity.intent ?: return@hookBefore
            val nickname = intent.getStringExtra("Contact_Nick") ?: ""
            if (nickname.isNotEmpty()) {
                val formatText = textFormat
                val formatTime = timeFormat
                val formattedTime = formatEpoch(System.currentTimeMillis(), formatTime)

                val remark = formatText
                    .replace($$"$nickname", nickname)
                    .replace($$"$time", formattedTime)

                intent.putExtra("Contact_RemarkName", remark)
                WeLogger.i(TAG, "auto remark succeeded: $remark")
            }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var displayFormatInput by remember { mutableStateOf(TextFieldValue(textFormat)) }
            var timeFormat by remember { mutableStateOf(timeFormat) }
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
                title = { Text("添加自动备注") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = displayFormatInput,
                            onValueChange = { displayFormatInput = it },
                            label = { Text("备注格式") },
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
                                $$"$nickname",
                                $$"$time"
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
                            value = timeFormat,
                            onValueChange = { timeFormat = it },
                            label = { Text("时间格式") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        textFormat = displayFormatInput.text
                        AutoRemarkNewFriends.timeFormat = timeFormat
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
