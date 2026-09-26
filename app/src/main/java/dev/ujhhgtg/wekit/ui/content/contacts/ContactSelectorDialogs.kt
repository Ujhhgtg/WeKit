package dev.ujhhgtg.wekit.ui.content.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import kotlinx.coroutines.launch

/**
 * Dialog contact list with search, filter chips, sticky section headers and a letter index.
 * Searching, filtering and sorting are handled here; selection stays with the caller.
 */
@Composable
fun BaseContactSelector(
    title: String,
    contacts: List<IWeContact>,
    confirmButtonText: String,
    confirmButtonEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    selectionKey: Any?,
    isSelected: (IWeContact) -> Boolean,
    showConfirmButton: Boolean = true,
    dismissButtonText: String? = null,
    avatarModelProvider: ((IWeContact) -> Any)? = { it.avatarUrl },
    subtitleProvider: ((IWeContact) -> String)? = { it.wxId },
    leadingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    trailingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    onItemClick: (IWeContact) -> Unit,
    selectionActions: ContactSelectionActions? = null,
) {
    val state = rememberContactSelectorState(contacts)
    val sections = state.rememberSections(selectionKey, isSelected)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    AlertDialogContent(
        modifier = modifier.fillMaxWidth(),
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                ContactSearchField(state, Modifier.padding(bottom = 4.dp))
                ContactFilterPanel(state, selectionActions = selectionActions)
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                if (sections.isEmpty()) {
                    ContactNoMatches(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            sections.forEach { section ->
                                stickyHeader(key = section.key.itemKey) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ) {
                                        Text(
                                            text = contactSectionTitle(section.key),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                items(items = section.contacts, key = { it.wxId }) { contact ->
                                    Row(
                                        modifier = Modifier
                                            .animateItem()
                                            .fillMaxWidth()
                                            .clickable { onItemClick(contact) }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (leadingControl != null) {
                                            leadingControl(contact)
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }
                                        ContactAvatar(avatarModelProvider?.invoke(contact) ?: contact.avatarUrl)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = contact.displayName, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                text = subtitleProvider?.invoke(contact) ?: contact.wxId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (trailingControl != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            trailingControl(contact)
                                        }
                                    }
                                }
                            }
                        }
                        AlphabetIndexBar(
                            state = state,
                            sections = sections,
                            onScrollTo = { index -> coroutineScope.launch { listState.scrollToItem(index) } },
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = 8.dp, end = 4.dp),
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onDismiss) {
                Text(dismissButtonText ?: stringResource(R.string.dialog_cancel))
            }
        },
        confirmButton = if (showConfirmButton) {
            {
                Button(onClick = onConfirm, enabled = confirmButtonEnabled) {
                    Text(confirmButtonText)
                }
            }
        } else null,
    )
}

@Composable
fun SingleContactSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedWxId by remember { mutableStateOf(initialSelectedWxId) }

    BaseContactSelector(
        title = title,
        contacts = contacts,
        confirmButtonText = stringResource(R.string.dialog_confirm),
        confirmButtonEnabled = selectedWxId != null,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxId!!) },
        selectionKey = selectedWxId,
        isSelected = { it.wxId == selectedWxId },
        leadingControl = { contact -> RadioButton(selected = contact.wxId == selectedWxId, onClick = null) },
        onItemClick = { contact -> selectedWxId = contact.wxId },
    )
}

@Composable
fun ContactsSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selectedWxIds by remember { mutableStateOf(initialSelectedWxIds) }

    BaseContactSelector(
        title = title,
        contacts = contacts,
        confirmButtonText = stringResource(R.string.contact_confirm_selected_count, selectedWxIds.size),
        confirmButtonEnabled = true,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxIds) },
        selectionKey = selectedWxIds,
        isSelected = { it.wxId in selectedWxIds },
        leadingControl = { contact -> Checkbox(checked = contact.wxId in selectedWxIds, onCheckedChange = null) },
        onItemClick = { contact ->
            selectedWxIds = if (contact.wxId in selectedWxIds) selectedWxIds - contact.wxId else selectedWxIds + contact.wxId
        },
        selectionActions = multiSelectActions({ selectedWxIds }) { selectedWxIds = it },
    )
}
