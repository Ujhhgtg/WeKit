package dev.ujhhgtg.wekit.features.items.entertain

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.ModProfileProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.OpLog
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.OpLogRespProto
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

object ClearProfileDetails : ClickableFeature() {

    override val technicalId = "清空资料信息"
    override val nameRes = R.string.feature_clear_profile_details_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_clear_profile_details_description

    private const val TAG = "ClearProfileDetails"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_clear_profile_details_name)) },
                text = { Text(stringResource(R.string.clear_profile_details_confirmation)) },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
//                        val payload = """{"1":{"1":1,"2":{"1":1,"2":{"1":91,"2":{"1":128,"2":{"1":""},"3":{"1":""},"4":0,"5":{"1":""},"6":{"1":""},"7":0,"8":0,"9":"","10":0,"11":"","12":"","13":"","14":1,"16":0,"17":0,"19":0,"20":0,"21":0,"22":0,"23":0,"24":"","25":0,"27":"","28":"","29":0,"30":0,"31":0,"33":0,"34":0,"36":0,"38":""}}}}}"""

                        val reqBytes = OpLog.encodeSingle(
                            OpLog.CMD_MOD_PROFILE,
                            ModProfileProto()
                        )

                        WePacketHelper.sendCgi(
//                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            reqBytes = reqBytes
//                            payload
                        ) {
                            onSuccess { bytes ->
                                val resp = bytes?.let { OpLogRespProto.decode(it) }
                                WeLogger.i(TAG, "success: ret=${resp?.ret}")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = {
                                            Text(stringResource(R.string.clear_profile_details_send_success))
                                        },
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.clear_profile_details_server_response_code,
                                                    resp?.ret?.toString()
                                                        ?: stringResource(R.string.unknown),
                                                )
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) {
                                                Text(stringResource(R.string.dialog_close))
                                            }
                                        }
                                    )
                                }
                            }

                            onFailure { type, code, msg ->
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = {
                                            Text(stringResource(R.string.clear_profile_details_send_failure))
                                        },
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.clear_profile_details_send_failure_details,
                                                    type.toString(),
                                                    code.toString(),
                                                    msg,
                                                )
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) {
                                                Text(stringResource(R.string.dialog_close))
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
