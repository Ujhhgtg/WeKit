package dev.ujhhgtg.wekit.activity.testsettings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_delete
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Upload
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.settings.LocalComponentActivity
import dev.ujhhgtg.wekit.activity.settings.SettingsConfigActions
import dev.ujhhgtg.wekit.activity.settings.featureCategoryTitleRes
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.debug.ResetDexCache
import dev.ujhhgtg.wekit.i18n.LanguageSelection
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.nukex.NukeButton
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCategoryIcon
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCountAndChevron
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDialogSurface
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDivider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyph
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyphKind
import dev.ujhhgtg.wekit.ui.content.nukex.NukePageScaffold
import dev.ujhhgtg.wekit.ui.content.nukex.NukePreferenceRow
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSearchField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSelectPreference
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSettingGroup
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSettingGroupTitle
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSquircleShape
import dev.ujhhgtg.wekit.ui.content.nukex.NukeStatusPill
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSwitch
import dev.ujhhgtg.wekit.ui.content.nukex.NukeText
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTheme
import dev.ujhhgtg.wekit.ui.content.nukex.NukeVectorCategoryIcon
import dev.ujhhgtg.wekit.ui.content.nukex.nukeGroupedCardItem
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.utils.AppUpdater
import dev.ujhhgtg.wekit.utils.UpdateResult
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.restartHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

@Composable
internal fun NukeDestinationPage(
    destination: NukeDestination,
    featureItems: List<SwitchFeature>,
    onBack: (Offset) -> Unit,
    onOpenDestination: (NukeDestination, Offset) -> Unit,
) {
    when (destination) {
        is NukeDestination.Category -> NukeFeatureCategoryPage(
            categoryId = destination.id,
            featureItems = featureItems,
            onBack = onBack,
        )

        NukeDestination.ModuleDebug -> NukeModuleDebugPage(onBack)
        NukeDestination.Update -> NukeUpdatePage(onBack)
        NukeDestination.GeneralSettings -> NukeGeneralSettingsPage(onBack)
        NukeDestination.Appearance -> NukeAppearancePage(onBack)
        NukeDestination.About -> NukeAboutPage(onBack, onOpenDestination)
        NukeDestination.Licenses -> NukeLicensesPage(onBack)
    }
}

@Composable
private fun NukeModuleDebugPage(onBack: (Offset) -> Unit) {
    val context = LocalWeKitLocalizedContext.current
    val resolvedLocale = WeKitLocaleController.resolvedLocale
    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val features = remember(resolvedLocale) {
        FeaturesProvider.ALL_FEATURES.sortedWith { first, second ->
            featureNameCollator.compare(first.localizedName(context), second.localizedName(context))
        }
    }
    var selectedFeature by remember { mutableStateOf<BaseFeature?>(null) }
    // Only the FEATURES rows visible on the first frame animate in; scrolling reveals further
    // rows statically.
    var featuresEntranceEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        featuresEntranceEnabled = false
    }

    NukePageScaffold(
        title = stringResource(R.string.nuke_module_debug_title),
        onBack = onBack,
        // FEATURES rows are individual items; 0 spacing keeps them flush and explicit spacer
        // items below restore the 12dp rhythm between sections.
        itemSpacing = 0.dp,
    ) {
        item(key = "actions") {
            NukeSettingGroup(title = stringResource(R.string.nuke_section_actions)) {
                NukePreferenceRow(
                    title = stringResource(R.string.nuke_restart_host_title),
                    description = stringResource(R.string.nuke_restart_host_summary),
                    leading = { NukeCategoryIcon(NukeGlyphKind.Restart) },
                    onClick = { restartHost() },
                )
            }
        }
        item(key = "gap_overview") { Spacer(Modifier.height(12.dp)) }
        item(key = "overview") {
            NukeSettingGroup(title = stringResource(R.string.nuke_status_overview)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NukeStatusPill(
                        stringResource(R.string.nuke_status_normal_count, features.size),
                        Color(0xFF16A34A),
                    )
                }
            }
        }
        item(key = "gap_features") { Spacer(Modifier.height(12.dp)) }
        item(key = "features_title") {
            // The section title always bounces when it appears (including the second time after
            // scrolling back to the top); only the rows are gated to first-appearance motion.
            NukeSettingGroupTitle(title = stringResource(R.string.nuke_features_heading))
        }
        itemsIndexed(features, key = { _, feature -> feature.technicalId }) { index, feature ->
            Column(
                Modifier.nukeGroupedCardItem(
                    index,
                    features.size,
                    animate = featuresEntranceEnabled,
                ),
            ) {
                NukeFeatureStatusRow(feature = feature, onClick = { selectedFeature = feature })
                if (index < features.lastIndex) NukeDivider()
            }
        }
    }
    selectedFeature?.let { feature ->
        NukeFeatureStatusDialog(feature = feature, onDismiss = { selectedFeature = null })
    }
}

