package dev.ujhhgtg.wekit.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.WeProtoData
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast

object SendPacket : ClickableFeature() {

    override val technicalId = "发包调试"
    override val nameRes = R.string.feature_send_packet_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_send_packet_description

    private const val TAG = "SendPacket"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var uri by remember { mutableStateOf("/cgi-bin/micromsg-bin/oplog") }
            var cmdIdStr by remember { mutableStateOf("681") }
            var funcIdStr by remember { mutableStateOf("0") }
            var routeIdStr by remember { mutableStateOf("0") }
            var jsonPayloadStr by remember { mutableStateOf("{}") }

            AlertDialogContent(
                title = { Text(stringResource(R.string.debug_send_packet_title)) },
                text = {
                    DefaultColumn {
                        TextField(
                            uri, onValueChange = { uri = it },
                            label = { Text(stringResource(R.string.debug_send_packet_cgi_path)) })
                        TextField(
                            cmdIdStr, onValueChange = { cmdIdStr = it },
                            label = { Text(stringResource(R.string.debug_send_packet_cmd_id)) })
                        TextField(
                            funcIdStr, onValueChange = { funcIdStr = it },
                            label = { Text(stringResource(R.string.debug_send_packet_func_id)) })
                        TextField(
                            routeIdStr, onValueChange = { routeIdStr = it },
                            label = { Text(stringResource(R.string.debug_send_packet_route_id)) })
                        TextField(
                            jsonPayloadStr,
                            onValueChange = { jsonPayloadStr = it },
                            label = { Text(stringResource(R.string.debug_send_packet_json_payload)) })
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uri = uri.trim()
                        val cmdId = cmdIdStr.toIntOrNull()
                        val funcId = funcIdStr.toIntOrNull()
                        val routeId = routeIdStr.toIntOrNull()
                        val payload = jsonPayloadStr.trim()

                        if (uri.isEmpty()) {
                            showToast(context, context.localizedDebugString(R.string.debug_send_packet_uri_required))
                            return@TextButton
                        }

                        if (cmdId == null || funcId == null || routeId == null) {
                            showToast(
                                context,
                                context.localizedDebugString(R.string.debug_send_packet_integer_ids_required),
                            )
                            return@TextButton
                        }

                        WePacketHelper.sendCgi(
                            uri,
                            cmdId,
                            funcId,
                            routeId,
                            payload
                        ) {
                            onSuccess { byteArray ->
                                val json = byteArray
                                    ?.let { WeProtoData.fromBytes(it).toJsonObject().toString() }
                                    ?: "{}"
                                WeLogger.i(TAG, "success: $json")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text(stringResource(R.string.debug_send_packet_success_title)) },
                                        text = {
                                            val byteCount = byteArray?.size ?: 0
                                            Text(
                                                stringResource(
                                                    R.string.debug_send_packet_success_result,
                                                    json,
                                                    pluralStringResource(
                                                        R.plurals.debug_send_packet_byte_count,
                                                        byteCount,
                                                        byteCount,
                                                    ),
                                                )
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) {
                                                Text(stringResource(R.string.action_close))
                                            }
                                        }
                                    )
                                }
                            }
                            onFailure { type, code, msg ->
                                WeLogger.e(TAG, "失败: $type, $code, $msg")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text(stringResource(R.string.debug_send_packet_failure_title)) },
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.debug_send_packet_failure_result,
                                                    type,
                                                    code,
                                                    msg,
                                                )
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) {
                                                Text(stringResource(R.string.action_close))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    override val noSwitchWidget: Boolean
        get() = true
}
