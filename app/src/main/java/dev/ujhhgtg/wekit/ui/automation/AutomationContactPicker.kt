package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ujhhgtg.wekit.activity.automation.AutomationRoute
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.ui.content.contacts.ContactPickerScreen
import dev.ujhhgtg.wekit.ui.content.contacts.ContactsSelectorScreen
import dev.ujhhgtg.wekit.ui.navigation.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * A contact screen requested by an automation screen or editor. Results return a route that
 * replaces the picker, or null to go back to the opener (which stays composed underneath, so
 * callbacks may update its draft state).
 */
sealed interface AutomationContactPick {
    val title: String

    /** Runs off the main thread when the picker opens. */
    val loadContacts: () -> List<IWeContact>

    /** Runs once the picker has left, whether it produced a result or not. */
    val onClose: () -> Unit

    class Multiple(
        override val title: String,
        override val loadContacts: () -> List<IWeContact>,
        val initialSelected: Set<String>,
        override val onClose: () -> Unit = {},
        val onConfirm: (Set<String>) -> AutomationRoute?,
    ) : AutomationContactPick

    /** Tapping a contact picks it; [isMarked] contacts (e.g. already configured) are listed first. */
    class Single(
        override val title: String,
        override val loadContacts: () -> List<IWeContact>,
        val markedKey: Any? = null,
        val isMarked: (IWeContact) -> Boolean = { false },
        override val onClose: () -> Unit = {},
        val onPick: (IWeContact) -> AutomationRoute?,
    ) : AutomationContactPick
}

/** Holds pending [AutomationContactPick]s for the [AutomationRoute.ContactPicker] entries. */
class AutomationContactPicker(private val navigator: Navigator) {
    private val requests = HashMap<String, AutomationContactPick>()

    /** Registers [pick] and returns its route, e.g. to chain pickers from a result. */
    fun route(pick: AutomationContactPick): AutomationRoute.ContactPicker {
        val id = UUID.randomUUID().toString()
        requests[id] = pick
        return AutomationRoute.ContactPicker(id)
    }

    fun open(pick: AutomationContactPick) = navigator.push(route(pick))

    fun request(id: String): AutomationContactPick? = requests[id]

    fun finish(next: AutomationRoute?) {
        if (next != null) navigator.replace(next) else navigator.pop()
    }

    fun release(id: String) {
        requests.remove(id)?.onClose?.invoke()
    }
}

val LocalAutomationContactPicker = staticCompositionLocalOf<AutomationContactPicker> {
    error("no AutomationContactPicker provided")
}

@Composable
fun AutomationContactPickerScreen(picker: AutomationContactPicker, id: String, onBack: () -> Unit) {
    val pick = remember(id) { picker.request(id) }
    DisposableEffect(id) { onDispose { picker.release(id) } }
    if (pick == null) {
        // The request did not survive activity recreation; there is nothing to pick for.
        LaunchedEffect(id) { onBack() }
        return
    }

    var contacts by remember(pick) { mutableStateOf<List<IWeContact>?>(null) }
    LaunchedEffect(pick) { contacts = withContext(Dispatchers.IO) { pick.loadContacts() } }

    when (pick) {
        is AutomationContactPick.Multiple -> {
            var selected by remember(pick) { mutableStateOf(pick.initialSelected) }
            ContactsSelectorScreen(
                title = pick.title,
                contacts = contacts,
                selectedWxIds = selected,
                onSelectionChange = { selected = it },
                onBack = onBack,
                onConfirm = { picker.finish(pick.onConfirm(selected)) },
            )
        }

        is AutomationContactPick.Single -> ContactPickerScreen(
            title = pick.title,
            contacts = contacts,
            onBack = onBack,
            onPick = { picker.finish(pick.onPick(it)) },
            markedKey = pick.markedKey,
            isMarked = pick.isMarked,
        )
    }
}
