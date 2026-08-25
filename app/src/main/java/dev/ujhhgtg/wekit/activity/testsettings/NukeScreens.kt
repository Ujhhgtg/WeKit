package dev.ujhhgtg.wekit.activity.testsettings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Fiber_new
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Shield
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Volunteer_activism
import dev.ujhhgtg.wekit.activity.settings.FEATURE_CATEGORIES
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.settings.ENABLED_FEATURES_CATEGORY
import dev.ujhhgtg.wekit.activity.settings.FeatureCategoryState
import dev.ujhhgtg.wekit.activity.settings.featureCategoryTitleRes
import dev.ujhhgtg.wekit.activity.settings.LocalComponentActivity
import dev.ujhhgtg.wekit.activity.settings.NEW_FEATURE_ITEMS
import dev.ujhhgtg.wekit.activity.settings.NEW_FEATURES_CATEGORY
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.SelfProfileField
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.core.featureCategoryComparator
import dev.ujhhgtg.wekit.features.items.system.SafeMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCategoryIcon
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCountAndChevron
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDivider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeEmptyState
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyph
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyphKind
import dev.ujhhgtg.wekit.ui.content.nukex.NukePageScaffold
import dev.ujhhgtg.wekit.ui.content.nukex.NukePreferenceRow
import dev.ujhhgtg.wekit.ui.content.nukex.NukeRevealStackNavigator
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSearchField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSettingGroup
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSettingGroupTitle
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSquircleShape
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSwitch
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTheme
import dev.ujhhgtg.wekit.ui.content.nukex.NukeVectorCategoryIcon
import dev.ujhhgtg.wekit.ui.content.nukex.nukeGroupedCardItem
import dev.ujhhgtg.wekit.ui.content.nukex.rememberNukeRevealStackState
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.openInSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import java.text.Collator
import java.util.Locale

internal sealed interface NukeDestination {
    data class Category(val id: String) : NukeDestination
    data object ModuleDebug : NukeDestination
    data object Update : NukeDestination
    data object GeneralSettings : NukeDestination
    data object Appearance : NukeDestination
    data object About : NukeDestination
    data object Licenses : NukeDestination
}

private data class NukeRootEntry(
    val title: String,
    val glyph: NukeGlyphKind? = null,
    val imageVector: ImageVector? = null,
    val count: Int? = null,
    val destination: NukeDestination? = null,
    val action: (() -> Unit)? = null,
)

@Composable
internal fun NukeSettingsRoot() {
    val context = LocalWeKitLocalizedContext.current
    val resolvedLocale = WeKitLocaleController.resolvedLocale
    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    var query by rememberSaveable { mutableStateOf("") }
    val featureItems = remember(resolvedLocale) {
        FeaturesProvider.ALL_FEATURES
            .filterIsInstance<SwitchFeature>()
            .sortedWith { first, second ->
                featureNameCollator.compare(first.localizedName(context), second.localizedName(context))
            }
    }
    val navigator = rememberNukeRevealStackState<NukeDestination>()

    PredictiveBackHandler(enabled = navigator.canPop) { events ->
        navigator.predictivePop(
            events = events,
            optimizeExitOrigin = ThemeSettings.nukePageExitOptimization,
        )
    }

    // Miuix-style back chain while searching: the IME consumes the first back (closing the
    // keyboard), the next back clears the query and drops the field's focus, and only then does
    // back exit the page. Disabled while a destination is pushed so back keeps popping pages.
    BackHandler(enabled = query.isNotBlank() && !navigator.canPop) {
        query = ""
    }

    NukeRevealStackNavigator(
        state = navigator,
        base = {
            NukeHomePage(
                query = query,
                onQueryChange = { query = it },
                featureItems = featureItems,
                onOpenDestination = { destination, origin -> navigator.push(destination, origin) },
            )
        },
    ) { destination ->
        NukeDestinationPage(
            destination = destination,
            featureItems = featureItems,
            onBack = { origin ->
                navigator.pop(
                    from = origin,
                    optimizeExitOrigin = ThemeSettings.nukePageExitOptimization,
                )
            },
            onOpenDestination = { child, origin -> navigator.push(child, origin) },
        )
    }
}

