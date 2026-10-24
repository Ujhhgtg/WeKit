package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.ui.content.contacts.ContactPickerScreen
import dev.ujhhgtg.wekit.ui.content.contacts.ContactsSelectorScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A full-screen picker requested by an automation screen, editor or sheet. */
sealed interface AutomationPick {
    val title: String

    /** Multi-select; [onConfirm] receives the selected wxids. */
    class Contacts(
        override val title: String,
        /** Runs off the main thread when the picker opens. */
        val loadContacts: () -> List<IWeContact>,
        val initialSelected: Set<String>,
        val onConfirm: (Set<String>) -> Unit,
    ) : AutomationPick

    /**
     * Tapping a contact picks it; [isMarked] contacts (e.g. already configured) are listed first.
     */
    class Contact(
        override val title: String,
        val loadContacts: () -> List<IWeContact>,
        val isMarked: (IWeContact) -> Boolean = { false },
        val onPick: (IWeContact) -> Unit,
    ) : AutomationPick

    /** Multi-select of favorites; [onConfirm] receives local ids, newest first. */
    class Favorites(
        override val title: String,
        val initialSelected: Set<Long>,
        val onConfirm: (List<Long>) -> Unit,
    ) : AutomationPick
}

/**
 * Shows [AutomationPick]s as full-screen dialogs, so they cover the current screen and any open
 * bottom sheet (which lives in its own window) while the opener keeps its state. A result callback
 * may open the next pick, e.g. a group's members after choosing the group.
 */
class AutomationPicker {
    var current by mutableStateOf<AutomationPick?>(null)
        private set

    fun open(pick: AutomationPick) {
        current = pick
    }

    fun close() {
        current = null
    }
}

@Composable
fun AutomationPickerHost(picker: AutomationPicker) {
    val pick = picker.current ?: return
    Dialog(
        onDismissRequest = picker::close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        key(pick) {
            when (pick) {
                is AutomationPick.Contacts -> ContactsPick(pick, picker)
                is AutomationPick.Contact -> ContactPick(pick, picker)
                is AutomationPick.Favorites ->
                    AutomationFavoritePickerScreen(
                        title = pick.title,
                        initialSelected = pick.initialSelected,
                        onBack = picker::close,
                        onConfirm = { ids ->
                            picker.close()
                            pick.onConfirm(ids)
                        },
                    )
            }
        }
    }
}

@Composable
private fun rememberLoadedContacts(load: () -> List<IWeContact>): List<IWeContact>? {
    var contacts by remember { mutableStateOf<List<IWeContact>?>(null) }
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { load() } }
    return contacts
}

@Composable
private fun ContactsPick(pick: AutomationPick.Contacts, picker: AutomationPicker) {
    val contacts = rememberLoadedContacts(pick.loadContacts)
    var selected by remember { mutableStateOf(pick.initialSelected) }
    ContactsSelectorScreen(
        title = pick.title,
        contacts = contacts,
        selectedWxIds = selected,
        onSelectionChange = { selected = it },
        onBack = picker::close,
        onConfirm = {
            picker.close()
            pick.onConfirm(selected)
        },
    )
}

@Composable
private fun ContactPick(pick: AutomationPick.Contact, picker: AutomationPicker) {
    val contacts = rememberLoadedContacts(pick.loadContacts)
    ContactPickerScreen(
        title = pick.title,
        contacts = contacts,
        onBack = picker::close,
        onPick = {
            picker.close()
            pick.onPick(it)
        },
        markedKey = pick,
        isMarked = pick.isMarked,
    )
}
