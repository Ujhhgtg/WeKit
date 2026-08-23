package dev.ujhhgtg.wekit.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.fetchBeforeTransfer
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.sendPlaceOrder
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.ShowComposeDialogScope
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Experiments : ClickableFeature() {

    override val technicalId = "测试"
    override val nameRes = R.string.feature_experiments_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_experiments_description

    @Suppress("unused")
    private const val TAG = "Experiments"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) { ExperimentDialog() }
    }

    // ── Phase model ──────────────────────────────────────────────────────────

    private sealed interface Phase {
        data object Idle : Phase
        data object Running : Phase
        data class Done(
            /** beforetransfer 的完整 protobuf 响应 */
            val beforeTransfer: ResultText,
            /** transferplaceorder 的完整 JSON 响应 */
            val placeOrder: ResultText?
        ) : Phase
    }

    private sealed interface ResultText {
        data class Raw(val value: String) : ResultText
        data class Exception(val detail: String) : ResultText
        data object RequestFailed : ResultText
        data object TimedOut : ResultText
    }

    @Composable
    private fun ResultText.asDisplayText(): String = when (this) {
        is ResultText.Raw -> value
        is ResultText.Exception -> stringResource(R.string.debug_experiments_exception, detail)
        ResultText.RequestFailed -> stringResource(R.string.debug_experiments_request_failed)
        ResultText.TimedOut -> stringResource(R.string.debug_experiments_request_timed_out)
    }

    // ── Dialog ───────────────────────────────────────────────────────────────

    @Composable
    private fun ShowComposeDialogScope.ExperimentDialog() {
        var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
        var selectedWxId by remember { mutableStateOf<String?>(null) }

        val contacts = remember { WeDatabaseApi.getContacts() }

        LaunchedEffect(phase) {
            if (phase is Phase.Running) {
                dialog.setCancelable(false)
                CoroutineScope(Dispatchers.IO).launch {
                    val id = selectedWxId!!
                    val result = runCatching {
                        val beforeTransfer = fetchBeforeTransfer(id, null)
                        val beforeStr = if (beforeTransfer != null) {
                            ResultText.Raw(buildString {
                                appendLine("maskedRealName: ${beforeTransfer.maskedRealName}")
                                append("key: ${beforeTransfer.key}")
                            })
                        } else {
                            ResultText.RequestFailed
                        }

                        val placeOrderStr = if (beforeTransfer?.key != null) {
                            val ctx = WeTransferApi.TransferContext(
                                memberId = id,
                                groupId = null,
                                maskedRealName = beforeTransfer.maskedRealName ?: "",
                                truenameExtend = beforeTransfer.key,
                                nickname = id,
                                amountYuan = 100000.0,
                                placeorderReserves = System.currentTimeMillis().toString()
                            )
                            val resp = sendPlaceOrder(ctx, inputName = null, checknameSign = null)
                            if (resp != null) ResultText.Raw(resp.toString(2)) else ResultText.TimedOut
                        } else null

                        Phase.Done(beforeStr, placeOrderStr)
                    }.getOrElse { e ->
                        WeLogger.e(TAG, "发包失败", e)
                        Phase.Done(ResultText.Exception(e.message ?: e.javaClass.simpleName), null)
                    }

                    if (phase is Phase.Running) {
                        phase = result
                        dialog.setCancelable(true)
                    }
                }
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.debug_experiments_title)) },
            text = {
                DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                    when (val current = phase) {
                        is Phase.Idle -> {
                            Text(stringResource(R.string.debug_experiments_instructions))

                            Spacer(Modifier.height(8.dp))

                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showComposeDialog(context) {
                                            SingleContactSelector(
                                                title = stringResource(R.string.debug_experiments_select_contact),
                                                contacts = contacts,
                                                initialSelectedWxId = selectedWxId,
                                                onDismiss = onDismiss,
                                                onConfirm = { wxId ->
                                                    selectedWxId = wxId
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedWxId
                                            ?: stringResource(R.string.debug_experiments_tap_to_select_contact),
                                        color = if (selectedWxId != null) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }

                        is Phase.Running -> {
                            Text(stringResource(R.string.debug_experiments_sending))
                            LinearWavyProgressIndicator()
                        }

                        is Phase.Done -> {
                            Text(
                                text = stringResource(
                                    R.string.debug_experiments_phase_one_result,
                                    current.beforeTransfer.asDisplayText(),
                                ),
                                fontFamily = FontFamily.Monospace
                            )
                            if (current.placeOrder != null) {
                                Text(stringResource(R.string.debug_experiments_phase_two_title))
                                Text(
                                    text = current.placeOrder.asDisplayText(),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (phase) {
                    is Phase.Idle -> {
                        Button(
                            onClick = {
                                if (selectedWxId != null) phase = Phase.Running
                            },
                            enabled = selectedWxId != null
                        ) { Text(stringResource(R.string.debug_experiments_start)) }
                    }

                    is Phase.Done -> Button(onDismiss) { Text(stringResource(R.string.action_close)) }
                    else -> {}
                }
            },
            dismissButton = {
                when (phase) {
                    is Phase.Running -> TextButton(onClick = {
                        dialog.setCancelable(true)
                        phase = Phase.Idle
                    }) { Text(stringResource(R.string.debug_experiments_stop)) }
                    else -> {}
                }
            }
        )
    }
}
