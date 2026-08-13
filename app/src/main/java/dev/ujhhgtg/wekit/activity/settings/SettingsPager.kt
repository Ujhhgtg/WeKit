package dev.ujhhgtg.wekit.activity.settings


import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Auto_delete
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Colorize
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Language
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Lightbulb_2
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Palette
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Shield
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Sync
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Volunteer_activism
import com.composables.icons.materialsymbols.outlined.Wallpaper
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.items.debug.ResetDexCache
import dev.ujhhgtg.wekit.features.items.system.SafeMode
import dev.ujhhgtg.wekit.i18n.LanguageSelection
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.MiuixSmallTitle
import dev.ujhhgtg.wekit.ui.content.WeKitWindowDialog
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.ui.utils.theme.AppColorSpec
import dev.ujhhgtg.wekit.ui.utils.theme.AppPaletteStyle
import dev.ujhhgtg.wekit.ui.utils.theme.AppThemeMode
import dev.ujhhgtg.wekit.ui.utils.theme.SettingsUiEngine
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.AppUpdater
import dev.ujhhgtg.wekit.utils.UpdateResult
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.openInSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---------------------------------------------------------------------------
//  Page 2 — Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsPager(onOpenLicense: () -> Unit) {
    val context = LocalComponentActivity.current
    val localizedContext = LocalContext.current
    val currentLocalizedContext = rememberUpdatedState(localizedContext)

    var showClearConfirm by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateResult.UpdateAvailable?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }

    ClearConfigDialog(show = showClearConfirm, onDismiss = { showClearConfirm = false })
    UpdateAvailableDialog(info = updateInfo, onDismiss = { updateInfo = null }, context = context)
    UpdateErrorDialog(message = updateError, onDismiss = { updateError = null })

    MiuixListScaffold(title = stringResource(R.string.settings_title)) {
        // Account info card.
        item {
            Spacer(Modifier.height(12.dp))
            ProfileCard()
        }

        // 界面
        item {
            MiuixSmallTitle(
                text = stringResource(R.string.settings_section_interface),
                modifier = Modifier.padding(top = 12.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                ThemeSection()
            }
        }

        // 调试
        item {
            MiuixSmallTitle(text = stringResource(R.string.settings_section_debug), modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                SecuritySwitch(context)
                PrefSwitch(
                    key = Preferences.VERBOSE_LOG,
                    title = stringResource(R.string.settings_verbose_log_title),
                    summary = stringResource(R.string.settings_verbose_log_summary),
                    icon = MaterialSymbols.Outlined.Frame_bug,
                )
                PrefSwitch(
                    key = Preferences.SHOW_STARTUP_TOAST,
                    title = stringResource(R.string.settings_startup_toast_title),
                    summary = stringResource(R.string.settings_startup_toast_summary),
                    icon = MaterialSymbols.Outlined.Notifications,
                )
                PrefSwitch(
                    key = Preferences.MATCH_GENERIC_WXID_EXP,
                    title = stringResource(R.string.settings_generic_wxid_title),
                    summary = stringResource(R.string.settings_generic_wxid_summary),
                    icon = MaterialSymbols.Outlined.Rule_settings,
                    default = true,
                )
            }
        }

        // 兼容
        item {
            MiuixSmallTitle(text = stringResource(R.string.settings_section_compatibility), modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefSwitch(
                    key = Preferences.NO_DEX_RESOLVE,
                    title = stringResource(R.string.settings_disable_resolution_title),
                    summary = stringResource(R.string.settings_disable_resolution_summary),
                    icon = MaterialSymbols.Outlined.Block,
                )
                PrefArrow(
                    title = stringResource(R.string.settings_reset_resolution_title),
                    summary = stringResource(R.string.settings_reset_resolution_summary),
                    icon = MaterialSymbols.Outlined.Build_circle,
                    onClick = { ResetDexCache.onClick(context) },
                )
                PrefSwitch(
                    key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                    title = stringResource(R.string.settings_hot_update_resolution_title),
                    summary = stringResource(R.string.settings_hot_update_resolution_summary),
                    icon = MaterialSymbols.Outlined.Auto_delete,
                )
            }
        }

        // 配置
        item {
            MiuixSmallTitle(text = stringResource(R.string.settings_section_configuration), modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = stringResource(R.string.settings_export_config_title),
                    summary = stringResource(R.string.settings_export_config_summary),
                    icon = MaterialSymbols.Outlined.Upload,
                    onClick = { SettingsConfigActions.export(localizedContext) },
                )
                PrefArrow(
                    title = stringResource(R.string.settings_import_config_title),
                    summary = stringResource(R.string.settings_import_config_summary),
                    icon = MaterialSymbols.Outlined.Download,
                    onClick = { SettingsConfigActions.importFromDocument(localizedContext) },
                )
                PrefArrow(
                    title = stringResource(R.string.settings_clear_config_title),
                    summary = stringResource(R.string.settings_clear_config_summary),
                    icon = MaterialSymbols.Outlined.Delete_forever,
                    onClick = { showClearConfirm = true },
                )
            }
        }

        // 更新
        item {
            MiuixSmallTitle(text = stringResource(R.string.settings_section_update), modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = stringResource(R.string.settings_check_update_title),
                    summary = stringResource(R.string.settings_check_update_summary),
                    icon = MaterialSymbols.Outlined.Update,
                    onClick = {
                        checkForUpdate(
                            context = { currentLocalizedContext.value },
                            onAvailable = { updateInfo = it },
                            onError = { updateError = it },
                        )
                    },
                )
            }
        }

        // 关于
        item {
            MiuixSmallTitle(text = stringResource(R.string.settings_section_about), modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(title = stringResource(R.string.settings_version_title), summary = stringResource(R.string.home_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), icon = MaterialSymbols.Outlined.Label)
                PrefArrow(title = stringResource(R.string.settings_build_commit_time_title), summary = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true), icon = MaterialSymbols.Outlined.Build_circle)
                PrefArrow(
                    title = stringResource(R.string.settings_tip_title),
                    summary = stringResource(R.string.settings_tip_summary),
                    icon = MaterialSymbols.Outlined.Lightbulb_2,
                )
                PrefArrow(
                    title = stringResource(R.string.settings_donate_title),
                    summary = stringResource(R.string.settings_donate_summary),
                    icon = MaterialSymbols.Outlined.Volunteer_activism,
                    onClick = {
//                        context.startActivity(Intent().apply {
//                            setClassName(HostInfo.packageName, "${PackageNames.WECHAT}.plugin.collect.reward.ui.QrRewardSelectMoneyUI")
//                            putExtra("key_qrcode_url", "m0n#Z7LGW*s4AVH!z'd(?)")
//                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                        })
                        "https://ifdian.net/a/ujhhgtg".toUri().openInSystem(context, true)
                    },
                )
                PrefArrow(
                    title = stringResource(R.string.about_translators_title),
                    summary = stringResource(R.string.about_translators_summary),
                    icon = MaterialSymbols.Outlined.Volunteer_activism,
                    onClick = {
                        "https://github.com/Ujhhgtg/WeKit/blob/dev/docs/translations/TRANSLATORS.md"
                            .toUri()
                            .openInSystem(context, true)
                    },
                )
                PrefArrow(
                    title = stringResource(R.string.settings_open_source_licenses_title),
                    summary = stringResource(R.string.settings_open_source_licenses_summary),
                    icon = MaterialSymbols.Outlined.License,
                    onClick = onOpenLicense,
                )
                PrefArrow(
                    title = stringResource(R.string.brand_github),
                    summary = "Ujhhgtg/WeKit",
                    icon = GitHubIcon,
                    onClick = { "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(context, true) })
                PrefArrow(
                    title = stringResource(R.string.brand_telegram),
                    summary = "https://t.me/+7j5dJ6g16B43OWVl",
                    icon = TelegramIcon,
                    onClick = { "https://t.me/+7j5dJ6g16B43OWVl".toUri().openInSystem(context, true) })
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Profile card — account info at the top of the Settings tab
// ---------------------------------------------------------------------------

@Composable
private fun ProfileCard() {
    val wxId = remember { WeApi.selfWxId }

    // WeChat identity — loaded once from the local DB; doesn't change mid-session.
    data class WechatIdentity(val nickname: String, val avatarUrl: String)

    val identity by produceState(WechatIdentity("", "")) {
        withContext(Dispatchers.IO) {
            val db = dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
            val nickname = if (db.isReady) {
                db.getSelfProfileField(dev.ujhhgtg.wekit.features.api.core.models.SelfProfileField.NAME, "")
                    ?.toString().orEmpty()
            } else ""
            val avatarUrl = if (db.isReady && wxId.isNotEmpty()) db.getAvatarUrl(wxId) else ""
            value = WechatIdentity(nickname, avatarUrl)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (identity.avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = identity.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                )
            } else {
                AvatarPlaceholder()
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = identity.nickname.ifEmpty { wxId.ifEmpty { "—" } },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (wxId.isNotEmpty()) {
                    Text(
                        text = wxId,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Account_circle,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** A miuix dropdown bound to an enum's entries. */
@Composable
private fun <T> EnumDropdown(
    title: String,
    entries: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    WindowDropdownPreference(
        title = title,
        summary = summary,
        items = entries.map(labelOf),
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        enabled = enabled,
        startAction = icon?.let { { PrefIcon(it) } },
        onSelectedIndexChange = { onSelected(entries[it]) },
    )
}

@Composable
private fun ThemeSection() {
    val context = LocalContext.current
    val selectedLanguage = WeKitLocaleController.selection
    val resolvedLanguage = WeKitLocaleController.resolvedLocale
    val languageLabels = mapOf(
        LanguageSelection.SYSTEM to stringResource(R.string.language_follow_system),
        LanguageSelection.ENGLISH to stringResource(R.string.language_english),
        LanguageSelection.SIMPLIFIED_CHINESE to stringResource(R.string.language_simplified_chinese),
        LanguageSelection.TRADITIONAL_CHINESE to stringResource(R.string.language_traditional_chinese),
    )
    val languageSummary = if (selectedLanguage == LanguageSelection.SYSTEM) {
        stringResource(
            R.string.settings_language_summary,
            stringResource(selectedLanguage.labelRes),
            stringResource(resolvedLanguage.labelRes),
        )
    } else {
        stringResource(selectedLanguage.labelRes)
    }
    val themeModeLabels = mapOf(
        AppThemeMode.SYSTEM to stringResource(R.string.theme_mode_system),
        AppThemeMode.LIGHT to stringResource(R.string.theme_mode_light),
        AppThemeMode.DARK to stringResource(R.string.theme_mode_dark),
    )
    val uiEngineLabels = mapOf(
        SettingsUiEngine.MIUIX to stringResource(R.string.ui_engine_miuix),
        SettingsUiEngine.NUKE to stringResource(R.string.ui_engine_nuke),
    )
    val paletteStyleLabels = mapOf(
        AppPaletteStyle.TONAL_SPOT to stringResource(R.string.palette_style_tonal_spot),
        AppPaletteStyle.NEUTRAL to stringResource(R.string.palette_style_neutral),
        AppPaletteStyle.VIBRANT to stringResource(R.string.palette_style_vibrant),
        AppPaletteStyle.EXPRESSIVE to stringResource(R.string.palette_style_expressive),
        AppPaletteStyle.RAINBOW to stringResource(R.string.palette_style_rainbow),
        AppPaletteStyle.FRUIT_SALAD to stringResource(R.string.palette_style_fruit_salad),
        AppPaletteStyle.MONOCHROME to stringResource(R.string.palette_style_monochrome),
        AppPaletteStyle.FIDELITY to stringResource(R.string.palette_style_fidelity),
        AppPaletteStyle.CONTENT to stringResource(R.string.palette_style_content),
    )
    val colorSpecLabels = mapOf(
        AppColorSpec.SPEC_2021 to stringResource(R.string.color_spec_material_2021),
        AppColorSpec.SPEC_2025 to stringResource(R.string.color_spec_expressive_2025),
    )
    EnumDropdown(
        title = stringResource(R.string.settings_language_title),
        entries = LanguageSelection.entries,
        selected = selectedLanguage,
        labelOf = languageLabels::getValue,
        onSelected = WeKitLocaleController::updateSelection,
        summary = languageSummary,
        icon = MaterialSymbols.Outlined.Language,
    )

    EnumDropdown(
        title = stringResource(R.string.settings_ui_engine_title),
        entries = SettingsUiEngine.entries,
        selected = ThemeSettings.uiEngine,
        labelOf = uiEngineLabels::getValue,
        onSelected = { ThemeSettings.updateUiEngine(it) },
        icon = MaterialSymbols.Outlined.Style,
    )

    EnumDropdown(
        title = stringResource(R.string.settings_theme_mode_title),
        entries = AppThemeMode.entries,
        selected = ThemeSettings.themeMode,
        labelOf = themeModeLabels::getValue,
        onSelected = { ThemeSettings.updateThemeMode(it) },
        icon = MaterialSymbols.Outlined.Brightness_medium,
    )

    var customColor by remember { mutableStateOf(ThemeSettings.customColor) }
    SwitchPreference(
        title = stringResource(R.string.settings_custom_color_title),
        summary = stringResource(R.string.settings_custom_color_summary),
        startAction = { PrefIcon(MaterialSymbols.Outlined.Palette) },
        checked = customColor,
        onCheckedChange = {
            customColor = it
            ThemeSettings.updateCustomColor(it)
        },
    )

    var showColorPicker by remember { mutableStateOf(false) }
    SeedColorPickerDialog(show = showColorPicker, onDismiss = { showColorPicker = false })

    AnimatedVisibility(visible = customColor) {
        Column {
            var dynamicWallpaper by remember { mutableStateOf(ThemeSettings.dynamicWallpaper) }
            SwitchPreference(
                title = stringResource(R.string.settings_dynamic_wallpaper_title),
                summary = stringResource(R.string.settings_dynamic_wallpaper_summary),
                startAction = { PrefIcon(MaterialSymbols.Outlined.Wallpaper) },
                checked = dynamicWallpaper,
                onCheckedChange = {
                    dynamicWallpaper = it
                    ThemeSettings.updateDynamicWallpaper(it)
                },
            )
            AnimatedVisibility(visible = !dynamicWallpaper) {
                BasicComponent(
                    title = stringResource(R.string.settings_seed_color_title),
                    summary = stringResource(R.string.settings_seed_color_summary),
                    startAction = { PrefIcon(MaterialSymbols.Outlined.Colorize) },
                    onClick = { showColorPicker = true },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(ThemeSettings.seedColor)),
                        )
                    },
                )
            }
            EnumDropdown(
                title = stringResource(R.string.settings_palette_style_title),
                entries = AppPaletteStyle.entries,
                selected = ThemeSettings.paletteStyle,
                labelOf = paletteStyleLabels::getValue,
                onSelected = {
                    ThemeSettings.updatePaletteStyle(it)
                    // Keep the stored spec valid for the new style.
                    if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                        ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                    }
                },
                icon = MaterialSymbols.Outlined.Style,
            )
            val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
            EnumDropdown(
                title = stringResource(R.string.settings_color_spec_title),
                entries = if (spec2025Supported) AppColorSpec.entries else listOf(AppColorSpec.SPEC_2021),
                selected = ThemeSettings.effectiveColorSpec,
                labelOf = colorSpecLabels::getValue,
                onSelected = { ThemeSettings.updateColorSpec(it) },
                enabled = spec2025Supported,
                summary = if (!spec2025Supported) {
                    stringResource(R.string.settings_color_spec_unsupported)
                } else null,
                icon = MaterialSymbols.Outlined.Contrast,
            )

            var applyToWechat by remember { mutableStateOf(ThemeSettings.applyToWechat) }
            SwitchPreference(
                title = stringResource(R.string.settings_apply_to_wechat_title),
                summary = stringResource(R.string.settings_apply_to_wechat_summary),
                startAction = { PrefIcon(MaterialSymbols.Outlined.Sync) },
                checked = applyToWechat,
                onCheckedChange = {
                    applyToWechat = it
                    ThemeSettings.updateApplyToWechat(it)
                    CoroutineScope(Dispatchers.Main).launch {
                        showToastSuspend(context.getString(R.string.restart_wechat))
                    }
                },
            )
        }
    }
}

/** miuix color-picker dialog for the custom seed color; commits to ThemeSettings on confirm. */
@Composable
private fun SeedColorPickerDialog(show: Boolean, onDismiss: () -> Unit) {
    var picked by remember(show) { mutableStateOf(Color(ThemeSettings.seedColor)) }

    WeKitWindowDialog(
        show = show,
        title = stringResource(R.string.settings_custom_color_title),
        onDismissRequest = onDismiss,
    ) {
        Column {
            ColorPicker(
                color = picked,
                onColorChanged = { picked = it },
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_reset),
                    onClick = { picked = Color(ThemeSettings.DEFAULT_SEED_COLOR) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.dialog_confirm),
                    onClick = {
                        ThemeSettings.updateSeedColor(picked.toArgb())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Preference helper composables
// ---------------------------------------------------------------------------

@Composable
private fun PrefSwitch(
    key: String,
    title: String,
    summary: String,
    icon: ImageVector,
    default: Boolean = false,
) {
    // Must match the default declared on the matching `prefOption`, otherwise the switch shows
    // "off" for a preference that is actually on until the user toggles it once.
    var checked by remember(key, default) { mutableStateOf(WePrefs.getBoolOrDef(key, default)) }
    SwitchPreference(
        title = title,
        summary = summary,
        startAction = { PrefIcon(icon) },
        checked = checked,
        onCheckedChange = {
            checked = it
            WePrefs.putBool(key, it)
        },
    )
}

@Composable
private fun PrefArrow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        // Informational row: no trailing arrow, no ripple.
        BasicComponent(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
        )
    } else {
        ArrowPreference(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
            onClick = onClick,
        )
    }
}

@Composable
private fun PrefIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

@Composable
private fun SecuritySwitch(context: Context) {
    var checked by remember { mutableStateOf(SafeMode.isEnabled) }
    SwitchPreference(
        title = stringResource(R.string.settings_safe_mode_title),
        summary = stringResource(R.string.settings_safe_mode_summary),
        startAction = { PrefIcon(MaterialSymbols.Outlined.Shield) },
        checked = checked,
        onCheckedChange = {
            if (it) {
                SafeMode.showEnableConfirmDialog(context) {
                    checked = true
                    SafeMode.setEnabled(true)
                }
            } else {
                checked = false
                SafeMode.setEnabled(false)
            }
        },
    )
}
// ---------------------------------------------------------------------------
//  Update checks
// ---------------------------------------------------------------------------

private fun checkForUpdate(
    context: () -> Context,
    onAvailable: (UpdateResult.UpdateAvailable) -> Unit,
    onError: (String) -> Unit,
) {
    CoroutineScope(Dispatchers.Main).launch {
        showToastSuspend(context().getString(R.string.update_checking))
        when (val result = AppUpdater.checkForUpdate()) {
            UpdateResult.UpToDate -> showToastSuspend(context().getString(R.string.update_up_to_date))
            is UpdateResult.UpdateAvailable -> onAvailable(result)
            is UpdateResult.Error -> {
                WeLogger.e("AppUpdater", "failed to check for updates", result.cause)
                onError(result.cause.message ?: context().getString(R.string.error_unknown))
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Dialogs (miuix WindowDialog)
// ---------------------------------------------------------------------------

@Composable
private fun ClearConfigDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    MiuixConfirmDialog(
        show = show,
        title = stringResource(R.string.clear_config_dialog_title),
        message = stringResource(R.string.clear_config_dialog_message),
        confirmText = stringResource(R.string.action_clear),
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            CoroutineScope(Dispatchers.IO).launch {
                showToastSuspend(context.getString(R.string.config_clearing))
                SettingsConfigActions.clear()
                showToastSuspend(context.getString(R.string.config_clear_success))
            }
        },
    )
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateResult.UpdateAvailable?,
    onDismiss: () -> Unit,
    context: ComponentActivity,
) {
    val currentLocalizedContext = rememberUpdatedState(LocalContext.current)
    MiuixConfirmDialog(
        show = info != null,
        title = stringResource(R.string.update_available_title),
        message = if (info != null) {
            stringResource(
                R.string.update_available_message,
                BuildConfig.VERSION_NAME,
                info.info.versionName,
            )
        } else "",
        confirmText = stringResource(R.string.dialog_confirm),
        onDismiss = onDismiss,
        onConfirm = {
            val target = info ?: return@MiuixConfirmDialog
            onDismiss()
            // The activity's scope, so closing settings mid-download cancels the download wait
            // (and with it the BroadcastReceiver it keeps registered on this activity).
            // This UI is proxied into WeChat's process: an escaping exception here would take
            // WeChat down with it, so nothing may leave this coroutine.
            context.lifecycleScope.launch(Dispatchers.Default) {
                runCatching { AppUpdater.downloadAndInstall(context, target.info) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        WeLogger.e("AppUpdater", "failed to download update", e)
                        val localizedContext = currentLocalizedContext.value
                        showToastSuspend(
                            context,
                            localizedContext.getString(
                                R.string.update_download_failed,
                                e.message ?: localizedContext.getString(R.string.error_unknown),
                            ),
                        )
                    }
            }
        },
    )
}

@Composable
private fun UpdateErrorDialog(message: String?, onDismiss: () -> Unit) {
    MiuixMessageDialog(
        show = message != null,
        title = stringResource(R.string.update_check_failed_title),
        message = stringResource(R.string.update_error_message, message.orEmpty()),
        dismissText = stringResource(R.string.dialog_close),
        onDismiss = onDismiss,
    )
}

/** Two-button (cancel / confirm) miuix dialog. */
@Composable
private fun MiuixConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissText: String? = null,
) {
    WeKitWindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            Text(text = message)
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = dismissText ?: stringResource(R.string.dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** Single-button (dismiss only) miuix dialog. */
@Composable
private fun MiuixMessageDialog(
    show: Boolean,
    title: String,
    message: String,
    dismissText: String,
    onDismiss: () -> Unit,
) {
    WeKitWindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            Text(text = message)
            Spacer(Modifier.height(20.dp))
            TextButton(
                text = dismissText,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}


// ---------------------------------------------------------------------------
//  Open-source license screen
// ---------------------------------------------------------------------------

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val libraries = remember(resources) {
        val json = resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader()
            .use { it.readText() }
        Libs.Builder().withJson(json).build().libraries
    }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val filtered = remember(query, libraries) {
        if (query.isBlank()) libraries
        else libraries.filter { lib ->
            lib.name.contains(query, ignoreCase = true) ||
                    lib.developers.any { it.name?.contains(query, ignoreCase = true) == true } ||
                    lib.description?.contains(query, ignoreCase = true) == true
        }
    }

    MiuixListScaffold(
        title = stringResource(R.string.licenses_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Arrow_back,
                    contentDescription = stringResource(R.string.accessibility_back),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        item {
            TextField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = stringResource(R.string.licenses_search_hint),
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { queryState.clearText() }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = stringResource(R.string.action_clear),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                },
            )
        }

        item {
            MiuixSmallTitle(
                text = if (query.isBlank()) {
                    stringResource(R.string.licenses_count, libraries.size)
                } else {
                    stringResource(R.string.licenses_filtered_count, filtered.size, libraries.size)
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.licenses_no_results, query),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(filtered, key = { it.uniqueId }) { library ->
                LibraryRow(library, modifier = Modifier.padding(top = 12.dp))
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryRow(library: Library, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = library.name,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                library.artifactVersion?.let { version ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = version,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            val author = library.developers.firstOrNull()?.name ?: library.organization?.name
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            library.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (library.licenses.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    library.licenses.forEach { license ->
                        Text(
                            text = license.name,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
