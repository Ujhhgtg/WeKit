package dev.ujhhgtg.wekit.activity.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Code
import com.composables.icons.materialsymbols.outlined.Colorize
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Extension
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Language
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Shield
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Volunteer_activism
import com.composables.icons.materialsymbols.outlined.Wallpaper
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import com.mikepenz.aboutlibraries.ui.compose.m3.style.m3VariantColors
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryRow
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.features.items.system.SafeMode
import dev.ujhhgtg.wekit.i18n.LanguageSelection
import dev.ujhhgtg.wekit.i18n.LocalLocalizedContext
import dev.ujhhgtg.wekit.i18n.LocaleController
import dev.ujhhgtg.wekit.i18n.SupportedLocale
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.ui.content.DexResolutionMode
import dev.ujhhgtg.wekit.ui.content.DexResolutionPrompt
import dev.ujhhgtg.wekit.ui.content.WeColorPickerDialog
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.CornerRadius
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveCollapsingTopAppBar
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.ui.content.m3AppBarColor
import dev.ujhhgtg.wekit.ui.content.m3BackdropLayer
import dev.ujhhgtg.wekit.ui.content.rememberMaterial3BlurBackdrop
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.AppColorSpec
import dev.ujhhgtg.wekit.ui.utils.theme.AppPaletteStyle
import dev.ujhhgtg.wekit.ui.utils.theme.AppThemeMode
import dev.ujhhgtg.wekit.ui.utils.theme.PageTransitionAnimation
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.openInSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
//  Page 2 — Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsPager(onOpenLicense: () -> Unit) {
    val context = LocalComponentActivity.current
    val platformContext = LocalContext.current

    var showClearConfirm by remember { mutableStateOf(false) }

    ClearConfigDialog(show = showClearConfirm, onDismiss = { showClearConfirm = false })

    M3ListScaffold(title = stringResource(R.string.settings_title)) {
        // Account info card.
        item {
            Spacer(Modifier.height(12.dp))
            ProfileCard()
        }

        // 界面
        item {
            ThemeSection()
        }

        // 调试
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_debug)) {
                item { SecuritySwitch(context) }
                item {
                    PrefSwitch(
                        key = Preferences.SHOW_STARTUP_TOAST,
                        title = stringResource(R.string.settings_startup_toast_title),
                        summary = stringResource(R.string.settings_startup_toast_summary),
                        icon = MaterialSymbols.Outlined.Notifications,
                    )
                }
                item {
                    PrefSwitch(
                        key = Preferences.MATCH_GENERIC_WXID_EXP,
                        title = stringResource(R.string.settings_generic_wxid_title),
                        summary = stringResource(R.string.settings_generic_wxid_summary),
                        icon = MaterialSymbols.Outlined.Rule_settings,
                        default = true,
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_rerun_dex_resolution_title),
                        summary = stringResource(R.string.settings_rerun_dex_resolution_summary),
                        icon = MaterialSymbols.Outlined.Build_circle,
                        onClick = {
                            DexResolutionPrompt.show(
                                context,
                                FeaturesProvider.ALL_FEATURES.filterIsInstance<IResolveDex>(),
                                DexResolutionMode.Rerun,
                            )
                        },
                    )
                }
            }
        }

        // 配置
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_configuration)) {
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_export_config_title),
                        summary = stringResource(R.string.settings_export_config_summary),
                        icon = MaterialSymbols.Outlined.Upload,
                        onClick = { SettingsConfigActions.export(platformContext) },
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_import_config_title),
                        summary = stringResource(R.string.settings_import_config_summary),
                        icon = MaterialSymbols.Outlined.Download,
                        onClick = { SettingsConfigActions.importFromDocument(platformContext) },
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_clear_config_title),
                        summary = stringResource(R.string.settings_clear_config_summary),
                        icon = MaterialSymbols.Outlined.Delete_forever,
                        onClick = { showClearConfirm = true },
                    )
                }
                item {
                    val actCtx = LocalComponentActivity.current
                    PrefArrow(
                        title = stringResource(R.string.settings_extensions_title),
                        summary = stringResource(R.string.settings_extensions_summary),
                        icon = MaterialSymbols.Outlined.Extension,
                        onClick = {
                            actCtx.startActivity(
                                Intent(context, ExtensionsSettingsActivity::class.java),
                            )
                        },
                    )
                }
            }
        }

        // 关于
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_about)) {
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_version_title),
                        summary =
                            stringResource(
                                R.string.home_version_value,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE,
                            ),
                        icon = MaterialSymbols.Outlined.Label,
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_build_commit_time_title),
                        summary = formatEpoch(BuildConfig.COMMIT_TIMESTAMP, true),
                        icon = MaterialSymbols.Outlined.Build_circle,
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_donate_title),
                        summary = stringResource(R.string.settings_donate_summary),
                        icon = MaterialSymbols.Outlined.Volunteer_activism,
                        onClick = {
                            //                        context.startActivity(Intent().apply {
                            //                            setClassName(HostInfo.packageName,
                            // "${PackageNames.WECHAT}.plugin.collect.reward.ui.QrRewardSelectMoneyUI")
                            //                            putExtra("key_qrcode_url",
                            // "m0n#Z7LGW*s4AVH!z'd(?)")
                            //                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            //                        })
                            "https://ifdian.net/a/ujhhgtg".toUri().openInSystem(context, true)
                        },
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.settings_open_source_licenses_title),
                        summary = stringResource(R.string.settings_open_source_licenses_summary),
                        icon = MaterialSymbols.Outlined.License,
                        onClick = onOpenLicense,
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.brand_github),
                        summary = "Ujhhgtg/WeKit",
                        icon = MaterialSymbols.Outlined.Code,
                        onClick = {
                            "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(context, true)
                        },
                    )
                }
                item {
                    PrefArrow(
                        title = stringResource(R.string.brand_telegram),
                        summary = "https://t.me/+7j5dJ6g16B43OWVl",
                        icon = MaterialSymbols.Outlined.Send,
                        onClick = {
                            "https://t.me/+7j5dJ6g16B43OWVl".toUri().openInSystem(context, true)
                        },
                    )
                }
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
    val activity = LocalComponentActivity.current
    val authorizationControls = StartupInfo.payloadEnvironment.authorizationControls
    val cardModifier =
        authorizationControls?.let { controls ->
            Modifier.clickable { controls.show(activity) }
        } ?: Modifier

    // WeChat identity — loaded once from the local DB; doesn't change mid-session.
    data class WechatIdentity(val nickname: String, val avatarUrl: String)

    val identity by
        produceState(WechatIdentity("", "")) {
            withContext(Dispatchers.IO) {
                val db = dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
                val nickname =
                    if (db.isReady) {
                        db.getSelfProfileField(
                                dev.ujhhgtg.wekit.features.api.core.models.SelfProfileField.NAME,
                                "",
                            )
                            ?.toString()
                            .orEmpty()
                    } else {
                        ""
                    }
                val avatarUrl = if (db.isReady && wxId.isNotEmpty()) db.getAvatarUrl(wxId) else ""
                value = WechatIdentity(nickname, avatarUrl)
            }
        }

    SegmentedColumn {
        item {
            BaseItemContainer(modifier = cardModifier) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (identity.avatarUrl.isNotEmpty()) {
                        AsyncImage(
                            model = identity.avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape),
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
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (wxId.isNotEmpty()) {
                            Text(
                                text = wxId,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Box(
        modifier =
            Modifier.size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Account_circle,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeSection() {
    val localizedContext by rememberUpdatedState(LocalLocalizedContext.current)
    val selectedLanguage = LocaleController.selection
    val resolvedLanguage = LocaleController.resolvedLocale
    val languageLabels =
        mapOf(
            LanguageSelection.SYSTEM to stringResource(R.string.language_follow_system),
            LanguageSelection.ENGLISH to stringResource(R.string.language_english),
            LanguageSelection.SIMPLIFIED_CHINESE to
                stringResource(R.string.language_simplified_chinese),
            LanguageSelection.MEOW_CHINESE to stringResource(R.string.language_meow_chinese),
            LanguageSelection.TRADITIONAL_CHINESE to
                stringResource(R.string.language_traditional_chinese),
        )
    val languageSummary =
        if (selectedLanguage == LanguageSelection.SYSTEM) {
            stringResource(
                R.string.settings_language_summary,
                stringResource(selectedLanguage.labelRes),
                stringResource(resolvedLanguage.labelRes),
            )
        } else {
            stringResource(selectedLanguage.labelRes)
        }
    val themeModeLabels =
        mapOf(
            AppThemeMode.SYSTEM to stringResource(R.string.theme_mode_system),
            AppThemeMode.LIGHT to stringResource(R.string.theme_mode_light),
            AppThemeMode.DARK to stringResource(R.string.theme_mode_dark),
        )
    val paletteStyleLabels =
        mapOf(
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
    val colorSpecLabels =
        mapOf(
            AppColorSpec.SPEC_2021 to stringResource(R.string.color_spec_material_2021),
            AppColorSpec.SPEC_2025 to stringResource(R.string.color_spec_expressive_2025),
        )
    val pageTransitionLabels =
        mapOf(
            PageTransitionAnimation.AOSP to
                stringResource(R.string.settings_page_transition_animation_aosp),
            PageTransitionAnimation.MIUIX to
                stringResource(R.string.settings_page_transition_animation_miuix),
        )
    var dynamicWallpaper by remember { mutableStateOf(ThemeSettings.dynamicWallpaper) }
    val context = LocalContext.current

    SegmentedColumn(title = stringResource(R.string.settings_section_interface)) {
        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_language_title),
                description = languageSummary,
                value = selectedLanguage,
                options = languageLabels.map { DropdownOption(it.key, it.value) },
                onValueChange = LocaleController::updateSelection,
                icon = MaterialSymbols.Outlined.Language,
            )
        }

        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_theme_mode_title),
                description = null,
                value = ThemeSettings.themeMode,
                options =
                    AppThemeMode.entries.map {
                        DropdownOption(it, themeModeLabels.getValue(it))
                    },
                onValueChange = ThemeSettings::updateThemeMode,
                icon = MaterialSymbols.Outlined.Brightness_medium,
            )
        }

        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_page_transition_animation_title),
                description = null,
                value = ThemeSettings.pageTransitionAnimation,
                options =
                    PageTransitionAnimation.entries.map {
                        DropdownOption(it, pageTransitionLabels.getValue(it))
                    },
                onValueChange = ThemeSettings::updatePageTransitionAnimation,
                icon = MaterialSymbols.Outlined.Style,
            )
        }

        item {
            SwitchWidget(
                title = stringResource(R.string.settings_dynamic_wallpaper_title),
                description = stringResource(R.string.settings_dynamic_wallpaper_summary),
                icon = MaterialSymbols.Outlined.Wallpaper,
                checked = dynamicWallpaper,
                onCheckedChange = {
                    dynamicWallpaper = it
                    ThemeSettings.updateDynamicWallpaper(it)
                },
            )
        }
        item(animatedVisibility = !dynamicWallpaper) {
            BaseWidget(
                title = stringResource(R.string.settings_seed_color_title),
                description = stringResource(R.string.settings_seed_color_summary),
                icon = MaterialSymbols.Outlined.Colorize,
                onClick = {
                    showComposeDialog(context) {
                        WeColorPickerDialog(
                            initial = ThemeSettings.seedColor,
                            onDismiss = onDismiss,
                            onConfirm = { picked ->
                                ThemeSettings.updateSeedColor(picked)
                                onDismiss()
                            },
                            resetColor = ThemeSettings.DEFAULT_SEED_COLOR,
                            showAlpha = false,
                        )
                    }
                },
            ) {
                Box(
                    modifier =
                        Modifier.size(24.dp)
                            .clip(CircleShape)
                            .background(Color(ThemeSettings.seedColor)),
                )
            }
        }
        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_palette_style_title),
                description = null,
                value = ThemeSettings.paletteStyle,
                options =
                    AppPaletteStyle.entries.map {
                        val localizedName = paletteStyleLabels.getValue(it)
                        DropdownOption(
                            it,
                            if (resolvedLanguage == SupportedLocale.ENGLISH) {
                                it.displayName
                            } else {
                                stringResource(
                                    R.string.palette_style_bilingual_format,
                                    localizedName,
                                    it.displayName,
                                )
                            },
                        )
                    },
                onValueChange = {
                    ThemeSettings.updatePaletteStyle(it)
                    // Keep the stored spec valid for the new style.
                    if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                        ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                    }
                },
                icon = MaterialSymbols.Outlined.Style,
            )
        }
        val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
        item {
            DropDownMenuWidget(
                title = stringResource(R.string.settings_color_spec_title),
                description =
                    if (!spec2025Supported) {
                        stringResource(R.string.settings_color_spec_unsupported)
                    } else {
                        null
                    },
                value = ThemeSettings.effectiveColorSpec,
                options =
                    (if (spec2025Supported) {
                            AppColorSpec.entries
                        } else {
                            listOf(AppColorSpec.SPEC_2021)
                        })
                        .map {
                            DropdownOption(it, colorSpecLabels.getValue(it))
                        },
                onValueChange = ThemeSettings::updateColorSpec,
                enabled = spec2025Supported,
                icon = MaterialSymbols.Outlined.Contrast,
            )
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
    var checked by remember(key, default) { mutableStateOf(KvStore.getBoolOrDef(key, default)) }
    SwitchWidget(
        title = title,
        description = summary,
        icon = icon,
        checked = checked,
        onCheckedChange = {
            checked = it
            KvStore.putBool(key, it)
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
        BaseWidget(
            title = title,
            description = summary,
            icon = icon,
        )
    } else {
        BaseWidget(
            title = title,
            description = summary,
            icon = icon,
            onClick = onClick,
            trailingContent = {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                )
            },
        )
    }
}

