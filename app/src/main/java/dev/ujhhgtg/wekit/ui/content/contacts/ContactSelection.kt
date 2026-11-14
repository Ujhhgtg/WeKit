package dev.ujhhgtg.wekit.ui.content.contacts

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Sort_by_alpha
import com.composables.icons.materialsymbols.outlined.Tag
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.core.models.WeGroup
import dev.ujhhgtg.wekit.features.api.core.models.WeOfficialAccount
import java.text.CollationKey
import java.text.Collator
import java.util.Locale

enum class ContactTypeFilter(@StringRes val displayNameRes: Int, val icon: ImageVector) {
    ALL(R.string.contact_filter_all, MaterialSymbols.Outlined.Search),
    FRIENDS(R.string.contact_filter_friends, MaterialSymbols.Outlined.Person),
    GROUPS(R.string.contact_filter_groups, MaterialSymbols.Outlined.Groups),
    OFFICIAL_ACCOUNTS(R.string.contact_filter_official_accounts, MaterialSymbols.Outlined.Chat),
    OTHERS(R.string.contact_filter_others, MaterialSymbols.Outlined.Tag),
}

/** Which kind of [ContactFilterOption]s the second chip row offers. */
enum class ContactFilterMode(val icon: ImageVector, @StringRes val nameRes: Int) {
    LABELS(MaterialSymbols.Outlined.Label, R.string.contact_filter_mode_labels),
    AGGREGATION(MaterialSymbols.Outlined.Folder, R.string.contact_filter_mode_aggregation),
    GROUPING(MaterialSymbols.Outlined.Groups, R.string.contact_filter_mode_grouping),
}

enum class ContactSortMode(val icon: ImageVector) {
    ALPHABETICAL(MaterialSymbols.Outlined.Sort_by_alpha),
    LAST_MESSAGE_TIME(MaterialSymbols.Outlined.Schedule);

    @StringRes
    fun displayNameRes(reversed: Boolean): Int =
        when (this) {
            ALPHABETICAL ->
                if (reversed) R.string.contact_sort_alphabetical_descending
                else R.string.contact_sort_alphabetical_ascending
            LAST_MESSAGE_TIME ->
                if (reversed) R.string.contact_sort_oldest_first
                else R.string.contact_sort_newest_first
        }
}

/** A label, aggregation folder or conversation group, reduced to the wxIds it contains. */
data class ContactFilterOption(
    val id: String,
    val name: String,
    val wxIds: Set<String>,
)

/** Database facts behind the type filter and the label/aggregation/grouping chips. */
class ContactFilterData(
    val friendWxIds: Set<String>,
    val groupWxIds: Set<String>,
    val officialAccountWxIds: Set<String>,
    val options: Map<ContactFilterMode, List<ContactFilterOption>>,
) {
    /** The single friend/group/official-account/other rule every filter and count uses. */
    fun typeOf(contact: IWeContact): ContactTypeFilter {
        val isGroup =
            contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
        val isOfficial =
            contact is WeOfficialAccount ||
                contact.wxId.startsWith("gh_") ||
                contact.wxId in officialAccountWxIds
        val isFriend =
            contact.wxId in friendWxIds ||
                contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0
        return when {
            isGroup -> ContactTypeFilter.GROUPS
            isOfficial -> ContactTypeFilter.OFFICIAL_ACCOUNTS
            isFriend -> ContactTypeFilter.FRIENDS
            else -> ContactTypeFilter.OTHERS
        }
    }

    fun countByType(contacts: List<IWeContact>): Map<ContactTypeFilter, Int> {
        val counts = contacts.groupingBy(::typeOf).eachCount()
        return ContactTypeFilter.entries.associateWith { type ->
            if (type == ContactTypeFilter.ALL) contacts.size else counts[type] ?: 0
        }
    }

    fun matches(contact: IWeContact, type: ContactTypeFilter): Boolean =
        type == ContactTypeFilter.ALL || typeOf(contact) == type

    companion object {
        val EMPTY = ContactFilterData(emptySet(), emptySet(), emptySet(), emptyMap())
    }
}

sealed interface ContactSectionKey {
    /** A Bundle-saveable key for the section's header item in lazy lists. */
    val itemKey: String

    /** Pinned first: the selected (or otherwise marked) contacts. */
    data object Selected : ContactSectionKey {
        override val itemKey = "header_selected"
    }

    data object Newest : ContactSectionKey {
        override val itemKey = "header_newest"
    }

    data object Oldest : ContactSectionKey {
        override val itemKey = "header_oldest"
    }

    /** An alphabetical section: `A`–`Z` or `#`. */
    data class Initial(val letter: String) : ContactSectionKey {
        override val itemKey
            get() = "header_$letter"
    }
}

data class ContactSection(val key: ContactSectionKey, val contacts: List<IWeContact>)

/** The index bar's entries in display order; [ContactSectionKey.Selected] stays on top. */
fun alphabetIndexKeys(reversed: Boolean): List<ContactSectionKey> {
    val letters = ('A'..'Z').map { ContactSectionKey.Initial(it.toString()) }
    return listOf(ContactSectionKey.Selected) +
        (if (reversed) letters.reversed() else letters) +
        ContactSectionKey.Initial("#")
}

