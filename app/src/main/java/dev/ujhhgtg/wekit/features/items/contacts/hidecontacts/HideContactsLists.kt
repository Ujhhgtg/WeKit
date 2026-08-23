package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.LinkedList

private const val TAG = "HideContacts.Lists"

/**
 * List/adapter-level hiding for the surfaces whose rows never pass through a rewritable SQL query.
 *
 * The SQL rewriter in HideContactsSql.kt covers everything WeChat reads out of `rcontact` /
 * `rconversation` with a statement we can see. The four surfaces installed here are invisible to it:
 *
 * - the 通讯录 and @成员 lists are MVVM "live lists" whose rows are already-materialised
 *   `Contact` objects by the time any adapter sees them;
 * - group-member lists come from `chatroom.memberlist`, a `;`-separated string column, so there is
 *   no per-member row to filter in SQL at all;
 * - 收藏 has largely moved to the WCDB ORM builder (`r82.e` / `br5.f`), which emits no raw SQL;
 * - 视频号 like lists are network-driven protobuf, with no local table behind them.
 *
 * Every hook here filters the *input* the adapter is built from rather than remapping adapter
 * indices. That is deliberate: an earlier iteration of this feature shifted `getView` positions
 * through a `hiddenPositions` set without touching `getItem`, so tapping a row opened the wrong
 * chat. Cutting the entry before the adapter ever counts it keeps `getCount()`, `getItem()` and
 * every click listener consistent by construction, and a resolve failure degrades to "the contact
 * stays visible" instead of "the wrong contact opens".
 *
 * Filtering is done by **substituting a filtered copy**, never by mutating the collection WeChat
 * handed us, unless that collection is provably a per-call/per-response object with no other
 * consumer. 隐藏 here is a display-time decision that `#show`, the triple-tap gesture and the
 * 定时显示 scheduler must be able to undo instantly; a destructive `removeAll` on a list the host
 * retains would make "temporarily show" unable to bring the rows back until WeChat repopulates it.
 */
internal fun HideContacts.installListHooks() {
    installMvvmListHooks()
    installContactCountHook()
    installGroupMemberHooks()
    installFavoriteHooks()
    installFinderLikeHooks()
}

// ---------------------------------------------------------------------------------------------
// MvvmList — 通讯录 (AddressLiveList) and the @成员选择器 (AtSomeoneLiveList)
// ---------------------------------------------------------------------------------------------

/**
 * `MvvmList.e(List snapshotList)` is WeChat's per-list "preprocess the snapshot before it reaches
 * the adapter" hook, and both list classes implement it identically: sort the snapshot in place,
 * walk it once to recompute the section headers *from whatever it was handed*, then `map` it into a
 * list of *clones* which becomes the adapter's data. Everything downstream — the adapter rows, the
 * section flags and (for `AtSomeoneLiveList`) the A-Z index list it rebuilds into its `C` field — is
 * derived from the argument alone, so replacing the argument with a filtered copy in a `hookBefore`
 * yields a fully self-consistent result with no index arithmetic anywhere. Verified identical on
 * 8.0.69, 8.0.74 and 8.0.76 (`ui/contact/address/AddressLiveList.e`,
 * `ui/chatting/atsomeone/AtSomeoneLiveList.e`).
 *
 * The argument is **substituted, not mutated**. `MvvmList.l()` calls `e(this.f182257p)`
 * (`plugin/mvvmlist/MvvmList.java:373` on 8.0.76, `:373` on 8.0.74, `:361` on 8.0.69), i.e. `args[0]`
 * *is* the list's persistent snapshot field, not a per-call copy. An earlier version of this hook
 * called `removeAll` on it, which deleted the rows out of WeChat's own backing store: `#show`, the
 * triple-tap gesture and a scheduled SHOW could not bring the 通讯录 rows back, and neither could
 * un-hiding a contact, until WeChat repopulated the list from its data source.
 *
 * A `hookAfter` on the return value would not do either: it would drop the adapter rows but leave
 * the section headers that `e()` already computed over the unfiltered snapshot, so a section whose
 * only member was hidden would keep its header. Filtering the input is what keeps them in sync.
 *
 * `AddressLiveList` (通讯录) and `AtSomeoneLiveList` (@成员) differ only in their entry class
 * (`ah5.g` vs `com.tencent.mm.ui.chatting.atsomeone.b` on 8.0.76), and both entry classes carry the
 * contact as their single `com.tencent.mm.storage.*` field, so one reflective shape works for both —
 * hence the shared [hookMvvmListPreprocess] helper, which is the only thing either surface installs.
 */
