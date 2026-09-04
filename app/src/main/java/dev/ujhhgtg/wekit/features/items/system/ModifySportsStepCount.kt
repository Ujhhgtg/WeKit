package dev.ujhhgtg.wekit.features.items.system

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Upload
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.math.DecimalExpression
import java.math.RoundingMode

object ModifySportsStepCount : ClickableFeature(), IResolveDex {

    private const val KEY_PASSIVE_EXPRESSION = "step_passive_expression"
    private const val LEGACY_MODE = "step_passive_mode"
    private const val LEGACY_VALUE = "step_passive_value"
    private const val VALUE_VARIABLE = "value"
    private const val TAG = "ModifySportsStepCount"

    override val technicalId = "修改运动步数"
    override val nameRes = R.string.feature_modify_sports_step_count_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_modify_sports_step_count_description

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
        migrateLegacySettings()
        methodGetSteps.hookAfter {
            val original = result as Long
            evaluatePassiveExpression(original)?.let { result = it }
        }
    }

    private var passiveExpression by prefOption(KEY_PASSIVE_EXPRESSION, VALUE_VARIABLE)
    private var cachedExpression: Pair<String, DecimalExpression>? = null

    private fun evaluatePassiveExpression(original: Long): Long? {
        val source = passiveExpression
        return runCatching {
            val expression = synchronized(this) {
                cachedExpression?.takeIf { it.first == source }?.second
                    ?: DecimalExpression.parse(source, setOf(VALUE_VARIABLE)).also {
                        cachedExpression = source to it
                    }
            }
            val evaluated = expression.evaluate(mapOf(VALUE_VARIABLE to original.toBigDecimal()))
            if (evaluated.signum() < 0) error("Step count cannot be negative")
            evaluated.setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.onFailure { error ->
            WeLogger.e(
                TAG,
                "failed to evaluate passive step expression '$source' with value $original; keeping original steps",
                error,
            )
        }.getOrNull()
    }

    private fun expressionError(expression: String): String? = runCatching {
        DecimalExpression.parse(expression, setOf(VALUE_VARIABLE))
    }.exceptionOrNull()?.message

    private fun migrateLegacySettings() {
        if (WePrefs.default.contains(KEY_PASSIVE_EXPRESSION)) return
        val mode = WePrefs.getStringOrDef(LEGACY_MODE, "FIXED")
        val value = WePrefs.getLongOrDef(LEGACY_VALUE, -1L)
        passiveExpression = migrateSportsStepExpression(mode, value)
        WeLogger.i(TAG, "migrated legacy passive step settings to an expression")
    }

    override fun onClick(context: ComponentActivity) {
        migrateLegacySettings()
        showComposeDialog(context) {
            var passiveInput by remember { mutableStateOf(passiveExpression) }
            var activeInput by remember { mutableStateOf("") }
            val passiveError = expressionError(passiveInput)
            val activeError = stringResource(R.string.system_invalid_format)
                .takeIf { activeInput.isNotEmpty() && activeInput.toLongOrNull() == null }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_modify_sports_step_count_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseSupportingWidget(
                                title = stringResource(R.string.system_sports_passive_value),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    value = passiveInput,
                                    onValueChange = { passiveInput = it },
                                    supportingText = {
                                        Text(
                                            passiveError
                                                ?: stringResource(R.string.system_sports_passive_expression_hint)
                                        )
                                    },
                                    isError = passiveError != null,
                                    singleLine = true,
                                )
                            }
                        }

                        item {
                            BaseSupportingWidget(
                                title = stringResource(R.string.system_sports_active_value),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    value = activeInput,
                                    onValueChange = {
                                        activeInput = it.filter(Char::isDigit).trim()
                                    },
                                    supportingText = activeError?.let { error -> { Text(error) } },
                                    isError = activeError != null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    trailingIcon = {
                                        IconButton(
                                            enabled = activeInput.isNotEmpty() && activeError == null,
                                            onClick = {
                                                val count = activeInput.toLong()
                                                val sportsMan =
                                                    methodUploadSteps.method.declaringClass.createInstance()
                                                val ok = methodUploadSteps.method.invoke(sportsMan, count) as Boolean
                                                val result = localizedSystemString(
                                                    if (ok) R.string.system_success else R.string.system_failure
                                                )
                                                showToast(
                                                    context,
                                                    context.localizedSystemString(
                                                        R.string.system_sports_upload_result,
                                                        result,
                                                    ),
                                                )
                                            },
                                        ) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Upload,
                                                contentDescription = stringResource(R.string.system_sports_upload),
                                            )
                                        }
                                    },
                                    singleLine = true,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = passiveError == null,
                        onClick = {
                            passiveExpression = passiveInput.trim()
                            synchronized(this@ModifySportsStepCount) { cachedExpression = null }
                            onDismiss()
                        },
                    ) {
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

internal fun migrateSportsStepExpression(mode: String, value: Long): String = when {
    value < 0 -> "value"
    mode == "MULTIPLIER" -> "value * $value"
    else -> value.toString()
}
