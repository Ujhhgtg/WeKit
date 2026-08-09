package dev.ujhhgtg.wekit.features.items.voip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

@Feature(
    id = "屏蔽铃声",
    nameRes = "feature_block_voip_ringtone_name",
    categoryIds = [FeatureCategoryIds.CHAT, FeatureCategoryIds.VOIP],
    descriptionRes = "feature_block_voip_ringtone_description",
)
object BlockVoipRingtone : ClickableFeature(), IResolveDex {

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
                title = { Text("屏蔽铃声") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { outCall = !outCall },
                            trailingContent = { Switch(checked = outCall, onCheckedChange = { outCall = it }) },
                            supportingContent = { Text("屏蔽拨出音视频通话时的铃声") },
                            content = { Text("屏蔽呼出铃声") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { inCall = !inCall },
                            trailingContent = { Switch(checked = inCall, onCheckedChange = { inCall = it }) },
                            supportingContent = { Text("屏蔽收到音视频通话请求时的铃声") },
                            content = { Text("屏蔽呼入铃声") },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        disableOutCall = outCall
                        disableInCall = inCall
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