private fun HideContacts.installMvvmListHooks() {
    hookMvvmListPreprocess(methodAddressMvvmListPreprocessList, "AddressLiveList")
    hookMvvmListPreprocess(methodAtSomeoneMvvmListPreprocessList, "AtSomeoneLiveList")
}

private fun HideContacts.hookMvvmListPreprocess(target: DexMethodDelegate, label: String) {
    if (target.isPlaceholder) {
        WeLogger.w(TAG, "$label preprocess method wasn't resolved; that list stays unfiltered")
        return
    }

    target.hookBefore {
        if (isTemporarilyShown) return@hookBefore

        val contacts = args[0] as? List<*> ?: return@hookBefore
        // MvvmList hands us an empty snapshot on the initial load and whenever a filter matches
        // nothing; there is no entry to read the shape off then.
        if (contacts.isEmpty()) return@hookBefore

        // The entry classes are obfuscated, but each holds exactly one field whose type lives in
        // com.tencent.mm.storage (the Contact — y3 on 8.0.76, a3 on 8.0.69); everything else on them
        // is an int/boolean/String or a UI helper from another package. Selecting by declared type
        // rather than by name survives the per-version field renames.
        //
        // Both lookups are the *OrNull* variants behind an early return, matching the 收藏 hook
        // below: this body runs on every single 通讯录 render, so a shape change on some unverified
        // version has to degrade to "the list stays unfiltered", never throw out of a hook.
        val sample = contacts.firstNotNullOfOrNull { it } ?: return@hookBefore
        val contactInfoField = sample.reflekt()
            .firstFieldOrNull { type { it.name.startsWith("${PackageNames.WECHAT}.storage") } }
            ?.self?.makeAccessible()
        if (contactInfoField == null) {
            WeLogger.w(TAG, "$label entry has no com.tencent.mm.storage field; that list stays unfiltered")
            return@hookBefore
        }
        val usernameField = contactInfoField.type.reflekt()
            .firstFieldOrNull {
                name = "field_username"
                superclass()
            }?.self?.makeAccessible()
        if (usernameField == null) {
            WeLogger.w(TAG, "$label contact has no field_username; that list stays unfiltered")
            return@hookBefore
        }

        val kept = contacts.filterNot { contact ->
            val contactInfo = contact?.let { contactInfoField.get(it) } ?: return@filterNot false
            val username = usernameField.get(contactInfo) as? String ?: return@filterNot false
            isHiddenNow(username)
        }
        if (kept.size == contacts.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${contacts.size - kept.size} hidden contact(s) out of $label")
        // Substitution, never `contacts.removeAll { ... }`: this list is MvvmList's own persistent
        // `f182257p` snapshot field. See the KDoc above.
        args[0] = ArrayList(kept)
    }
}

// ---------------------------------------------------------------------------------------------
// 通讯录 「N 位联系人」
// ---------------------------------------------------------------------------------------------

/**
 * The footer under 通讯录 (`ContactCountView`) has two producers, and only one of them can be fixed
 * in SQL.
 *
 * - 群聊 (`contactType == 2`) counts inline in `ui/contact/f1.run()` and is rewritten by the
 *   `group-count` rule in HideContactsSql.kt.
 * - 联系人 (`contactType == 1`) calls `ContactStorage.getNormalContactCount`, whose statement ends in
 *   a bare `or username = 'weixin'`. `AND` binds tighter than `OR`, so an appended predicate would
 *   attach to that last operand and change nothing at all — the count has to be corrected after the
 *   fact instead.
 *
 * The correction re-counts the hidden set against the same predicates *and the same excluded
 * usernames* WeChat uses, so that hidden groups, 公众号, blocked contacts and helper accounts (none
 * of which the footer counted in the first place) are never subtracted. `includeBlack` and the
 * exclusion varargs are both mirrored from the call's own arguments.
 *
 * 「仅聊天的朋友」 needs nothing: `OnlyChatContactMgrUI` takes its count from an already-filtered
 * `@social.black.android` cursor rather than from this method.
 */
private fun HideContacts.installContactCountHook() {
    if (methodNormalContactCount.isPlaceholder) {
        WeLogger.w(TAG, "getNormalContactCount wasn't resolved; 通讯录联系人计数 stays unadjusted")
        return
    }

    methodNormalContactCount.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        val total = result as? Int ?: return@hookAfter

        val hidden = hiddenContacts
        if (hidden.isEmpty()) return@hookAfter

        // `O(boolean includeBlack, String[] excluded, String... more)` — both vararg groups are
        // turned into `and rcontact.username != '<each>'` terms, so the population it counts is
        // narrower than the bare predicates suggest. Reading them off the live call rather than
        // hard-coding `e01.e2.f269714p` keeps the mirror correct for every caller and every version.
        val excluded = buildSet {
            addAll(stringArrayArg(args.getOrNull(1)))
            addAll(stringArrayArg(args.getOrNull(2)))
            // WeChat skips "weixin" in both loops (`if (!"weixin".equals(str))`) and re-admits it
            // with the trailing `or username = 'weixin'`, so it is not an exclusion at all.
            remove("weixin")
        }

        val hiddenNormal = countHiddenNormalContacts(
            hidden,
            includeBlack = args[0] == true,
            excluded = excluded,
        )
        if (hiddenNormal <= 0) return@hookAfter

        WeLogger.d(TAG, "adjusted 通讯录 contact count by -$hiddenNormal")
        result = (total - hiddenNormal).coerceAtLeast(0)
    }
}

