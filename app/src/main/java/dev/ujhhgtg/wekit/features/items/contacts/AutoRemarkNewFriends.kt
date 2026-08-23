package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch

object AutoRemarkNewFriends : ClickableFeature() {

    override val technicalId = "添加自动备注"
    override val nameRes = R.string.feature_auto_remark_new_friends_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_remark_new_friends_description

    private const val TAG = "AutoRemarkNewFriends"

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

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var displayFormatInput by remember { mutableStateOf(TextFieldValue(textFormat)) }
            var timeFormat by remember { mutableStateOf(timeFormat) }
            var isFocused by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_remark_new_friends_name)) },
                text = {
                    DefaultColumn {
                        TextField(
                            value = displayFormatInput,
                            onValueChange = { displayFormatInput = it },
                            label = { Text(stringResource(R.string.contacts_auto_remark_format)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        )

                        Text(stringResource(R.string.contacts_auto_remark_insert_placeholder))

                        PlaceholderChips(
                            placeholders = listOf($$"$nickname", $$"$time"),
                            value = displayFormatInput,
                            isFieldFocused = isFocused,
                            onValueChange = { displayFormatInput = it },
                        )

                        TextField(
                            value = timeFormat,
                            onValueChange = { timeFormat = it },
                            label = { Text(stringResource(R.string.contacts_auto_remark_time_format)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        textFormat = displayFormatInput.text
                        AutoRemarkNewFriends.timeFormat = timeFormat
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                }
            )
        }
    }
}
