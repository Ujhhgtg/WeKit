package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

object MassSendMessage : ClickableFeature() {

    override val technicalId = "群发消息"
    override val nameRes = R.string.feature_mass_send_message_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_mass_send_message_description

    private const val TAG = "MassSendMessage"

    override val noSwitchWidget = true

    /** Space out sends to avoid WeChat's server-side rate limiting. */
    private const val SEND_INTERVAL_MS = 800L

    private enum class SendMode(
        @StringRes val displayNameRes: Int,
        @StringRes val hintRes: Int,
        @StringRes val labelRes: Int,
    ) {
        TEXT(
            R.string.batch_mass_send_text_mode,
            R.string.batch_mass_send_text_hint,
            R.string.batch_mass_send_text_label,
        ),
        CARD(
            R.string.batch_mass_send_card_mode,
            R.string.batch_mass_send_card_hint,
            R.string.batch_mass_send_card_label,
        ),
    }

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            MassSendMessageDialog(
                context = context,
                contacts = contacts,
                onDismiss = onDismiss
            )
        }
    }

    @Composable
    private fun MassSendMessageDialog(
        context: Context,
        contacts: List<IWeContact>,
        onDismiss: () -> Unit
    ) {
        var text by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(SendMode.TEXT) }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_mass_send_message_name)) },
            text = {
                DefaultColumn {
                    SendMode.entries.forEach { option ->
                        RadioButtonWidget(
                            iconPlaceholder = false,
                            title = stringResource(option.displayNameRes),
                            selected = mode == option,
                            onClick = { mode = option },
                        )
                    }
                    Text(stringResource(mode.hintRes))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(mode.labelRes)) },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 8
                    )
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                Button(onClick = {
                    if (text.isBlank()) {
                        showToast(context.localizedBatchString(R.string.batch_mass_send_enter_content))
                        return@Button
                    }

                    onDismiss()
                    pickRecipientsAndSend(context, contacts, mode, text)
                }) { Text(stringResource(R.string.batch_mass_send_select_recipients)) }
            }
        )
    }

    private fun pickRecipientsAndSend(
        context: Context,
        contacts: List<IWeContact>,
        mode: SendMode,
        text: String
    ) {
        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedBatchString(R.string.batch_mass_send_select_title),
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.batch_mass_send_select_at_least_one))
                        return@ContactsSelector
                    }

                    onDismiss()
                    sendToAll(selectedWxIds, mode, text)
                }
            )
        }
    }

    private fun sendToAll(wxIds: Set<String>, mode: SendMode, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend(
                localizedBatchQuantity(R.plurals.batch_mass_send_progress, wxIds.size, wxIds.size),
            )

            var success = 0
            wxIds.forEachIndexed { index, wxId ->
                val sent = runCatching {
                    when (mode) {
                        SendMode.TEXT -> WeMessageApi.sendText(wxId, text)
                        SendMode.CARD -> WeMessageApi.sendXmlAppMsg(wxId, text)
                    }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to send message to $wxId", it)
                    false
                }
                if (sent) success++
                if (index < wxIds.size - 1) delay(SEND_INTERVAL_MS.milliseconds)
            }

            showToastSuspend(
                localizedBatchQuantity(
                    if (success == wxIds.size) R.plurals.batch_mass_send_done
                    else R.plurals.batch_mass_send_partial,
                    wxIds.size,
                    success,
                    wxIds.size,
                )
            )
        }
    }
}