@Composable
private fun NukeHomePage(
    query: String,
    onQueryChange: (String) -> Unit,
    featureItems: List<SwitchFeature>,
    onOpenDestination: (NukeDestination, Offset) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val localizedContext = LocalWeKitLocalizedContext.current
    val activity = LocalComponentActivity.current
    val revision = FeatureCategoryState.revision
    val enabledItems = remember(revision) { FeatureCategoryState.enabledItems() }
    // Rows only animate on the first appearance of a search session; rows revealed later by
    // scrolling (second-time appearance) stay static. Reset per blank -> non-blank transition.
    var searchEntranceEnabled by remember(query.isBlank()) { mutableStateOf(true) }
    LaunchedEffect(query.isBlank()) {
        searchEntranceEnabled = false
    }
    val featureEntries = buildList {
        add(
            NukeRootEntry(
                title = localizedContext.getString(featureCategoryTitleRes(NEW_FEATURES_CATEGORY)),
                imageVector = MaterialSymbols.Outlined.Fiber_new,
                count = NEW_FEATURE_ITEMS.count { it is SwitchFeature },
                destination = NukeDestination.Category(NEW_FEATURES_CATEGORY),
            )
        )
        add(
            NukeRootEntry(
                title = localizedContext.getString(featureCategoryTitleRes(ENABLED_FEATURES_CATEGORY)),
                imageVector = MaterialSymbols.Outlined.Check_circle,
                count = enabledItems.size,
                destination = NukeDestination.Category(ENABLED_FEATURES_CATEGORY),
            )
        )
        FEATURE_CATEGORIES.forEach { category ->
            add(
                NukeRootEntry(
                    title = localizedContext.getString(category.titleRes),
                    imageVector = category.icon,
                    count = featureItems.count { category.id in it.categoryIds },
                    destination = NukeDestination.Category(category.id),
                )
            )
        }
        add(
            NukeRootEntry(
                title = stringResource(R.string.nuke_module_debug_title),
                imageVector = MaterialSymbols.Outlined.Settings,
                count = FeaturesProvider.ALL_FEATURES.size,
                destination = NukeDestination.ModuleDebug,
            )
        )
    }
    val secondaryEntries = listOf(
        NukeRootEntry(
            stringResource(R.string.nuke_update_title),
            imageVector = MaterialSymbols.Outlined.Update,
            destination = NukeDestination.Update,
        ),
        NukeRootEntry(
            stringResource(R.string.nuke_general_settings_title),
            imageVector = MaterialSymbols.Outlined.Settings,
            destination = NukeDestination.GeneralSettings,
        ),
        NukeRootEntry(
            stringResource(R.string.nuke_appearance_title),
            imageVector = MaterialSymbols.Outlined.Style,
            destination = NukeDestination.Appearance,
        ),
        NukeRootEntry(
            stringResource(R.string.nuke_about_title),
            imageVector = MaterialSymbols.Outlined.Info,
            destination = NukeDestination.About,
        ),
        NukeRootEntry(
            title = stringResource(R.string.nuke_support_us_title),
            imageVector = MaterialSymbols.Outlined.Volunteer_activism,
            action = {
                "https://ifdian.net/a/ujhhgtg".toUri().openInSystem(context, true)
            },
        ),
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(NukeTheme.colors.background),
    ) {
        dev.ujhhgtg.wekit.ui.content.nukex.NukeTopAppBar(
            title = stringResource(R.string.nuke_settings_title)
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp),
            // Search results rows are virtualized as their own items; they must sit flush against
            // each other (and their title) to keep the card look, so spacing is applied manually.
            verticalArrangement = Arrangement.spacedBy(if (query.isBlank()) 12.dp else 0.dp),
        ) {
            item(key = "search") {
                NukeSearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.features_search_hint),
                )
            }
            if (query.isBlank()) {
                item(key = "account") { NukeCurrentAccountCard() }
                item(key = "security") {
                    NukeSettingGroup(title = stringResource(R.string.nuke_section_security)) {
                        SafeModeNukeRow()
                    }
                }
                nukeFeatureCategoryGroups(featureEntries).forEachIndexed { index, entries ->
                    item(key = "feature_group_$index") {
                        NukeRootEntryGroup(
                            title = if (index == 0) stringResource(R.string.nav_features) else null,
                            entries = entries,
                            onOpenDestination = onOpenDestination,
                        )
                    }
                }
                secondaryEntries.chunked(3).forEachIndexed { index, entries ->
                    item(key = "general_group_$index") {
                        NukeRootEntryGroup(
                            title = if (index == 0) stringResource(R.string.nuke_section_general) else null,
                            entries = entries,
                            onOpenDestination = onOpenDestination,
                        )
                    }
                }
            } else {
                NukeFeatureSearchResults(
                    query = query,
                    featureItems = featureItems,
                    activity = activity,
                    localizedContext = localizedContext,
                    animate = searchEntranceEnabled,
                )
            }
            item(key = "tail") {
                // With 0 arrangement spacing in search mode, grow the tail spacer by the missing
                // 12dp so the bottom inset matches the non-search layout.
                Spacer(Modifier.height(if (query.isBlank()) 8.dp else 20.dp))
            }
        }
    }
}