/** Flattens a `String[]` / vararg argument into a list of non-null strings. */
private fun stringArrayArg(arg: Any?): List<String> =
    (arg as? Array<*>)?.mapNotNull { it as? String }.orEmpty()

/**
 * How many of the hidden contacts WeChat's own 「N 位联系人」 query would have counted.
 *
 * Mirrors `getNormalContactCount`'s predicates: a normal, non-hidden-flag contact
 * (`type & 1 != 0`, `type & 32 = 0`), not a 公众号 (`verifyFlag & 8 = 0`), optionally excluding the
 * blacklist (`type & 8 = 0`), and a plain wxid — `username not like '%@%'` is what
 * `e01.e2.b(username, "@micromsg.qq.com", …)` emits, and it is what keeps 群聊 (`@chatroom`) and
 * 企业微信 (`@openim`) out of the total.
 *
 * Accepted limitation: `username not like '%@%'` is hardcoded here, but `e2.b` forks on the
 * `usernameFlag` experiment (`com.tencent.mm.contact.d.f93632g.a()`, `e01/e2.java:434` on 8.0.76) and
 * emits `and ( usernameFlag in ( 0 ) )` instead when it is on. The two are semantically the same
 * classification — `usernameFlag` is the denormalised form of "is this a plain wxid" — so the mirror
 * stays correct in practice. If that column ever lags behind `username` for some row, WeChat's total
 * and this mirror would disagree about that row and the footer could be over-subtracted by one. Not
 * worth a second query path: the failure mode is a slightly wrong count, never a wrong row.
 *
 * [excluded] mirrors the two vararg groups WeChat turns into `and rcontact.username != '<each>'`
 * terms. Skipping them would over-subtract: `ContactCountView` passes `e01.e2.f269714p` plus
 * `z1.r(), "weixin", "helper_entry", "filehelper"`, and entries like `filehelper` or `medianote`
 * satisfy `type & 1` and contain no `@`, so they are pickable as hidden contacts yet were never in
 * WeChat's total to begin with.
 *
 * The `or username = 'weixin'` disjunct is reproduced too, parenthesised: it re-admits 微信团队
 * unconditionally, whatever its flags, so a hidden `weixin` really is in WeChat's total.
 *
 * The statement runs on WeKit's own database handle rather than WeChat's storage wrapper, so it
 * cannot re-enter the wrapper hook. Its formatting deliberately differs from WeChat's
 * (`type & 1 != 0` vs `type & 1 !=0`, and the hidden-set predicate comes first) so it can never be
 * mistaken for the query the `group-count` rule matches either.
 */
