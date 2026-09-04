package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import java.util.LinkedList

private const val TAG = "HideContacts.Moments"

/** Every Moments-side surface a hidden contact can leak through. */
fun HideContacts.installMomentsHooks() {
    installMomentsRedDotHook()
    installMomentsInlineEntryHook()
}

/**
 * Keeps a hidden contact out of the 发现 tab's "N 位朋友的新动态" avatar + red dot.
 *
 * On 8.0.76 that entry is a *single* avatar, not an avatar strip, and the avatar it draws is driven
 * by one user-info KV pair: 68377 holds the wxid. "68377 is non-empty" is only *one of two* terms of
 * the dot predicate, though — `FindMoreFriendsUI.java:916-918` computes it as
 * `!(t8.K0(this.f226904x) && this.f226907y == 0)`, i.e. "68377 non-empty **OR** there are unread
 * likes/comments" (`f226907y` is `SnsCommentStorage.O0()`, `FindMoreFriendsUI.java:904`). The
 * bottom-tab dot in `com/tencent/mm/ui/ie.java:494` gates on 68377 alone. So suppressing 68377
 * removes the avatar and the 68377-attributable dot, but a hidden contact who likes or comments on a
 * post *you* can see still counts towards the unread-comment term — that surface is the inline
 * like/comment hook below, not this one.
 *
 * There is exactly one writer of that KV in the sync path — `NetSceneSnsSync.updateSyncDataCache`,
 * inlined by the decompiler into the synthetic accessor `static c3.H(c3, SnsObject)` at
 * `com/tencent/mm/plugin/sns/model/c3.java:103` (`c3.I` on 8.0.69), whose `:134` does
 * `j1.u().c().w(68377, snsObject.Username)`.
 *
 * So the fix is to cancel that whole cache write when the post's author is hidden, rather than to
 * patch the avatar view: it kills the avatar and the red dot in one place, and every other consumer
 * of KV 68377 inherits the fix for free — the chat-list banner
 * (`com/tencent/mm/ui/conversation/banner/z.java:44`) and the `+` menu's 朋友圈 entry
 * (`com/tencent/mm/ui/rg.java:371`) both read 68377 purely to decide whether to open Moments in
 * "there is something new" mode.
 *
 * Cancelling — as opposed to rewriting `Username` to `""` — is deliberate. `H` writes 68377 (author)
 * together with 68422/68418 (feed id), 68400 (create time) and 68421 (WeiShang feed type), and
 * `w2`'s `isNeedToUpdateRedDot` compares the *next* incoming post against all of them. Skipping the
 * method leaves those five fields internally consistent, still describing the last non-hidden post
 * that legitimately earned the red dot — which is exactly the state the user should keep seeing.
 * Blanking only the username would leave the other four fields pointing at a post nobody can see.
 *
 * One caveat to "consistent": `H` also *resets* the 68419/68420 counters, and cancelling it skips
 * those resets. 68420 is the "held the old red dot N times in a row" counter that
 * `isNeedToUpdateRedDot` increments on the branch where it does *not* call `H`
 * (`w2.java:191`), then compares against `clicfg_sns_red_dot_compare_times` (default 10). With `H`
 * permanently cancelled for hidden authors that counter only ever grows, so after ~10 suppressed
 * syncs WeChat starts biasing towards showing a dot for the next non-hidden post. That is benign —
 * it can only cause an *extra* dot for a post the user is allowed to see, never a leak — so it is
 * accepted rather than worked around.
 *
 * The method returns `void`, so cancellation is `result = null`, and it neither reads nor writes any
 * state we touch — there is no way for this hook to re-trigger itself.
 */
private fun HideContacts.installMomentsRedDotHook() {
    methodSnsSyncUpdateRedDotCache.hookBefore {
        // Static two-arg method: args[0] is the NetSceneSnsSync instance the synthetic accessor was
        // handed, args[1] is the SnsObject. `Username` is a real (unobfuscated) protobuf field.
        val snsObject = args.getOrNull(1) as? SnsObject ?: return@hookBefore
        val username = snsObject.Username ?: return@hookBefore
        if (!isHiddenNow(username)) return@hookBefore
        WeLogger.i(TAG, "suppressing moments red-dot cache update for $username")
        result = null
    }
}

/**
 * Hides a hidden contact's likes/comments that surface inline under a *mutual friend's* Moments
 * post.
 *
 * These never touch the `SnsComment` table, so the "moments-comments" SQL rule in
 * HideContactsSql.kt (WRAPPER_RULES) — which filters rows selected `from SnsComment` — never sees
 * them. WeChat instead serializes them straight into the post's own protobuf blob:
 * `SnsInfo.attrBuf` -> `SnsObject.LikeUserList` / `CommentUserList` (`a65.ha6` entries).
 *
 * `fb4.z0.D0` ("SnsUtil.snsInfoToSnsStruct") is the single chokepoint that turns that raw
 * `SnsObject` into the UI-facing `dt` struct, for both Moments renderers that exist in 8.0.76 (the
 * classic feed item and the "improve" recycler feed — see the two call sites, `vc4/g0.java:76` and
 * `.../ui/improve/component/f2.java:765`). Filtering there means every renderer downstream already
 * gets a clean object.
 */
