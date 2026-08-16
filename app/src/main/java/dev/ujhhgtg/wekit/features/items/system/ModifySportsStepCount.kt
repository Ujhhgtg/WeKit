package dev.ujhhgtg.wekit.features.items.system

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast

/** Which value row currently has its edit view swapped in. */
private enum class EditField { PASSIVE, ACTIVE }

@Feature(
    id = "修改运动步数",
    nameRes = "feature_modify_sports_step_count_name",
    categoryIds = [FeatureCategoryIds.SYSTEM_PRIVACY],
    descriptionRes = "feature_modify_sports_step_count_description",
)
object ModifySportsStepCount : ClickableFeature(), IResolveDex {

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
            var mode by remember { mutableStateOf(passiveMode) }
            var passiveValueShown by remember { mutableLongStateOf(passiveValue) }
            var activeValue by remember { mutableStateOf("") }
            var editing by remember { mutableStateOf<EditField?>(null) }
            var draft by remember { mutableStateOf("") }

            val editingField = editing
            if (editingField == null) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.feature_modify_sports_step_count_name)) },
                    text = {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item(key = "passive_mode_header") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.system_sports_passive_mode),
                                )
                            }
                            // 被动模式: 固定 / 倍率
                            PassiveMode.entries.forEach { entry ->
                                item(key = entry.name) {
                                    RadioButtonWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(
                                            if (entry == PassiveMode.FIXED) R.string.system_sports_fixed
                                            else R.string.system_sports_multiplier
                                        ),
                                        selected = mode == entry,
                                        onClick = {
                                            mode = entry
                                            passiveMode = entry
                                        },
                                    )
                                }
                            }
                            // 被动值: 空 = -1 = 不修改
                            item(key = "passive_value") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.system_sports_passive_value),
                                    description = if (passiveValueShown >= 0) passiveValueShown.toString()
                                    else stringResource(R.string.system_none),
                                    onClick = {
                                        draft = if (passiveValueShown >= 0) passiveValueShown.toString() else ""
                                        editing = EditField.PASSIVE
                                    },
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                            }
                            // 主动值 + 立即上传 (事务性动作, 不做草稿化)
                            item(key = "active_upload") {
                                BaseItemContainer {
                                    BaseWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.system_sports_active_value),
                                        description = activeValue.ifEmpty { stringResource(R.string.system_none) },
                                        onClick = {
                                            draft = activeValue
                                            editing = EditField.ACTIVE
                                        },
                                        trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                    )
                                    Button(
                                        enabled = activeValue.isNotEmpty(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        onClick = {
                                            val count = activeValue.toLongOrNull() ?: run {
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
                                        },
                                    ) {
                                        Text(stringResource(R.string.system_sports_upload))
                                    }
                                }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                    },
                )
            } else {
                AlertDialogContent(
                    title = {
                        Text(
                            stringResource(
                                if (editingField == EditField.PASSIVE) R.string.system_sports_passive_value
                                else R.string.system_sports_active_value
                            )
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.filter(Char::isDigit) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            when (editingField) {
                                EditField.PASSIVE -> {
                                    passiveValue = draft.toLongOrNull() ?: -1L
                                    passiveValueShown = passiveValue
                                }
                                EditField.ACTIVE -> activeValue = draft
                            }
                            editing = null
                        }) { Text(stringResource(R.string.dialog_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { editing = null }) { Text(stringResource(R.string.dialog_cancel)) }
                    },
                )
            }
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
