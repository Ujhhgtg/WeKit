package dev.ujhhgtg.wekit.ui.content.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SETTINGS_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.ui.content.m3.SettingsEmptyState
import dev.ujhhgtg.wekit.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import kotlinx.coroutines.launch

/**
 * Full-screen multi-select contact selector for Material 3 activities. Selection is controlled by
 * the caller; [onConfirm] is offered from the app bar. [contacts] is null while still loading.
 */
@Composable
fun ContactsSelectorScreen(
    title: String,
    contacts: List<IWeContact>?,
    selectedWxIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    subtitleProvider: (IWeContact) -> String = { it.wxId },
) {
    ContactSelectorScaffold(
        title = title,
        contacts = contacts,
        onBack = onBack,
        actions = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(stringResource(R.string.contact_confirm_selected_count, selectedWxIds.size))
            }
        },
        selectionKey = selectedWxIds,
        isSelected = { it.wxId in selectedWxIds },
        selectionActions = multiSelectActions({ selectedWxIds }, onSelectionChange),
    ) { contact ->
        val checked = contact.wxId in selectedWxIds
        BaseWidget(
            title = contact.displayName,
            description = subtitleProvider(contact),
            leadingContent = { ContactAvatar(contact.avatarUrl, size = 40.dp) },
            onClick = { onSelectionChange(if (checked) selectedWxIds - contact.wxId else selectedWxIds + contact.wxId) },
            trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        )
    }
}

/**
 * Full-screen contact list where tapping a row picks it immediately (e.g. opens its settings).
 * Contacts for which [isMarked] holds are pinned in the top section; [markedKey] must change
 * whenever [isMarked] does. [contacts] is null while still loading.
 */
@Composable
fun ContactPickerScreen(
    title: String,
    contacts: List<IWeContact>?,
    onBack: () -> Unit,
    onPick: (IWeContact) -> Unit,
    markedKey: Any? = null,
    isMarked: (IWeContact) -> Boolean = { false },
    subtitleProvider: (IWeContact) -> String = { it.wxId },
    trailingIcon: ImageVector = MaterialSymbols.Outlined.Chevron_right,
) {
    ContactSelectorScaffold(
        title = title,
        contacts = contacts,
        onBack = onBack,
        selectionKey = markedKey,
        isSelected = isMarked,
    ) { contact ->
        BaseWidget(
            title = contact.displayName,
            description = subtitleProvider(contact),
            leadingContent = { ContactAvatar(contact.avatarUrl, size = 40.dp) },
            onClick = { onPick(contact) },
            trailingContent = {
                Icon(trailingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    }
}

/**
 * [SettingsScaffold] with the search field and filter chips pinned under the app bar, one
 * segmented group per section, and the letter index floating on the end edge.
 */
@Composable
private fun ContactSelectorScaffold(
    title: String,
    contacts: List<IWeContact>?,
    onBack: () -> Unit,
    selectionKey: Any?,
    isSelected: (IWeContact) -> Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    selectionActions: ContactSelectionActions? = null,
    row: @Composable (IWeContact) -> Unit,
) {
    val state = rememberContactSelectorState(contacts.orEmpty())
    val sections = state.rememberSections(selectionKey, isSelected)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // Rows leave room for the index bar, which only exists in alphabetical order.
    val rowEndPadding = if (state.sortMode == ContactSortMode.ALPHABETICAL) 28.dp else 16.dp

    SettingsScaffold(
        title = title,
        onBack = onBack,
        actions = actions,
        listState = listState,
        pinnedHeader = {
            ContactSearchField(state, Modifier.padding(start = 16.dp, end = 8.dp))
            ContactFilterPanel(
                state = state,
                modifier = Modifier.padding(top = 4.dp),
                selectionActions = selectionActions,
                horizontalPadding = 16.dp,
            )
            Spacer(Modifier.height(8.dp))
        },
        overlay = {
            if (contacts != null && sections.isNotEmpty()) {
                AlphabetIndexBar(
                    state = state,
                    sections = sections,
                    onScrollTo = { index -> coroutineScope.launch { listState.scrollToItem(index) } },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 2.dp),
                )
            }
        },
    ) {
        when {
            contacts == null -> item(key = "loading") {
                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            sections.isEmpty() -> item(key = "empty") {
                SettingsEmptyState(title = stringResource(R.string.contact_no_matches))
            }

            else -> {
                sections.forEach { section ->
                    item(key = section.key.itemKey) {
                        Text(
                            text = contactSectionTitle(section.key),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .animateItem()
                                .padding(start = 32.dp, end = rowEndPadding, top = 16.dp, bottom = 12.dp),
                        )
                    }
                    lazySegmentedItems(section.contacts, key = { it.wxId }) { contact ->
                        Box(modifier = Modifier.padding(start = 16.dp, end = rowEndPadding)) {
                            row(contact)
                        }
                    }
                }
                item(key = "bottom_inset") { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET)) }
            }
        }
    }
}
