package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeGroup
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.concurrent.thread

@Feature(
    name = "删除假群组",
    categories = ["娱乐"],
    description = "彻底清除由「分裂群组」功能产生的假群 (仅清除本地数据库，不影响原群)"
)
object DeleteSplitGroupChats : ClickableFeature() {

    private const val TAG = "DeleteSplitGroupChats"

    override fun onClick(context: ComponentActivity) {
        val fakeGroups = getFakeGroups()
        if (fakeGroups.isEmpty()) {
            showToast("未发现假群组!")
            return
        }

        showComposeDialog(context) {
            ContactsSelector(
                title = "删除假群组 (共 ${fakeGroups.size} 个)",
                contacts = fakeGroups,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedIds ->
                    if (selectedIds.isEmpty()) {
                        showToast("请选择至少一个假群")
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
                title = { Text("确认删除") },
                text = { Text("确定要删除选中的 ${selectedIds.size} 个假群组吗? 此操作不可逆，原群不受影响。") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        thread(name = "DeleteSplitGroupsThread") {
                            selectedIds.forEach { id ->
                                val name = fakeGroups.firstOrNull { it.wxId == id }?.nickname ?: id
                                deleteFakeGroup(id, name)
                            }
                            runOnUiThread {
                                showToast("已清除 ${selectedIds.size} 个假群组")
                            }
                        }
                    }) { Text("删除") }
                }
            )
        }
    }

    /**
     * Hard-delete all DB rows belonging to [fakeGroupId] (format: `xxx@@chatroom`).
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
            "message"         to { WeDatabaseApi.delete("message",        "talker=?",         arrayOf(fakeGroupId)) },
            "ImgInfo2"        to { WeDatabaseApi.delete("ImgInfo2",       "msgTalker=?",      arrayOf(fakeGroupId)) },
            "MsgQuote"        to { WeDatabaseApi.delete("MsgQuote",       "quotedMsgTalker=?",arrayOf(fakeGroupId)) },
            "GroupBindApp"    to { WeDatabaseApi.delete("GroupBindApp",   "chatRoomName=?",   arrayOf(fakeGroupId)) },
            "GroupSolitatire" to { WeDatabaseApi.delete("GroupSolitatire","username=?",       arrayOf(fakeGroupId)) },
            "GroupTodo"       to { WeDatabaseApi.delete("GroupTodo",      "roomname=?",       arrayOf(fakeGroupId)) },
            "GroupTools"      to { WeDatabaseApi.delete("GroupTools",     "chatroomname=?",   arrayOf(fakeGroupId)) },
            "chatroom"        to { WeDatabaseApi.delete("chatroom",       "chatroomname=?",   arrayOf(fakeGroupId)) },
            "rconversation"   to { WeDatabaseApi.delete("rconversation",  "username=?",       arrayOf(fakeGroupId)) },
            "img_flag"        to { WeDatabaseApi.delete("img_flag",       "username=?",       arrayOf(fakeGroupId)) },
            // rcontact last: it's the identity anchor that WeChat caches most aggressively
            "rcontact"        to { WeDatabaseApi.delete("rcontact",       "username=?",       arrayOf(fakeGroupId)) },
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

        // Refresh the conversation list so the entry disappears immediately
        WeConversationApi.reloadConversations()
    }

    /**
     * Returns all fake groups (username LIKE '%@@chatroom') currently in rcontact.
     * The double-@ prefix is the fingerprint left by [SplitGroupChats]: it builds
     * the split-chat ID as `"${rawId}@@chatroom"` from the original `rawId@chatroom`.
     */
    private fun getFakeGroups(): List<WeGroup> {
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                """
                SELECT r.username, r.nickname, r.pyInitial, r.quanPin, i.reserved2 AS avatarUrl
                FROM rcontact r
                LEFT JOIN img_flag i ON r.username = i.username
                WHERE r.username LIKE '%@@chatroom'
                """.trimIndent()
            )
            val result = mutableListOf<WeGroup>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    result += WeGroup(
                        wxId = c.getString(0) ?: continue,
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

    override val noSwitchWidget = true
}