@Composable
private fun NukeFeatureStatusRow(feature: BaseFeature, onClick: () -> Unit) {
    val context = LocalWeKitLocalizedContext.current
    NukePreferenceRow(
        title = feature.localizedName(context),
        description = feature.categoryIds
            .joinToString(" / ") { context.getString(featureCategoryTitleRes(it)) }
            .ifBlank { stringResource(R.string.nuke_feature_kind_base) },
        leading = { NukeCategoryIcon(NukeGlyphKind.CheckCircle) },
        trailing = {
            NukeStatusPill(stringResource(R.string.nuke_status_normal), Color(0xFF16A34A))
        },
        onClick = { onClick() },
    )
}

@Composable
private fun NukeFeatureStatusDialog(feature: BaseFeature, onDismiss: () -> Unit) {
    val context = LocalWeKitLocalizedContext.current
    val kind = when (feature) {
        is ClickableFeature -> stringResource(R.string.nuke_feature_kind_configurable)
        is SwitchFeature -> stringResource(R.string.nuke_feature_kind_switch)
        else -> stringResource(R.string.nuke_feature_kind_base)
    }
    NukeMessageDialog(
        title = feature.localizedName(context),
        message = buildString {
            appendLine(stringResource(R.string.nuke_feature_status_line, stringResource(R.string.nuke_status_normal)))
            appendLine(stringResource(R.string.nuke_feature_type_line, kind))
            val categories = feature.categoryIds
                .joinToString(" / ") { context.getString(featureCategoryTitleRes(it)) }
                .ifBlank { stringResource(R.string.nuke_uncategorized) }
            appendLine(stringResource(R.string.nuke_feature_categories_line, categories))
            feature.localizedDescription(context).takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun NukeGeneralSettingsPage(onBack: (Offset) -> Unit) {
    val context = LocalContext.current
    val activity = LocalComponentActivity.current
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    var showClearConfirmation by remember { mutableStateOf(false) }

    NukePageScaffold(title = stringResource(R.string.settings_general_title), onBack = onBack) {
        item(key = "language") {
            val selectedLanguage = WeKitLocaleController.selection
            val resolvedLanguage = WeKitLocaleController.resolvedLocale
            val languageLabels = mapOf(
                LanguageSelection.SYSTEM to stringResource(R.string.language_follow_system),
                LanguageSelection.ENGLISH to stringResource(R.string.language_english),
                LanguageSelection.SIMPLIFIED_CHINESE to stringResource(R.string.language_simplified_chinese),
                LanguageSelection.MEOW_CHINESE to stringResource(R.string.language_meow_chinese),
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
            NukeSettingGroup(title = null) {
                NukeSelectPreference(
                    title = stringResource(R.string.settings_language_title),
                    description = languageSummary,
                    options = LanguageSelection.entries,
                    selected = selectedLanguage,
                    optionLabel = languageLabels::getValue,
                    onSelected = WeKitLocaleController::updateSelection,
                )
            }
        }
        item(key = "debug") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_debug)) {
                NukeBooleanPreference(
                    key = Preferences.VERBOSE_LOG,
                    title = stringResource(R.string.settings_verbose_log_title),
                    description = stringResource(R.string.settings_verbose_log_summary),
                    imageVector = MaterialSymbols.Outlined.Frame_bug,
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.SHOW_STARTUP_TOAST,
                    title = stringResource(R.string.settings_startup_toast_title),
                    description = stringResource(R.string.settings_startup_toast_summary),
                    imageVector = MaterialSymbols.Outlined.Notifications,
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.MATCH_GENERIC_WXID_EXP,
                    title = stringResource(R.string.settings_generic_wxid_title),
                    description = stringResource(R.string.settings_generic_wxid_summary),
                    imageVector = MaterialSymbols.Outlined.Rule_settings,
                    default = true,
                )
            }
        }
        item(key = "compatibility") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_compatibility)) {
                NukeBooleanPreference(
                    key = Preferences.NO_DEX_RESOLVE,
                    title = stringResource(R.string.settings_disable_resolution_title),
                    description = stringResource(R.string.settings_disable_resolution_summary),
                    imageVector = MaterialSymbols.Outlined.Block,
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.settings_reset_resolution_title),
                    description = stringResource(R.string.settings_reset_resolution_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Build_circle) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = { ResetDexCache.onClick(activity) },
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                    title = stringResource(R.string.settings_hot_update_resolution_title),
                    description = stringResource(R.string.settings_hot_update_resolution_summary),
                    imageVector = MaterialSymbols.Outlined.Auto_delete,
                )
            }
        }
        item(key = "configuration") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_configuration)) {
                NukePreferenceRow(
                    title = stringResource(R.string.settings_export_config_title),
                    description = stringResource(R.string.settings_export_config_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Upload) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        SettingsConfigActions.export(context) { localizedContext }
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.settings_import_config_title),
                    description = stringResource(R.string.settings_import_config_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Download) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        SettingsConfigActions.importFromDocument(context) { localizedContext }
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.settings_clear_config_title),
                    description = stringResource(R.string.settings_clear_config_summary),
                    leading = {
                        NukeVectorCategoryIcon(
                            MaterialSymbols.Outlined.Delete_forever,
                            error = true,
                        )
                    },
                    trailing = { NukeCountAndChevron(text = null, error = true) },
                    onClick = { showClearConfirmation = true },
                )
            }
        }
    }
    if (showClearConfirmation) {
        NukeConfirmDialog(
            title = stringResource(R.string.clear_config_dialog_title),
            message = stringResource(R.string.clear_config_dialog_message),
            confirmText = stringResource(R.string.action_clear),
            onDismiss = { showClearConfirmation = false },
            onConfirm = {
                SettingsConfigActions.clear()
                showClearConfirmation = false
            },
        )
    }
}