@Composable
private fun SafeModeNukeRow() {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(SafeMode.isEnabled) }

    fun requestToggle(next: Boolean) {
        if (next) {
            SafeMode.showEnableConfirmDialog(context) {
                checked = true
                SafeMode.setEnabled(true)
            }
        } else {
            checked = false
            SafeMode.setEnabled(false)
        }
    }

    NukePreferenceRow(
        title = stringResource(R.string.settings_safe_mode_title),
        description = stringResource(R.string.settings_safe_mode_summary),
        leading = { NukeVectorCategoryIcon(MaterialSymbols.Outlined.Shield) },
        onClick = { requestToggle(!checked) },
        trailing = {
            NukeSwitch(
                checked = checked,
                onCheckedChange = { requestToggle(it) },
            )
        },
    )
}

@Composable
private fun NukeRootEntryGroup(
    title: String?,
    entries: List<NukeRootEntry>,
    onOpenDestination: (NukeDestination, Offset) -> Unit,
) {
    NukeSettingGroup(title = title) {
        entries.forEachIndexed { index, entry ->
            NukePreferenceRow(
                title = entry.title,
                leading = {
                    val imageVector = entry.imageVector
                    if (imageVector != null) {
                        NukeVectorCategoryIcon(imageVector)
                    } else {
                        NukeCategoryIcon(entry.glyph ?: NukeGlyphKind.Info)
                    }
                },
                trailing = {
                    NukeCountAndChevron(text = entry.count?.toString())
                },
                onClick = { origin ->
                    entry.destination?.let { onOpenDestination(it, origin) } ?: entry.action?.invoke()
                },
            )
            if (index < entries.lastIndex) NukeDivider()
        }
    }
}

private data class NukeWechatIdentity(
    val nickname: String,
    val avatarUrl: String,
)

@Composable
private fun NukeCurrentAccountCard() {
    val wxId = remember { WeApi.selfWxId }
    val identity by produceState(initialValue = NukeWechatIdentity("", ""), wxId) {
        value = withContext(Dispatchers.IO) {
            val nickname = if (WeDatabaseApi.isReady) {
                WeDatabaseApi.getSelfProfileField(SelfProfileField.NAME, "")?.toString().orEmpty()
            } else {
                ""
            }
            val avatarUrl = if (WeDatabaseApi.isReady && wxId.isNotEmpty()) {
                WeDatabaseApi.getAvatarUrl(wxId)
            } else {
                ""
            }
            NukeWechatIdentity(nickname, avatarUrl)
        }
    }
    val title = identity.nickname.ifEmpty { wxId.ifEmpty { stringResource(R.string.nuke_wechat_account) } }
    val description = wxId.ifEmpty { stringResource(R.string.nuke_account_unavailable) }

    NukeSettingGroup(title = null) {
        NukePreferenceRow(
            title = title,
            description = description,
            leading = { NukeAccountAvatar(identity.avatarUrl) },
        )
    }
}

