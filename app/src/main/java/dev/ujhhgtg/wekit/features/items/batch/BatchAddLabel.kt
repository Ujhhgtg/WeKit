package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearWavyProgressIndicator
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
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
import kotlin.time.Duration.Companion.milliseconds

object BatchAddLabel : ClickableFeature() {

    override val technicalId = "批量打标签"
    override val nameRes = R.string.feature_batch_add_label_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_add_label_description

    private const val TAG = "BatchAddLabel"

    override val noSwitchWidget = true

    /** Space out label modifications to avoid hammering the netscene dispatcher. */
    private const val MODIFY_INTERVAL_MS = 1000L

    override fun onClick(context: ComponentActivity) {
        val friends = WeDatabaseApi.getFriends()

        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedBatchString(R.string.batch_add_label_select_friends),
                contacts = friends,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.batch_select_at_least_one_friend))
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
            title = { Text(stringResource(R.string.batch_add_label_select_label)) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = newLabelName,
                        onValueChange = { newLabelName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.batch_add_label_new_label_hint)) },
                        singleLine = true
                    )

                    val loaded = labels
                    if (loaded == null) {
                        LinearWavyProgressIndicator()
                    } else if (loaded.isNotEmpty()) {
                        Text(stringResource(R.string.batch_add_label_existing_labels))
                        LazyColumn {
                            items(loaded) { label ->
                                ListItem(
                                    modifier = Modifier.clickable { onPick(label.labelName) },
                                    content = { Text(label.labelName) },
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                Button(
                    enabled = newLabelName.isNotBlank(),
                    onClick = { onPick(newLabelName.trim()) }
                ) { Text(stringResource(R.string.batch_add_label_create_and_apply)) }
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
                        showToastSuspend(
                            context,
                            context.localizedBatchString(R.string.batch_add_label_create_failed, labelName),
                        )
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
                        if (index < total - 1) delay(MODIFY_INTERVAL_MS.milliseconds)
                    }

                    done = true
                }
            }

            val completedValue by completed
            AlertDialogContent(
                title = {
                    Text(
                        stringResource(
                            if (done) R.string.batch_add_label_done_title
                            else R.string.batch_add_label_progress_title,
                        ),
                    )
                },
                text = {
                    DefaultColumn {
                        Text(
                            if (done) {
                                pluralStringResource(
                                    R.plurals.batch_add_label_done,
                                    total,
                                    completedValue,
                                    total,
                                    labelName,
                                )
                            } else {
                                pluralStringResource(
                                    R.plurals.batch_add_label_progress,
                                    total,
                                    labelName,
                                    completedValue,
                                    total,
                                )
                            }
                        )
                        LinearWavyProgressIndicator(progress = { if (total == 0) 1f else completedValue.toFloat() / total })
                    }
                },
                confirmButton = if (done) {
                    { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
                } else null
            )
        }
    }
}
