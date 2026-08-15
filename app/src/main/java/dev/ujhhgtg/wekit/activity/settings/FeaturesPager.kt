package dev.ujhhgtg.wekit.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Fiber_new
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.features.core.NewFeatures
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
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
    var query by remember { mutableStateOf("") }
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
    BackHandler(enabled = searching) { query = "" }

    M3ListScaffold(title = stringResource(R.string.nav_features)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = { Text(stringResource(R.string.features_search_hint)) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = stringResource(R.string.features_clear_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            text = stringResource(R.string.features_no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item {
                    SegmentedColumn(modifier = Modifier.padding(top = 12.dp)) {
                        filteredItems.forEach { feature ->
                            item(key = feature.technicalId) {
                                FeatureRow(
                                    item = feature,
                                    checked = featureChecked(feature),
                                    onCheckedChange = { featureToggleRevision++ },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Its own card, so it reads as separate from the real categories below.
            if (NEW_FEATURE_ITEMS.isNotEmpty()) {
                item {
                    SegmentedColumn(modifier = Modifier.padding(top = 12.dp)) {
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Fiber_new,
                                title = stringResource(featureCategoryTitleRes(NEW_FEATURES_CATEGORY)),
                                description = stringResource(
                                    R.string.features_new_summary,
                                    NewFeatures.WINDOW_DAYS,
                                    NEW_FEATURE_ITEMS.size,
                                ),
                                onClick = { onOpenCategory(NEW_FEATURES_CATEGORY) },
                                trailingContent = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                SegmentedColumn(modifier = Modifier.padding(top = 12.dp)) {
                    FEATURE_CATEGORIES.forEach { category ->
                        item(key = category.id) {
                            BaseWidget(
                                icon = category.icon,
                                title = stringResource(category.titleRes),
                                onClick = { onOpenCategory(category.id) },
                                trailingContent = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
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

    M3ListScaffold(
        title = stringResource(featureCategoryTitleRes(categoryId)),
        navigationIcon = { ExpressiveBackButton(onClick = onBack) },
    ) {
        if (items.isEmpty()) return@M3ListScaffold

        item {
            SegmentedColumn(modifier = Modifier.padding(top = 12.dp)) {
                items.forEach { feature ->
                    item(key = feature.technicalId) {
                        FeatureRow(
                            item = feature,
                            checked = featureChecked(feature),
                            onCheckedChange = { featureToggleRevision++ },
                        )
                        feature.Ui()
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