@Composable
private fun NukeBooleanPreference(
    key: String,
    title: String,
    description: String,
    imageVector: ImageVector,
    default: Boolean = false,
) {
    var checked by remember(key, default) { mutableStateOf(WePrefs.getBoolOrDef(key, default)) }
    NukePreferenceRow(
        title = title,
        description = description,
        leading = { NukeVectorCategoryIcon(imageVector) },
        trailing = {
            NukeSwitch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    WePrefs.putBool(key, it)
                },
            )
        },
        onClick = {
            checked = !checked
            WePrefs.putBool(key, checked)
        },
    )
}

@Composable
private fun NukeUpdatePage(onBack: (Offset) -> Unit) {
    val activity = LocalComponentActivity.current
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateResult.UpdateAvailable?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var resultSummaryRes by remember { mutableIntStateOf(R.string.nuke_update_not_checked) }
    var availableVersion by remember { mutableStateOf<String?>(null) }

    fun checkForUpdate() {
        if (checking) return
        scope.launch {
            checking = true
            when (val result = AppUpdater.checkForUpdate()) {
                UpdateResult.UpToDate -> {
                    availableVersion = null
                    resultSummaryRes = R.string.update_up_to_date
                }
                is UpdateResult.UpdateAvailable -> {
                    availableVersion = result.info.versionName
                    resultSummaryRes = R.string.nuke_update_available_summary
                    updateInfo = result
                }
                is UpdateResult.Error -> {
                    WeLogger.e("AppUpdater", "failed to check for updates", result.cause)
                    updateError = result.cause.message ?: localizedContext.getString(R.string.error_unknown)
                    availableVersion = null
                    resultSummaryRes = R.string.update_check_failed_title
                }
            }
            checking = false
        }
    }

    NukePageScaffold(title = stringResource(R.string.nuke_update_title), onBack = onBack) {
        item(key = "installed") {
            NukeSettingGroup(title = stringResource(R.string.nuke_update_installed)) {
                NukePreferenceRow(
                    title = BuildConfig.VERSION_NAME,
                    description = stringResource(
                        R.string.nuke_installed_version_details,
                        BuildConfig.VERSION_CODE,
                        formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
                    ),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Label) },
                )
            }
        }
        item(key = "update") {
            NukeSettingGroup(title = stringResource(R.string.settings_section_update)) {
                NukePreferenceRow(
                    title = when {
                        checking -> stringResource(R.string.nuke_update_checking)
                        availableVersion != null -> stringResource(resultSummaryRes, availableVersion!!)
                        else -> stringResource(resultSummaryRes)
                    },
                    description = stringResource(R.string.nuke_update_check_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Update) },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.nuke_update_check_again),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Update) },
                    trailing = { NukeCountAndChevron(text = null) },
                    enabled = !checking,
                    onClick = { checkForUpdate() },
                )
            }
        }
    }
    updateInfo?.let { result ->
        NukeConfirmDialog(
            title = stringResource(R.string.update_available_title),
            message = stringResource(
                R.string.update_available_message,
                BuildConfig.VERSION_NAME,
                result.info.versionName,
            ),
            confirmText = stringResource(R.string.nuke_download_install),
            onDismiss = { updateInfo = null },
            onConfirm = {
                updateInfo = null
                activity.lifecycleScope.launch {
                    runCatching { AppUpdater.downloadAndInstall(activity, result.info) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            WeLogger.e("AppUpdater", "failed to download update", error)
                            updateError = localizedContext.getString(
                                R.string.update_download_failed,
                                error.message ?: localizedContext.getString(R.string.error_unknown),
                            )
                        }
                }
            },
        )
    }
    updateError?.let { message ->
        NukeMessageDialog(
            title = stringResource(R.string.update_check_failed_title),
            message = stringResource(R.string.update_error_message, message),
            onDismiss = { updateError = null },
        )
    }
}

