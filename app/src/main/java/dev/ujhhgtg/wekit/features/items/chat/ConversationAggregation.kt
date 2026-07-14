package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.conversation.BaseConversationUI
import com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI
import com.tencent.mm.ui.conversation.MainUI
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.chat.ConversationAggregation.clearStaleFolderMappings
import dev.ujhhgtg.wekit.features.items.chat.ConversationAggregation.composeFolderFlag
import dev.ujhhgtg.wekit.features.items.chat.ConversationAggregation.syncFoldersToDatabase
import dev.ujhhgtg.wekit.features.items.contacts.CustomLocalFriendAvatars
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.BaseContactSelector
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
import dev.ujhhgtg.wekit.utils.invokeOriginal
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.lang.reflect.Proxy
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.lang.reflect.Modifier as JavaModifier

@Feature(name = "对话归拢", categories = ["聊天"], description = "将多个对话归拢在一个文件夹内\n设置对话头像需同时启用「自定义好友本地头像」")
object ConversationAggregation : ClickableFeature(),
    WeDatabaseListenerApi.IQueryListener,
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    WeStartActivityApi.IStartActivityListener,
    IResolveDex {

    private const val TAG = "AggregateChats"
    const val FOLDER_PREFIX = "wekit_folder_"
    private const val FOLDER_CONFIG_MENU_ID = 0x0721C0DE
    private const val REMOVE_FROM_FOLDER_MENU_ID = 777020

    // Order pushes our item to the end of the container's context menu (its own items use 0).
    private const val REMOVE_FROM_FOLDER_MENU_ORDER = 1000

    // rconversation.flag packing (see WeChat xg3.b.c): high 8 bits = pin / move-up state
    // owned by WeChat (setPlacedTop / unSetPlacedTop), low 56 bits = conversationTime.
    private const val FLAG_TIME_MASK = 0x00FFFFFFFFFFFFFFL
    private const val FLAG_HIGH_MASK = FLAG_TIME_MASK.inv()

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

    // SelectConversationUI#doClickUser(username) — the single entry point for all conversation
    // taps in the "share to conversation" picker. WeChat only intercepts known virtual usernames
    // ("conversationboxservice", "opencustomerservicemsg") before forwarding to its share logic.
    // Our folder rows (wekit_folder_XXX) pass those guards and reach the share machinery, which
    // tries to open a chat thread for a non-existent contact → crash.
    private val methodSelectConversationDoClickUser by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.SelectConversationUI", "doClickUser=%s")
            paramTypes("java.lang.String")
            returnType("void")
        }
    }

    // The MVVM "select contact" picker (com.tencent.mm.ui.mvvm.MvvmContactListUI) used for in-app
    // forwarding routes every row tap through its list item-click listener cj5.g2#g(View, item, int)
    // (interface in5.u). A tap on a normal conversation dispatches wi5.c0(listOf(username)) to the
    // state center, which sets the "Select_Conv_User" result extra and finishes. Our folder rows
    // (wekit_folder_XXX) reach that same path with a non-existent username → crash downstream.
    // We match the two concrete listeners (main list + search results) by their unique log tags.
    private val methodMvvmMainListItemClick by dexMethod {
        matcher {
            usingStrings("MicroMsg.SelectContactMainRecycleViewUIC", "onItemClickListener data.type")
        }
    }
    private val methodMvvmSearchItemClick by dexMethod {
        matcher {
            usingStrings("MicroMsg.SelectContactSearchMvvmListUIC", "onItemClick: isAlwaysCheck=")
            paramTypes("android.view.View", null, "int")
            returnType("void")
        }
    }

    // com.tencent.mm.storage.m4 (ConversationStorage)#b0(username) — "updateUnreadByTalker".
    // The folder container (ConvBoxServiceConversationUI) sets its superUsername to our folder id
    // (via the Contact_User extra we inject). WeChat's ConvBoxServiceConversationFmUI.onPause()
    // then calls b0(superUsername), which zeroes unReadCount / unReadMuteCount and clears the mute
    // attrflag bit on that exact row — wiping our folder's badge just for opening and leaving the
    // folder without touching any member. We no-op it for folder ids so the aggregate row keeps
    // reflecting its members' (still-unread) state.
    private val methodConversationStorageUpdateUnreadByTalker by dexMethod(allowFailure = true) {
        matcher {
            usingStrings("MicroMsg.ConversationStorage", "updateUnreadByTalker %s", "update conversation failed")
            paramTypes("java.lang.String")
            returnType("boolean")
        }
    }

    // com.tencent.mm.ui.widget.menu.MMPopupMenu#showMenu(view, pos, id, onCreateListener, selectCb, x, y)
    // The shared long-press popup used by both the homepage list and the folder container. We hook
    // it (gated on activeFolderId) to inject a "remove from folder" item only inside our folders.
    private val methodShowPopupMenu by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingStrings("MicroMsg.MMPopupMenu")
            }
            paramTypes(
                "android.view.View", "int", "long",
                $$"android.view.View$OnCreateContextMenuListener", null, "int", "int"
            )
            returnType("void")
        }
    }

    @Volatile
    private var activeFolderId: String? = null

    @Volatile
    private var folderSchemaReady: Boolean? = null

    @Volatile
    private var foldersCache: List<ChatFolder>? = null

    private val folderMembersCache = ConcurrentHashMap<String, List<String>>()

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
        hookFolderContextMenu()
        hookSelectConversationUi()
        hookMvvmContactListItemClick()
        hookSqliteWrapperQuery()
        hookConversationStorageParentQuery()
        hookConversationStorageUpdateUnread()

        CustomLocalFriendAvatars.fallbackUsernameProvider = { folderId ->
            if (isFolderId(folderId) && !CustomLocalFriendAvatars.avatarMap.containsKey(folderId)) {
                getFallbackAvatarMember(folderId)
            } else {
                null
            }
        }

        // Restore the materialized folder rows when re-enabled at runtime (DB already up), since
        // onDisable released them. On cold startup the DB isn't ready yet and this is a no-op —
        // MainUI.onResume (hookMainUiRefresh) runs the first sync once WeChat is up.
        if (WeDatabaseApi.isReady) {
            syncFoldersToDatabase()
        }
        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeStartActivityApi.removeListener(this)
        CustomLocalFriendAvatars.fallbackUsernameProvider = null
        stopRefreshThread()

        // Release every folder back to the homepage — unmap members and delete all wekit_folder_*
        // rows — so disabling doesn't leave ghost aggregate conversations behind, exactly as if the
        // user had deleted every folder. The saved config is left untouched so onEnable can restore.
        releaseAllFolders()
    }

    /**
     * Reverses [syncFoldersToDatabase]: returns every folder member to the root homepage list and
     * removes all folder rows (rconversation / rcontact / img_flag). Mirrors deleting every folder
     * by hand, but keeps the on-disk config so the folders come back on the next onEnable.
     */
    private fun releaseAllFolders() {
        if (!WeDatabaseApi.isReady) return
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                // Preserve each folder's pin / move-up bits in memory so a later onEnable in the
                // same process restores them when composeFolderFlag recreates the rows.
                snapshotFolderPinFlags(loadFolders())
                clearStaleFolderMappings()
            }
            WeConversationApi.reloadConversations()
            WeLogger.i(TAG, "released all folders on disable")
        }.onFailure {
            WeLogger.e(TAG, "failed to release folders on disable", it)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showManagerDialog(context)
    }

    /** Whether [username] is one of our materialized folder rows (vs. a real conversation). */
    fun isAggregationFolderId(username: String): Boolean = isFolderId(username)

    /** A folder choice exposed to other features (e.g. the "add to folder" conversation menu). */
    data class FolderChoice(val id: String, val name: String, val isAuto: Boolean)

    /** Public snapshot of the configured folders, for features that let the user pick one. */
    fun aggregationFolders(): List<FolderChoice> =
        loadFolders().map { FolderChoice(it.id, it.name, it.type != FolderType.MANUAL) }

    /**
     * Adds [talker] to the manual folder [folderId] and opens the existing edit dialog so the
     * user can review and save. Returns false without acting when the folder is missing or in an
     * auto mode (members are computed, not hand-picked); callers surface that to the user.
     */
    fun showAddToFolderDialog(context: Context, folderId: String, talker: String): Boolean {
        val folder = folderById(folderId) ?: return false
        if (folder.type != FolderType.MANUAL) return false
        val updated = folder.copy(members = (folder.members + talker).distinct().sorted())
        showEditFolderDialog(
            context = context,
            folder = updated,
            onFolderUpdated = { syncFoldersToDatabase() },
            onFolderDeleted = { syncFoldersToDatabase() }
        )
        return true
    }

    /**
     * Adds [talker] to the manual folder [folderId] and persists immediately (no dialog),
     * rebuilding the index so the row appears in the folder. Returns false without acting when the
     * folder is missing or in an auto mode (members are computed, not hand-picked).
     */
    fun addToFolder(folderId: String, talker: String): Boolean {
        val folder = folderById(folderId) ?: return false
        if (folder.type != FolderType.MANUAL) return false
        if (talker !in folder.members) {
            val updated = folder.copy(members = (folder.members + talker).distinct().sorted())
            saveFolders(loadFolders().map { if (it.id == updated.id) updated else it })
            syncFoldersToDatabase()
            WeConversationApi.reloadConversations()
        }
        return true
    }

    /**
     * Removes [talker] from the manual folder [folderId], persists, and rebuilds the index so the
     * row disappears from the folder immediately. No-op for missing / auto folders, or when the
     * talker isn't actually a member.
     */
    private fun removeMemberFromFolder(folderId: String, talker: String) {
        val folder = folderById(folderId) ?: return
        if (folder.type != FolderType.MANUAL || talker !in folder.members) {
            showToast("该对话不在此手动文件夹中!")
            return
        }
        val updated = folder.copy(members = folder.members.filterNot { it == talker })
        saveFolders(loadFolders().map { if (it.id == updated.id) updated else it })
        syncFoldersToDatabase()
        WeConversationApi.reloadConversations()
        showToast("已移出「${folder.name}」")
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
                    // WeChat's REPLACE INTO on new-message receipt can recreate the member row
                    // without parentRef (REPLACE = DELETE + INSERT, and WeChat's INSERT omits
                    // parentRef).  Re-anchor any member whose parentRef was cleared before
                    // reading the summary so the folder container query still finds them and
                    // ORDER BY flag DESC, conversationTime DESC puts the new-message member first.
                    reanchorFolderMembers(folder.id, members)
                    writeFolderSummaryRow(folder.id, readFolderSummary(members))
                }
            }
            WeConversationApi.reloadConversations()
        }.onFailure {
            WeLogger.e(TAG, "failed to refresh folder summaries", it)
        }
    }

    /**
     * Restores [ConversationTable.PARENT_REF] = [folderId] for any member whose row was
     * replaced by WeChat's own conversation update without a parentRef. Only rows where
     * parentRef is currently NULL or '' are touched — rows already mapped to this folder
     * (or to another folder) are left unchanged.
     */
    private fun reanchorFolderMembers(folderId: String, members: List<String>) {
        if (members.isEmpty()) return
        val placeholders = members.joinToString(",") { "?" }
        WeDatabaseApi.execStatement(
            """
            UPDATE ${ConversationTable.NAME}
            SET ${ConversationTable.PARENT_REF}=?
            WHERE ${ConversationTable.USERNAME} IN ($placeholders)
              AND (${ConversationTable.PARENT_REF} IS NULL OR ${ConversationTable.PARENT_REF}='')
            """.trimIndent(),
            arrayOf(folderId, *members.toTypedArray())
        )
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

    // The folder container (ConvBoxServiceConversationUI) does NOT use the homepage's
    // ConversationLongClickListener that WeConversationContextMenuApi hooks; it builds its long-press
    // menu through the shared MMPopupMenu.showMenu(...). We hook that chokepoint, gated on
    // activeFolderId (null on the homepage, so that path is untouched), and inject a "remove from
    // folder" item by wrapping the menu-create listener and the (obfuscated) select callback.
    private fun hookFolderContextMenu() {
        if (methodShowPopupMenu.isPlaceholder) return

        // The 5th parameter's declared type is the obfuscated select-callback interface (db5.t4,
        // with the single method onMMMenuItemSelected). We proxy it to intercept our own item.
        val selectCallbackInterface = methodShowPopupMenu.method.parameterTypes[4]

        methodShowPopupMenu.hookBefore {
            val folderId = activeFolderId ?: return@hookBefore
            val folder = folderById(folderId) ?: return@hookBefore
            if (folder.type != FolderType.MANUAL) return@hookBefore

            val createListener = args[3] as? View.OnCreateContextMenuListener ?: return@hookBefore
            val originalSelect = args[4] ?: return@hookBefore
            val position = args[1] as? Int ?: return@hookBefore

            val talker = runCatching { extractFolderTalker(createListener, position) }
                .onFailure { WeLogger.w(TAG, "failed to resolve long-pressed conversation", it) }
                .getOrNull() ?: return@hookBefore

            // Only offer removal on a row that is actually a member of this manual folder.
            if (talker !in folder.members) return@hookBefore

            args[3] = View.OnCreateContextMenuListener { menu, view, menuInfo ->
                createListener.onCreateContextMenu(menu, view, menuInfo)
                runCatching {
                    menu.add(0, REMOVE_FROM_FOLDER_MENU_ID, REMOVE_FROM_FOLDER_MENU_ORDER, "移出文件夹")
                }.onFailure { WeLogger.e(TAG, "failed to add folder menu item", it) }
            }

            args[4] = Proxy.newProxyInstance(
                selectCallbackInterface.classLoader,
                arrayOf(selectCallbackInterface)
            ) { _, method, methodArgs ->
                val params = methodArgs ?: emptyArray()
                if (method.name == "onMMMenuItemSelected") {
                    val menuItem = params.getOrNull(0) as? MenuItem
                    if (menuItem?.itemId == REMOVE_FROM_FOLDER_MENU_ID) {
                        runCatching { removeMemberFromFolder(folderId, talker) }
                            .onFailure { WeLogger.e(TAG, "failed to remove from folder", it) }
                        return@newProxyInstance null
                    }
                }
                method.invoke(originalSelect, *params)
            }
        }
    }

    // Intercepts the "share to conversation" picker (SelectConversationUI) before WeChat's share
    // machinery runs. Our folder rows appear in that list because their parentRef is '' (root-level),
    // but they have no real chat thread — forwarding to one crashes. We cancel the call, show a
    // picker scoped to that folder's members, then re-invoke doClickUser with the chosen member so
    // the original share flow proceeds normally.
    private fun hookSelectConversationUi() {
        if (methodSelectConversationDoClickUser.isPlaceholder) return
        methodSelectConversationDoClickUser.hookBefore {
            val username = args.firstOrNull() as? String ?: return@hookBefore
            if (!isFolderId(username)) return@hookBefore

            val folder = folderById(username) ?: return@hookBefore
            val context = thisObject as? Context ?: return@hookBefore

            // Cancel forwarding to the folder row itself — it has no real chat thread.
            result = null

            // Capture the hook param so the dialog callback can re-run the ORIGINAL doClickUser
            // (bypassing this hook, so no recursion) with the chosen member's username.
            val param = this
            showFolderMemberPicker(context, folder) { selectedWxId ->
                runCatching {
                    param.invokeOriginal(args = arrayOf(selectedWxId))
                }.onFailure {
                    WeLogger.e(TAG, "failed to forward share to member $selectedWxId", it)
                }
            }
        }
    }

    // Same folder-row problem as SelectConversationUI, but for the MVVM contact picker
    // (com.tencent.mm.ui.mvvm.MvvmContactListUI) used by in-app forwarding. Every row tap goes
    // through a list item-click listener (cj5.g2#g for the main list, cj5.e4#g for search) whose
    // 2nd arg is the tapped item model (ri5.j). A normal conversation is forwarded by dispatching
    // wi5.c0(listOf(username)); our folder rows reach that path with a non-existent username →
    // crash. We cancel the tap and re-run the ORIGINAL listener with the model's username rewritten
    // to the chosen member so WeChat's own forward flow proceeds.
    private fun hookMvvmContactListItemClick() {
        listOf(
            methodMvvmMainListItemClick,
            methodMvvmSearchItemClick
        ).forEach { method ->
            if (method.isPlaceholder) return@forEach
            method.hookBefore { handleMvvmFolderTap(this) }
        }
    }

    private fun handleMvvmFolderTap(param: XC_MethodHook.MethodHookParam) {
        val itemView = param.args[0] as View
        val data = param.args[1]

        val folderField = data.reflekt().fields {
            type = BString
            modifiers(Modifiers.FINAL)
        }[1]
        val folderId = folderField.get()!! as String

        val folder = folderById(folderId) ?: return

        // Cancel the tap on the folder row itself — it has no real chat thread.
        param.result = null

        showFolderMemberPicker(itemView.context, folder) { selectedWxId ->
            runCatching {
                folderField.set(selectedWxId)
                // Re-run the ORIGINAL listener (bypasses this hook → no recursion) so WeChat
                // forwards to the real member exactly as if that row had been tapped.
                param.invokeOriginal()
                folderField.set(folderId)
            }.onFailure {
                WeLogger.e(TAG, "failed to forward folder tap to member $selectedWxId", it)
            }
        }
    }

    // Shows a picker scoped to a folder's members and invokes onMemberSelected with the chosen
    // member's wxid. Shared by both the SelectConversationUI and MvvmContactListUI interceptions.
    private fun showFolderMemberPicker(
        context: Context,
        folder: ChatFolder,
        onMemberSelected: (String) -> Unit
    ) {
        val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
        if (members.isEmpty()) {
            showToast("文件夹中没有对话")
            return
        }

        val membersSet = members.toHashSet()
        val contacts = runCatching {
            withQueryRewriteSuppressed {
                WeDatabaseApi.getContacts().filter { it.wxId in membersSet }
            }
        }.getOrDefault(emptyList())

        showComposeDialog(context) {
            FolderShareTargetSelector(
                contacts = contacts,
                onDismiss = onDismiss,
                onSelect = { selectedWxId ->
                    onDismiss()
                    onMemberSelected(selectedWxId)
                }
            )
        }
    }

    // A member picker for the "share to conversation" folder interception. Mirrors the
    // CustomLocalFriendAvatars pattern: no confirm button, each row carries a "选择" trailing
    // button that fires the forward immediately (onItemclick does the same for convenience).
    @Composable
    private fun FolderShareTargetSelector(
        contacts: List<IWeContact>,
        onDismiss: () -> Unit,
        onSelect: (String) -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
        val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

        val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
            contacts.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                        it.wxId.contains(searchQuery, ignoreCase = true)
            }.sortedWith(
                compareBy<IWeContact> { it.displayName.isBlank() }
                    .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
            )
        }

        BaseContactSelector(
            title = "选择文件夹里的转发对象",
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            filteredContacts = filteredContacts,
            confirmButtonText = "",
            confirmButtonEnabled = false,
            showConfirmButton = false,
            dismissButtonText = "取消",
            onDismiss = onDismiss,
            onConfirm = {},
            selectionKey = Unit,
            isSelected = { false },
            trailingControl = { contact ->
                TextButton(onClick = { onSelect(contact.wxId) }) { Text("选择") }
            },
            onItemClick = { contact -> onSelect(contact.wxId) }
        )
    }

    // Resolves the long-pressed conversation's username from the menu-create listener WeChat passes
    // into MMPopupMenu.showMenu. Chain: createListener -> its OnItemLongClickListener -> the
    // container fragment -> its list adapter -> adapter.getItem(position) (an rconversation row) ->
    // its field_username (kept unobfuscated by WeChat's auto-DB ORM).
    private fun extractFolderTalker(createListener: Any, position: Int): String? {
        val longClickListener = createListener.reflekt()
            .firstFieldOrNull { type { it isSubclassOf AdapterView.OnItemLongClickListener::class } }
            ?.get() ?: return null

        val fragment = longClickListener.reflekt()
            .firstFieldOrNull { type { it.name.endsWith("ConvBoxServiceConversationFmUI") } }
            ?.get() ?: return null

        val adapter = fragment.reflekt()
            .firstFieldOrNull { type { it isSubclassOf android.widget.Adapter::class } }
            ?.get() as? android.widget.Adapter ?: return null

        if (position < 0 || position >= adapter.count) return null
        val conversation = adapter.getItem(position) ?: return null

        return conversation.reflekt()
            .firstFieldOrNull { name = "field_username"; superclass() }
            ?.get() as? String
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

    // See methodConversationStorageUpdateUnreadByTalker: cancel the "mark box read on leave" that
    // WeChat's folder container fires against our folder id, so exiting a folder without opening any
    // member never clears the aggregate row's unread badge.
    private fun hookConversationStorageUpdateUnread() {
        if (methodConversationStorageUpdateUnreadByTalker.isPlaceholder) return
        methodConversationStorageUpdateUnreadByTalker.hookBefore {
            val username = args.firstOrNull() as? String ?: return@hookBefore
            if (isFolderId(username)) result = true
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

    /**
     * Captures each folder row's live pin / move-up bits into [ChatFolder.pinFlag] and persists the
     * folder list, just before [clearStaleFolderMappings] deletes the rows. Since the pin flag is
     * stored in chat_folders.json, it survives a process restart between onDisable (which deletes
     * every row) and the next onEnable — [composeFolderFlag] restores it when recreating the rows.
     *
     * Only folders whose row actually exists update the snapshot — the live row is WeChat's current
     * truth (pin set/cleared via setPlacedTop). When no row exists (e.g. the first sync after
     * re-enabling in a fresh process, where onDisable already deleted every row), the previously
     * persisted [ChatFolder.pinFlag] is left intact rather than overwritten with 0, so the user's
     * pin choice isn't silently lost.
     */
    private fun snapshotFolderPinFlags(folders: List<ChatFolder>) {
        var changed = false
        val updated = folders.map { folder ->
            val liveHigh = existingFolderFlag(folder.id)?.and(FLAG_HIGH_MASK) ?: return@map folder
            if (liveHigh == folder.pinFlag) return@map folder
            changed = true
            folder.copy(pinFlag = liveHigh)
        }
        if (changed) saveFolders(updated)
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
        // Split unread once (a single pass over the member rows) and reuse for both buckets.
        val (normalUnread, mutedUnread) = unreadSplitForMembers(members)
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
                unreadCount = normalUnread,
                unreadMuteCount = mutedUnread,
                content = cursor.getStringOrEmpty(ConversationTable.CONTENT),
                msgType = cursor.getStringOrEmpty(ConversationTable.MSG_TYPE),
                chatMode = cursor.getIntOrZero(ConversationTable.CHAT_MODE)
            )
        }
        return latest ?: FolderSummary(
            conversationTime = fallbackTime,
            unreadCount = normalUnread,
            unreadMuteCount = mutedUnread
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
     * Prefers the live folder row's high bits (WeChat's current truth); when the row was just
     * deleted by clearStaleFolderMappings — or was never created because the process restarted
     * while disabled — falls back to the pin flag persisted on the folder in chat_folders.json.
     */
    private fun composeFolderFlag(folderId: String, conversationTime: Long): Long {
        val liveHigh = existingFolderFlag(folderId)?.and(FLAG_HIGH_MASK)
        val highBits = liveHigh ?: (folderById(folderId)?.pinFlag ?: 0L)
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
     * Splits the members' unread into (nonMutedUnread, mutedUnread).
     *
     * WeChat stores every child's unread in unReadCount regardless of mute (member rows never
     * populate unReadMuteCount themselves); the homepage badge classifier then re-decides
     * dot-vs-number per row with a LIVE mute check. Our synthetic folder row can't trigger that
     * live check, so we pre-split the members' unread into a number bucket (non-muted) and a dot
     * bucket (muted) ourselves — the folder's attrflag mute bit then renders a dot when the whole
     * folder only has muted unread.
     *
     * Mute is NOT a single column: friends/biz use rcontact.type & 512 (c01.e2.P), but a group's
     * mute lives in the ChatRoomNotify flag (z3.T) packed inside the rcontact.lvbuff blob with no
     * column of its own (WeChat's w3.b tests `R4(username) && z3.T == 0`). So we read the members'
     * rows once, join their contact type + lvbuff, and classify each in Kotlin.
     */
    private fun unreadSplitForMembers(members: List<String>): Pair<Int, Int> {
        if (members.isEmpty()) return 0 to 0
        val placeholders = members.joinToString(",") { "?" }
        val cursor = WeDatabaseApi.rawQuery(
            """
            SELECT r.${ConversationTable.USERNAME}, r.${ConversationTable.UNREAD_COUNT}
            FROM ${ConversationTable.NAME} r
            LEFT JOIN ${ContactTable.NAME} c ON c.${ContactTable.USERNAME} = r.${ConversationTable.USERNAME}
            WHERE r.${ConversationTable.USERNAME} IN ($placeholders)
            """.trimIndent(),
            arrayOf(*members.toTypedArray())
        )
        var normal = 0
        var muted = 0
        cursor.use { c ->
            val userIdx = c.getColumnIndex(ConversationTable.USERNAME)
            val unreadIdx = c.getColumnIndex(ConversationTable.UNREAD_COUNT)
            while (c.moveToNext()) {
                val unread = if (unreadIdx >= 0 && !c.isNull(unreadIdx)) c.getInt(unreadIdx) else 0
                if (unread <= 0) continue
                val username = if (userIdx >= 0) c.getString(userIdx) ?: "" else ""
                if (WeConversationApi.isDnd(username)) muted += unread else normal += unread
            }
        }
        return normal to muted
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
                                val context = LocalContext.current
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        showComposeDialog(context) {
                                            ContactsSelector(
                                                title = "选择对话",
                                                contacts = remember { WeDatabaseApi.getContacts() },
                                                initialSelectedWxIds = members,
                                                onDismiss = onDismiss,
                                                onConfirm = {
                                                    members = it
                                                }
                                            )
                                        }
                                    }
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
                            whereClause = whereClause.trim(),
                            // Carry the pin state forward — editing a folder must not reset its pin.
                            pinFlag = folder?.pinFlag ?: 0L
                        )
                        onSave(next)
                        showToast("已保存")
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
                    val query =
                        "SELECT $select FROM rcontact r LEFT JOIN img_flag i ON r.username = i.username LEFT JOIN rconversation c ON r.username = c.username WHERE $where"
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
        val whereClause: String = "",
        // High 8 bits (pin / move-up state, owned by WeChat's setPlacedTop / unSetPlacedTop) of this
        // folder's rconversation row, mirrored here so it survives onDisable deleting the row. Kept
        // in sync from the live row by snapshotFolderPinFlags and restored by composeFolderFlag.
        val pinFlag: Long = 0L
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
