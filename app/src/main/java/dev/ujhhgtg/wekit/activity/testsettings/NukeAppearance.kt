package dev.ujhhgtg.wekit.activity.testsettings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.nukex.NukeAnimatedVisibility
import dev.ujhhgtg.wekit.ui.content.nukex.NukeButton
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCategoryIcon
import dev.ujhhgtg.wekit.ui.content.nukex.NukeColorSwatch
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCountAndChevron
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDialogSectionTitle
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDialogSurface
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDivider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyphKind
import dev.ujhhgtg.wekit.ui.content.nukex.NukeHueBar
import dev.ujhhgtg.wekit.ui.content.nukex.NukePageScaffold
import dev.ujhhgtg.wekit.ui.content.nukex.NukePopupAnimationMode
import dev.ujhhgtg.wekit.ui.content.nukex.NukePreferenceRow
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSaturationValuePalette
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSelectPreference
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSettingGroup
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSwitch
import dev.ujhhgtg.wekit.ui.content.nukex.NukeText
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTextField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTheme
import dev.ujhhgtg.wekit.ui.content.nukex.parseNukeColor
import dev.ujhhgtg.wekit.ui.content.nukex.toNukeHex
import dev.ujhhgtg.wekit.ui.content.nukex.toNukeHsv
import dev.ujhhgtg.wekit.ui.utils.theme.AppThemeMode
import dev.ujhhgtg.wekit.ui.utils.theme.SettingsUiEngine
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.launch

private val nukePresetColors = listOf(
    Color(0xFFEC4899), Color(0xFFF43F5E), Color(0xFFF97316), Color(0xFFF59E0B),
    Color(0xFF22C55E), Color(0xFF14B8A6), Color(0xFF0EA5E9), Color(0xFF3B82F6),
    Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFA855F7), Color(0xFF64748B),
)

