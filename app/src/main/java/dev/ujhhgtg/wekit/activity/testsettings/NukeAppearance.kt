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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineLabels = mapOf(
        SettingsUiEngine.MIUIX to "Miuix",
        SettingsUiEngine.NUKE to "Nuke",
    )
    val themeLabels = mapOf(
        AppThemeMode.SYSTEM to "系统默认",
        AppThemeMode.LIGHT to "浅色主题",
        AppThemeMode.DARK to "深色主题",
    )
    val popupAnimationLabels = mapOf(
        NukePopupAnimationMode.Vanilla to "原版",
        NukePopupAnimationMode.ExitAlignedToEnter to "exit 对齐 enter",
        NukePopupAnimationMode.EnterAlignedToExit to "enter 对齐 exit",
    )

    NukePageScaffold(title = "界面设置", onBack = onBack) {
        item(key = "ui_engine") {
            NukeSettingGroup(title = "界面") {
                NukeSelectPreference(
                    title = "UI 组件引擎",
                    description = "选择模块设置界面使用的组件库。",
                    options = SettingsUiEngine.entries,
                    selected = ThemeSettings.uiEngine,
                    optionLabel = { engineLabels.getValue(it) },
                    onSelected = ThemeSettings::updateUiEngine,
                )
            }
        }
        item(key = "theme_mode") {
            NukeSettingGroup(title = "主题") {
                NukeSelectPreference(
                    title = "主题",
                    description = "选择设置界面的明暗表现。",
                    options = AppThemeMode.entries,
                    selected = ThemeSettings.themeMode,
                    optionLabel = { themeLabels.getValue(it) },
                    onSelected = ThemeSettings::updateThemeMode,
                )
            }
        }
        item(key = "click_haptic") {
            NukeSettingGroup(title = "交互") {
                NukePreferenceRow(
                    title = "触觉反馈",
                    description = "点击按钮和设置项时提供触觉反馈",
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
            NukeSettingGroup(title = "颜色") {
                NukePreferenceRow(
                    title = "自定义颜色",
                    description = "启用后使用共享主题颜色，而非 Nuke 默认粉色",
                    trailing = {
                        NukeSwitch(
                            checked = ThemeSettings.customColor,
                            onCheckedChange = ThemeSettings::updateCustomColor,
                        )
                    },
                    onClick = { ThemeSettings.updateCustomColor(!ThemeSettings.customColor) },
                )
                NukeAnimatedVisibility(
                    visible = ThemeSettings.customColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        NukeDivider(startPadding = 14.dp, endPadding = 14.dp)
                        NukePreferenceRow(
                            title = "动态壁纸取色",
                            description = "使用系统壁纸的强调色作为种子，需要 Android 12 或更高版本",
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
                                    title = "种子颜色",
                                    description = "点击选择配色的种子颜色",
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
                            title = "同时对微信生效",
                            description = "将自定义配色应用到微信本身，重启微信后生效",
                            trailing = {
                                NukeSwitch(
                                    checked = ThemeSettings.applyToWechat,
                                    onCheckedChange = { value ->
                                        ThemeSettings.updateApplyToWechat(value)
                                        scope.launch { showToastSuspend(context, "重启微信生效") }
                                    },
                                )
                            },
                            onClick = {
                                ThemeSettings.updateApplyToWechat(!ThemeSettings.applyToWechat)
                                scope.launch { showToastSuspend(context, "重启微信生效") }
                            },
                        )
                    }
                }
            }
        }
        item(key = "fine_tuning") {
            Column {
                NukeSettingGroup(title = "微调") {
                    NukePreferenceRow(
                        title = "套用推荐设置",
                        description = "使用推荐的按压、页面返回和 Popup 动画组合",
                        leading = { NukeCategoryIcon(NukeGlyphKind.CheckCircle) },
                        onClick = { ThemeSettings.applyNukeRecommendedFineTuning() },
                    )
                    NukeDivider()
                    NukePreferenceRow(
                        title = "恢复原版设置",
                        description = "恢复这些微调项的原版默认行为",
                        leading = { NukeCategoryIcon(NukeGlyphKind.Restart) },
                        onClick = { ThemeSettings.restoreNukeOriginalFineTuning() },
                    )
                }
                Spacer(Modifier.height(12.dp))
                NukeSettingGroup(title = null) {
                    NukePreferenceRow(
                        title = "即时按压反馈",
                        description = "移除按压反馈延迟，使快速轻点也立即触发缩放和倾斜动画",
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
                        title = "页面 exit 动画语义逻辑优化",
                        description = "根据返回来源收缩到手势边缘、导航栏返回键或左上角返回键",
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
                        title = "Popup 动画",
                        description = "调整原版 Popup 出现与消失动画的配对方式",
                        options = NukePopupAnimationMode.entries,
                        selected = ThemeSettings.nukePopupAnimation,
                        optionLabel = { popupAnimationLabels.getValue(it) },
                        onSelected = ThemeSettings::updateNukePopupAnimation,
                    )
                    NukeDivider(startPadding = 14.dp, endPadding = 14.dp)
                    NukePreferenceRow(
                        title = "Popup 使用 Dialog 作为宿主",
                        description = "改用与 Miuix Popup 相同的窗口宿主，以支持完整的返回手势分发",
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
                                title = "Popup exit 动画预见性返回",
                                description = "返回手势进行时，Popup 会提前跟随手势播放部分消失动画",
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
        title = "选择主题色",
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton("取消", modifier = Modifier.weight(1f), onClick = dismiss)
            NukeButton(
                "保存",
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
            NukeDialogSectionTitle("预制颜色")
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
            NukeDialogSectionTitle("自定义色值")
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
                text = parsedCustom?.toNukeHex() ?: "无效颜色，请输入 #RRGGBB",
                color = if (parsedCustom == null) NukeTheme.colors.accent else NukeTheme.colors.textSecondary,
                fontSize = 12,
                lineHeight = 16,
            )
            Spacer(Modifier.height(16.dp))
            NukeDialogSectionTitle("调色板")
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