private fun countHiddenNormalContacts(
    hidden: Set<String>,
    includeBlack: Boolean,
    excluded: Set<String>,
): Int {
    // `IN ()` is a SQLite syntax error; the caller already bails, this is belt-and-braces.
    if (hidden.isEmpty()) return 0
    if (!WeDatabaseApi.isReady) return 0

    val blacklistClause = if (includeBlack) "" else "and type & 8 = 0 "
    val excludedClause =
        if (excluded.isEmpty()) "" else "and username not in (${excluded.toSqlList()}) "
    val sql = "select count(username) from rcontact " +
            "where username in (${hidden.toSqlList()}) " +
            "and ( ( type & 1 != 0 and type & 32 = 0 $blacklistClause" +
            "and verifyFlag & 8 = 0 and username not like '%@%' " +
            "$excludedClause) or username = 'weixin' )"

    val value = WeDatabaseApi.executeQuery(sql).firstOrNull()?.values?.firstOrNull()
    return (value as? Long)?.toInt() ?: 0
}

// ---------------------------------------------------------------------------------------------
// 群成员列表
// ---------------------------------------------------------------------------------------------

/**
 * Group-member lists cannot be filtered in SQL: the members live in `chatroom.memberlist`, a single
 * `;`-separated text column, so a row that contains a hidden contact also contains everyone else.
 * Both member UIs do, however, start from a plain `List<String>` of usernames, which is the ideal
 * place to cut.
 *
 * - 查看全部群成员 (`SeeRoomMemberUI`): its adapter is fed by `cc.d(List usernames)`, which clears
 *   its item list and rebuilds it from the argument. Filtering the argument in a `hookBefore` means
 *   the adapter's list, `getCount()` and `getItem()` are all built from the same filtered input.
 * - `SelectMemberUI` and the subclasses that inherit its `V6()` (`j7()` on 8.0.69): the adapter's
 *   loader Runnable reads it once and builds its `bd` items from it, so a `hookAfter` returning a
 *   filtered copy is equivalent. Identical on 8.0.69/8.0.74/8.0.76, the inheriting UIs are
 *   `SelectDelMemberUI` (删除成员), `SelectAddRoomManagerUI` (添加管理员),
 *   `TransferRoomOwnerUI` (转让群主) and `SeeMemberRecordUI` (群成员记录) — there is **no** invite UI
 *   among them, despite what an earlier version of this comment and the feature description claimed.
 *
 * `V6()` returns `ChatroomInfo.z0()`, which **caches** the parsed member list in a field and hands
 * back the same instance every time — so this must never filter in place. A fresh `ArrayList` is
 * returned instead, and only when something was actually removed, leaving WeChat's cache (used by
 * member counts, @全体 delivery and message routing) untouched.
 *
 * NB: `SelectDelRoomManagerUI` / `SelectRoomFollowMemberManagerUI` override `V6()` without calling
 * super, so the 管理员 lists are deliberately not covered — they are a different surface.
 */
private fun HideContacts.installGroupMemberHooks() {
    if (methodSeeRoomMemberSetMemberList.isPlaceholder) {
        WeLogger.w(TAG, "SeeRoomMemberUI adapter setter wasn't resolved; 查看全部群成员 stays unfiltered")
    } else {
        methodSeeRoomMemberSetMemberList.hookBefore {
            if (isTemporarilyShown) return@hookBefore
            val members = args[0] as? List<*> ?: return@hookBefore
            val filtered = filterHiddenUsernames(members) ?: return@hookBefore
            WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from SeeRoomMemberUI")
            args[0] = filtered
        }
    }

    if (methodSelectMemberUiGetMemberList.isPlaceholder) {
        WeLogger.w(TAG, "SelectMemberUI member-list getter wasn't resolved; 群成员选择器 stays unfiltered")
        return
    }

    methodSelectMemberUiGetMemberList.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        val members = result as? List<*> ?: return@hookAfter
        val filtered = filterHiddenUsernames(members) ?: return@hookAfter
        WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from SelectMemberUI")
        result = filtered
    }
}