@Composable
internal fun NukeAppearancePage(onBack: (Offset) -> Unit) {
    var showColorDialog by remember { mutableStateOf(false) }
    var nativeSettingsIntegration by remember {
        mutableStateOf(Preferences.nativeSettingsIntegration)
    }
    val context = LocalContext.current
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val scope = rememberCoroutineScope()
    val engineLabels = mapOf(
        SettingsUiEngine.MATERIAL3 to "Material 3",
        SettingsUiEngine.NUKE to "Nuke",
    )
    val themeLabels = mapOf(
        AppThemeMode.SYSTEM to stringResource(R.string.theme_mode_system),
        AppThemeMode.LIGHT to stringResource(R.string.theme_mode_light),
        AppThemeMode.DARK to stringResource(R.string.theme_mode_dark),
    )
    val popupAnimationLabels = mapOf(
        NukePopupAnimationMode.Vanilla to stringResource(R.string.nuke_popup_animation_vanilla),
        NukePopupAnimationMode.ExitAlignedToEnter to stringResource(R.string.nuke_popup_animation_exit_to_enter),
        NukePopupAnimationMode.EnterAlignedToExit to stringResource(R.string.nuke_popup_animation_enter_to_exit),
    )
    fun updatePredictiveBackEnabled(value: Boolean) {
        if (value == ThemeSettings.predictiveBackEnabled) return
        ThemeSettings.updatePredictiveBackEnabled(value)
        scope.launch { showToastSuspend(context, localizedContext.getString(R.string.restart_wechat_to_apply)) }
    }

    NukePageScaffold(title = stringResource(R.string.nuke_appearance_title), onBack = onBack) {
        item(key = "ui_engine") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_interface)) {
                NukeSelectPreference(
                    title = stringResource(R.string.settings_ui_engine_title),
                    description = stringResource(R.string.nuke_ui_engine_summary),
                    options = SettingsUiEngine.entries,
                    selected = ThemeSettings.uiEngine,
                    optionLabel = { engineLabels.getValue(it) },
                    onSelected = ThemeSettings::updateUiEngine,
                )
                NukePreferenceRow(
                    title = stringResource(R.string.settings_native_integration_title),
                    description = stringResource(R.string.settings_native_integration_summary),
                    trailing = {
                        NukeSwitch(
                            checked = nativeSettingsIntegration,
                            onCheckedChange = { enabled ->
                                nativeSettingsIntegration = enabled
                                Preferences.nativeSettingsIntegration = enabled
                            },
                        )
                    },
                    onClick = {
                        nativeSettingsIntegration = !nativeSettingsIntegration
                        Preferences.nativeSettingsIntegration = nativeSettingsIntegration
                    },
                )
            }
        }
        item(key = "theme_mode") {
            NukeSettingGroup(title = stringResource(R.string.nuke_section_theme)) {
                NukeSelectPreference(
                    title = stringResource(R.string.settings_theme_mode_title),
                    description = stringResource(R.string.nuke_theme_mode_summary),
                    options = AppThemeMode.entries,
                    selected = ThemeSettings.themeMode,
                    optionLabel = { themeLabels.getValue(it) },
                    onSelected = ThemeSettings::updateThemeMode,
                )
            }
        }
        item(key = "predictive_back") {
            NukeSettingGroup(title = stringResource(R.string.nuke_section_interaction)) {
                NukePreferenceRow(
                    title = stringResource(R.string.settings_predictive_back_animation_title),
                    description = stringResource(R.string.settings_predictive_back_animation_nuke_summary),
                    trailing = {
                        NukeSwitch(
                            checked = ThemeSettings.predictiveBackEnabled,
                            onCheckedChange = ::updatePredictiveBackEnabled,
                        )
                    },
                    onClick = {
                        updatePredictiveBackEnabled(!ThemeSettings.predictiveBackEnabled)
                    },
                )
            }
        }
        item(key = "click_haptic") {
            NukeSettingGroup(title = stringResource(R.string.nuke_section_interaction)) {
                NukePreferenceRow(
                    title = stringResource(R.string.nuke_haptics_title),
                    description = stringResource(R.string.nuke_haptics_summary),
                    trailing = {
                        NukeSwitch(
                            checked = ThemeSettings.nukeHaptics,
                            onCheckedChange = ThemeSettings::updateNukeHaptics,
                        )
                    },
                    onClick = { ThemeSettings.updateNukeHaptics(!ThemeSettings.nukeHaptics) },
                )
            }
        }
        item(key = "color") {
            NukeSettingGroup(title = stringResource(R.string.nuke_section_colors)) {
                Column {
                        NukeDivider(startPadding = 14.dp, endPadding = 14.dp)
                        NukePreferenceRow(
                            title = stringResource(R.string.settings_dynamic_wallpaper_title),
                            description = stringResource(R.string.nuke_dynamic_wallpaper_summary),
                            trailing = {
                                NukeSwitch(
                                    checked = ThemeSettings.dynamicWallpaper,
                                    onCheckedChange = ThemeSettings::updateDynamicWallpaper,
                                )
                            },
                            onClick = {
                                ThemeSettings.updateDynamicWallpaper(!ThemeSettings.dynamicWallpaper)
                            },
                        )
                        NukeAnimatedVisibility(visible = !ThemeSettings.dynamicWallpaper) {
                            Column {
                                NukeDivider(startPadding = 14.dp, endPadding = 14.dp)
                                NukePreferenceRow(
                                    title = stringResource(R.string.settings_seed_color_title),
                                    description = stringResource(R.string.settings_seed_color_summary),
                                    trailing = {
                                        val seedColor = Color(ThemeSettings.seedColor)
                                        NukeColorSwatch(color = seedColor, selected = false)
                                        Spacer(Modifier.width(10.dp))
                                        NukeText(
                                            text = seedColor.toNukeHex(),
                                            color = NukeTheme.colors.textSecondary,
                                            fontSize = 13,
                                            lineHeight = 18,
                                            maxLines = 1,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        NukeCountAndChevron(text = null)
                                    },
                                    onClick = { showColorDialog = true },
                                )
                            }
                        }
                        NukeDivider(startPadding = 14.dp, endPadding = 14.dp)
                        NukePreferenceRow(
                            title = stringResource(R.string.settings_apply_to_wechat_title),
                            description = stringResource(R.string.nuke_apply_to_wechat_summary),
                            trailing = {
                                NukeSwitch(
                                    checked = ThemeSettings.applyToWechat,
                                    onCheckedChange = { value ->
                                        ThemeSettings.updateApplyToWechat(value)
                                        scope.launch { showToastSuspend(context, localizedContext.getString(R.string.restart_wechat_to_apply)) }
                                    },
                                )
                            },
                            onClick = {
                                ThemeSettings.updateApplyToWechat(!ThemeSettings.applyToWechat)
                                scope.launch { showToastSuspend(context, localizedContext.getString(R.string.restart_wechat_to_apply)) }
                            },
                        )
                }
            }
        }
        item(key = "fine_tuning") {
            Column {
                NukeSettingGroup(title = stringResource(R.string.nuke_section_fine_tuning)) {
                    NukePreferenceRow(
                        title = stringResource(R.string.nuke_apply_recommended_title),
                        description = stringResource(R.string.nuke_apply_recommended_summary),
                        leading = { NukeCategoryIcon(NukeGlyphKind.CheckCircle) },
                        onClick = { ThemeSettings.applyNukeRecommendedFineTuning() },
                    )
                    NukeDivider()
                    NukePreferenceRow(
                        title = stringResource(R.string.nuke_restore_original_title),
                        description = stringResource(R.string.nuke_restore_original_summary),
                        leading = { NukeCategoryIcon(NukeGlyphKind.Restart) },
                        onClick = { ThemeSettings.restoreNukeOriginalFineTuning() },
                    )
                }
                Spacer(Modifier.height(12.dp))
                NukeSettingGroup(title = null) {
                    NukePreferenceRow(
                        title = stringResource(R.string.nuke_immediate_press_title),
                        description = stringResource(R.string.nuke_immediate_press_summary),
                        trailing = {
                            NukeSwitch(
                                checked = ThemeSettings.nukeImmediatePressFeedback,
                                onCheckedChange = ThemeSettings::updateNukeImmediatePressFeedback,
                            )
                        },
                        onClick = {
                            ThemeSettings.updateNukeImmediatePressFeedback(
                                !ThemeSettings.nukeImmediatePressFeedback,
                            )
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                NukeSettingGroup(title = null) {
                    NukePreferenceRow(
                        title = stringResource(R.string.nuke_page_exit_title),
                        description = stringResource(R.string.nuke_page_exit_summary),
                        trailing = {
                            NukeSwitch(
                                checked = ThemeSettings.nukePageExitOptimization,
                                onCheckedChange = ThemeSettings::updateNukePageExitOptimization,
                            )
                        },
                        onClick = {
                            ThemeSettings.updateNukePageExitOptimization(
                                !ThemeSettings.nukePageExitOptimization,
                            )
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                NukeSettingGroup(title = null) {
                    NukeSelectPreference(
                        title = stringResource(R.string.nuke_popup_animation_title),
                        description = stringResource(R.string.nuke_popup_animation_summary),
                        options = NukePopupAnimationMode.entries,
                        selected = ThemeSettings.nukePopupAnimation,
                        optionLabel = { popupAnimationLabels.getValue(it) },
                        onSelected = ThemeSettings::updateNukePopupAnimation,
                    )
                    NukeDivider(startPadding = 14.dp, endPadding = 14.dp)
                    NukePreferenceRow(
                        title = stringResource(R.string.nuke_popup_dialog_host_title),
                        description = stringResource(R.string.nuke_popup_dialog_host_summary),
                        trailing = {
                            NukeSwitch(
                                checked = ThemeSettings.nukePopupDialogHost,
                                onCheckedChange = ThemeSettings::updateNukePopupDialogHost,
                            )
                        },
                        onClick = {
                            ThemeSettings.updateNukePopupDialogHost(!ThemeSettings.nukePopupDialogHost)
                        },
                    )
                    NukeAnimatedVisibility(
                        visible = ThemeSettings.nukePopupDialogHost &&
                            ThemeSettings.nukePopupAnimation.supportsPredictiveExit,
                    ) {
                        Column {
                            NukeDivider(startPadding = 14.dp, endPadding = 14.dp)
                            NukePreferenceRow(
                                title = stringResource(R.string.nuke_popup_predictive_exit_title),
                                description = stringResource(R.string.nuke_popup_predictive_exit_summary),
                                trailing = {
                                    NukeSwitch(
                                        checked = ThemeSettings.nukePopupPredictiveExit,
                                        onCheckedChange = ThemeSettings::updateNukePopupPredictiveExit,
                                    )
                                },
                                onClick = {
                                    ThemeSettings.updateNukePopupPredictiveExit(
                                        !ThemeSettings.nukePopupPredictiveExit,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showColorDialog) {
        NukeThemeColorDialog(
            accent = Color(ThemeSettings.seedColor),
            onAccentChange = { ThemeSettings.updateSeedColor(it.toArgb()) },
            onDismiss = { showColorDialog = false },
        )
    }
}

@Composable
private fun NukeThemeColorDialog(
    accent: Color,
    onAccentChange: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedColor by remember(accent) { mutableStateOf(accent) }
    var customHex by remember(accent) { mutableStateOf(accent.toNukeHex()) }
    var hue by remember(accent) { mutableFloatStateOf(accent.toNukeHsv()[0]) }
    var saturation by remember(accent) { mutableFloatStateOf(accent.toNukeHsv()[1]) }
    var value by remember(accent) { mutableFloatStateOf(accent.toNukeHsv()[2]) }
    val parsedCustom = customHex.parseNukeColor()

    fun updateFromHsv() {
        selectedColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
        customHex = selectedColor.toNukeHex()
    }

    NukeDialogSurface(
        title = stringResource(R.string.nuke_choose_theme_color),
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton(stringResource(R.string.dialog_cancel), modifier = Modifier.weight(1f), onClick = dismiss)
            NukeButton(
                stringResource(R.string.logs_save),
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = parsedCustom != null,
                onClick = {
                    onAccentChange(selectedColor)
                    dismiss()
                },
            )
        },
    ) {
        Column(
            Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            NukeDialogSectionTitle(stringResource(R.string.nuke_preset_colors))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nukePresetColors.chunked(6).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { color ->
                            NukeColorSwatch(
                                color = color,
                                selected = color.toNukeHex() == selectedColor.toNukeHex(),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    selectedColor = color
                                    customHex = color.toNukeHex()
                                    color.toNukeHsv().also { hsv ->
                                        hue = hsv[0]
                                        saturation = hsv[1]
                                        value = hsv[2]
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            NukeDialogSectionTitle(stringResource(R.string.nuke_custom_color_value))
            NukeTextField(
                value = customHex,
                onValueChange = { input ->
                    customHex = input.take(7)
                    input.parseNukeColor()?.let { parsed ->
                        selectedColor = parsed
                        parsed.toNukeHsv().also { hsv ->
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "#RRGGBB",
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            NukeText(
                text = parsedCustom?.toNukeHex() ?: stringResource(R.string.nuke_invalid_color),
                color = if (parsedCustom == null) NukeTheme.colors.accent else NukeTheme.colors.textSecondary,
                fontSize = 12,
                lineHeight = 16,
            )
            Spacer(Modifier.height(16.dp))
            NukeDialogSectionTitle(stringResource(R.string.settings_palette_style_title))
            NukeSaturationValuePalette(
                hue = hue,
                saturation = saturation,
                value = value,
                onChanged = { newSaturation, newValue ->
                    saturation = newSaturation
                    value = newValue
                    updateFromHsv()
                },
            )
            Spacer(Modifier.height(10.dp))
            NukeHueBar(
                hue = hue,
                onHueChange = {
                    hue = it
                    updateFromHsv()
                },
            )
        }
    }
}
