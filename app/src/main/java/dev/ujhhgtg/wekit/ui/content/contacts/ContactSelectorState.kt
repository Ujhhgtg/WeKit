package dev.ujhhgtg.wekit.ui.content.contacts

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.items.chat.ConversationAggregation
import dev.ujhhgtg.wekit.features.items.chat.ConversationGrouping
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "ContactSelectorState"

private var persistedFilterMode by KvStore.prefOption(
    "contact_selector_filter_mode",
    ContactFilterMode.LABELS.name,
)

private fun ContactFilterMode.isAvailable(): Boolean = when (this) {
    ContactFilterMode.LABELS -> true
    ContactFilterMode.AGGREGATION -> ConversationAggregation.isEnabled
    ContactFilterMode.GROUPING -> ConversationGrouping.isEnabled
}

/**
 * Search, filter and sort state shared by the dialog and screen contact selectors. Selection is
 * owned by the caller; [rememberSections] only needs to know which contacts are selected.
 */
@Stable
class ContactSelectorState(
    private val coroutineScope: CoroutineScope,
    private val showMessage: (Int) -> Unit,
) {
    val initials = ContactInitials()

    /** All contacts, sorted by display name. */
    var contacts by mutableStateOf(emptyList<IWeContact>())

    var searchQuery by mutableStateOf("")
    var filtersExpanded by mutableStateOf(true)

    var filterData by mutableStateOf(ContactFilterData.EMPTY)
        private set
    var filtersLoaded by mutableStateOf(false)
        private set

    var typeFilter by mutableStateOf(ContactTypeFilter.ALL)
    var filterMode by mutableStateOf(
        ContactFilterMode.entries.firstOrNull { it.name == persistedFilterMode }
            ?.takeIf { it.isAvailable() } ?: ContactFilterMode.LABELS
    )
        private set

    /** The chosen label / aggregation folder / conversation group of [filterMode]; null = all. */
    var selectedOptionId by mutableStateOf<String?>(null)

    var sortMode by mutableStateOf(ContactSortMode.ALPHABETICAL)
        private set
    var sortReversed by mutableStateOf(false)
    var lastMessageTimes by mutableStateOf<Map<String, Long>?>(null)
        private set
    var sortLoading by mutableStateOf(false)
        private set

    /** [contacts] matching [searchQuery]. */
    val searchedContacts by derivedStateOf { filterSortedContacts(contacts, searchQuery) }

    val typeCounts by derivedStateOf { filterData.countByType(searchedContacts) }

    // Chip visibility is decided from the full contact list so that filter rows do not
    // disappear while a search query is active (the filters still apply, hiding them is confusing).
    val availableTypes by derivedStateOf {
        val allCounts = filterData.countByType(contacts)
        ContactTypeFilter.entries.filter { it == ContactTypeFilter.ALL || (allCounts[it] ?: 0) > 0 }
    }
    val showTypeFilters by derivedStateOf { filtersLoaded && availableTypes.size > 2 }

    val availableFilterModes by derivedStateOf {
        if (!filtersLoaded) emptyList() else ContactFilterMode.entries.filter { it.isAvailable() }
    }

    val availableOptions by derivedStateOf {
        filterData.options[filterMode].orEmpty().filter { option -> contacts.any { it.wxId in option.wxIds } }
    }

    val optionCounts by derivedStateOf {
        availableOptions.associate { option -> option.id to searchedContacts.count { it.wxId in option.wxIds } }
    }

    /** What the list shows: searched, then narrowed by the type and option filters. */
    val displayedContacts by derivedStateOf {
        val option = selectedOptionId?.let { id -> filterData.options[filterMode].orEmpty().firstOrNull { it.id == id } }
        searchedContacts.filter { contact ->
            filterData.matches(contact, typeFilter) && (option == null || contact.wxId in option.wxIds)
        }
    }

    fun onFilterDataLoaded(data: ContactFilterData?) {
        if (data == null) showMessage(R.string.contact_filter_database_unavailable)
        else filterData = data
        filtersLoaded = true
    }

    /** Switches to the next available filter mode; false when there is only one to choose from. */
    fun cycleFilterMode(): Boolean {
        val modes = availableFilterModes
        if (modes.size <= 1) return false
        val index = modes.indexOf(filterMode).coerceAtLeast(0)
        filterMode = modes[(index + 1) % modes.size]
        persistedFilterMode = filterMode.name
        selectedOptionId = null
        return true
    }

    fun selectSortMode(target: ContactSortMode) {
        if (target == sortMode || sortLoading) return
        if (target == ContactSortMode.ALPHABETICAL || lastMessageTimes != null) {
            sortMode = target
            return
        }
        sortLoading = true
        coroutineScope.launch {
            val times = withContext(Dispatchers.IO) {
                if (WeDatabaseApi.isReady) WeDatabaseApi.getLastMessageTimes() else null
            }
            // getLastMessageTimes() 内部吞掉异常后返回空表, 所以空结果也当作失败:
            // 否则会静默按"所有会话时间相同"排出一个随意的顺序, 而且缓存住之后再也不会重试。
            if (times.isNullOrEmpty()) {
                showMessage(R.string.contact_sort_database_unavailable)
            } else {
                lastMessageTimes = times
                sortMode = ContactSortMode.LAST_MESSAGE_TIME
            }
            sortLoading = false
        }
    }

    /** Sections of [displayedContacts]; [selectionKey] must change whenever [isSelected] does. */
    @Composable
    fun rememberSections(selectionKey: Any?, isSelected: (IWeContact) -> Boolean): List<ContactSection> {
        val displayed = displayedContacts
        return remember(displayed, selectionKey, sortMode, sortReversed, lastMessageTimes) {
            buildContactSections(displayed, isSelected, sortMode, sortReversed, lastMessageTimes, initials::initialOf)
        }
    }
}