@Composable
private fun NukeAboutPage(
    onBack: (Offset) -> Unit,
    onOpenDestination: (NukeDestination, Offset) -> Unit,
) {
    val context = LocalContext.current
    val contributors by produceState(
        initialValue = NukeGitHubContributors.fallbackContributors,
    ) {
        value = NukeGitHubContributors.fetchOrFallback()
    }
    NukePageScaffold(title = stringResource(R.string.nuke_about_title), onBack = onBack) {
        item(key = "avatar") { NukeAboutIcon() }
        item(key = "project") {
            NukeSettingGroup(title = stringResource(R.string.nuke_about_project)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NukeText(
                        text = stringResource(R.string.nuke_about_description_one),
                        color = NukeTheme.colors.textSecondary,
                        fontSize = 13,
                        lineHeight = 19,
                    )
                    NukeText(
                        text = stringResource(R.string.nuke_about_description_two),
                        color = NukeTheme.colors.textSecondary,
                        fontSize = 13,
                        lineHeight = 19,
                    )
                }
            }
        }
        item(key = "developers") {
            NukeSettingGroup(title = stringResource(R.string.nuke_about_developers)) {
                contributors.forEachIndexed { index, contributor ->
                    NukeDeveloperRow(
                        contributor = contributor,
                        onClick = {
                            contributor.profileUrl.toUri().openInSystem(context, true)
                        },
                    )
                    if (index < contributors.lastIndex) NukeDivider()
                }
            }
        }
        item(key = "links") {
            NukeSettingGroup(title = stringResource(R.string.nuke_about_links)) {
                NukePreferenceRow(
                    title = stringResource(R.string.brand_github),
                    description = "Ujhhgtg/WeKit",
                    leading = { NukeVectorCategoryIcon(GitHubIcon) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(context, true)
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.brand_telegram),
                    description = "https://t.me/+7j5dJ6g16B43OWVl",
                    leading = { NukeVectorCategoryIcon(TelegramIcon) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        "https://t.me/+7j5dJ6g16B43OWVl".toUri().openInSystem(context, true)
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = stringResource(R.string.settings_open_source_licenses_title),
                    description = stringResource(R.string.settings_open_source_licenses_summary),
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.License) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = { origin -> onOpenDestination(NukeDestination.Licenses, origin) },
                )
            }
        }
    }
}

@Composable
private fun NukeDeveloperRow(
    contributor: NukeGitHubContributor,
    onClick: () -> Unit,
) {
    NukePreferenceRow(
        title = contributor.login,
        description = contributor.contributionCount?.let {
            stringResource(R.string.nuke_github_contributions, it)
        } ?: stringResource(R.string.nuke_wekit_developer),
        leading = { NukeDeveloperAvatar(contributor) },
        trailing = { NukeCountAndChevron(text = null) },
        onClick = { onClick() },
    )
}