private fun HideContacts.installMomentsInlineEntryHook() {
    // `a65.ha6`'s own field names are obfuscated and drift across versions, and the class name
    // itself isn't guaranteed either — so it is never referenced directly. Instead we borrow the
    // same trick FakeMomentsLikes already uses: ha6 is also the return type of
    // SnsCommentFooter.getCommentInfo(), and that method name is real (not obfuscated).
    val entryClass = runCatching {
        SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve the SNS like/comment entry class; moments inline hiding unavailable", it)
        return
    }

    // Declared String fields, in source order, are [Username, Nickname, Content, ReplyUsername,
    // ...] — verified against a65/ha6.java (f9152d/f9153e/f9156h/f9160o) and cross-checked in
    // com/tencent/mm/plugin/sns/ui/widget/t2.java, which reads the same four fields by their real
    // names. Selecting by declared type + ordinal (mirrors SplitGroupCall's ILinkMember lookup and
    // HideContactsVoip.readMultiTalkInvite) survives a field rename; hardcoding "f9152d" would not.
    val stringFields = entryClass.reflekt().fields { type = BString }.map { it.self.makeAccessible() }
    val usernameField = stringFields.getOrNull(0)
    val replyUsernameField = stringFields.getOrNull(3)
    if (usernameField == null || replyUsernameField == null) {
        WeLogger.w(TAG, "failed to resolve username/replyUsername fields on the SNS entry class; moments inline hiding unavailable")
        return
    }

    // SnsObject (com.tencent.mm.protobuf.f) has no clone(); the only reliable way to duplicate one
    // is a full serialize + reparse round trip through the protobuf codec it already implements.
    // parseFrom() isn't declared on the SnsObject stub (only toByteArray()/the LikeUserList family
    // are), so it is resolved reflectively once here, exactly as FakeMomentsLikes already does.
    val parseFromMethod = runCatching {
        SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve SnsObject.parseFrom; moments inline hiding unavailable", it)
        return
    }

    fun isEntryHidden(entry: Any): Boolean {
        val username = usernameField.get(entry) as? String
        if (username != null && isHiddenNow(username)) return true
        // A reply that quotes a hidden contact's own comment ("回复 张三: ...") still names them,
        // even when the reply's own author isn't hidden — so it must be stripped too.
        val replyUsername = replyUsernameField.get(entry) as? String
        return replyUsername != null && isHiddenNow(replyUsername)
    }

    methodSnsInfoToSnsStruct.hookBefore {
        // isEntryHidden() below already re-checks isHiddenNow() per-entry (so #show / triple-tap
        // keep working); this is purely a fast path to skip all reflection when nothing is hidden.
        if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

        val original = args.getOrNull(1) as? SnsObject ?: return@hookBefore

        val likeList = original.LikeUserList as? List<*>
        val commentList = original.CommentUserList as? List<*>
        val hasHiddenLike = likeList?.any { it != null && isEntryHidden(it) } == true
        val hasHiddenComment = commentList?.any { it != null && isEntryHidden(it) } == true
        if (!hasHiddenLike && !hasHiddenComment) return@hookBefore

        // MUST operate on a clone, never on `original` directly: SnsInfoStorageLogic.e (s5.e in
        // the decompile) caches the parsed SnsObject keyed by a content hash and hands back that
        // SAME instance on every later render of this post. Stripping entries in place would
        // permanently truncate the cached object; the next time anyone (dis)likes or comments and
        // this object is re-serialized back into SnsInfo.attrBuf, the entries we removed here would
        // be gone from what gets persisted, not just from what gets displayed.
        val clone = SnsObject().also { parseFromMethod.invoke(it, original.toByteArray()) }

        if (hasHiddenLike) {
            val filtered = LinkedList((clone.LikeUserList as List<*>).filterNotNull().filterNot(::isEntryHidden))
            clone.LikeUserList = filtered
            clone.LikeUserListCount = filtered.size
            clone.LikeCount = filtered.size
        }
        if (hasHiddenComment) {
            val filtered = LinkedList((clone.CommentUserList as List<*>).filterNotNull().filterNot(::isEntryHidden))
            clone.CommentUserList = filtered
            clone.CommentUserListCount = filtered.size
            clone.CommentCount = filtered.size
        }

        args[1] = clone
    }
}
