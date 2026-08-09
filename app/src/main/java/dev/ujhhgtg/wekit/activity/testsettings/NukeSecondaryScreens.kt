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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val context = LocalContext.current
    val resolvedLocale = WeKitLocaleController.resolvedLocale
    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val features = remember(resolvedLocale) {
        FeaturesProvider.ALL_HOOK_ITEMS.sortedWith { first, second ->
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
        title = "模块设置及调试",
        onBack = onBack,
        // FEATURES rows are individual items; 0 spacing keeps them flush and explicit spacer
        // items below restore the 12dp rhythm between sections.
        itemSpacing = 0.dp,
    ) {
        item(key = "actions") {
            NukeSettingGroup(title = "操作") {
                NukePreferenceRow(
                    title = "重启宿主",
                    description = "重新启动当前微信进程。",
                    leading = { NukeCategoryIcon(NukeGlyphKind.Restart) },
                    onClick = { restartHost() },
                )
            }
        }
        item(key = "gap_overview") { Spacer(Modifier.height(12.dp)) }
        item(key = "overview") {
            NukeSettingGroup(title = "状态概览") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NukeStatusPill("正常 ${features.size}", Color(0xFF16A34A))
                }
            }
        }
        item(key = "gap_features") { Spacer(Modifier.height(12.dp)) }
        item(key = "features_title") {
            // The section title always bounces when it appears (including the second time after
            // scrolling back to the top); only the rows are gated to first-appearance motion.
            NukeSettingGroupTitle(title = "FEATURES")
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
    val context = LocalContext.current
    NukePreferenceRow(
        title = feature.localizedName(context),
        description = feature.categoryIds
            .joinToString(" / ") { context.getString(featureCategoryTitleRes(it)) }
            .ifBlank { "模块基础能力" },
        leading = { NukeCategoryIcon(NukeGlyphKind.CheckCircle) },
        trailing = { NukeStatusPill("正常", Color(0xFF16A34A)) },
        onClick = { onClick() },
    )
}