@Composable
private fun NukeDeveloperAvatar(contributor: NukeGitHubContributor) {
    Box(
        Modifier
            .size(34.dp)
            .clip(NukeSquircleShape(11.dp))
            .background(NukeTheme.colors.accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        // Keep a Nuke-native placeholder visible while Coil loads or if the avatar fails.
        NukeGlyph(
            kind = NukeGlyphKind.Person,
            color = NukeTheme.colors.accent,
            modifier = Modifier.size(18.dp),
        )
        AsyncImage(
            model = contributor.avatarUrl,
            contentDescription = stringResource(R.string.nuke_github_avatar, contributor.login),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun NukeAboutIcon() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(94.dp)
                .clip(CircleShape)
                .background(NukeTheme.colors.accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(86.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun NukeLicensesPage(onBack: (Offset) -> Unit) {
    val resources = LocalResources.current
    val libraries = remember(resources) {
        resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader()
            .use { Libs.Builder().withJson(it.readText()).build().libraries }
            .sortedWith(compareBy(::nukeLibraryAuthor, Library::name))
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, libraries) {
        if (query.isBlank()) libraries else libraries.filter { library ->
            library.name.contains(query, ignoreCase = true) ||
                nukeLibraryAuthor(library).contains(query, ignoreCase = true) ||
                library.description?.contains(query, ignoreCase = true) == true
        }
    }
    val libraryGroups = remember(filtered) {
        filtered
            .groupBy(::nukeLibraryAuthor)
            .toSortedMap()
            .map { (author, authorLibraries) ->
                NukeLibraryGroup(
                    author = author,
                    libraries = authorLibraries.sortedBy(Library::name),
                )
            }
    }

    NukePageScaffold(title = stringResource(R.string.settings_open_source_licenses_title), onBack = onBack) {
        item(key = "search") {
            NukeSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.licenses_search_hint),
            )
        }
        item(key = "count") {
            NukeText(
                text = if (query.isBlank()) {
                    stringResource(R.string.licenses_count, libraries.size)
                } else {
                    stringResource(R.string.licenses_filtered_count, filtered.size, libraries.size)
                },
                color = NukeTheme.colors.textSecondary,
                fontSize = 12,
                lineHeight = 16,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                NukeSettingGroup(title = null) {
                    NukeText(
                        text = stringResource(R.string.licenses_no_results, query),
                        color = NukeTheme.colors.textSecondary,
                        fontSize = 13,
                        lineHeight = 18,
                        modifier = Modifier.padding(18.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(libraryGroups, key = NukeLibraryGroup::author) { group ->
                NukeLibraryGroup(group)
            }
        }
    }
}

@Composable
private fun NukeLibraryGroup(group: NukeLibraryGroup) {
    NukeSettingGroup(
        title = if (group.author == UNKNOWN_LIBRARY_AUTHOR_KEY) {
            stringResource(R.string.licenses_unknown_author)
        } else group.author,
    ) {
        group.libraries.forEachIndexed { index, library ->
            NukeLibraryRow(library)
            if (index < group.libraries.lastIndex) NukeDivider()
        }
    }
}

@Composable
private fun NukeLibraryRow(library: Library) {
    val licenseNames = library.licenses.joinToString("、") { it.name }
    val versionLabel = library.artifactVersion?.let {
        stringResource(R.string.licenses_version, it)
    }
    val licensesLabel = licenseNames.takeIf(String::isNotBlank)?.let {
        stringResource(R.string.licenses_license_names, it)
    }
    NukePreferenceRow(
        title = library.name,
        description = buildString {
            versionLabel?.let(::append)
            library.description?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
            if (licensesLabel != null) {
                if (isNotEmpty()) append('\n')
                append(licensesLabel)
            }
        }.ifBlank { null },
    )
}

private data class NukeLibraryGroup(
    val author: String,
    val libraries: List<Library>,
)

private const val UNKNOWN_LIBRARY_AUTHOR_KEY = "\u0000unknown-author"

private fun nukeLibraryAuthor(library: Library): String =
    library.developers.firstOrNull()?.name?.takeIf(String::isNotBlank)
        ?: library.organization?.name?.takeIf(String::isNotBlank)
        ?: UNKNOWN_LIBRARY_AUTHOR_KEY

@Composable
private fun NukeConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    NukeDialogSurface(
        title = title,
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton(
                stringResource(R.string.dialog_cancel),
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
            NukeButton(
                confirmText,
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = {
                    onConfirm()
                    dismiss()
                },
            )
        },
    ) {
        NukeText(
            text = message,
            color = NukeTheme.colors.textSecondary,
            fontSize = 13,
            lineHeight = 19,
        )
    }
}

@Composable
private fun NukeMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    NukeDialogSurface(
        title = title,
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton(
                stringResource(R.string.dialog_close),
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = dismiss,
            )
        },
    ) {
        NukeText(
            text = message,
            color = NukeTheme.colors.textSecondary,
            fontSize = 13,
            lineHeight = 19,
        )
    }
}
