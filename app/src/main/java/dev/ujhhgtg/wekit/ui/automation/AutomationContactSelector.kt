package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.ui.content.BaseContactSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import java.text.Collator

/** Contact list whose rows open a contact's automation settings instead of selecting it. */
@Composable
fun AutomationContactSettingsSelector(
    title: String,
    contacts: List<IWeContact>,
    selectionKey: Any,
    subtitle: (IWeContact) -> String,
    isConfigured: (IWeContact) -> Boolean,
    onDismiss: () -> Unit,
    onOpen: (IWeContact) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val currentLocale = LocalConfiguration.current.locales[0]
    val collator = remember(currentLocale) { Collator.getInstance(currentLocale) }
    val filteredContacts = remember(searchQuery, contacts, collator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { first, second -> collator.compare(first.displayName, second.displayName) }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "",
        confirmButtonEnabled = false,
        showConfirmButton = false,
        dismissButtonText = stringResource(R.string.dialog_close),
        onDismiss = onDismiss,
        onConfirm = {},
        selectionKey = selectionKey,
        isSelected = isConfigured,
        subtitleProvider = subtitle,
        trailingControl = { contact ->
            TextButton(onClick = { onOpen(contact) }) { Text(stringResource(R.string.action_settings)) }
        },
        onItemClick = onOpen,
    )
}
