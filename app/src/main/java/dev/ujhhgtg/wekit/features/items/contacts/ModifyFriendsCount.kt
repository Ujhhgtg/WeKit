package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    id = "修改好友数量",
    nameRes = "feature_modify_friends_count_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS],
    descriptionRes = "feature_modify_friends_count_description",
)
object ModifyFriendsCount : ClickableFeature() {

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
            var draft by remember { mutableStateOf(if (count == HIDE) 0 else count) }
            var hide by remember { mutableStateOf(count == HIDE) }
            AlertDialogContent(
                title = { Text("修改好友数量") },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("隐藏好友数量", modifier = Modifier.weight(1f))
                            Switch(checked = hide, onCheckedChange = { hide = it })
                        }
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            value = draft.toString(),
                            enabled = !hide,
                            onValueChange = { input ->
                                draft = input.filter(Char::isDigit).take(7).toIntOrNull() ?: 0
                            },
                            label = { Text("显示数量") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        count = if (hide) HIDE else draft
                        WeLogger.i(TAG, "friend count display set to ${if (hide) "hidden" else count}")
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
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
