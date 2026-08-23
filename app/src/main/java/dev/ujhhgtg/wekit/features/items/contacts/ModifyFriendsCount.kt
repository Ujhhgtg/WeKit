package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

object ModifyFriendsCount : ClickableFeature() {

    override val technicalId = "修改好友数量"
    override val nameRes = R.string.feature_modify_friends_count_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_modify_friends_count_description

    private const val TAG = "ModifyFriendsCount"
    private const val HIDE = -1
    private val FRIEND_COUNT_REGEX = Regex("\\d+(?=个朋友)")

    private var count by prefOption("modify_friends_count", 10)

    override fun onEnable() {
        TextView::class.reflekt()
            .firstMethod { name = "setText"; parameterCount = 1 }.hookBefore {
                val text = args[0] as? CharSequence ?: return@hookBefore
                if (!FRIEND_COUNT_REGEX.containsMatchIn(text)) return@hookBefore
                val view = thisObject as TextView
                val activity = view.context.findActivity() ?: return@hookBefore
                if (!activity.javaClass.name.startsWith("com.tencent.mm.ui.contact")) return@hookBefore

                if (count == HIDE) {
                    view.visibility = View.GONE
                } else {
                    view.visibility = View.VISIBLE
                    args[0] = FRIEND_COUNT_REGEX.replaceFirst(text.toString(), count.toString())
                }
            }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var hide by remember { mutableStateOf(count == HIDE) }
            var displayCount by remember { mutableStateOf(if (count == HIDE) "0" else count.toString()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_modify_friends_count_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_modify_count_hide),
                                checked = hide,
                                onCheckedChange = { hide = it },
                            )
                        }
                        item {
                            BaseSupportingWidget(
                                title = stringResource(R.string.contacts_modify_count_display),
                                enabled = !hide,
                            ) {
                                OutlinedTextField(
                                    value = displayCount,
                                    onValueChange = {
                                        displayCount = it.filter(Char::isDigit).take(7)
                                    },
                                    enabled = !hide,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                        count = if (hide) HIDE else displayCount.toIntOrNull() ?: 0
                        WeLogger.i(TAG, "friend count display set to ${if (hide) "hidden" else count}")
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

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