/**
 * Splits [contacts] (already filtered) into sections: selected contacts first, then either one
 * newest/oldest section or one section per initial letter, with `#` always last.
 */
fun buildContactSections(
    contacts: List<IWeContact>,
    isSelected: (IWeContact) -> Boolean,
    sortMode: ContactSortMode,
    reversed: Boolean,
    lastMessageTimes: Map<String, Long>?,
    initialOf: (String) -> String,
): List<ContactSection> {
    if (sortMode == ContactSortMode.LAST_MESSAGE_TIME) {
        val times = lastMessageTimes.orEmpty()
        val sorted =
            if (reversed) {
                contacts.sortedBy { times[it.wxId] ?: Long.MIN_VALUE }
            } else {
                contacts.sortedByDescending { times[it.wxId] ?: Long.MIN_VALUE }
            }
        val (selected, rest) = sorted.partition(isSelected)
        return buildList {
            if (selected.isNotEmpty()) add(ContactSection(ContactSectionKey.Selected, selected))
            if (rest.isNotEmpty()) {
                add(
                    ContactSection(
                        if (reversed) ContactSectionKey.Oldest else ContactSectionKey.Newest,
                        rest,
                    ),
                )
            }
        }
    }

    val (selected, rest) = contacts.partition(isSelected)
    val letters =
        rest
            .groupBy { initialOf(it.displayName) }
            .toSortedMap { a, b ->
                when {
                    a == b -> 0
                    a == "#" -> 1
                    b == "#" -> -1
                    else -> if (reversed) b.compareTo(a) else a.compareTo(b)
                }
            }
    return buildList {
        if (selected.isNotEmpty()) add(ContactSection(ContactSectionKey.Selected, selected))
        letters.forEach { (letter, members) ->
            add(ContactSection(ContactSectionKey.Initial(letter), members))
        }
    }
}

/** Flat list index of each section's header when every section renders one header + its rows. */
fun sectionHeaderIndices(sections: List<ContactSection>): Map<ContactSectionKey, Int> {
    val indices = HashMap<ContactSectionKey, Int>(sections.size)
    var index = 0
    sections.forEach { section ->
        indices[section.key] = index
        index += 1 + section.contacts.size
    }
    return indices
}

/**
 * The header index an index-bar tap on [key] scrolls to: the tapped letter's section, or the
 * nearest following one in the current direction, falling back to the last section.
 */
fun indexBarTarget(
    key: ContactSectionKey,
    headerIndices: Map<ContactSectionKey, Int>,
    sections: List<ContactSection>,
    reversed: Boolean,
): Int? {
    headerIndices[key]?.let {
        return it
    }
    if (key !is ContactSectionKey.Initial) return null
    val letter = key.letter.first()
    val letterSections =
        sections
            .map { it.key }
            .filterIsInstance<ContactSectionKey.Initial>()
            .filter { it.letter != "#" }
    val target =
        if (reversed) {
            letterSections.firstOrNull { it.letter.first() <= letter }
        } else {
            letterSections.firstOrNull { it.letter.first() >= letter }
        } ?: sections.lastOrNull()?.key
    return target?.let(headerIndices::get)
}

private class SortableContact(
    val contact: IWeContact,
    val isBlankName: Boolean,
    val key: CollationKey,
)

/**
 * 按显示名排序 (与 `Collator.compare` 的顺序完全一致)。
 *
 * ICU 的 [Collator] 每次 compare 都要重新分析两个字符串, 排序过程里同一个名字会被反复分析; 这里先给每个不同的显示名各算一份 [CollationKey]
 * (每个名字只分析一次), 排序时只比较键。 `displayName` 是 getter (WeContact 每次都会重新拼字符串), 所以也只取一次。
 *
 * 调用方只在联系人列表变化时调用一次, 搜索时改用 [filterSortedContacts] 在已排好序的 列表上做纯字符串过滤, 避免每敲一个字就在主线程上重跑一遍 ICU 排序。
 */
fun sortContactsByDisplayName(
    contacts: List<IWeContact>,
    locale: Locale = Locale.CHINA,
): List<IWeContact> {
    if (contacts.size < 2) return contacts
    val collator = Collator.getInstance(locale)
    val keyCache = HashMap<String, CollationKey>()
    return contacts
        .map { contact ->
            val name = contact.displayName
            SortableContact(
                contact = contact,
                isBlankName = name.isBlank(),
                key = keyCache.getOrPut(name) { collator.getCollationKey(name) },
            )
        }
        .sortedWith(compareBy<SortableContact> { it.isBlankName }.thenBy { it.key })
        .map { it.contact }
}

/** 在已排好序的列表上过滤。过滤保序, 所以结果与"先过滤再排序"完全一致。 */
fun filterSortedContacts(sorted: List<IWeContact>, query: String): List<IWeContact> {
    if (query.isEmpty()) return sorted
    return sorted.filter {
        it.displayName.contains(query, ignoreCase = true) ||
            it.wxId.contains(query, ignoreCase = true)
    }
}
