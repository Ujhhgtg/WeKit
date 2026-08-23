package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeGroup
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.contacts.DeleteFakeGroups.LEGIT_CHATROOM_REGEX
import dev.ujhhgtg.wekit.features.items.contacts.DeleteFakeGroups.isFakeGroup
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.concurrent.thread

object DeleteFakeGroups : ClickableFeature() {

    override val technicalId = "删除假群组"
    override val nameRes = R.string.feature_delete_fake_groups_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_delete_fake_groups_description

    private const val TAG = "DeleteFakeGroups"

    /** Matches a legitimate chatroom wxid composed only of digits and the standard suffix. */
    private val LEGIT_CHATROOM_REGEX = Regex("""^\d+@chatroom$""")

    override fun onClick(context: ComponentActivity) {
        val fakeGroups = getFakeGroups()
        if (fakeGroups.isEmpty()) {
            showToast(localizedContactsString(R.string.contacts_delete_fake_none))
            return
        }

        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedContactsQuantity(
                    R.plurals.contacts_delete_fake_select_title,
                    fakeGroups.size,
                    fakeGroups.size,
                ),
                contacts = fakeGroups,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedIds ->
                    if (selectedIds.isEmpty()) {
                        showToast(localizedContactsString(R.string.contacts_delete_fake_select_one))
                        return@ContactsSelector
                    }
                    onDismiss()
                    confirmAndDelete(context, selectedIds, fakeGroups)
                }
            )
        }
    }

    private fun confirmAndDelete(
        context: ComponentActivity,
        selectedIds: Set<String>,
        fakeGroups: List<WeGroup>
    ) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.contacts_delete_fake_confirm_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.contacts_delete_fake_confirm_message,
                            selectedIds.size,
                            selectedIds.size,
                        ),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        thread(name = "DeleteFakeGroupsThread") {
                            deleteFakeGroups(
                                selectedIds,
                                fakeGroups.associate { it.wxId to it.nickname },
                            )
                            runOnUiThread {
                                showToast(
                                    localizedContactsQuantity(
                                        R.plurals.contacts_delete_fake_done,
                                        selectedIds.size,
                                        selectedIds.size,
                                    ),
                                )
                            }
                        }
                    }) { Text(stringResource(R.string.action_delete)) }
                }
            )
        }
    }

    /**
     * Hard-delete the fake groups identified by [fakeGroupIds], then refresh the conversation list
     * once after the whole batch. Callers that generated exact IDs can reuse this without scanning
     * every fake group currently present in the database.
     */
    internal fun deleteFakeGroups(
        fakeGroupIds: Collection<String>,
        displayNames: Map<String, String> = emptyMap(),
    ) {
        fakeGroupIds.distinct().forEach { id ->
            deleteFakeGroup(id, displayNames[id].orEmpty().ifEmpty { id })
        }
        if (fakeGroupIds.isNotEmpty()) WeConversationApi.reloadConversations()
    }

    /**
     * Hard-delete all DB rows belonging to [fakeGroupId].
     *
     * A group is considered fake when its wxid contains `@chatroom` but does not fully match
     * `<digits>@chatroom`.
     *
     * Tables touched:
     *   rcontact        — contact/group identity row
     *   rconversation   — conversation list entry
     *   chatroom        — group metadata & roster
     *   message         — chat messages
     *   ImgInfo2        — image/video transfer metadata
     *   img_flag        — avatar cache entry
     *   GroupBindApp    — bound mini-program data
     *   GroupSolitatire — 接龙 records
     *   GroupTodo       — group to-do items
     *   GroupTools      — pinned/recent tool list
     *   MsgQuote        — quoted-message index pointing at this group
     *
     * Deliberately does NOT go through WeChat's native delete path (which would
     * sync to the server and could affect the real group sharing the same numeric ID).
     */
    private fun deleteFakeGroup(fakeGroupId: String, name: String) {
        WeLogger.i(TAG, "deleting fake group: $fakeGroupId ($name)")

        // Ordered from most-derived to most-foundational so foreign-key-like dependencies
        // (message → conversation → contact) are cleaned up outward-in.
        val steps: List<Pair<String, () -> Int>> = listOf(
            "message" to { WeDatabaseApi.delete("message", "talker=?", arrayOf(fakeGroupId)) },
            "ImgInfo2" to { WeDatabaseApi.delete("ImgInfo2", "msgTalker=?", arrayOf(fakeGroupId)) },
            "MsgQuote" to { WeDatabaseApi.delete("MsgQuote", "quotedMsgTalker=?", arrayOf(fakeGroupId)) },
            "GroupBindApp" to { WeDatabaseApi.delete("GroupBindApp", "chatRoomName=?", arrayOf(fakeGroupId)) },
            "GroupSolitatire" to { WeDatabaseApi.delete("GroupSolitatire", "username=?", arrayOf(fakeGroupId)) },
            "GroupTodo" to { WeDatabaseApi.delete("GroupTodo", "roomname=?", arrayOf(fakeGroupId)) },
            "GroupTools" to { WeDatabaseApi.delete("GroupTools", "chatroomname=?", arrayOf(fakeGroupId)) },
            "chatroom" to { WeDatabaseApi.delete("chatroom", "chatroomname=?", arrayOf(fakeGroupId)) },
            "rconversation" to { WeDatabaseApi.delete("rconversation", "username=?", arrayOf(fakeGroupId)) },
            "img_flag" to { WeDatabaseApi.delete("img_flag", "username=?", arrayOf(fakeGroupId)) },
            // rcontact last: it's the identity anchor that WeChat caches most aggressively
            "rcontact" to { WeDatabaseApi.delete("rcontact", "username=?", arrayOf(fakeGroupId)) },
        )

        var anyError = false
        for ((table, op) in steps) {
            try {
                val rows = op()
                WeLogger.d(TAG, "  $table: deleted $rows row(s)")
            } catch (e: Exception) {
                WeLogger.w(TAG, "  $table: delete failed", e)
                anyError = true
                // Continue — partial cleanup is still better than nothing
            }
        }

        WeLogger.i(TAG, "fake group deletion complete: $fakeGroupId (anyError=$anyError)")
    }

    /**
     * Returns all fake groups currently in rcontact. The query prefilters wxids containing
     * `@chatroom`, then [isFakeGroup] excludes legitimate `<digits>@chatroom` identifiers.
     */
    private fun getFakeGroups(): List<WeGroup> {
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                """
                SELECT r.username, r.nickname, r.pyInitial, r.quanPin, i.reserved2 AS avatarUrl
                FROM rcontact r
                LEFT JOIN img_flag i ON r.username = i.username
                WHERE r.username LIKE '%@chatroom%'
                """.trimIndent()
            )
            val result = mutableListOf<WeGroup>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val wxId = c.getString(0) ?: continue
                    if (!wxId.isFakeGroup()) continue
                    result += WeGroup(
                        wxId = wxId,
                        nickname = c.getString(1) ?: "",
                        nicknameShortPinyin = c.getString(2) ?: "",
                        nicknamePinyin = c.getString(3) ?: "",
                        avatarUrl = c.getString(4) ?: ""
                    )
                }
            }
            result
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to query fake groups", e)
            emptyList()
        }
    }

    /** Returns true when the wxid contains `@chatroom` but is not a legitimate chatroom wxid. */
    private fun String.isFakeGroup(): Boolean =
        contains("@chatroom") && !LEGIT_CHATROOM_REGEX.matches(this)

    override val noSwitchWidget = true
}