/**
 * Returns a copy of [members] without the hidden usernames, or `null` when nothing was removed —
 * so callers can leave the host's own (possibly cached) list object completely alone.
 */
private fun filterHiddenUsernames(members: List<*>): ArrayList<Any?>? {
    if (members.isEmpty()) return null
    val filtered = members.filterNot { it is String && HideContacts.isHiddenNow(it) }
    if (filtered.size == members.size) return null
    return ArrayList(filtered)
}

// ---------------------------------------------------------------------------------------------
// 收藏
// ---------------------------------------------------------------------------------------------

/**
 * 收藏 rows carry their sender in `FavItemInfo.field_fromUser`, but the plugin has largely migrated
 * to the WCDB ORM builder (`r82.e` / `br5.f`, see `fav/ui/adapter/c.java:349-360`), which produces
 * no raw SQL for the query rewriter to intercept. Only the legacy path still emits a statement.
 *
 * `FavoriteAdapter` funnels *both* paths — the ORM branch, the legacy branch, the search branch and
 * the "get null list, new empty one" fallback — through one private setter, `r(List)` (`t(List)` on
 * 8.0.69), which is the sole writer of the adapter's pending data list. Filtering its argument
 * therefore covers every load path at once, and since `getCount()`/`getItem()` both read the list
 * this method installs (after `notifyDataSetChanged` swaps it in), positions can never desync.
 *
 * The filtered result is handed over as a fresh `ArrayList` rather than removed in place: some call
 * sites pass Kotlin's immutable `EmptyList` singleton, whose iterator rejects `remove()`.
 */