@Composable
private fun NukeAccountAvatar(url: String) {
    Box(
        Modifier
            .size(42.dp)
            .clip(NukeSquircleShape(14.dp))
            .background(NukeTheme.colors.accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isBlank()) {
            NukeGlyph(
                kind = NukeGlyphKind.Person,
                color = NukeTheme.colors.accent,
                modifier = Modifier.size(22.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun LazyListScope.NukeFeatureSearchResults(
    query: String,
    featureItems: List<SwitchFeature>,
    activity: androidx.activity.ComponentActivity,
    localizedContext: Context,
    animate: Boolean,
) {
    val normalizedQuery = query.trim().lowercase()
    val matchingItems = featureItems.filter { feature ->
        buildList {
            add(feature.localizedName(localizedContext))
            add(feature.localizedDescription(localizedContext))
            addAll(feature.categoryIds.map { localizedContext.getString(featureCategoryTitleRes(it)) })
        }.any { matchesNukeQuery(it.lowercase(), normalizedQuery) }
    }

    if (matchingItems.isEmpty()) {
        item(key = "search_empty") {
            NukeSettingGroup(
                title = localizedContext.getString(R.string.nuke_search_results),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                NukeEmptyState(
                    title = localizedContext.getString(R.string.nuke_no_matching_results),
                    description = localizedContext.getString(R.string.nuke_try_other_keywords),
                )
            }
        }
    } else {
        item(key = "search_title") {
            NukeSettingGroupTitle(
                title = localizedContext.getString(R.string.nuke_search_results),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        itemsIndexed(matchingItems, key = { _, feature -> feature.technicalId }) { index, feature ->
            Column(
                Modifier
                    .animateItem()
                    .nukeGroupedCardItem(index, matchingItems.size, animate = animate),
            ) {
                NukeFeatureRow(
                    feature = feature,
                    activity = activity,
                )
                if (index < matchingItems.lastIndex) NukeDivider()
            }
        }
    }
}

@Composable
internal fun NukeFeatureCategoryPage(
    categoryId: String,
    featureItems: List<SwitchFeature>,
    onBack: (Offset) -> Unit,
) {
    val localizedContext = LocalWeKitLocalizedContext.current
    val categoryTitle = localizedContext.getString(featureCategoryTitleRes(categoryId))
    val resolvedLocale = WeKitLocaleController.resolvedLocale
    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val revision = FeatureCategoryState.revision
    val items = remember(categoryId, featureItems, revision, resolvedLocale) {
        when (categoryId) {
            NEW_FEATURES_CATEGORY -> NEW_FEATURE_ITEMS.filterIsInstance<SwitchFeature>()
            ENABLED_FEATURES_CATEGORY -> FeatureCategoryState.enabledItems()
            else -> featureItems
                .filter { categoryId in it.categoryIds }
                .sortedWith(
                    featureCategoryComparator { first, second ->
                        featureNameCollator.compare(
                            first.localizedName(localizedContext),
                            second.localizedName(localizedContext),
                        )
                    },
                )
        }
    }
    val activity = LocalComponentActivity.current
    // Rows only animate on the first appearance of the page; rows revealed later by scrolling
    // stay static. The section title keeps bouncing on every appearance.
    var entranceEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        entranceEnabled = false
    }

    NukePageScaffold(
        title = categoryTitle,
        onBack = onBack,
        // Rows are emitted as individual items; 0 spacing keeps them flush inside one card.
        itemSpacing = 0.dp,
    ) {
        if (items.isEmpty()) {
            item(key = "empty") {
                NukeEmptyState(
                    title = stringResource(R.string.nuke_no_features),
                    description = stringResource(R.string.nuke_no_features_in_group),
                )
            }
        } else {
            item(key = "title") {
                NukeSettingGroupTitle(title = categoryTitle)
            }
            itemsIndexed(items, key = { _, feature -> feature.technicalId }) { index, feature ->
                Column(
                    Modifier
                        .animateItem()
                        .nukeGroupedCardItem(index, items.size, animate = entranceEnabled),
                ) {
                    NukeFeatureRow(
                        feature = feature,
                        activity = activity,
                    )
                    if (index < items.lastIndex) NukeDivider()
                }
            }
        }
    }
}

@Composable
internal fun NukeFeatureRow(
    feature: SwitchFeature,
    activity: androidx.activity.ComponentActivity,
) {
    val context = LocalWeKitLocalizedContext.current
    val revision = FeatureCategoryState.revision
    val checked = remember(feature.technicalId, revision) {
        WePrefs.getBoolOrDef(feature.technicalId, feature.defaultEnabled)
    }
    val configurable = feature as? ClickableFeature

    DisposableEffect(feature.technicalId) {
        feature.setToggleCompletionCallback { FeatureCategoryState.notifyToggleChanged() }
        onDispose {}
    }

    fun toggle(requested: Boolean) {
        if (feature.onBeforeToggle(requested, activity)) {
            feature.applyToggle(requested)
            FeatureCategoryState.notifyToggleChanged()
        }
    }

    NukePreferenceRow(
        title = feature.localizedName(context),
        description = feature.localizedDescription(context).takeIf { it.isNotBlank() },
        onClick = if (configurable != null) {
            {
                runCatching { configurable.onClick(activity) }
                    .onFailure {
                        WeLogger.e("NukeSettings", "onClick failed for ${feature.technicalPath}", it)
                    }
            }
        } else {
            { toggle(!checked) }
        },
        trailing = {
            if (configurable != null) {
                NukeGlyph(
                    kind = NukeGlyphKind.Chevron,
                    color = NukeTheme.colors.accent.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp),
                )
                if (!configurable.noSwitchWidget) Spacer(Modifier.width(8.dp))
            }
            if (configurable?.noSwitchWidget != true) {
                NukeSwitch(
                    checked = checked,
                    onCheckedChange = ::toggle,
                )
            }
        },
    )
}

private fun matchesNukeQuery(candidate: String, query: String): Boolean {
    if (query.isBlank() || candidate.contains(query)) return true
    var queryIndex = 0
    candidate.forEach { character ->
        if (queryIndex < query.length && character == query[queryIndex]) queryIndex += 1
    }
    return queryIndex == query.length
}

private fun nukeFeatureCategoryGroups(entries: List<NukeRootEntry>): List<List<NukeRootEntry>> {
    val byCategoryId = buildMap {
        entries.forEach { entry ->
            when (val destination = entry.destination) {
                is NukeDestination.Category -> put(destination.id, entry)
                else -> Unit
            }
        }
    }
    val categoryGroups = listOf(
        listOf(NEW_FEATURES_CATEGORY, ENABLED_FEATURES_CATEGORY),
        listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.PAYMENT),
        listOf(FeatureCategoryIds.MOMENTS, FeatureCategoryIds.SYSTEM_PRIVACY, FeatureCategoryIds.VOIP, FeatureCategoryIds.NOTIFICATIONS),
        listOf(FeatureCategoryIds.BEAUTIFY, FeatureCategoryIds.OFFICIAL_ACCOUNTS, FeatureCategoryIds.MINIAPPS, FeatureCategoryIds.CHANNELS),
        listOf(FeatureCategoryIds.PROFILE, FeatureCategoryIds.DEBUG),
        listOf(FeatureCategoryIds.SCRIPTING_JAVA),
        listOf(FeatureCategoryIds.ENTERTAIN, FeatureCategoryIds.BATCH),
        listOf(FeatureCategoryIds.HOME_SCREEN_MENU, FeatureCategoryIds.CONTACT_DETAILS),
    ).map { ids -> ids.map(byCategoryId::getValue) }.toMutableList()
    categoryGroups += entries.filter { it.destination == NukeDestination.ModuleDebug }
    return categoryGroups
}
