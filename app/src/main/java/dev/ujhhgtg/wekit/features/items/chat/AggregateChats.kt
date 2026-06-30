package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.conversation.BaseConversationUI
import com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI
import com.tencent.mm.ui.conversation.MainUI
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.contacts.CustomLocalFriendAvatars
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.lang.reflect.Modifier as JavaModifier

@Feature(name = "对话归拢", categories = ["聊天"], description = "将多个对话归拢在一个文件夹内\n设置对话头像需同时启用「自定义好友本地头像」")
object AggregateChats : ClickableFeature(),
    WeDatabaseListenerApi.IQueryListener,
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    WeStartActivityApi.IStartActivityListener,
    IResolveDex {

    private val TAG = This.Class.simpleName
    private const val FOLDER_PREFIX = "wekit_folder_"
    private const val FOLDER_CONFIG_MENU_ID = 0x0721C0DE

    // rconversation.flag packing (see WeChat xg3.b.c): high 8 bits = pin / move-up state
    // owned by WeChat (setPlacedTop / unSetPlacedTop), low 56 bits = conversationTime.
    private const val FLAG_TIME_MASK = 0x00FFFFFFFFFFFFFFL
    private const val FLAG_HIGH_MASK = FLAG_TIME_MASK.inv()

    // A conversation is muted when rcontact.type has bit 512 set (WeChat c01.e2.P).
    private const val CONTACT_TYPE_MUTE_BIT = 512
    // attrflag bit the conversation box uses to mark "has muted unread" so the homepage
    // badge renders a small dot instead of a number (WeChat w3.b / s2 require this bit set
    // alongside unReadMuteCount > 0 when unReadCount == 0).
    private const val ATTR_FLAG_MUTE_BIT = 2097152

    private val foldersFile by lazy { KnownPaths.moduleData / "chat_folders.json" }

    private const val CONTAINER_UI_NAME = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
    private val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }
    private val methodConversationStorageQueryByParent by dexMethod(allowFailure = true) {
        matcher {
            usingStrings(
                "select * from rconversation where ",
                " order by flag desc, conversationTime desc"
            )
            paramTypes("int", "java.util.List", "java.lang.String", "int")
            returnType("android.database.Cursor")
        }
    }

    @Volatile
    private var activeFolderId: String? = null

    @Volatile
    private var folderSchemaReady: Boolean? = null

    @Volatile
    private var foldersCache: List<ChatFolder>? = null

    private val folderMembersCache = ConcurrentHashMap<String, List<String>>()

    // High 8 bits (pin / move-up) of each folder row, captured before clearStaleFolderMappings
    // deletes the row, so the pin state can be restored when syncFolder recreates it.
    private val folderPinFlags = ConcurrentHashMap<String, Long>()

    private val suppressQueryRewrite = ThreadLocal.withInitial { false }

    // Reactive refresh: WeChat updates member conversation rows (new message / read state)
    // through the ContentValues insert/update path, but our materialized folder rows are
    // written via raw execSQL and never recomputed until MainUI.onResume. We listen for
    // member-row writes and debounce a lightweight summary recompute so the homepage folder
    // row tracks its members in real time.
    private const val REFRESH_DEBOUNCE_MS = 250L
    private val REFRESH_TASK_TOKEN = Any()

    @Volatile
    private var refreshThread: HandlerThread? = null

    @Volatile
    private var refreshHandler: Handler? = null

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        WeStartActivityApi.addListener(this)

        startRefreshThread()

        hookMainUiRefresh()
        hookOpenFolder()
        hookConversationPages()
        hookSqliteWrapperQuery()
        hookConversationStorageParentQuery()

        CustomLocalFriendAvatars.fallbackUsernameProvider = { folderId ->
            if (isFolderId(folderId) && !CustomLocalFriendAvatars.avatarMap.containsKey(folderId)) {
                getFallbackAvatarMember(folderId)
            } else {
                null
            }
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeStartActivityApi.removeListener(this)
        CustomLocalFriendAvatars.fallbackUsernameProvider = null
        stopRefreshThread()
    }

    override fun onClick(context: Context) {
        showManagerDialog(context)
    }

    // Called by WeDatabaseListenerApi when WeChat inserts a conversation row
    override fun onInsert(table: String, values: ContentValues) {
        if (table != ConversationTable.NAME) return
        val username = values.getAsString(ConversationTable.USERNAME) ?: return
        if (isFolderId(username)) return  // skip our own folder row writes
        scheduleRefresh()
    }

    // Called by WeDatabaseListenerApi when WeChat updates conversation rows
    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != ConversationTable.NAME) return
        // Skip updates that target only folder rows
        val targetUsername = whereArgs?.singleOrNull()
        if (targetUsername != null && isFolderId(targetUsername)) return
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        val handler = refreshHandler ?: return
        if (loadFolders().isEmpty()) return
        handler.removeCallbacksAndMessages(REFRESH_TASK_TOKEN)
        handler.postAtTime(
            ::doRefreshFolderSummaries,
            REFRESH_TASK_TOKEN,
            SystemClock.uptimeMillis() + REFRESH_DEBOUNCE_MS
        )
    }

    private fun doRefreshFolderSummaries() {
        if (!WeDatabaseApi.isReady) return
        val folders = loadFolders()
        if (folders.isEmpty()) return
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                folders.forEach { folder ->
                    val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
                    if (members.isEmpty()) return@forEach
                    writeFolderSummaryRow(folder.id, readFolderSummary(members))
                }
            }
            WeConversationApi.reloadConversations()
        }.onFailure {
            WeLogger.e(TAG, "failed to refresh folder summaries", it)
        }
    }

    private fun startRefreshThread() {
        val thread = HandlerThread("wekit-folder-refresh").also {
            it.start()
            refreshThread = it
        }
        refreshHandler = Handler(thread.looper)
    }

    private fun stopRefreshThread() {
        refreshHandler?.removeCallbacksAndMessages(null)
        refreshHandler = null
        refreshThread?.quitSafely()
        refreshThread = null
    }

    override fun onQuery(sql: String): String? {
        if (suppressQueryRewrite.get()!!) return null

        val folderId = activeFolderId
        if (folderId != null) {
            val containerSql = rewriteContainerSql(sql, folderId)
            if (containerSql != sql) return containerSql
        }

        if (!sql.contains(ConversationTable.NAME, ignoreCase = true)) return null
        if (sql.contains(FOLDER_PREFIX, ignoreCase = true)) return null
        if (!looksLikeConversationListQuery(sql)) return null
        return appendParentRefFilter(sql)
    }

    override fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        val folderId = readFolderIdFromIntent(intent) ?: return
        val componentName = intent.component?.className
        if (componentName != CONTAINER_UI_NAME) {
            activeFolderId = folderId
            intent.setClassName(param.thisObject as? Context ?: return, CONTAINER_UI_NAME)
        }
        applyFolderContainerIntent(intent, folderId)
    }

    private fun hookMainUiRefresh() {
        MainUI::class.reflekt().firstMethod("onResume").hookAfter {
            syncFoldersToDatabase()
        }
    }

    private fun hookOpenFolder() {
        LauncherUI::class.reflekt().firstMethod("startChatting").hookBefore {
            interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                result = null
            }
        }

        BaseConversationUI::class.reflekt().firstMethod("startChatting").hookBefore {
            interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                result = null
            }
        }
    }

    private inline fun interceptFolderChatOpen(
        username: String?,
        source: Any?,
        cancelOriginal: () -> Unit
    ) {
        if (username == null || !isFolderId(username)) return
        activeFolderId = username
        launchFolderContainer(source, username)
        cancelOriginal()
    }

    private fun hookConversationPages() {
        ConvBoxServiceConversationUI::class.hookBeforeOnCreate {
            val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
            activeFolderId = readFolderIdFromIntent(activity.intent) ?: activeFolderId
        }

        BaseConversationUI::class.reflekt().apply {
            firstMethod("onResume").hookAfter {
                val activity = thisObject as? BaseConversationUI ?: return@hookAfter
                activeFolderId = activeFolderId ?: readFolderIdFromIntent(activity.intent)
                configureFolderActivity(activity)
            }

            firstMethod("onDestroy").hookAfter {
                activeFolderId = null
            }
        }
    }

    private fun hookSqliteWrapperQuery() {
        if (methodSqliteWrapperRawQuery.isPlaceholder) return
        methodSqliteWrapperRawQuery.hookBefore {
            if (suppressQueryRewrite.get()!!) return@hookBefore
            val sql = args.firstOrNull() as? String ?: return@hookBefore
            onQuery(sql)?.let { args[0] = it }
        }
    }

    private fun hookConversationStorageParentQuery() {
        if (methodConversationStorageQueryByParent.isPlaceholder) return
        methodConversationStorageQueryByParent.hookBefore {
            val folderId = activeFolderId ?: return@hookBefore
            val parentRef = args.getOrNull(2) as? String ?: return@hookBefore
            if (parentRef == WeChatFolderPlaceholder.CONVERSATION_BOX ||
                parentRef == WeChatFolderPlaceholder.MESSAGE_FOLD
            ) {
                args[2] = folderId
            }
        }
    }

    private fun launchFolderContainer(source: Any?, folderId: String) {
        val context = source as? Context ?: return
        val intent = Intent().apply {
            setClassName(context, CONTAINER_UI_NAME)
            applyFolderContainerIntent(this, folderId)
        }
        context.startActivity(intent)
    }

    private fun applyFolderContainerIntent(intent: Intent, folderId: String) {
        intent.putExtra(WeChatIntentExtra.CONTACT_USER, folderId)
        intent.putExtra(WeChatIntentExtra.CONTACT_CHAT_ROOM_ID, folderId)
        intent.putExtra(WeChatIntentExtra.ROOM_NAME, folderId)
    }

    private fun configureFolderActivity(activity: BaseConversationUI) {
        val folder = folderById(activeFolderId ?: return) ?: return
        activity.setTitle(folder.name)

        val fragment = activity.conversationFm

        // onResume may fire repeatedly; drop any previous entry before re-adding
        fragment.removeOptionMenu(FOLDER_CONFIG_MENU_ID)

        val listener = MenuItem.OnMenuItemClickListener {
            showEditFolderDialog(
                context = activity,
                folder = folder,
                onFolderUpdated = {
                    syncFoldersToDatabase()
                    configureFolderActivity(activity)
                },
                onFolderDeleted = {
                    syncFoldersToDatabase()
                    activity.finish()
                }
            )
            true
        }

        fragment.addIconOptionMenu(FOLDER_CONFIG_MENU_ID, "配置", EditIcon, listener)
    }

    private fun syncFoldersToDatabase() {
        foldersCache = null
        folderMembersCache.clear()
        val folders = loadFolders()
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                // clearStaleFolderMappings() deletes the folder rows, which would drop the
                // pin / move-up bits WeChat stored on them. Snapshot those bits first so
                // composeFolderFlag() can restore them when the rows are recreated below.
                snapshotFolderPinFlags(folders)
                clearStaleFolderMappings()
                folders.forEach { syncFolder(it) }
            }
            WeLogger.i(TAG, "synced ${folders.size} folders")
        }.onFailure {
            WeLogger.e(TAG, "failed to sync folders", it)
        }
    }

    private fun snapshotFolderPinFlags(folders: List<ChatFolder>) {
        folders.forEach { folder ->
            folderPinFlags[folder.id] = (existingFolderFlag(folder.id) ?: 0L) and FLAG_HIGH_MASK
        }
    }

    private fun clearStaleFolderMappings() {
        listOf(FOLDER_PREFIX).forEach { prefix ->
            WeDatabaseApi.execStatement(
                """
                DELETE FROM ${ConversationTable.NAME}
                WHERE ${ConversationTable.PARENT_REF} LIKE ?
                  AND ${ConversationTable.DIGEST}=''
                  AND ${ConversationTable.CONTENT}=''
                  AND ${ConversationTable.UNREAD_COUNT}=0
                  AND ${ConversationTable.CONVERSATION_TIME}=0
                  AND ${ConversationTable.FLAG}=0
                  AND ${ConversationTable.MSG_TYPE}=''
                  AND ${ConversationTable.STATUS}=0
                  AND ${ConversationTable.IS_SEND}=0
                """.trimIndent(),
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}='' WHERE ${ConversationTable.PARENT_REF} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ContactTable.NAME} WHERE ${ContactTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM img_flag WHERE username LIKE ?",
                arrayOf("$prefix%")
            )
        }
    }

    private fun syncFolder(folder: ChatFolder) {
        val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
        if (members.isNotEmpty()) {
            if (folder.type == FolderType.MANUAL) {
                ensureMemberConversationRows(folder.id, members)
            }
            val placeholders = members.joinToString(",") { "?" }
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}=? WHERE ${ConversationTable.USERNAME} IN ($placeholders)",
                arrayOf(folder.id, *members.toTypedArray())
            )
        }

        WeDatabaseApi.execStatement(
            """
            REPLACE INTO ${ContactTable.NAME} (${ContactTable.USERNAME}, ${ContactTable.NICKNAME}, ${ContactTable.TYPE}, ${ContactTable.VERIFY_FLAG})
            VALUES (?, ?, 3, 0)
            """.trimIndent(),
            arrayOf(folder.id, folder.name)
        )

        WeDatabaseApi.execStatement(
            """
            REPLACE INTO img_flag (username, imgflag, lastupdatetime, reserved1, reserved2)
            VALUES (?, 3, ?, 0, ?)
            """.trimIndent(),
            arrayOf(folder.id, System.currentTimeMillis() / 1000, "http://wekit.local/avatar/${folder.id}")
        )

        val summary = readFolderSummary(members)
        writeFolderSummaryRow(folder.id, summary)
    }

    /** Writes (REPLACEs) a folder's aggregate row in rconversation from its computed summary. */
    private fun writeFolderSummaryRow(folderId: String, summary: FolderSummary) {
        WeDatabaseApi.execStatement(
            """
            REPLACE INTO ${ConversationTable.NAME} (
                ${ConversationTable.USERNAME}, ${ConversationTable.DIGEST}, ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS},
                ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT}, ${ConversationTable.UNREAD_MUTE_COUNT}, ${ConversationTable.CONTENT}, ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}, ${ConversationTable.ATTR_FLAG}
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                folderId,
                summary.digest,
                summary.digestUser,
                summary.isSend,
                summary.status,
                summary.conversationTime,
                composeFolderFlag(folderId, summary.conversationTime),
                summary.unreadCount,
                summary.unreadMuteCount,
                summary.content,
                summary.msgType,
                summary.chatMode,
                summary.attrFlag
            )
        )
    }

    private fun ensureMemberConversationRows(folderId: String, members: List<String>) {
        members.forEach { member ->
            WeDatabaseApi.execStatement(
                """
                INSERT OR IGNORE INTO ${ConversationTable.NAME} (
                    ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF}, ${ConversationTable.DIGEST}, ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND},
                    ${ConversationTable.STATUS}, ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT}, ${ConversationTable.UNREAD_MUTE_COUNT}, ${ConversationTable.CONTENT},
                    ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}
                ) VALUES (?, ?, '', '', 0, 0, 0, 0, 0, 0, '', '', 0)
                """.trimIndent(),
                arrayOf(member, folderId)
            )
        }
    }

    private fun readFolderSummary(members: List<String>): FolderSummary {
        if (members.isEmpty()) return FolderSummary()
        val placeholders = members.joinToString(",") { "?" }
        val cursor = WeDatabaseApi.rawQuery(
            """
            SELECT ${ConversationTable.USERNAME}, ${ConversationTable.DIGEST}, ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS}, ${ConversationTable.CONVERSATION_TIME},
                   ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT}, ${ConversationTable.UNREAD_MUTE_COUNT}, ${ConversationTable.CONTENT}, ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}
            FROM ${ConversationTable.NAME}
            WHERE ${ConversationTable.USERNAME} IN ($placeholders)
            ORDER BY ${ConversationTable.CONVERSATION_TIME} DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(*members.toTypedArray())
        )
        val fallbackTime = System.currentTimeMillis()
        // NOTE: flag is intentionally NOT derived from member rows. The folder row's high
        // 8 bits hold the pin / move-up state that WeChat writes directly via setPlacedTop.
        // Deriving it from members would clobber the user's pin choice on every sync. The
        // write sites compose the final flag via composeFolderFlag(), preserving those bits.
        val latest = cursor.use { cursor ->
            if (!cursor.moveToFirst()) null else FolderSummary(
                digest = prefixWithConversationName(
                    cursor.getStringOrEmpty(ConversationTable.USERNAME),
                    cursor.getStringOrEmpty(ConversationTable.DIGEST)
                ),
                digestUser = cursor.getStringOrEmpty(ConversationTable.DIGEST_USER),
                isSend = cursor.getIntOrZero(ConversationTable.IS_SEND),
                status = cursor.getIntOrZero(ConversationTable.STATUS),
                conversationTime = cursor.getLongOrZero(ConversationTable.CONVERSATION_TIME).takeIf { it > 0L }
                    ?: fallbackTime,
                unreadCount = unreadCountForMembers(members),
                unreadMuteCount = unreadMuteCountForMembers(members),
                content = cursor.getStringOrEmpty(ConversationTable.CONTENT),
                msgType = cursor.getStringOrEmpty(ConversationTable.MSG_TYPE),
                chatMode = cursor.getIntOrZero(ConversationTable.CHAT_MODE)
            )
        }
        return latest ?: FolderSummary(
            conversationTime = fallbackTime,
            unreadCount = unreadCountForMembers(members),
            unreadMuteCount = unreadMuteCountForMembers(members)
        )
    }

    /**
     * Prefixes the folder digest with the originating conversation's display name, so the
     * homepage folder row reads like "群聊名: 最新一条消息" instead of a bare message whose
     * source is ambiguous once several chats are aggregated. Returns the digest untouched
     * when it is blank or the name can't be resolved, to avoid a dangling "name: " prefix.
     */
    private fun prefixWithConversationName(username: String, digest: String): String {
        if (digest.isBlank() || username.isBlank()) return digest
        val name = runCatching { WeDatabaseApi.getDisplayName(username) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != username }
            ?: return digest
        return "$name: $digest"
    }

    /**
     * Builds the folder row's `flag` so its pin / move-up state (high 8 bits, owned by
     * WeChat's setPlacedTop / unSetPlacedTop) survives our REPLACE, while the low 56 bits
     * track the latest member's conversationTime. Mirrors WeChat's xg3.b.c() packing.
     *
     * Prefers the live folder row's high bits (WeChat's current truth); when the row was
     * just deleted by clearStaleFolderMappings, falls back to the pre-delete snapshot.
     */
    private fun composeFolderFlag(folderId: String, conversationTime: Long): Long {
        val liveHigh = existingFolderFlag(folderId)?.and(FLAG_HIGH_MASK)
        val highBits = liveHigh ?: (folderPinFlags[folderId] ?: 0L)
        return highBits or (conversationTime and FLAG_TIME_MASK)
    }

    /** Returns the folder row's raw flag, or null when no such row exists. */
    private fun existingFolderFlag(folderId: String): Long? {
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT ${ConversationTable.FLAG} FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME}=? LIMIT 1",
            arrayOf(folderId)
        )
        return cursor.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    /**
     * Unread of NON-muted members only. WeChat stores every child's unread in unReadCount
     * regardless of mute (sv/g.java); the conversation box then splits it: non-muted children
     * feed the box's unReadCount (number badge), muted children feed unReadMuteCount (dot).
     * Mute status comes from rcontact.type & 512 (c01.e2.P), so we join against rcontact.
     */
    private fun unreadCountForMembers(members: List<String>): Int =
        unreadSumForMembers(members, muted = false)

    /** Unread of MUTED members only (drives the small-dot badge on the folder row). */
    private fun unreadMuteCountForMembers(members: List<String>): Int =
        unreadSumForMembers(members, muted = true)

    private fun unreadSumForMembers(members: List<String>, muted: Boolean): Int {
        if (members.isEmpty()) return 0
        val placeholders = members.joinToString(",") { "?" }
        val muteTest = if (muted) {
            "(IFNULL(c.${ContactTable.TYPE}, 0) & $CONTACT_TYPE_MUTE_BIT) != 0"
        } else {
            "(IFNULL(c.${ContactTable.TYPE}, 0) & $CONTACT_TYPE_MUTE_BIT) = 0"
        }
        val sumCursor = WeDatabaseApi.rawQuery(
            """
            SELECT SUM(r.${ConversationTable.UNREAD_COUNT})
            FROM ${ConversationTable.NAME} r
            LEFT JOIN ${ContactTable.NAME} c ON c.${ContactTable.USERNAME} = r.${ConversationTable.USERNAME}
            WHERE r.${ConversationTable.USERNAME} IN ($placeholders) AND $muteTest
            """.trimIndent(),
            arrayOf(*members.toTypedArray())
        )
        val sum = sumCursor.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0
        }
        if (sum > 0) return sum

        // Fallback: if SUM returned 0 (or null), count members with unReadCount>0 in this bucket
        val countCursor = WeDatabaseApi.rawQuery(
            """
            SELECT COUNT(1)
            FROM ${ConversationTable.NAME} r
            LEFT JOIN ${ContactTable.NAME} c ON c.${ContactTable.USERNAME} = r.${ConversationTable.USERNAME}
            WHERE r.${ConversationTable.USERNAME} IN ($placeholders) AND r.${ConversationTable.UNREAD_COUNT}>0 AND $muteTest
            """.trimIndent(),
            arrayOf(*members.toTypedArray())
        )
        return countCursor.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }


    private fun isFolderSchemaReady(): Boolean {
        folderSchemaReady?.let { return it }
        val result = runCatching {
            val conversationColumns = tableColumns(ConversationTable.NAME)
            val contactColumns = tableColumns(ContactTable.NAME)
            val missingConversationColumns = ConversationTable.REQUIRED_COLUMNS - conversationColumns
            val missingContactColumns = ContactTable.REQUIRED_COLUMNS - contactColumns
            if (missingConversationColumns.isNotEmpty() || missingContactColumns.isNotEmpty()) {
                WeLogger.w(
                    TAG,
                    "skip folders sync, schema mismatch: " +
                            "rconversation missing=${missingConversationColumns.joinToString()}, " +
                            "rcontact missing=${missingContactColumns.joinToString()}"
                )
                false
            } else {
                true
            }
        }.onFailure {
            WeLogger.w(TAG, "skip folders sync, failed to inspect WeChat database schema", it)
        }.getOrDefault(false)
        folderSchemaReady = result
        return result
    }

    private fun tableColumns(table: String): Set<String> {
        val columns = linkedSetOf<String>()
        val cursor = WeDatabaseApi.rawQuery("PRAGMA table_info($table)")
        cursor.use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }
        return columns
    }

    private fun looksLikeConversationListQuery(sql: String): Boolean {
        val lower = sql.lowercase()
        if (!lower.contains("select")) return false
        if (!lower.contains("from ${ConversationTable.NAME}")) return false
        return lower.contains(ConversationTable.PARENT_REF.lowercase()) ||
                lower.contains(ConversationTable.CONVERSATION_TIME.lowercase()) ||
                lower.contains(ConversationTable.UNREAD_COUNT.lowercase())
    }

    private fun appendParentRefFilter(sql: String): String {
        val insertionPoint = listOf(" order by ", " group by ", " limit ")
            .map { sql.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: sql.length
        val head = sql.substring(0, insertionPoint)
        val tail = sql.substring(insertionPoint)
        val condition = listOf(FOLDER_PREFIX)
            .joinToString(" AND ") { "ifnull(${ConversationTable.PARENT_REF},'') NOT LIKE '$it%'" }
        val connector = if (head.contains(" where ", ignoreCase = true)) " AND " else " WHERE "
        return "$head$connector$condition$tail"
    }

    private fun rewriteContainerSql(sql: String, folderId: String): String {
        if (!sql.contains(ConversationTable.NAME, ignoreCase = true) ||
            !sql.contains(ConversationTable.PARENT_REF, ignoreCase = true)
        ) {
            return sql
        }
        if (!sql.contains(WeChatFolderPlaceholder.CONVERSATION_BOX) && !sql.contains(WeChatFolderPlaceholder.MESSAGE_FOLD)) {
            return sql
        }
        return sql
            .replace(WeChatFolderPlaceholder.CONVERSATION_BOX, folderId)
            .replace(WeChatFolderPlaceholder.MESSAGE_FOLD, folderId)
    }

    private fun readFolderIdFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return WeChatIntentExtra.ALL
            .asSequence()
            .mapNotNull { intent.getStringExtra(it) }
            .firstOrNull(::isFolderId)
    }

    private inline fun <T> withQueryRewriteSuppressed(action: () -> T): T {
        val oldValue = suppressQueryRewrite.get()
        suppressQueryRewrite.set(true)
        return try {
            action()
        } finally {
            suppressQueryRewrite.set(oldValue)
        }
    }

    private fun showManagerDialog(context: Context) {
        showComposeDialog(context) {
            var folders by remember { mutableStateOf(loadFolders()) }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("对话归拢") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (folders.isEmpty()) {
                                item {
                                    Text("暂无文件夹, 点击「新建」来创建一个")
                                }
                            }
                            items(folders, key = { it.id }) { folder ->
                                FolderRow(folder) {
                                    showEditFolderDialog(
                                        context = context,
                                        folder = folder,
                                        onFolderUpdated = { folders = loadFolders() },
                                        onFolderDeleted = { folders = loadFolders() }
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                    TextButton(onClick = {
                        syncFoldersToDatabase()
                        showToast("已重建文件夹索引")
                    }) { Text("重载") }
                    TextButton(onClick = {
                        showCreateFolderDialog(context) {
                            folders = loadFolders()
                        }
                    }) { Text("新建") }
                },
                confirmButton = {
                    Button(onClick = {
                        saveFolders(folders)
                        syncFoldersToDatabase()
                        showToast(context, "已保存, 重启微信生效")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    private fun showCreateFolderDialog(context: Context, onFolderCreated: () -> Unit) {
        showComposeDialog(context) {
            FolderEditorDialog(
                title = "新建文件夹",
                folder = null,
                onDismiss = onDismiss,
                onSave = { folder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders + folder)
                    onFolderCreated()
                    onDismiss()
                }
            )
        }
    }

    private fun showEditFolderDialog(
        context: Context,
        folder: ChatFolder,
        onFolderUpdated: () -> Unit,
        onFolderDeleted: () -> Unit
    ) {
        showComposeDialog(context) {
            FolderEditorDialog(
                title = "编辑文件夹",
                folder = folder,
                onDismiss = onDismiss,
                onDelete = {
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders.filterNot { it.id == folder.id })
                    onFolderDeleted()
                    onDismiss()
                },
                onSave = { updatedFolder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders.map { if (it.id == updatedFolder.id) updatedFolder else it })
                    onFolderUpdated()
                    onDismiss()
                }
            )
        }
    }

    @Composable
    private fun FolderRow(folder: ChatFolder, onClick: () -> Unit) {
        val count = remember(folder) { getFolderMembers(folder).size }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text(folder.name)
            val desc = when (folder.type) {
                FolderType.MANUAL -> "手动选择: $count 个对话"
                FolderType.PRESET_GROUPS -> "所有群聊: $count 个对话"
                FolderType.PRESET_OFFICIALS -> "所有公众号: $count 个对话"
                FolderType.SQL -> "SQL规则: $count 个对话"
            }
            Text(desc)
        }
    }

    @Composable
    private fun FolderEditorDialog(
        title: String,
        folder: ChatFolder?,
        onDismiss: () -> Unit,
        onDelete: (() -> Unit)? = null,
        onSave: (ChatFolder) -> Unit
    ) {
        val folderId = remember(folder) { folder?.id ?: newFolderId() }
        var name by remember(folder) { mutableStateOf(folder?.name ?: "") }
        var members by remember(folder) { mutableStateOf(folder?.members?.toSet().orEmpty()) }
        var selectingMembers by remember { mutableStateOf(false) }

        var type by remember(folder) { mutableStateOf(folder?.type ?: FolderType.MANUAL) }
        var selectFields by remember(folder) { mutableStateOf(folder?.selectFields ?: "r.username") }
        var whereClause by remember(folder) { mutableStateOf(folder?.whereClause ?: "") }

        val matchedCount = remember(type, members, selectFields, whereClause) {
            val tempFolder = ChatFolder(
                id = folderId,
                name = name,
                members = members.toList(),
                type = type,
                selectFields = selectFields,
                whereClause = whereClause
            )
            getFolderMembers(tempFolder).size
        }

        var hasAvatar by remember(folderId) {
            mutableStateOf(CustomLocalFriendAvatars.avatarMap.containsKey(folderId))
        }

        if (selectingMembers) {
            ContactsSelector(
                title = "选择对话",
                contacts = remember { WeDatabaseApi.getContacts() },
                initialSelectedWxIds = members,
                onDismiss = { selectingMembers = false },
                onConfirm = {
                    members = it
                    selectingMembers = false
                }
            )
            return
        }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            title = { Text(title) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文件夹名称") },
                        singleLine = true
                    )

                    var typeExpanded by remember { mutableStateOf(false) }
                    Column {
                        Text("归拢模式", style = MaterialTheme.typography.labelSmall)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { typeExpanded = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = when (type) {
                                    FolderType.MANUAL -> "手动选择"
                                    FolderType.PRESET_GROUPS -> "自动所有群聊"
                                    FolderType.PRESET_OFFICIALS -> "自动所有公众号"
                                    FolderType.SQL -> "自定义 SQL 规则"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("手动选择") },
                                onClick = {
                                    type = FolderType.MANUAL
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有群聊") },
                                onClick = {
                                    type = FolderType.PRESET_GROUPS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有公众号") },
                                onClick = {
                                    type = FolderType.PRESET_OFFICIALS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自定义 SQL 规则") },
                                onClick = {
                                    type = FolderType.SQL
                                    typeExpanded = false
                                }
                            )
                        }
                    }

                    when (type) {
                        FolderType.MANUAL -> {
                            Text("已选择 $matchedCount 个对话")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = { selectingMembers = true }
                                ) {
                                    Text("选择对话")
                                }

                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(onClick = {
                                    if (!CustomLocalFriendAvatars.isEnabled) {
                                        showToast("请启用「自定义好友本地头像」以使用头像相关功能!")
                                    }

                                    CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                }) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }
                        FolderType.PRESET_GROUPS -> {
                            Text("自动归拢所有群聊（当前匹配到 $matchedCount 个对话）")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }
                        FolderType.PRESET_OFFICIALS -> {
                            Text("自动归拢所有公众号（当前匹配到 $matchedCount 个对话）")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }
                        FolderType.SQL -> {
                            OutlinedTextField(
                                value = selectFields,
                                onValueChange = { selectFields = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("SELECT 字段") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = whereClause,
                                onValueChange = { whereClause = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("WHERE 条件") },
                                singleLine = false,
                                maxLines = 4
                            )
                            Text(
                                text = "当前匹配到 $matchedCount 个对话",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "数据源自 rcontact r, img_flag i, rconversation c\n示例: c.unReadCount > 0 AND r.username LIKE '%@chatroom'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                if (onDelete != null) {
                    TextButton(onDelete) { Text("删除") }
                }
                TextButton(onDismiss) { Text("取消") }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val next = ChatFolder(
                            id = folderId,
                            name = name.trim(),
                            members = members.toList().sorted(),
                            type = type,
                            selectFields = selectFields.trim(),
                            whereClause = whereClause.trim()
                        )
                        onSave(next)
                    }
                ) { Text("确定") }
            }
        )
    }

    private fun resolveFolderMembers(folder: ChatFolder): List<String> {
        return when (folder.type) {
            FolderType.MANUAL -> folder.members
            FolderType.PRESET_GROUPS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE '%@chatroom'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset groups", it)
                    emptyList()
                }
            }
            FolderType.PRESET_OFFICIALS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE 'gh_%'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset officials", it)
                    emptyList()
                }
            }
            FolderType.SQL -> {
                runCatching {
                    val select = folder.selectFields.ifBlank { "r.username" }
                    val where = folder.whereClause.ifBlank { "1=1" }
                    val query = "SELECT $select FROM rcontact r LEFT JOIN img_flag i ON r.username = i.username LEFT JOIN rconversation c ON r.username = c.username WHERE $where"
                    val result = WeDatabaseApi.executeQuery(query)
                    result.mapNotNull { row ->
                        val username = row["username"]?.toString()
                        if (username != null) return@mapNotNull username
                        row.values.firstOrNull()?.toString()
                    }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query custom sql for folder ${folder.id}", it)
                    emptyList()
                }
            }
        }
    }

    private fun getFolderMembers(folder: ChatFolder): List<String> {
        if (folder.type == FolderType.MANUAL) {
            return folder.members
        }
        val cached = folderMembersCache[folder.id]
        if (cached != null) return cached

        if (!WeDatabaseApi.isReady) {
            return emptyList()
        }
        val resolved = resolveFolderMembers(folder)
        if (resolved.isNotEmpty()) {
            folderMembersCache[folder.id] = resolved
        }
        return resolved
    }

    private fun getFallbackAvatarMember(folderId: String): String? {
        val folder = folderById(folderId) ?: return null
        val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
        if (members.isEmpty()) return null
        // Prefer the member whose conversation most recently saw activity: WeChat bumps
        // rconversation.conversationTime on every sent or received message, so the folder
        // borrows the avatar of the chat that last lit up rather than an arbitrary first
        // member. Falls back to the first member when none of them has any message yet.
        return latestActiveMember(members) ?: members.firstOrNull()
    }

    /** Member with the newest conversationTime (latest sent/received message), or null. */
    private fun latestActiveMember(members: List<String>): String? {
        if (members.isEmpty() || !WeDatabaseApi.isReady) return null
        return runCatching {
            // Suppress query rewrite: these members' parentRef is the folder id, which
            // appendParentRefFilter would otherwise filter out, hiding every row.
            withQueryRewriteSuppressed {
                val placeholders = members.joinToString(",") { "?" }
                val cursor = WeDatabaseApi.rawQuery(
                    """
                    SELECT ${ConversationTable.USERNAME}
                    FROM ${ConversationTable.NAME}
                    WHERE ${ConversationTable.USERNAME} IN ($placeholders) AND ${ConversationTable.CONVERSATION_TIME} > 0
                    ORDER BY ${ConversationTable.CONVERSATION_TIME} DESC
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(*members.toTypedArray())
                )
                cursor.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to resolve latest active member", it)
        }.getOrNull()
    }

    private fun loadFolders(): List<ChatFolder> {
        foldersCache?.let { return it }
        val folders = runCatching {
            val file = foldersFile
            if (!file.exists()) return emptyList()
            val raw = file.readText()
            DefaultJson.decodeFromString<List<ChatFolder>>(raw)
                .map { folder ->
                    folder.copy(members = folder.members.filter { it.isNotBlank() })
                }
                .filter { isFolderId(it.id) && it.name.isNotBlank() }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode folders config from $foldersFile", it)
        }.getOrDefault(emptyList())
        foldersCache = folders
        return folders
    }

    private fun saveFolders(folders: List<ChatFolder>) {
        foldersCache = folders
        folderMembersCache.clear()
        runCatching {
            val raw = DefaultJson.encodeToString(folders)
            foldersFile.writeText(raw)
        }.onFailure {
            WeLogger.w(TAG, "failed to save folders to $foldersFile", it)
        }
    }

    private fun folderById(folderId: String): ChatFolder? {
        return loadFolders().firstOrNull { it.id == folderId }
    }

    private fun newFolderId(): String = "$FOLDER_PREFIX${System.currentTimeMillis()}"

    private fun isFolderId(value: String): Boolean = value.startsWith(FOLDER_PREFIX)


    enum class FolderType {
        MANUAL,
        PRESET_GROUPS,
        PRESET_OFFICIALS,
        SQL
    }

    @Serializable
    private data class ChatFolder(
        val id: String = "",
        val name: String = "",
        val members: List<String> = emptyList(),
        val type: FolderType = FolderType.MANUAL,
        val selectFields: String = "",
        val whereClause: String = ""
    )

    private data class FolderSummary(
        val digest: String = "",
        val digestUser: String = "",
        val isSend: Int = 0,
        val status: Int = 0,
        val conversationTime: Long = System.currentTimeMillis(),
        val unreadCount: Int = 0,
        val unreadMuteCount: Int = 0,
        val content: String = "",
        val msgType: String = "",
        val chatMode: Int = 0
    ) {
        /**
         * The folder row needs a mute attrflag bit set for the homepage badge to render a
         * small dot (WeChat w3.b requires unReadCount==0 && unReadMuteCount>0 && attrflag has
         * a mute bit). We add the bit only when there's muted-but-no-normal unread, and clear
         * it otherwise so a stale dot never lingers.
         */
        val attrFlag: Int
            get() = if (unreadCount == 0 && unreadMuteCount > 0) ATTR_FLAG_MUTE_BIT else 0
    }

    private object ConversationTable {
        const val NAME = "rconversation"
        const val USERNAME = "username"
        const val PARENT_REF = "parentRef"
        const val DIGEST = "digest"
        const val DIGEST_USER = "digestUser"
        const val IS_SEND = "isSend"
        const val STATUS = "status"
        const val CONVERSATION_TIME = "conversationTime"
        const val FLAG = "flag"
        const val UNREAD_COUNT = "unReadCount"
        const val UNREAD_MUTE_COUNT = "unReadMuteCount"
        const val CONTENT = "content"
        const val MSG_TYPE = "msgType"
        const val CHAT_MODE = "chatmode"
        const val ATTR_FLAG = "attrflag"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            PARENT_REF,
            DIGEST,
            DIGEST_USER,
            IS_SEND,
            STATUS,
            CONVERSATION_TIME,
            FLAG,
            UNREAD_COUNT,
            UNREAD_MUTE_COUNT,
            CONTENT,
            MSG_TYPE,
            CHAT_MODE,
            ATTR_FLAG
        )
    }

    private object ContactTable {
        const val NAME = "rcontact"
        const val USERNAME = "username"
        const val NICKNAME = "nickname"
        const val TYPE = "type"
        const val VERIFY_FLAG = "verifyFlag"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            NICKNAME,
            TYPE,
            VERIFY_FLAG
        )
    }

    private object WeChatIntentExtra {
        const val CONTACT_USER = "Contact_User"
        const val CONTACT_CHAT_ROOM_ID = "Contact_ChatRoomId"
        const val ROOM_NAME = "room_name"
        const val CHAT_USER = "Chat_User"

        val ALL = listOf(
            CONTACT_USER,
            CONTACT_CHAT_ROOM_ID,
            ROOM_NAME,
            CHAT_USER
        )
    }

    private object WeChatFolderPlaceholder {
        const val CONVERSATION_BOX = "conversationboxservice"
        const val MESSAGE_FOLD = "message_fold"
    }


    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) ?: "" else ""
    }

    private fun android.database.Cursor.getIntOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun android.database.Cursor.getLongOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

}
