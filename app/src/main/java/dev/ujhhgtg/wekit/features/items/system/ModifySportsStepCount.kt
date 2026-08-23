package dev.ujhhgtg.wekit.features.items.system

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast

object ModifySportsStepCount : ClickableFeature(), IResolveDex {

    override val technicalId = "修改运动步数"
    override val nameRes = R.string.feature_modify_sports_step_count_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_modify_sports_step_count_description

    enum class PassiveMode { FIXED, MULTIPLIER }

    private val methodGetSteps by dexMethod {
        searchPackages("com.tencent.mm.plugin.sport.model")
        matcher {
            usingEqStrings("MicroMsg.Sport.DeviceStepManager", "get today step from %s todayStep %d")
        }
    }
    private val methodUploadSteps by dexMethod {
        searchPackages("com.tencent.mm.plugin.sport.model")
        matcher {
            usingEqStrings("MicroMsg.Sport.DeviceStepManager", "update device Step time: %s stepCount: %s")
        }
    }

    override fun onEnable() {
        methodGetSteps.hookAfter {
            val value = passiveValue
            if (value < 0) return@hookAfter
            result = when (passiveMode) {
                PassiveMode.FIXED -> value
                PassiveMode.MULTIPLIER -> (result as Long) * value
            }
        }
    }

    private var passiveModeStr by prefOption("step_passive_mode", PassiveMode.FIXED.name)
    private var passiveMode: PassiveMode
        get() = runCatching { PassiveMode.valueOf(passiveModeStr) }.getOrDefault(PassiveMode.FIXED)
        set(v) {
            passiveModeStr = v.name
        }

    private var passiveValue by prefOption("step_passive_value", -1L)

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var modeState by remember { mutableStateOf(passiveMode) }
            var passiveInput by remember {
                mutableStateOf(if (passiveValue >= 0) passiveValue.toString() else "")
            }
            var activeInput by remember { mutableStateOf("") }
            val activeIsEmpty = activeInput.isEmpty()

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_modify_sports_step_count_name)) },
                text = {
                    DefaultColumn {
                        // 被动模式: 固定 / 倍率
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.system_sports_passive_mode), modifier = Modifier.weight(1f))
                            SingleChoiceSegmentedButtonRow {
                                PassiveMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = modeState == mode,
                                        onClick = { modeState = mode },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index, PassiveMode.entries.size
                                        )
                                    ) {
                                        Text(
                                            stringResource(
                                                if (mode == PassiveMode.FIXED) R.string.system_sports_fixed
                                                else R.string.system_sports_multiplier
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // 被动值
                        TextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = passiveInput,
                            onValueChange = {
                                passiveInput = it.filter { c -> c.isDigit() }.trim()
                            },
                            label = { Text(stringResource(R.string.system_sports_passive_value)) }
                        )

                        // 主动值 + 立即上传
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                modifier = Modifier.weight(1f),
                                value = activeInput,
                                onValueChange = {
                                    activeInput = it.filter { c -> c.isDigit() }.trim()
                                },
                                label = { Text(stringResource(R.string.system_sports_active_value)) },
                            )
                            Button(
                                enabled = !activeIsEmpty,
                                onClick = {
                                    val count = activeInput.toLongOrNull() ?: run {
                                        showToast(localizedSystemString(R.string.system_invalid_format))
                                        return@Button
                                    }
                                    val sportsMan =
                                        methodUploadSteps.method.declaringClass.createInstance()
                                    val ok =
                                        methodUploadSteps.method.invoke(sportsMan, count) as Boolean
                                    val result = localizedSystemString(
                                        if (ok) R.string.system_success else R.string.system_failure
                                    )
                                    showToast(
                                        context,
                                        context.localizedSystemString(R.string.system_sports_upload_result, result)
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.system_sports_upload))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        passiveMode = modeState
                        passiveValue = passiveInput.toLongOrNull() ?: -1L
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            )
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = stringResource(R.string.warning)) },
                    text = { Text(text = stringResource(R.string.system_risky_feature_warning)) },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.dialog_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
