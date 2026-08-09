package dev.ujhhgtg.wekit.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Fiber_new
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.features.core.NewFeatures
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.preferences.WePrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale


// ---------------------------------------------------------------------------
//  Shared switch state
// ---------------------------------------------------------------------------

/**
 * Bumped on every feature toggle. Rows key their MMKV read on it, so the search list and a
 * category screen — which the navigator keeps composed at the same time — can never drift apart,
 * nor away from what MMKV actually holds.
 */
private var featureToggleRevision by mutableIntStateOf(0)

/**
 * Current switch state of [item], read straight from MMKV using the feature's own
 * [SwitchFeature.defaultEnabled] — the very default `SwitchFeature.startup()` applies.
 */
@Composable
private fun featureChecked(item: BaseFeature): Boolean {
    val revision = featureToggleRevision
    return remember(item.technicalId, revision) {
        WePrefs.getBoolOrDef(item.technicalId, (item as? SwitchFeature)?.defaultEnabled == true)
    }
}

// ---------------------------------------------------------------------------
//  Page 1 — Features (search bar + category list)
// ---------------------------------------------------------------------------

@Composable
fun FeaturesPager(onOpenCategory: (String) -> Unit) {
    val context = LocalContext.current
    val resolvedLocale = WeKitLocaleController.resolvedLocale
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val searching = query.isNotBlank()

    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val searchableItems = remember(resolvedLocale) {
        FeaturesProvider.ALL_HOOK_ITEMS
            .filterIsInstance<SwitchFeature>()
            .sortedWith { first, second ->
                featureNameCollator.compare(first.localizedName(context), second.localizedName(context))
            }
    }
    val filteredItems = remember(query, resolvedLocale) {
        if (!searching) emptyList()
        else searchableItems.filter {
            it.localizedName(context).contains(query, ignoreCase = true) ||
                it.localizedDescription(context).contains(query, ignoreCase = true)
        }
    }

    // A back press while searching clears the query first (after the IME's own
    // back has dismissed the keyboard) rather than exiting the module settings.
    BackHandler(enabled = searching) { queryState.clearText() }

    MiuixListScaffold(title = "功能") {
        item {
            TextField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = "搜索功能",
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { queryState.clearText() }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "Clear query",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                },
            )
        }

        if (searching) {
            // Search results replace the category list while a query is active
            if (filteredItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "未匹配到任何相关功能",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                itemsIndexed(filteredItems, key = { _, item -> item.technicalId }) { index, item ->
                    Column(
                        modifier = Modifier
                            .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                            .groupedCardItem(index, filteredItems.size),
                    ) {
                        FeatureRow(
                            item = item,
                            checked = featureChecked(item),
                            onCheckedChange = { featureToggleRevision++ },
                        )
                    }
                }
            }
        } else {
            // Its own card, so it reads as separate from the real categories below.
            if (NEW_FEATURE_ITEMS.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                    ) {
                        ArrowPreference(
                            title = stringResource(featureCategoryTitleRes(NEW_FEATURES_CATEGORY)),
                            summary = "最近 ${NewFeatures.WINDOW_DAYS} 天新增 ${NEW_FEATURE_ITEMS.size} 项",
                            startAction = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Fiber_new,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                            onClick = { onOpenCategory(NEW_FEATURES_CATEGORY) },
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                ) {
                    FEATURE_CATEGORIES.forEach { category ->
                        ArrowPreference(
                            title = stringResource(category.titleRes),
                            startAction = {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                            onClick = { onOpenCategory(category.id) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Category detail (replaces CategorySettingsScreen)
// ---------------------------------------------------------------------------

@Composable
fun CategoryDetailScreen(categoryId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val resolvedLocale = WeKitLocaleController.resolvedLocale
    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val items = remember(categoryId, resolvedLocale) {
        if (categoryId == NEW_FEATURES_CATEGORY) NEW_FEATURE_ITEMS
        else FeaturesProvider.ALL_HOOK_ITEMS
            .filter { categoryId in it.categoryIds }
            .sortedWith { first, second ->
                featureNameCollator.compare(first.localizedName(context), second.localizedName(context))
            }
    }

    MiuixListScaffold(
        title = stringResource(featureCategoryTitleRes(categoryId)),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Arrow_back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        if (items.isEmpty()) return@MiuixListScaffold

        itemsIndexed(items, key = { _, item -> item.technicalId }) { index, item ->
            Column(
                modifier = Modifier
                    .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                    .groupedCardItem(index, items.size),
            ) {
                FeatureRow(
                    item = item,
                    checked = featureChecked(item),
                    onCheckedChange = { featureToggleRevision++ },
                )
                item.Ui()
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