@Composable
private fun NukeFeatureStatusDialog(feature: BaseFeature, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val kind = when (feature) {
        is ClickableFeature -> "可配置功能"
        is SwitchFeature -> "开关功能"
        else -> "模块基础能力"
    }
    NukeMessageDialog(
        title = feature.localizedName(context),
        message = buildString {
            appendLine("状态：正常")
            appendLine("类型：$kind")
            val categories = feature.categoryIds
                .joinToString(" / ") { context.getString(featureCategoryTitleRes(it)) }
                .ifBlank { "未分类" }
            appendLine("分类：$categories")
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
    var showClearConfirmation by remember { mutableStateOf(false) }

    NukePageScaffold(title = stringResource(R.string.settings_general_title), onBack = onBack) {
        item(key = "language") {
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
            NukeSettingGroup(title = "调试") {
                NukeBooleanPreference(
                    key = Preferences.VERBOSE_LOG,
                    title = "详细日志",
                    description = "输出高频日志（这可能会暴露你的隐私信息）",
                    imageVector = MaterialSymbols.Outlined.Frame_bug,
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.SHOW_STARTUP_TOAST,
                    title = "显示加载完成 Toast",
                    description = "全部功能加载完成后显示 Toast 提示",
                    imageVector = MaterialSymbols.Outlined.Notifications,
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.MATCH_GENERIC_WXID_EXP,
                    title = "清理消息内容微信 ID 前缀时允许非标准 ID",
                    description = "允许处理不带 wxid_ 前缀的微信 ID，可能导致误伤消息原始内容（实验性）",
                    imageVector = MaterialSymbols.Outlined.Rule_settings,
                    default = true,
                )
            }
        }
        item(key = "compatibility") {
            NukeSettingGroup(title = "兼容") {
                NukeBooleanPreference(
                    key = Preferences.NO_DEX_RESOLVE,
                    title = "禁用版本适配",
                    description = "不弹出 DEX 查找对话框，未适配功能将不会被加载",
                    imageVector = MaterialSymbols.Outlined.Block,
                )
                NukeDivider()
                NukePreferenceRow(
                    title = "重置适配信息",
                    description = "清除 DEX 缓存，等待下次启动时重新适配",
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Build_circle) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = { ResetDexCache.onClick(activity) },
                )
                NukeDivider()
                NukeBooleanPreference(
                    key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                    title = "宿主热更新时重新适配",
                    description = "宿主热更新时是否重置 DEX 缓存，可能导致频繁重新适配（实验性）",
                    imageVector = MaterialSymbols.Outlined.Auto_delete,
                )
            }
        }
        item(key = "configuration") {
            NukeSettingGroup(title = "配置") {
                NukePreferenceRow(
                    title = "导出配置",
                    description = "将模块配置导出为 JSON",
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Upload) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = { SettingsConfigActions.export(context) },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = "导入配置",
                    description = "从 JSON 导入模块配置，并覆盖其中已有的配置项",
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Download) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = { SettingsConfigActions.importFromDocument(context) },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = "清除配置",
                    description = "清除全部模块配置（此操作不可逆）",
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
            title = "清除模块配置",
            message = "确定清除全部模块配置吗？此操作不可逆。",
            confirmText = "清除",
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
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateResult.UpdateAvailable?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var resultSummary by remember { mutableStateOf("尚未检查更新") }

    fun checkForUpdate() {
        if (checking) return
        scope.launch {
            checking = true
            when (val result = AppUpdater.checkForUpdate()) {
                UpdateResult.UpToDate -> resultSummary = "已是最新版本"
                is UpdateResult.UpdateAvailable -> {
                    resultSummary = "发现新版本 ${result.info.versionName}"
                    updateInfo = result
                }
                is UpdateResult.Error -> {
                    WeLogger.e("AppUpdater", "failed to check for updates", result.cause)
                    updateError = result.cause.message ?: "未知错误"
                    resultSummary = "检查更新失败"
                }
            }
            checking = false
        }
    }

    NukePageScaffold(title = "检测更新", onBack = onBack) {
        item(key = "installed") {
            NukeSettingGroup(title = "已安装") {
                NukePreferenceRow(
                    title = BuildConfig.VERSION_NAME,
                    description = "版本代码 ${BuildConfig.VERSION_CODE}\n构建时间 ${formatEpoch(BuildConfig.BUILD_TIMESTAMP, true)}",
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Label) },
                )
            }
        }
        item(key = "update") {
            NukeSettingGroup(title = "更新") {
                NukePreferenceRow(
                    title = if (checking) "正在检查更新" else resultSummary,
                    description = "检查 WeKit 是否有可用的新版本。",
                    leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Update) },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = "重新检测",
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
            title = "检测到新版本",
            message = "当前版本：${BuildConfig.VERSION_NAME}\n新版本：${result.info.versionName}\n是否下载并安装？",
            confirmText = "下载并安装",
            onDismiss = { updateInfo = null },
            onConfirm = {
                updateInfo = null
                activity.lifecycleScope.launch {
                    runCatching { AppUpdater.downloadAndInstall(activity, result.info) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            WeLogger.e("AppUpdater", "failed to download update", error)
                            updateError = "下载更新失败：${error.message ?: "未知错误"}"
                        }
                }
            },
        )
    }
    updateError?.let { message ->
        NukeMessageDialog(
            title = "检查更新失败",
            message = "错误信息：$message",
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
    NukePageScaffold(title = "关于模块", onBack = onBack) {
        item(key = "avatar") { NukeAboutIcon() }
        item(key = "project") {
            NukeSettingGroup(title = "项目") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NukeText(
                        text = "WeKit 是一个免费的开源 Xposed 模块。",
                        color = NukeTheme.colors.textSecondary,
                        fontSize = 13,
                        lineHeight = 19,
                    )
                    NukeText(
                        text = "它为微信提供可选的功能增强与界面优化。",
                        color = NukeTheme.colors.textSecondary,
                        fontSize = 13,
                        lineHeight = 19,
                    )
                }
            }
        }
        item(key = "developers") {
            NukeSettingGroup(title = "开发者") {
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
            NukeSettingGroup(title = "链接") {
                NukePreferenceRow(
                    title = "GitHub",
                    description = "Ujhhgtg/WeKit",
                    leading = { NukeVectorCategoryIcon(GitHubIcon) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(context, true)
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = "Telegram",
                    description = "https://t.me/+7j5dJ6g16B43OWVl",
                    leading = { NukeVectorCategoryIcon(TelegramIcon) },
                    trailing = { NukeCountAndChevron(text = null) },
                    onClick = {
                        "https://t.me/+7j5dJ6g16B43OWVl".toUri().openInSystem(context, true)
                    },
                )
                NukeDivider()
                NukePreferenceRow(
                    title = "开放源代码许可",
                    description = "本项目使用的开放源代码库许可",
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
        description = contributor.contributionCount?.let { "GitHub 贡献 $it 次" } ?: "WeKit 开发者",
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
            contentDescription = "${contributor.login} 的 GitHub 头像",
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
                contentDescription = "WeKit",
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

    NukePageScaffold(title = "开放源代码许可", onBack = onBack) {
        item(key = "search") {
            NukeSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索库",
            )
        }
        item(key = "count") {
            NukeText(
                text = if (query.isBlank()) "${libraries.size} 个库" else "${filtered.size}/${libraries.size} 个库",
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
                        text = "找不到「$query」的结果",
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
    NukeSettingGroup(title = group.author) {
        group.libraries.forEachIndexed { index, library ->
            NukeLibraryRow(library)
            if (index < group.libraries.lastIndex) NukeDivider()
        }
    }
}

@Composable
private fun NukeLibraryRow(library: Library) {
    val licenseNames = library.licenses.joinToString("、") { it.name }
    NukePreferenceRow(
        title = library.name,
        description = buildString {
            library.artifactVersion?.let { append("版本 $it") }
            library.description?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
            if (licenseNames.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("许可：$licenseNames")
            }
        }.ifBlank { null },
    )
}

private data class NukeLibraryGroup(
    val author: String,
    val libraries: List<Library>,
)

private fun nukeLibraryAuthor(library: Library): String =
    library.developers.firstOrNull()?.name?.takeIf(String::isNotBlank)
        ?: library.organization?.name?.takeIf(String::isNotBlank)
        ?: "未知作者"

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
            NukeButton("取消", modifier = Modifier.weight(1f), onClick = dismiss)
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
                "关闭",
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
