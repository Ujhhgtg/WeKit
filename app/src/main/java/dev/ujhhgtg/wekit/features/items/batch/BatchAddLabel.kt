package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Feature(
    name = "批量打标签",
    categories = ["批量操作"],
    description = "为选中的多个好友追加同一个标签, 可选择已有标签或新建, 请求会自动间隔以规避服务器风控"
)
object BatchAddLabel : ClickableFeature() {

    private val TAG = This.Class.simpleName

    override val noSwitchWidget = true

    /** Space out label modifications to avoid hammering the netscene dispatcher. */
    private const val MODIFY_INTERVAL_MS = 1000L

    override fun onClick(context: Context) {
        val friends = WeDatabaseApi.getFriends()

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要打标签的好友",
                contacts = friends,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个好友")
                        return@ContactsSelector
                    }

                    onDismiss()
                    pickLabelAndApply(context, selectedWxIds)
                }
            )
        }
    }

    private fun pickLabelAndApply(context: Context, wxIds: Set<String>) {
        showComposeDialog(context) {
            LabelPickerDialog(
                onDismiss = onDismiss,
                onPick = { labelName ->
                    onDismiss()
                    applyLabel(context, wxIds, labelName)
                }
            )
        }
    }

    @Composable
    private fun LabelPickerDialog(
        onDismiss: () -> Unit,
        onPick: (String) -> Unit
    ) {
        var newLabelName by remember { mutableStateOf("") }
        var labels by remember { mutableStateOf<List<WeContactLabelApi.ContactLabel>?>(null) }

        LaunchedEffect(Unit) {
            CoroutineScope(Dispatchers.IO).launch {
                labels = WeContactLabelApi.getAllLabels()
            }
        }

        AlertDialogContent(
            title = { Text("选择标签") },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = newLabelName,
                        onValueChange = { newLabelName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("新建标签 (输入名称后点击「新建并应用」)") },
                        singleLine = true
                    )

                    val loaded = labels
                    if (loaded == null) {
                        LinearWavyProgressIndicator()
                    } else if (loaded.isNotEmpty()) {
                        Text("或选择已有标签:")
                        LazyColumn {
                            items(loaded) { label ->
                                ListItem(
                                    modifier = Modifier.clickable { onPick(label.labelName) },
                                    headlineContent = { Text(label.labelName) }
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text("取消") } },
            confirmButton = {
                Button(
                    enabled = newLabelName.isNotBlank(),
                    onClick = { onPick(newLabelName.trim()) }
                ) { Text("新建并应用") }
            }
        )
    }

    private fun applyLabel(context: Context, wxIds: Set<String>, labelName: String) {
        showComposeDialog(context, directlyDismissable = false) {
            val completed = remember { mutableIntStateOf(0) }
            var done by remember { mutableStateOf(false) }
            val total = wxIds.size

            LaunchedEffect(Unit) {
                CoroutineScope(Dispatchers.IO).launch {
                    // ensure the target label exists before tagging; createLabel is a no-op when
                    // the label already exists, otherwise it dispatches addcontactlabel and waits
                    // for the server-assigned id to land
                    val labelId = WeContactLabelApi.createLabel(labelName)
                    if (labelId == null) {
                        showToastSuspend(context, "创建标签「$labelName」失败")
                        done = true
                        return@launch
                    }

                    wxIds.forEachIndexed { index, wxId ->
                        // additive: keep existing labels and append the target one
                        val existing = WeContactLabelApi.getLabelNamesForContact(wxId)
                        if (labelName !in existing) {
                            WeContactLabelApi.modifyLabel(wxId, existing + labelName)
                        }
                        WeLogger.i(TAG, "labeled $wxId with $labelName (${index + 1}/$total)")
                        completed.intValue++
                        if (index < total - 1) delay(MODIFY_INTERVAL_MS)
                    }

                    done = true
                }
            }

            val completedValue by completed
            AlertDialogContent(
                title = { Text(if (done) "打标签完成" else "正在打标签") },
                text = {
                    DefaultColumn {
                        Text(
                            if (done) "已为 $completedValue/$total 位好友添加标签「$labelName」"
                            else "正在添加标签「$labelName」, 请稍等...\n已完成: $completedValue/$total"
                        )
                        LinearWavyProgressIndicator(progress = { if (total == 0) 1f else completedValue.toFloat() / total })
                    }
                },
                confirmButton = if (done) {
                    { Button(onDismiss) { Text("关闭") } }
                } else null
            )
        }
    }
}