private fun HideContacts.installFavoriteHooks() {
    if (methodFavoriteAdapterSetDataList.isPlaceholder) {
        WeLogger.w(TAG, "FavoriteAdapter data setter wasn't resolved; 收藏 stays unfiltered")
        return
    }

    methodFavoriteAdapterSetDataList.hookBefore {
        if (isTemporarilyShown) return@hookBefore

        // `r(null)` is a real call shape (FavApiLogic returns null when the storage is missing),
        // so this must tolerate a null argument rather than assume a list.
        val items = args[0] as? List<*> ?: return@hookBefore
        if (items.isEmpty()) return@hookBefore

        val sample = items.firstNotNullOfOrNull { it } ?: return@hookBefore
        // `field_fromUser` is a real (unobfuscated) storage column name, declared on FavItemInfo's
        // generated base class — hence the superclass walk.
        val fromUserField = sample.reflekt()
            .firstFieldOrNull {
                name = "field_fromUser"
                superclass()
            }?.self?.makeAccessible()
        if (fromUserField == null) {
            WeLogger.w(TAG, "FavItemInfo.field_fromUser not found; 收藏 stays unfiltered")
            return@hookBefore
        }

        val filtered = items.filterNot { item ->
            val fromUser = item?.let { fromUserField.get(it) as? String } ?: return@filterNot false
            isHiddenNow(fromUser)
        }
        if (filtered.size == items.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${items.size - filtered.size} hidden favourite(s)")
        args[0] = ArrayList(filtered)
    }
}

// ---------------------------------------------------------------------------------------------
// 视频号「朋友❤过」
// ---------------------------------------------------------------------------------------------

/**
 * Index of `wxUsername` in the like-entry protobuf (`a65.je1` on 8.0.76, `mx4.v91` on 8.0.69).
 *
 * The generated field table is identical on both trees — `0=nickName, 1=headImgUrl, 2=likeId,
 * 3=likeFlag, 4=refuseFlag, 5=wxUsername, …, 11=finder_username` — and index 5 is the only entry
 * that is a real wxid. `finder_username` is a `v2_…` Finder identity with no mapping back to a
 * contact, which is why the Finder follow/aggregation lists are explicitly out of scope.
 */
private const val LIKE_ENTRY_WX_USERNAME = 5

/**
 * Hides a hidden contact from the 视频号 like list ("朋友❤过" / the ❤ drawer).
 *
 * Finder feeds have no local table, so there is nothing for the SQL rewriter to touch. The drawer
 * presenter (`FinderLikeDrawerPresenter`, and its "朋友❤过" subclass
 * `FinderFriendLikeListDrawerPresenter`) keeps one `ArrayList` of `FinderFeedLike` items that is
 * handed straight to `WxRecyclerAdapter` as its backing list, and exactly two callbacks append to
 * it: the refresh callback and the load-more callback. Both build their items in a loop over an
 * incoming list of raw like protobufs, so filtering that input list before the loop keeps the
 * adapter's list, its item count and the `notifyItemRangeInserted` offsets all in agreement — which
 * a `FinderFeedFriendLikeConvert.onBindViewHolder` hook could never do, since a bind cannot remove a
 * row.
 *
 * The refresh callback receives a `GetFinderFeedLikedListData` holder whose single `LinkedList`
 * field is the like list; it is a per-response object with no other consumer, so it is filtered in
 * place. The load-more callback receives the list directly, and gets a filtered copy instead —
 * cheaper than proving the network layer does not retain it.
 *
 * Accepted limitation (paging): the presenter decides whether more pages exist partly from how many
 * rows a page yielded, so a page whose entries are *all* hidden can look empty and stop the paging
 * loop early. The consequence is only "fewer rows than the server would have shown" — never a wrong
 * or misattributed row — and it self-corrects the next time the list is refreshed or the next page
 * is requested, since the server-side cursor is untouched. Not worth faking a row count for.
 */
private fun HideContacts.installFinderLikeHooks() {
    if (methodFinderLikeDrawerRefresh.isPlaceholder) {
        WeLogger.w(TAG, "Finder like-drawer refresh callback wasn't resolved; 视频号点赞列表 stays unfiltered")
    } else {
        methodFinderLikeDrawerRefresh.hookBefore {
            if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

            val data = args[0] ?: return@hookBefore
            val listField = data.reflekt()
                .firstFieldOrNull { type = LinkedList::class }
                ?.self?.makeAccessible() ?: return@hookBefore

            @Suppress("UNCHECKED_CAST")
            val likes = listField.get(data) as? MutableList<Any?> ?: return@hookBefore
            val removed = likes.removeAll { it != null && isHiddenLikeEntry(it) }
            if (removed) WeLogger.d(TAG, "filtered hidden contacts out of the finder like list")
        }
    }

    if (methodFinderLikeDrawerLoadMore.isPlaceholder) {
        WeLogger.w(TAG, "Finder like-drawer load-more callback wasn't resolved; 视频号点赞列表 stays unfiltered")
        return
    }

    methodFinderLikeDrawerLoadMore.hookBefore {
        if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

        val likes = args[0] as? List<*> ?: return@hookBefore
        val filtered = likes.filterNot { it != null && isHiddenLikeEntry(it) }
        if (filtered.size == likes.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${likes.size - filtered.size} hidden like(s) from the finder like list")
        args[0] = ArrayList(filtered)
    }
}

private fun HideContacts.isHiddenLikeEntry(entry: Any): Boolean {
    val wxId = protoGetString(entry, LIKE_ENTRY_WX_USERNAME) ?: return false
    return isHiddenNow(wxId)
}

/**
 * Reads a string field out of a `com.tencent.mm.protobuf.e` subclass by its *position* in the
 * generated field table. The concrete protobuf classes are obfuscated and renamed every version
 * (`a65.je1` -> `mx4.v91`), but `getString(int)` is declared on the unobfuscated base class, so it
 * is resolved by walking up the hierarchy — mirroring `WeMomentsApi.xs4GetString`.
 */
private fun protoGetString(proto: Any, fieldIndex: Int): String? = runCatching {
    var cls: Class<*>? = proto.javaClass
    while (cls != null) {
        val getter = cls.declaredMethods.firstOrNull {
            it.name == "getString" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (getter != null) {
            getter.isAccessible = true
            return@runCatching getter.invoke(proto, fieldIndex) as? String
        }
        cls = cls.superclass
    }
    null
}.getOrNull()