/**
 * [contacts] may change while the selector is shown (e.g. a newly configured entry); search and
 * filters are kept. The list is sorted once per change with [sortLocale]'s collator.
 */
@Composable
fun rememberContactSelectorState(
    contacts: List<IWeContact>,
    sortLocale: Locale = Locale.CHINA,
): ContactSelectorState {
    val context = LocalContext.current
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val coroutineScope = rememberCoroutineScope()
    val state = remember {
        ContactSelectorState(coroutineScope) { res: Int -> showToast(context, localizedContext.getString(res)) }
    }
    val sorted = remember(contacts, sortLocale) { sortContactsByDisplayName(contacts, sortLocale) }
    if (state.contacts !== sorted) state.contacts = sorted

    LaunchedEffect(state) {
        val data = withContext(Dispatchers.IO) {
            // 先在 IO 线程把分组字母算好, 主线程之后每次按键都只命中缓存。
            // (之后新增的联系人会在 initialOf 里按需补算, 结果一致。)
            runCatching { sorted.forEach { state.initials.initialOf(it.displayName) } }
            loadContactFilterData(localizedContext)
        }
        state.onFilterDataLoaded(data)
    }
    return state
}

/** Null when WeChat's database is not ready; other failures are logged and yield empty data. */
private fun loadContactFilterData(localizedContext: Context): ContactFilterData? {
    if (!WeDatabaseApi.isReady) return null
    return try {
        val labels = WeContactLabelApi.getAllLabels().map { label ->
            ContactFilterOption(
                id = label.labelName,
                name = label.labelName,
                wxIds = WeContactLabelApi.getContactsByLabelId(label.labelId).toSet(),
            )
        }
        val aggregation = if (ConversationAggregation.isEnabled) {
            ConversationAggregation.aggregationFolders().map { folder ->
                ContactFilterOption(folder.id, folder.name, ConversationAggregation.folderMembers(folder.id).toSet())
            }
        } else {
            emptyList()
        }
        val grouping = if (ConversationGrouping.isEnabled) {
            ConversationGrouping.groupFilterOptions(localizedContext).map { group ->
                ContactFilterOption(group.id, group.name, group.members.toSet())
            }
        } else {
            emptyList()
        }
        ContactFilterData(
            friendWxIds = WeDatabaseApi.getFriends().map { it.wxId }.toSet(),
            groupWxIds = WeDatabaseApi.getGroups().map { it.wxId }.toSet(),
            officialAccountWxIds = WeDatabaseApi.getOfficialAccounts().map { it.wxId }.toSet(),
            options = mapOf(
                ContactFilterMode.LABELS to labels,
                ContactFilterMode.AGGREGATION to aggregation,
                ContactFilterMode.GROUPING to grouping,
            ),
        )
    } catch (e: Exception) {
        WeLogger.e(TAG, "failed to load contact filters", e)
        ContactFilterData.EMPTY
    }
}
