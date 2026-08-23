package dev.ujhhgtg.wekit.features.items.voip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

object BlockVoipRingtone : ClickableFeature(), IResolveDex {

    override val technicalId = "屏蔽铃声"
    override val nameRes = R.string.feature_block_voip_ringtone_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.VOIP)
    override val descriptionRes = R.string.feature_block_voip_ringtone_description

    private var disableOutCall by prefOption("voip_disable_ringtone_out_call", true)
    private var disableInCall by prefOption("voip_disable_ringtone_in_call", false)

    private val methodPlaySound by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.BaseSceneSetting", "playSound Failed Throwable t = ")
        }
    }

    override fun onEnable() {
        methodPlaySound.hookBefore {
            val params = args[1] as? Bundle ?: return@hookBefore
            val scene = params.getString("scene") ?: return@hookBefore
            if (scene == "start") {
                val isOutCall = params.getBoolean("isOutCall")
                val disOutCall = isOutCall && disableOutCall
                val disInCall = !isOutCall && disableInCall
                if (disOutCall || disInCall) {
                    result = false
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var outCall by remember { mutableStateOf(disableOutCall) }
            var inCall by remember { mutableStateOf(disableInCall) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_block_voip_ringtone_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.voip_block_outgoing),
                                description = stringResource(R.string.voip_block_outgoing_summary),
                                checked = outCall,
                                onCheckedChange = {
                                    outCall = it
                                    disableOutCall = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.voip_block_incoming),
                                description = stringResource(R.string.voip_block_incoming_summary),
                                checked = inCall,
                                onCheckedChange = {
                                    inCall = it
                                    disableInCall = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
