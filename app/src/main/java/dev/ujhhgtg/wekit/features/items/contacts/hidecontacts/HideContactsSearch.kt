package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Field

private const val TAG = "HideContacts.Search"

/**
 * The two global-search tasks whose results cannot be reached from SQL.
 *
 * Everything else in 搜索 is filtered in HideContactsSql.kt, either by wrapping the FTS statement in
 * an `aux_index NOT IN (...)` outer select or by constraining the `FTS5ChatRoomMembers` join. These
 * two tasks are different because the hidden contact is not a *row* of the statement they run:
 *
 * - **SearchChatroomMemberTask** (`fts.logic.q0`) searches the members of one chatroom. Its FTS
 *   statement returns a single row for the chatroom — `aux_index` is the *group* id, not a member —
 *   and the individual members are decoded afterwards from `chatroom.memberlist`, a `;`-separated
 *   text column. So the existing `aux_index` filter only ever hides the group itself.
 * - **SearchCommonChatroomUserTask** (`fts.logic.h`) suggests people you share groups with. It reads
 *   a packed `content` blob out of the index, splits it in Java and then resolves the contacts
 *   through the WCDB ORM builder, which emits no statement the rewriter can see.
 *
 * Both are cut at the same place: `p(FTSResult)`, the task body, filtered on the way out. Removing
 * entries after the task has finished keeps every index the task computed internally consistent —
 * `q0` in particular indexes into the sorted `memberlist` array while it builds its entries, so
 * filtering the input would silently shift every member's data onto the wrong person.
 */
fun HideContacts.installSearchHooks() {
    installChatroomMemberSearchHook()
    installCommonChatroomUserSearchHook()
}

/**
 * 群聊内搜索成员 (`SearchChatroomMemberTask.p`).
 *
 * The task leaves its output as `result.entries[0].members` — one search entry per matched chatroom,
 * each carrying the list of matched member entries. Both hops are located structurally: the result
 * holder and the search entry each declare exactly one `java.util.List` field on both trees
 * (8.0.76 `j23/v.java:22` + `j23/y.java:42`, 8.0.69 `ov2/v.java:22` + `ov2/y.java:42`).
 */
private fun HideContacts.installChatroomMemberSearchHook() {
    if (methodFtsSearchChatroomMemberTask.isPlaceholder) {
        WeLogger.w(TAG, "SearchChatroomMemberTask wasn't resolved; 群成员搜索 stays unfiltered")
        return
    }

    methodFtsSearchChatroomMemberTask.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        if (hiddenContacts.isEmpty()) return@hookAfter

        val entries = searchResultEntries(args[0]) ?: return@hookAfter
        for (entry in entries) {
            entry ?: continue
            val membersField = singleListField(entry) ?: continue
            val members = membersField.get(entry) as? List<*> ?: continue
            if (members.isEmpty()) continue

            val filtered = members.filterNot { it != null && mentionsHiddenContact(it) }
            if (filtered.size == members.size) continue

            WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from 群成员搜索")
            membersField.set(entry, ArrayList(filtered))
        }
    }
}

/**
 * 共同群聊的好友建议 (`SearchCommonChatroomUserTask.p`).
 *
 * Every suggestion is one search entry whose key field holds the suggested contact's wxid
 * (8.0.76 `fts/logic/h.java:99`, 8.0.69 `h.java:100`), so here the *entries themselves* are dropped
 * rather than a nested list.
 */
private fun HideContacts.installCommonChatroomUserSearchHook() {
    if (methodFtsSearchCommonChatroomUserTask.isPlaceholder) {
        WeLogger.w(TAG, "SearchCommonChatroomUserTask wasn't resolved; 共同群聊好友建议 stays unfiltered")
        return
    }

    methodFtsSearchCommonChatroomUserTask.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        if (hiddenContacts.isEmpty()) return@hookAfter

        val response = args[0] ?: return@hookAfter
        val entriesField = singleListField(response) ?: return@hookAfter
        val entries = entriesField.get(response) as? List<*> ?: return@hookAfter
        if (entries.isEmpty()) return@hookAfter

        val filtered = entries.filterNot { it != null && mentionsHiddenContact(it) }
        if (filtered.size == entries.size) return@hookAfter

        WeLogger.d(TAG, "filtered ${entries.size - filtered.size} hidden 共同群聊 suggestion(s)")
        entriesField.set(response, ArrayList(filtered))
    }
}

/** `FTSResult.entries` — the task output list, or null when the shape is not what we expect. */
private fun searchResultEntries(response: Any?): List<*>? {
    response ?: return null
    val field = singleListField(response) ?: return null
    return field.get(response) as? List<*>
}

/**
 * The single `java.util.List` field declared on [obj]'s class hierarchy.
 *
 * The FTS model classes are obfuscated and renumbered every version, so they are addressed by shape
 * instead of by name. `firstFieldOrNull` returns null rather than throwing if a future version grows
 * a second list and the shape stops being unique — worst case the surface stays unfiltered.
 */
private fun singleListField(obj: Any): Field? = obj.reflekt()
    .firstFieldOrNull {
        type = List::class
        superclass()
    }?.self?.makeAccessible()

/**
 * True when any `String` field of [entry] holds a hidden wxid.
 *
 * The search-entry and member-entry classes both carry the contact id in one specific obfuscated
 * field alongside several display strings (nickname, remark, highlighted label). Rather than pin
 * that field down by ordinal — obfuscated field *order* is as version-unstable as the names —
 * every string on the object is tested. A false positive would need a nickname or remark that is
 * character-for-character a hidden contact's wxid, which cannot happen in practice.
 */
private fun mentionsHiddenContact(entry: Any): Boolean {
    val strings = entry.reflekt().fields {
        type = String::class
        superclass()
    }
    return strings.any { field ->
        val value = field.self.makeAccessible().get(entry) as? String ?: return@any false
        value.isNotEmpty() && HideContacts.isHiddenNow(value)
    }
}
