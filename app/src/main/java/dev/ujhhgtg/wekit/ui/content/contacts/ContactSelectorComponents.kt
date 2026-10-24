package dev.ujhhgtg.wekit.ui.content.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Compare_arrows
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Swap_vert
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.utils.android.showToast

/** Bulk selection actions; each receives the currently displayed contacts. */
class ContactSelectionActions(
    val onSelectAll: (List<IWeContact>) -> Unit,
    val onDeselectAll: (List<IWeContact>) -> Unit,
    val onInvert: (List<IWeContact>) -> Unit,
)

/** Multi-select actions over a [Set] of wxIds held by the caller. */
fun multiSelectActions(selected: () -> Set<String>, onChange: (Set<String>) -> Unit) =
    ContactSelectionActions(
        onSelectAll = { displayed -> onChange(selected() + displayed.map { it.wxId }) },
        onDeselectAll = { displayed -> onChange(selected() - displayed.map { it.wxId }.toSet()) },
        onInvert = { displayed ->
            val current = selected()
            val displayedIds = displayed.map { it.wxId }.toSet()
            onChange((current - displayedIds) + (displayedIds - current))
        },
    )

@Composable
fun ContactSearchField(state: ContactSelectorState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { state.searchQuery = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.contact_search_hint)) },
            leadingIcon = {
                Icon(
                    MaterialSymbols.Outlined.Search,
                    contentDescription =
                        stringResource(R.string.contact_search_content_description),
                )
            },
            singleLine = true,
        )
        IconButton(onClick = { state.filtersExpanded = !state.filtersExpanded }) {
            Icon(
                imageVector =
                    if (state.filtersExpanded) {
                        MaterialSymbols.Outlined.Expand_less
                    } else {
                        MaterialSymbols.Outlined.Expand_more
                    },
                contentDescription =
                    stringResource(
                        if (state.filtersExpanded) {
                            R.string.contact_filters_collapse
                        } else {
                            R.string.contact_filters_expand
                        },
                    ),
            )
        }
    }
}

/**
 * The collapsible chip rows: contact type, label/aggregation/grouping options, sort, and the
 * optional bulk [selectionActions]. Rows only appear when the loaded data makes them useful.
 */
@Composable
fun ContactFilterPanel(
    state: ContactSelectorState,
    modifier: Modifier = Modifier,
    selectionActions: ContactSelectionActions? = null,
    horizontalPadding: Dp = 4.dp,
) {
    AnimatedVisibility(
        visible = state.filtersExpanded,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (state.showTypeFilters) {
                ChipRow(horizontalPadding) {
                    items(state.availableTypes) { type ->
                        val displayName = stringResource(type.displayNameRes)
                        ContactFilterChip(
                            selected = state.typeFilter == type,
                            onClick = { state.typeFilter = type },
                            label =
                                stringResource(
                                    R.string.contact_filter_with_count,
                                    displayName,
                                    state.typeCounts[type] ?: 0,
                                ),
                            icon = type.icon,
                        )
                    }
                }
            }

            if (state.availableFilterModes.isNotEmpty()) {
                ChipRow(horizontalPadding) {
                    item {
                        val context = LocalContext.current
                        val localizedContext = LocalWeKitLocalizedContext.current
                        ContactFilterChip(
                            selected = true,
                            onClick = {
                                if (!state.cycleFilterMode()) {
                                    showToast(
                                        context,
                                        localizedContext.getString(
                                            R.string.contact_filter_mode_enable_more,
                                        ),
                                    )
                                }
                            },
                            label = stringResource(state.filterMode.nameRes),
                            icon = state.filterMode.icon,
                        )
                    }
                    item {
                        ContactFilterChip(
                            selected = state.selectedOptionId == null,
                            onClick = { state.selectedOptionId = null },
                            label = stringResource(R.string.contact_label_filter_all),
                        )
                    }
                    items(state.availableOptions) { option ->
                        val selected = state.selectedOptionId == option.id
                        ContactFilterChip(
                            selected = selected,
                            onClick = {
                                state.selectedOptionId = if (selected) null else option.id
                            },
                            label =
                                stringResource(
                                    R.string.contact_label_with_count,
                                    option.name,
                                    state.optionCounts[option.id] ?: 0,
                                ),
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = horizontalPadding)
                        .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContactSortMode.entries.forEach { mode ->
                    ContactFilterChip(
                        selected = state.sortMode == mode,
                        enabled = !state.sortLoading,
                        onClick = { state.selectSortMode(mode) },
                        label = stringResource(mode.displayNameRes(state.sortReversed)),
                        icon = mode.icon,
                        loading = state.sortLoading && mode == ContactSortMode.LAST_MESSAGE_TIME,
                    )
                }
                FilterChip(
                    selected = state.sortReversed,
                    enabled = !state.sortLoading,
                    onClick = { state.sortReversed = !state.sortReversed },
                    label = {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Swap_vert,
                            contentDescription =
                                stringResource(R.string.contact_sort_direction_toggle),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            if (selectionActions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = horizontalPadding)
                            .padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactFilterChip(
                        selected = false,
                        onClick = { selectionActions.onSelectAll(state.displayedContacts) },
                        label = stringResource(R.string.contact_select_all),
                        icon = MaterialSymbols.Outlined.Select_all,
                    )
                    ContactFilterChip(
                        selected = false,
                        onClick = { selectionActions.onDeselectAll(state.displayedContacts) },
                        label = stringResource(R.string.contact_deselect_all),
                        icon = MaterialSymbols.Outlined.Deselect,
                    )
                    ContactFilterChip(
                        selected = false,
                        onClick = { selectionActions.onInvert(state.displayedContacts) },
                        label = stringResource(R.string.contact_invert_selection),
                        icon = MaterialSymbols.Outlined.Compare_arrows,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(horizontalPadding: Dp, content: LazyListScope.() -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ContactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon =
            when {
                loading -> {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }

                icon != null -> {
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                else -> {
                    null
                }
            },
    )
}

@Composable
fun ContactAvatar(model: Any?, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.1875f)),
        imageLoader = GlobalImageLoader,
    )
}

@Composable
fun contactSectionTitle(key: ContactSectionKey): String =
    when (key) {
        ContactSectionKey.Selected -> stringResource(R.string.contact_selected_section)
        ContactSectionKey.Newest -> stringResource(R.string.contact_sort_newest_first)
        ContactSectionKey.Oldest -> stringResource(R.string.contact_sort_oldest_first)
        is ContactSectionKey.Initial -> key.letter
    }

/**
 * Letter index for alphabetical sorting (nothing otherwise). [onScrollTo] receives the flat index
 * of the target section's header (see [sectionHeaderIndices]).
 */
@Composable
fun AlphabetIndexBar(
    state: ContactSelectorState,
    sections: List<ContactSection>,
    onScrollTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.sortMode != ContactSortMode.ALPHABETICAL) return
    val headerIndices = remember(sections) { sectionHeaderIndices(sections) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        alphabetIndexKeys(state.sortReversed).forEach { key ->
            Text(
                text =
                    if (key == ContactSectionKey.Selected) {
                        "✓"
                    } else {
                        (key as ContactSectionKey.Initial).letter
                    },
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (key in headerIndices) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                modifier =
                    Modifier.clickable {
                            indexBarTarget(key, headerIndices, sections, state.sortReversed)
                                ?.let(onScrollTo)
                        }
                        .padding(vertical = 2.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun ContactNoMatches(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.contact_no_matches),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