@Composable
private fun SecuritySwitch(context: Context) {
    var checked by remember { mutableStateOf(SafeMode.isEnabled) }
    SwitchWidget(
        title = stringResource(R.string.settings_safe_mode_title),
        description = stringResource(R.string.settings_safe_mode_summary),
        icon = MaterialSymbols.Outlined.Shield,
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
//  Dialogs (Material 3 AlertDialog)
// ---------------------------------------------------------------------------

@Composable
private fun ClearConfigDialog(show: Boolean, onDismiss: () -> Unit) {
    val localizedContext by rememberUpdatedState(LocalLocalizedContext.current)
    ConfirmDialog(
        show = show,
        title = stringResource(R.string.clear_config_dialog_title),
        message = stringResource(R.string.clear_config_dialog_message),
        confirmText = stringResource(R.string.action_clear),
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            CoroutineScope(Dispatchers.IO).launch {
                showToastSuspend(localizedContext.getString(R.string.config_clearing))
                SettingsConfigActions.clearAndRestart()
                showToastSuspend(localizedContext.getString(R.string.config_clear_success))
            }
        },
    )
}

/** Two-button (cancel / confirm) dialog. */
@Composable
private fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissText: String? = null,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText ?: stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** Single-button (dismiss only) dialog. */
@Composable
private fun MessageDialog(
    show: Boolean,
    title: String,
    message: String,
    dismissText: String,
    onDismiss: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

// ---------------------------------------------------------------------------
//  Open-source license screen
// ---------------------------------------------------------------------------

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    var query by remember { mutableStateOf("") }
    val filteredLibraries =
        remember(query, libraries) {
            libraries?.copy(
                libraries =
                    libraries!!.libraries.filter { library ->
                        query.isBlank() ||
                            library.name.contains(query, ignoreCase = true) ||
                            library.developers.any {
                                it.name?.contains(query, ignoreCase = true) == true
                            } ||
                            library.description?.contains(query, ignoreCase = true) == true
                    },
            )
        }
    var selectedLibrary by remember { mutableStateOf<Library?>(null) }
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val barBackdrop = rememberMaterial3BlurBackdrop()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            ExpressiveCollapsingTopAppBar(
                modifier = Modifier.m3AppBarBlur(barBackdrop),
                title = stringResource(R.string.licenses_title),
                navigationIcon = { ExpressiveBackButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = barBackdrop.m3AppBarColor(),
                        scrolledContainerColor = barBackdrop.m3AppBarColor(),
                    ),
            )
        },
    ) { scaffoldPadding ->
        LibrariesContainer(
            libraries = filteredLibraries,
            modifier = Modifier.fillMaxSize().m3BackdropLayer(barBackdrop),
            contentPadding = scaffoldPadding + PaddingValues(horizontal = 16.dp),
            colors =
                LibraryDefaults.libraryColors(
                    libraryBackgroundColor = MaterialTheme.colorScheme.surfaceContainer,
                    libraryContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            variantColors =
                LibraryDefaults.m3VariantColors(
                    rowBackground = MaterialTheme.colorScheme.surfaceBright,
                    rowExpandedBackground = MaterialTheme.colorScheme.surfaceBright,
                    rowOnBackground = MaterialTheme.colorScheme.onSurface,
                    rowSubtleContent = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            detailMode = LibraryDetailMode.None,
            header = {
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                        label = { Text(stringResource(R.string.licenses_search_hint)) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Close,
                                        contentDescription = stringResource(R.string.action_clear),
                                    )
                                }
                            }
                        },
                    )
                }
            },
            libraryRow = { _, library, expanded, toggle, style ->
                LibraryRow(
                    library = library,
                    expanded = expanded,
                    onToggle = toggle,
                    style = style,
                    modifier =
                        Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(CornerRadius)),
                )
            },
            onLibraryClick = { library ->
                selectedLibrary = library
                true
            },
        )

        selectedLibrary?.let { library ->
            val uriHandler = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = { selectedLibrary = null },
                confirmButton = {
                    Button(onClick = { selectedLibrary = null }) {
                        Text(stringResource(R.string.dialog_close))
                    }
                },
                dismissButton = {
                    library.website?.let { url ->
                        OutlinedButton(onClick = { uriHandler.openUri(url) }) {
                            Text(stringResource(R.string.licenses_visit_home_page))
                        }
                    }
                },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = library.name,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                },
                text = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(library.licenses.toList()) { license ->
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                    CardDefaults.outlinedCardColors(
                                        containerColor =
                                            MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = license.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier =
                                            Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                                license.url?.let(uriHandler::openUri)
                                            },
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(
                                        text =
                                            license.licenseContent
                                                ?: stringResource(
                                                    R.string.licenses_no_license_text,
                                                ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                properties = DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
