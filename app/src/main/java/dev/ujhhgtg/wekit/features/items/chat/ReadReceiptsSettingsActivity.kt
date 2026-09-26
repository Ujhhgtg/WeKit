package dev.ujhhgtg.wekit.features.items.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.settings.M3ListScaffold
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme

@Keep
class ReadReceiptsSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    ReadReceiptsHomeScreen(onFinish = ::finish)
                }
            }
        }
    }
}

@Composable
private fun ReadReceiptsHomeScreen(onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = remember { ReadReceipts.configuration() }
    var url by rememberSaveable { mutableStateOf(initial.thirdPartyUrl) }
    var intervalSecs by rememberSaveable { mutableIntStateOf(initial.pollIntervalSecs.coerceIn(1, 60)) }
    var sendMode by rememberSaveable { mutableIntStateOf(ReadReceipts.sendMode) }
    var triggerPrefix by rememberSaveable { mutableStateOf(ReadReceipts.triggerPrefix) }
    var testing by remember { mutableStateOf(false) }
    var feedbackRes by rememberSaveable { mutableIntStateOf(0) }

    M3ListScaffold(
        title = stringResource(R.string.feature_read_receipts_name),
        navigationIcon = { ExpressiveBackButton(onClick = onFinish) },
    ) {
        item {
            SegmentedColumn(title = stringResource(R.string.chat_read_receipts_server)) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.read_receipts_third_party_server),
                        value = url,
                        valueHint = stringResource(R.string.read_receipts_server_url),
                        onValueChange = { value ->
                            val normalized = if (value.isEmpty()) "" else normalizeThirdPartyReadReceiptEndpoint(value)
                            if (normalized == null) {
                                feedbackRes = R.string.read_receipts_invalid_third_party_url
                            } else {
                                ReadReceipts.saveConfiguration(
                                    ReadReceipts.configuration().copy(thirdPartyUrl = normalized),
                                )
                                url = normalized
                                feedbackRes = 0
                            }
                        },
                        dialogTitle = stringResource(R.string.read_receipts_server_url),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = !testing,
                        keyboardType = KeyboardType.Uri,
                    )
                }
                item {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_test_connection),
                        enabled = !testing && url.isNotEmpty(),
                        onClick = {
                            testing = true
                            feedbackRes = 0
                            ReadReceipts.testThirdPartyEndpoint(url, scope) { result ->
                                testing = false
                                feedbackRes = if (result.isSuccess) {
                                    R.string.read_receipts_server_connection_succeeded
                                } else {
                                    R.string.read_receipts_server_connection_failed
                                }
                            } ?: run {
                                testing = false
                                feedbackRes = R.string.read_receipts_invalid_third_party_url
                            }
                        },
                    )
                }
                if (testing || feedbackRes != 0) {
                    item {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (testing) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(
                                    if (testing) R.string.read_receipts_connection_test_pending else feedbackRes,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (feedbackRes == R.string.read_receipts_invalid_third_party_url ||
                                    feedbackRes == R.string.read_receipts_server_connection_failed
                                ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.read_receipts_section_send_mode)) {
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_mode_passive),
                        description = stringResource(R.string.read_receipts_mode_passive_description),
                        selected = sendMode == ReadReceipts.MODE_PASSIVE,
                        onClick = {
                            sendMode = ReadReceipts.MODE_PASSIVE
                            ReadReceipts.sendMode = ReadReceipts.MODE_PASSIVE
                        },
                    )
                }
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_mode_active_menu),
                        description = stringResource(R.string.read_receipts_mode_active_menu_description),
                        selected = sendMode == ReadReceipts.MODE_ACTIVE_MENU,
                        onClick = {
                            sendMode = ReadReceipts.MODE_ACTIVE_MENU
                            ReadReceipts.sendMode = ReadReceipts.MODE_ACTIVE_MENU
                        },
                    )
                }
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_mode_active_prefix),
                        description = stringResource(R.string.read_receipts_mode_active_prefix_description),
                        selected = sendMode == ReadReceipts.MODE_ACTIVE_PREFIX,
                        onClick = {
                            sendMode = ReadReceipts.MODE_ACTIVE_PREFIX
                            ReadReceipts.sendMode = ReadReceipts.MODE_ACTIVE_PREFIX
                        },
                    )
                }
                item(key = "trigger_prefix", animatedVisibility = sendMode == ReadReceipts.MODE_ACTIVE_PREFIX) {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.chat_read_receipts_prefix),
                        value = triggerPrefix,
                        onValueChange = {
                            triggerPrefix = it
                            ReadReceipts.triggerPrefix = it
                        },
                        dialogTitle = stringResource(R.string.chat_read_receipts_prefix),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_configuration)) {
                item {
                    BaseItemContainer {
                        IntNumberPickerWidget(
                            title = stringResource(R.string.chat_read_receipts_poll_interval),
                            value = intervalSecs,
                            startInt = 1,
                            endInt = 60,
                            stepSize = 1,
                            onValueChange = {
                                ReadReceipts.saveConfiguration(
                                    ReadReceipts.configuration().copy(pollIntervalSecs = it),
                                )
                                intervalSecs = it
                            },
                        )
                    }
                }
            }
        }
    }
}
