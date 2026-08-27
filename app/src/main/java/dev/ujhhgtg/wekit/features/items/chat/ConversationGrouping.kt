package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.widget.ListView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Swap_vert
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object ConversationGrouping : ClickableFeature(), IResolveDex {

    override val technicalId = "对话分组"
    override val nameRes = R.string.feature_conversation_grouping_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_conversation_grouping_description

    const val GROUP_PREFIX = "wekit_group_"

    // The fixed "全部" tab. It behaves like a group for ordering purposes — it can be dragged to any
    // position and that position is persisted alongside the real groups — but it can never be
    // edited or deleted, and selecting it applies no filter (null predicate). It's stored as an
    // ordinary ChatGroup entry (identified solely by this id) so the list order is enough to
    // remember where it sits.
    private const val ALL_TAB_ID = "${GROUP_PREFIX}all"

    private enum class GroupingBackend(val value: String) {
        QUERY_REWRITE("query_rewrite"),
        ADAPTER_FILTER("adapter_filter");

        companion object {
            fun from(value: String): GroupingBackend =
                entries.firstOrNull { it.value == value } ?: QUERY_REWRITE
        }
    }

    private var groupingBackendValue by WePrefs.prefOption(
        "conversation_grouping_backend",
        GroupingBackend.QUERY_REWRITE.value,
    )

    private val groupingBackend: GroupingBackend
        get() = GroupingBackend.from(groupingBackendValue)

    private fun isAllTab(id: String?): Boolean = id == ALL_TAB_ID

    private fun allTab(): ChatGroup = ChatGroup(id = ALL_TAB_ID)

    // The SQL predicate for the currently selected tab, injected into WeChat's homepage
    // conversation-list query. null = "全部" (no filtering). We resolve the predicate once, when the
    // tab is tapped (on the main thread), so the query hook itself never runs nested DB reads while
    // WeChat is mid-query. Switching tabs then just asks WeChat to reload the cursor.
    @Volatile
    private var activePredicate: String? = null

    @Volatile
    private var activeAdapterGroup: ChatGroup = allTab()

    @Volatile
    private var activeAdapterMembers: Set<String> = emptySet()

    private data class AdapterCache(
        val visiblePositions: List<Int>,
        val rawToVisible: IntArray,
    )
    private data class AdapterMethods(
        val getCount: Method,
        val getItem: Method,
        val getView: Method,
        val storage: AdapterStorage,
    )
    private data class AdapterItemFields(
        val username: Field?,
        val unreadCounts: List<Field>,
    )

    private val adapterCaches = WeakHashMap<Any, AdapterCache>()
    private var adapterMethods: List<AdapterMethods> = emptyList()
    private val adapterSnapshotReader = ConversationAdapterSnapshotReader()
    private val adapterItemFields = ConcurrentHashMap<Class<*>, AdapterItemFields>()
    private val snapshotFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()
    private val bindingAdapter = ThreadLocal<Any?>()
    private val adapterPositionProvider = WeConversationListViewApi.IAdapterPositionProvider { adapter, rawPosition ->
        adapterPositionSnapshot(adapter, rawPosition)
    }

    private val groupsFile by lazy { KnownPaths.moduleData / "conversation_groups.json" }

    @Volatile
    private var groupsCache: List<ChatGroup>? = null

    private val groupMembersCache = ConcurrentHashMap<String, List<String>>()

    override fun onEnable() {
        if (groupingBackend == GroupingBackend.QUERY_REWRITE) {
            hookConversationListQuery()
        } else {
            hookConversationListAdapter()
        }

        methodOnTabCreate.hookAfter {
            val convListView = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.conversation.ConversationListView"
                }
                .get()!! as ListView

            val composeView = ComposeView(convListView.context).apply {
                val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
                setLifecycleOwner(lifecycleOwner)

                val context = convListView.context

                // These values get lost when ComposeView becomes invisible, so we have to lift them
                // out of the Composable.
                val selectedGroupIdState = mutableStateOf(ALL_TAB_ID)
                val groupsState = mutableStateOf(loadGroups())
                setContent {
                    InjectedUiTheme {
                        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
                        var selectedGroupId by selectedGroupIdState
                        var groups by groupsState

                        ConversationTabs(
                            groups = groups,
                            selectedGroupId = selectedGroupId,
                            onTabSelected = { groupId ->
                                selectedGroupId = groupId
                                selectTab(groupId)
                            },
                            onCreateGroup = {
                                showCreateGroupDialog(context) {
                                    groups = loadGroups()
                                }
                            },
                            onEditGroup = { group ->
                                showEditGroupDialog(
                                    context = context,
                                    group = group,
                                    onGroupUpdated = {
                                        groups = loadGroups()
                                        // Recompute the filter if the edited group is the active one.
                                        if (selectedGroupId == group.id) selectTab(group.id)
                                    },
                                    onGroupDeleted = {
                                        groups = loadGroups()
                                        if (selectedGroupId == group.id) {
                                            selectedGroupId = ALL_TAB_ID
                                            selectTab(ALL_TAB_ID)
                                        }
                                    }
                                )
                            },
                            onDeleteGroup = { group ->
                                showConfirmDeleteGroupDialog(context, group) {
                                    saveGroups(loadGroups().filterNot { it.id == group.id })
                                    groups = loadGroups()
                                    if (selectedGroupId == group.id) {
                                        selectedGroupId = ALL_TAB_ID
                                        selectTab(ALL_TAB_ID)
                                    }
                                    showToast(
                                        localizedContext.getString(
                                            R.string.conversation_group_deleted,
                                            localizedGroupName(localizedContext, group),
                                        )
                                    )
                                }
                            },
                            onReorder = { orderedIds ->
                                val current = loadGroups()
                                val byId = current.associateBy { it.id }
                                val reordered = orderedIds.mapNotNull { byId[it] }
                                // Keep any groups that somehow weren't in the ordered list appended.
                                val missing = current.filterNot { g -> orderedIds.contains(g.id) }
                                saveGroups(reordered + missing)
                                groups = loadGroups()
                            }
                        )
                    }
                }
            }
            convListView.addHeaderView(composeView)
        }
        if (groupingBackend == GroupingBackend.ADAPTER_FILTER) {
            WeConversationListViewApi.addPositionProvider(adapterPositionProvider)
        }
    }

    override fun onDisable() {
        WeConversationListViewApi.removePositionProvider(adapterPositionProvider)
        bindingAdapter.remove()
        clearAdapterCaches()
        snapshotFailuresLogged.clear()
    }

    private fun hookConversationListAdapter() {
        val viewHooks = listOf(
            WeConversationListViewApi.methodLegacyGetView to AdapterStorage.LEGACY_CURSOR,
            WeConversationListViewApi.methodMvvmGetView to AdapterStorage.MVVM_LIST,
        ).filterNot { (delegate, _) -> delegate.isPlaceholder }
        if (viewHooks.isEmpty()) {
            error("conversation adapter filter targets were not resolved")
        }
        adapterMethods = viewHooks.map { (delegate, storage) ->
            val getView = delegate.method
            val owner = getView.declaringClass.reflekt()
            AdapterMethods(
                getCount = owner.firstMethod { name = "getCount"; parameterCount = 0 }.self,
                getItem = owner.firstMethod { name = "getItem"; parameterCount = 1 }.self,
                getView = getView,
                storage = storage,
            )
        }
        adapterMethods.forEach { methods ->
            methods.getCount.hookAfter {
                if (groupingBackend != GroupingBackend.ADAPTER_FILTER) return@hookAfter
                if (isAllTab(activeAdapterGroup.id)) return@hookAfter
                val adapter = thisObject!!
                val boundCache = if (bindingAdapter.get() === adapter) {
                    synchronized(adapterCaches) { adapterCaches[adapter] }
                } else {
                    null
                }
                if (boundCache != null) {
                    result = boundCache.visiblePositions.size
                    return@hookAfter
                }
                rebuildAdapterCache(adapter, result as Int)?.let { result = it.visiblePositions.size }
            }
            methods.getView.hookBefore(priority = 100) {
                if (groupingBackend != GroupingBackend.ADAPTER_FILTER) return@hookBefore
                if (isAllTab(activeAdapterGroup.id)) return@hookBefore
                val adapter = thisObject!!
                val position = args[0] as Int
                val cache = synchronized(adapterCaches) { adapterCaches[adapter] } ?: return@hookBefore
                bindingAdapter.set(adapter)
                if (position in cache.visiblePositions.indices) {
                    args[0] = cache.visiblePositions[position]
                }
            }
            methods.getView.hookAfter(priority = 100) {
                if (bindingAdapter.get() === thisObject) bindingAdapter.remove()
            }
        }
    }

    private fun rebuildAdapterCache(adapter: Any, rawCount: Int): AdapterCache? {
        synchronized(adapterCaches) {
            // Never let a failed refresh leave an index built for an older backing dataset.
            adapterCaches.remove(adapter)
            val group = activeAdapterGroup
            val methods = adapterMethods(adapter)
            val items: List<Any?>? = runCatching {
                when (methods.storage) {
                    AdapterStorage.MVVM_LIST -> adapterSnapshotReader.read(adapter, rawCount) { index ->
                        methods.getItem.invoke(adapter, index)
                    }
                    AdapterStorage.LEGACY_CURSOR -> object : AbstractList<Any?>() {
                        override val size: Int get() = rawCount
                        override fun get(index: Int): Any? = methods.getItem.invoke(adapter, index)
                    }
                }
            }.getOrElse { error ->
                if (snapshotFailuresLogged.add(adapter.javaClass)) {
                    WeLogger.e(TAG, "adapter filter snapshot probe failed for ${adapter.javaClass.name}", error)
                }
                return null
            }
            if (items == null) {
                if (snapshotFailuresLogged.add(adapter.javaClass)) {
                    WeLogger.e(
                        TAG,
                        "adapter filter backing list unresolved for ${adapter.javaClass.name}; leaving it unfiltered",
                    )
                }
                adapterCaches.remove(adapter)
                return null
            }
            val visible = runCatching {
                items.mapIndexedNotNull { index, item ->
                    if (adapterItemMatches(item, group)) index else null
                }
            }.getOrElse { error ->
                if (snapshotFailuresLogged.add(adapter.javaClass)) {
                    WeLogger.e(TAG, "adapter filter snapshot failed for ${adapter.javaClass.name}", error)
                }
                adapterCaches.remove(adapter)
                return null
            }
            val rawToVisible = IntArray(rawCount) { -1 }
            visible.forEachIndexed { visiblePosition, rawPosition ->
                if (rawPosition in rawToVisible.indices) rawToVisible[rawPosition] = visiblePosition
            }
            return AdapterCache(visible, rawToVisible).also { adapterCaches[adapter] = it }
        }
    }

    private fun adapterPositionSnapshot(
        adapter: Any,
        currentRawPosition: Int,
    ): WeConversationListViewApi.AdapterPositionSnapshot? = synchronized(adapterCaches) {
        val cache = adapterCaches[adapter] ?: return@synchronized null
        val visiblePosition = cache.rawToVisible.getOrNull(currentRawPosition) ?: return@synchronized null
        if (visiblePosition < 0) return@synchronized null
        WeConversationListViewApi.AdapterPositionSnapshot(
            visiblePosition = visiblePosition,
            itemCount = cache.visiblePositions.size,
            currentRawPosition = currentRawPosition,
            previousRawPosition = cache.visiblePositions.getOrNull(visiblePosition - 1),
            nextRawPosition = cache.visiblePositions.getOrNull(visiblePosition + 1),
        )
    }

    private fun clearAdapterCaches() {
        synchronized(adapterCaches) { adapterCaches.clear() }
    }

    private fun adapterMethods(adapter: Any): AdapterMethods =
        adapterMethods.first { it.getView.declaringClass.isInstance(adapter) }

    private fun adapterItemMatches(item: Any?, group: ChatGroup): Boolean {
        if (isAllTab(group.id)) return true
        val username = adapterItemUsername(item) ?: return false
        return when (group.type) {
            GroupType.PRESET_UNREAD -> adapterItemUnread(item) > 0
            GroupType.PRESET_GROUPS -> username.endsWith("@chatroom")
            GroupType.PRESET_FRIENDS -> !username.endsWith("@chatroom") && !username.startsWith("gh_")
            GroupType.MANUAL, GroupType.SQL -> activeAdapterMembers.contains(username)
            GroupType.PRESET_OFFICIALS -> username.startsWith("gh_")
        }
    }

    private fun adapterItemUsername(item: Any?): String? {
        if (item == null) return null
        if (item is Map<*, *>) return item["username"]?.toString()
        return itemFields(item).username?.get(item) as? String
    }

    private fun adapterItemUnread(item: Any?): Int {
        if (item == null) return 0
        if (item is Map<*, *>) {
            return listOf("field_unReadCount", "unReadCount", "field_unReadMuteCount", "unReadMuteCount")
                .sumOf { (item[it] as? Number)?.toInt() ?: 0 }
        }
        return itemFields(item).unreadCounts.sumOf { (it.get(item) as? Number)?.toInt() ?: 0 }
    }

    private fun itemFields(item: Any): AdapterItemFields =
        adapterItemFields.getOrPut(item.javaClass) {
            val fields = generateSequence(item.javaClass as Class<*>?) { it.superclass }
                .takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
                .onEach { it.isAccessible = true }
                .toList()
            AdapterItemFields(
                username = fields.firstOrNull { it.name == "field_username" || it.name == "username" },
                unreadCounts = fields.filter {
                    it.name == "field_unReadCount" || it.name == "unReadCount" ||
                        it.name == "field_unReadMuteCount" || it.name == "unReadMuteCount"
                },
            )
        }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selected by remember { mutableStateOf(groupingBackend) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.conversation_grouping_backend_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = GroupingBackend.QUERY_REWRITE.value) {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.conversation_grouping_backend_query),
                                description = stringResource(R.string.conversation_grouping_backend_query_description),
                                selected = selected == GroupingBackend.QUERY_REWRITE,
                                onClick = {
                                    selected = GroupingBackend.QUERY_REWRITE
                                    selectGroupingBackend(GroupingBackend.QUERY_REWRITE)
                                },
                            )
                        }
                        item(key = GroupingBackend.ADAPTER_FILTER.value) {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.conversation_grouping_backend_adapter),
                                description = stringResource(R.string.conversation_grouping_backend_adapter_description),
                                selected = selected == GroupingBackend.ADAPTER_FILTER,
                                onClick = {
                                    selected = GroupingBackend.ADAPTER_FILTER
                                    selectGroupingBackend(GroupingBackend.ADAPTER_FILTER)
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun selectGroupingBackend(backend: GroupingBackend) {
        if (groupingBackend == backend) return
        groupingBackendValue = backend.value
        activePredicate = if (backend == GroupingBackend.QUERY_REWRITE &&
            !isAllTab(activeAdapterGroup.id)
        ) {
            buildGroupPredicate(activeAdapterGroup)
        } else {
            null
        }
        clearAdapterCaches()
        if (isActive) disable()
        if (isEnabled) {
            enable()
            if (!isActive) return
            refreshConversations(backend)
        }
    }

    private fun selectTab(groupId: String?) {
        // Resolve the predicate here, on the main thread, NOT inside the query hook: preset/SQL
        // groups need a DB read to materialize their member list, and doing that while WeChat is
        // already running the list query would nest reads on the same path.
        // The "全部" tab (or a null id) applies no filter.
        activeAdapterGroup = if (groupId == null || isAllTab(groupId)) {
            allTab()
        } else {
            groupById(groupId) ?: allTab()
        }
        activeAdapterMembers = when (activeAdapterGroup.type) {
            GroupType.MANUAL, GroupType.SQL ->
                getGroupMembers(activeAdapterGroup).toSet()
            else -> emptySet()
        }
        activePredicate = if (groupingBackend == GroupingBackend.QUERY_REWRITE &&
            groupId != null && !isAllTab(groupId)
        ) {
            buildGroupPredicate(activeAdapterGroup)
        } else {
            null
        }
        clearAdapterCaches()
        refreshConversations(groupingBackend)
    }

    private fun refreshConversations(backend: GroupingBackend) {
        if (backend == GroupingBackend.ADAPTER_FILTER) {
            // FreeMoe refreshes the concrete adapter directly. Avoid broadcasting a conversation
            // storage change here: that synchronously re-runs the database/query observer chain.
            WeConversationListViewApi.refresh()
        } else {
            // Query Rewrite needs a fresh host query so the new SQL predicate is applied.
            WeConversationApi.reloadConversations()
        }
    }

    /**
     * Translates a group definition into a SQL predicate over rconversation. Preset groups use a
     * live LIKE so newly-arrived chats appear without re-selecting the tab; manual / SQL groups
     * resolve to an explicit username set. A missing group or an empty member set yields "0" (match
     * nothing) rather than null, so an empty group shows an empty list instead of everything.
     */
    private fun buildGroupPredicate(group: ChatGroup?): String {
        group ?: return "0"
        return when (group.type) {
            GroupType.PRESET_UNREAD -> "rconversation.unReadCount>0 OR rconversation.unReadMuteCount>0"
            GroupType.PRESET_GROUPS -> "rconversation.username LIKE '%@chatroom'"
            GroupType.PRESET_FRIENDS ->
                "rconversation.username NOT LIKE '%@chatroom' AND rconversation.username NOT LIKE 'gh_%'"
            GroupType.PRESET_OFFICIALS -> "rconversation.username LIKE 'gh_%'"
            GroupType.MANUAL -> membersInClause(group.members)
            GroupType.SQL -> membersInClause(resolveGroupMembers(group))
        }
    }

    private fun membersInClause(members: List<String>): String {
        val cleaned = members.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return "0"
        val list = cleaned.joinToString(",") { "'${it.replace("'", "''")}'" }
        return "rconversation.username IN ($list)"
    }

    // The homepage conversation-list cursor does NOT flow through the standard
    // SQLiteDatabase.rawQuery path that WeDatabaseListenerApi hooks; WeChat builds it through its
    // own SQLite wrapper (n3 -> i0.a(sql, args, int)). We hook that wrapper directly, the same
    // chokepoint AggregateChats uses, and append our tab predicate to the SQL before it runs.
    private fun hookConversationListQuery() {
        if (WeDatabaseApi.methodSqliteWrapperRawQuery.isPlaceholder) {
            WeLogger.w(TAG, "SQLite wrapper query method not resolved; tab filtering disabled")
            return
        }
        WeDatabaseApi.methodSqliteWrapperRawQuery.hookBefore {
            val sql = args.firstOrNull() as? String ?: return@hookBefore
            rewriteConversationListSql(sql)?.let { args[0] = it }
        }
    }

    // Returns the rewritten SQL, or null to leave it untouched (all non-list queries and "全部").
    private fun rewriteConversationListSql(sql: String): String? {
        val predicate = activePredicate ?: return null
        if (!looksLikeConversationListQuery(sql)) return null

        val hidden = if (HideContacts.isEnabled) HideContacts.hiddenContacts else emptySet()
        val hiddenClause = if (hidden.isEmpty()) {
            ""
        } else {
            " AND rconversation.username NOT IN (" +
                    hidden.joinToString(",") { "'${it.replace("'", "''")}'" } + ")"
        }

        return injectCondition(sql, "($predicate)$hiddenClause")
    }

    private fun looksLikeConversationListQuery(sql: String): Boolean {
        val lower = sql.lowercase()
        if (!lower.contains("select")) return false
        if (!lower.contains("from rconversation")) return false
        // Don't touch AggregateChats folder-container queries (scoped to a wekit_folder_ parentRef)
        // or WeChat's own conversation-box container; the tabs only apply to the homepage list.
        if (lower.contains("wekit_folder_") || lower.contains("conversationboxservice")) return false
        // The homepage list query is the one carrying per-conversation display columns; ignore
        // aggregate/count/single-row lookups so we don't corrupt unrelated reads.
        return lower.contains("conversationtime") &&
                lower.contains("unreadcount") &&
                lower.contains("digestuser")
    }

    // Insert an extra WHERE predicate before any ORDER BY / GROUP BY / LIMIT tail, joining with the
    // existing WHERE when present. Mirrors AggregateChats.appendParentRefFilter.
    private fun injectCondition(sql: String, condition: String): String {
        val insertionPoint = listOf(" order by ", " group by ", " limit ")
            .map { sql.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: sql.length
        val head = sql.substring(0, insertionPoint)
        val tail = sql.substring(insertionPoint)
        val connector = if (head.contains(" where ", ignoreCase = true)) " AND " else " WHERE "
        return "$head$connector$condition$tail"
    }

    private const val TAG = "ConversationGrouping"

    private val methodOnTabCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.conversation.MainUI"
            usingEqStrings("MicroMsg.MainUI", "onTabCreate, %d")
        }
    }

    // WeChat's SQLite wrapper query: i0.a(String sql, String[] args, int) -> Cursor. Same anchor
    // AggregateChats uses to intercept the homepage/folder list queries.
    // ----------------------------------------------------------------------------------------------
    // Tab bar UI
    // ----------------------------------------------------------------------------------------------

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ConversationTabs(
        groups: List<ChatGroup>,
        selectedGroupId: String,
        onTabSelected: (String) -> Unit,
        onCreateGroup: () -> Unit,
        onEditGroup: (ChatGroup) -> Unit,
        onDeleteGroup: (ChatGroup) -> Unit,
        onReorder: (List<String>) -> Unit,
        modifier: Modifier = Modifier,
        containerColor: Color = if (isSystemInDarkTheme()) Color(0xFF111111) else Color(0xFFEDEDED),
    ) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        var menuForGroupId by remember { mutableStateOf<String?>(null) }
        // Sort (edit) mode: tabs jiggle in place and can be long-pressed to drag-reorder.
        var sortMode by remember { mutableStateOf(false) }
        // The working order while sorting. Seeded from `groups` on entry and mutated live as the
        // user drags; committed via onReorder only when the check button is tapped.
        var order by remember { mutableStateOf(groups.map { it.id }) }

        // Keep the working order in sync while NOT sorting (groups added/removed/edited elsewhere).
        LaunchedEffect(groups, sortMode) {
            if (!sortMode) order = groups.map { it.id }
        }

        val orderedGroups = remember(order, groups) {
            val byId = groups.associateBy { it.id }
            order.mapNotNull { byId[it] }
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(containerColor)
        ) {
            if (sortMode) {
                SortableTabsRow(
                    groups = orderedGroups,
                    onMove = { from, to ->
                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                    }
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 8.dp,
                        alignment = Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(orderedGroups, key = { it.id }) { group ->
                        val allTab = isAllTab(group.id)
                        val label = groupDisplayName(group)
                        Box {
                            GroupTab(
                                label = label,
                                selected = selectedGroupId == group.id,
                                onClick = { onTabSelected(group.id) },
                                onLongClick = { menuForGroupId = group.id }
                            )

                            DropdownMenu(
                                expanded = menuForGroupId == group.id,
                                onDismissRequest = { menuForGroupId = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conversation_group_action_new)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Add,
                                            contentDescription = stringResource(R.string.conversation_group_new_description),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        menuForGroupId = null
                                        onCreateGroup()
                                    }
                                )
                                // The fixed "全部" tab can be reordered but never edited or deleted.
                                if (!allTab) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.conversation_group_action_edit)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Edit,
                                                contentDescription = stringResource(R.string.conversation_group_action_edit),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            menuForGroupId = null
                                            onEditGroup(group)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conversation_group_action_reorder)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Swap_vert,
                                            contentDescription = stringResource(R.string.conversation_group_action_reorder),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        menuForGroupId = null
                                        order = groups.map { it.id }
                                        sortMode = true
                                    }
                                )
                                if (!allTab) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.conversation_group_action_delete)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Delete,
                                                contentDescription = stringResource(R.string.conversation_group_action_delete),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            menuForGroupId = null
                                            onDeleteGroup(group)
                                        }
                                    )
                                }
                            }
                        }
                    }

                }
            }

            // In sort mode the trailing "+" turns into a "✓" that commits the new order. Overlaid on
            // the right so it stays put regardless of how far the row scrolls.
            if (sortMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .background(containerColor, CircleShape)
                ) {
                    IconButton(
                        onClick = {
                            onReorder(order)
                            sortMode = false
                            showToast(localizedContext.getString(R.string.conversation_group_order_saved))
                        },
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors()
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check,
                            contentDescription = stringResource(R.string.conversation_group_save_order_description),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    private fun localizedGroupName(context: Context, group: ChatGroup): String {
        if (isAllTab(group.id)) return context.getString(R.string.conversation_group_all)
        if (group.name.isNotBlank()) return group.name
        return when (group.builtInLabel) {
            BuiltInGroupLabel.UNREAD -> context.getString(R.string.conversation_group_default_unread)
            BuiltInGroupLabel.GROUPS -> context.getString(R.string.conversation_group_default_groups)
            BuiltInGroupLabel.FRIENDS -> context.getString(R.string.conversation_group_default_friends)
            BuiltInGroupLabel.OFFICIALS -> context.getString(R.string.conversation_group_default_officials)
            null -> ""
        }
    }

    data class GroupChoice(val id: String, val name: String, val members: List<String>)

    /** Public member snapshots used by contact pickers that need to filter by group. */
    fun groupFilterOptions(context: Context): List<GroupChoice> =
        loadGroups()
            .filterNot { isAllTab(it.id) }
            .map { group ->
                GroupChoice(
                    id = group.id,
                    name = localizedGroupName(context, group),
                    members = getGroupMembers(group),
                )
            }

    @Composable
    private fun groupDisplayName(group: ChatGroup): String =
        localizedGroupName(LocalWeKitLocalizedContext.current, group)

    /**
     * The row shown while sorting: every tab jiggles in place (iOS home-screen editing feel), and a
     * long-press on any tab picks it up so dragging left/right reorders the row. Reordering mutates
     * the caller's working order via [onMove]; nothing is persisted until the ✓ button is tapped.
     *
     * We build our own drag handling rather than using a LazyRow so we can control the pickup +
     * live swap ourselves; the tab count is small so a plain Row of measured widths is fine.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SortableTabsRow(
        groups: List<ChatGroup>,
        onMove: (from: Int, to: Int) -> Unit,
    ) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Drag state, all read live inside a single row-level gesture detector so nothing captures a
        // stale `groups` snapshot:
        //  - draggingIndex: the position of the picked-up tab, updated as it swaps past neighbours.
        //  - initialOffset: the tab's layout offset at pickup (fixed for the whole drag).
        //  - draggedDelta: raw accumulated finger movement on X since pickup.
        // The tab's visual translation is initialOffset + draggedDelta - itsCurrentLayoutOffset, so a
        // swap that shifts the layout is compensated automatically without rebasing draggedDelta.
        var draggingIndex by remember { mutableIntStateOf(-1) }
        var initialOffset by remember { mutableIntStateOf(0) }
        var draggedDelta by remember { mutableFloatStateOf(0f) }

        // Drop-settle animation: on release the tab keeps its visual offset and springs it back to 0
        // (its slot), instead of teleporting. settleIndex marks which slot owns settleAnim.
        var settleIndex by remember { mutableIntStateOf(-1) }
        val settleAnim = remember { Animatable(0f) }

        // The dragged tab's live layout info (found by its current index, which we keep updated).
        fun offsetForIndex(index: Int): Float {
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                ?: return 0f
            return initialOffset + draggedDelta - item.offset
        }

        LazyRow(
            state = listState,
            // Keep normal horizontal scrolling while nothing is picked up, so an overflowing tab
            // row can be swiped left/right. Once a tab is picked up the drag consumes the gesture,
            // and the auto-scroll below handles scrolling near the edges.
            userScrollEnabled = draggingIndex == -1,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            // Hit-test the touch against the live layout to pick up the right tab.
                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                offset.x.toInt() in it.offset..it.offset + it.size
                            }
                            if (hit != null) {
                                draggingIndex = hit.index
                                initialOffset = hit.offset
                                draggedDelta = 0f
                            }
                        },
                        onDragEnd = {
                            val landed = draggingIndex
                            val from = offsetForIndex(landed)
                            draggingIndex = -1
                            // Spring the residual offset back to the slot so the tab glides home.
                            if (landed >= 0) scope.launch {
                                settleIndex = landed
                                settleAnim.snapTo(from)
                                settleAnim.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                settleIndex = -1
                            }
                        },
                        onDragCancel = { draggingIndex = -1 },
                        onDrag = { change, amount ->
                            change.consume()
                            if (draggingIndex < 0) return@detectDragGesturesAfterLongPress
                            draggedDelta += amount.x
                            val info = listState.layoutInfo.visibleItemsInfo
                            val cur = info.firstOrNull { it.index == draggingIndex }
                                ?: return@detectDragGesturesAfterLongPress
                            // Center of the dragged tab as it currently sits under the finger.
                            val center = (cur.offset + offsetForIndex(draggingIndex) + cur.size / 2f).toInt()
                            val target = info.firstOrNull { other ->
                                other.index != draggingIndex &&
                                        center in other.offset..other.offset + other.size
                            }
                            if (target != null) {
                                onMove(draggingIndex, target.index)
                                draggingIndex = target.index
                            }
                        }
                    )
                },
            // Leave room on the right for the overlaid ✓ button.
            contentPadding = PaddingValues(
                start = 12.dp, end = 56.dp, top = 8.dp, bottom = 8.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(groups.size, key = { groups[it].id }) { index ->
                val group = groups[index]
                val dragging = index == draggingIndex
                val settling = index == settleIndex

                JiggleTab(
                    label = groupDisplayName(group),
                    // Keep the lift look through the settle so scale eases out alongside the glide.
                    dragging = dragging || settling,
                    // Offset the jiggle phase by index so neighbouring tabs aren't perfectly in sync.
                    phaseIndex = index,
                    // Read at draw time so a swap-induced layout shift is reflected without a
                    // recomposition-timing gap. While dragging: follow the finger. While settling:
                    // the spring value. Otherwise: 0 (its slot).
                    dragOffsetX = {
                        when {
                            dragging -> offsetForIndex(index)
                            settling -> settleAnim.value
                            else -> 0f
                        }
                    },
                    modifier = Modifier
                        .zIndex(if (dragging || settling) 1f else 0f)
                        // Neighbours springing aside to make room animate their placement smoothly
                        // instead of jumping. The dragged tab is excluded (it tracks the finger).
                        .then(if (dragging || settling) Modifier else Modifier.animateItem())
                )
            }
        }

        // Auto-scroll the row when the dragged tab is pushed near either edge.
        LaunchedEffect(Unit) {
            snapshotFlow { if (draggingIndex >= 0) draggedDelta else Float.NaN }.collect { delta ->
                if (delta.isNaN()) return@collect
                val info = listState.layoutInfo
                val cur = info.visibleItemsInfo.firstOrNull { it.index == draggingIndex } ?: return@collect
                val center = cur.offset + offsetForIndex(draggingIndex) + cur.size / 2f
                val edge = 64
                when {
                    center < info.viewportStartOffset + edge && listState.canScrollBackward ->
                        scope.launch { listState.scrollBy(-12f) }

                    center > info.viewportEndOffset - edge && listState.canScrollForward ->
                        scope.launch { listState.scrollBy(12f) }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GroupTab(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) {
        val backgroundColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
        val contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Surface(
            color = backgroundColor,
            contentColor = contentColor,
            shape = CircleShape
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                )
            }
        }
    }

    /**
     * A tab pill in sort mode: continuously rotates a couple degrees back and forth (the iOS
     * home-screen "jiggle"), lifts and enlarges slightly while being dragged, and follows the
     * finger horizontally via [dragOffsetX].
     */
    @Composable
    private fun JiggleTab(
        label: String,
        dragging: Boolean,
        phaseIndex: Int,
        dragOffsetX: () -> Float,
        modifier: Modifier = Modifier,
    ) {
        val transition = rememberInfiniteTransition(label = "jiggle")
        // Alternate the start phase per index so neighbouring tabs jiggle out of sync.
        val rotation by transition.animateFloat(
            initialValue = -2.5f,
            targetValue = 2.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 160, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(if (phaseIndex % 2 == 0) 0 else 80)
            ),
            label = "rotation"
        )

        // Spring the lift scale up/down so picking up and dropping ease in and out.
        val scale by animateFloatAsState(
            targetValue = if (dragging) 1.1f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "scale"
        )
        // Fade the jiggle out while lifted rather than cutting it dead.
        val jiggleDamp by animateFloatAsState(
            targetValue = if (dragging) 0f else 1f,
            animationSpec = tween(durationMillis = 150),
            label = "jiggleDamp"
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape,
            modifier = modifier.graphicsLayer {
                rotationZ = rotation * jiggleDamp
                translationX = dragOffsetX()
                scaleX = scale
                scaleY = scale
            }
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Group configuration UI (copied 1:1 from AggregateChats' folder editor)
    // ----------------------------------------------------------------------------------------------

    private fun showCreateGroupDialog(context: Context, onGroupCreated: () -> Unit) {
        showComposeDialog(context) {
            GroupEditorDialog(
                titleRes = R.string.conversation_group_create_title,
                group = null,
                onDismiss = onDismiss,
                onSave = { group ->
                    val current = loadGroups()
                    saveGroups(current + group)
                    onGroupCreated()
                    onDismiss()
                }
            )
        }
    }

    private fun showEditGroupDialog(
        context: Context,
        group: ChatGroup,
        onGroupUpdated: () -> Unit,
        onGroupDeleted: () -> Unit
    ) {
        showComposeDialog(context) {
            GroupEditorDialog(
                titleRes = R.string.conversation_group_edit_title,
                group = group,
                onDismiss = onDismiss,
                onDelete = {
                    showConfirmDeleteGroupDialog(context, group) {
                        val current = loadGroups()
                        saveGroups(current.filterNot { it.id == group.id })
                        onGroupDeleted()
                        onDismiss()
                    }
                },
                onSave = { updated ->
                    val current = loadGroups()
                    saveGroups(current.map { if (it.id == updated.id) updated else it })
                    onGroupUpdated()
                    onDismiss()
                }
            )
        }
    }

    private fun showConfirmDeleteGroupDialog(
        context: Context,
        group: ChatGroup,
        onConfirm: () -> Unit,
    ) {
        showComposeDialog(context) {
            val groupName = groupDisplayName(group)
            AlertDialogContent(
                title = { Text(stringResource(R.string.conversation_group_delete_title)) },
                text = { Text(stringResource(R.string.conversation_group_delete_message, groupName)) },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        onConfirm()
                    }) { Text(stringResource(R.string.conversation_group_action_delete)) }
                }
            )
        }
    }

    @Composable
    private fun GroupEditorDialog(
        @StringRes titleRes: Int,
        group: ChatGroup?,
        onDismiss: () -> Unit,
        onDelete: (() -> Unit)? = null,
        onSave: (ChatGroup) -> Unit
    ) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        val groupId = remember(group) { group?.id ?: newGroupId() }
        var name by remember(group) { mutableStateOf(group?.name ?: "") }
        var members by remember(group) { mutableStateOf(group?.members?.toSet().orEmpty()) }

        var type by remember(group) { mutableStateOf(group?.type ?: GroupType.MANUAL) }
        var selectFields by remember(group) { mutableStateOf(group?.selectFields ?: "r.username") }
        var whereClause by remember(group) { mutableStateOf(group?.whereClause ?: "") }

        val matchedCount = remember(type, members, selectFields, whereClause) {
            val temp = ChatGroup(
                id = groupId,
                name = name,
                members = members.toList(),
                type = type,
                selectFields = selectFields,
                whereClause = whereClause
            )
            // Resolve directly instead of going through getGroupMembers: that cache is keyed by
            // group id, and this preview group reuses the id of the group being edited, so the
            // cached (stale) member list would freeze the count at the first result.
            resolveGroupMembers(temp).size
        }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            title = { Text(stringResource(titleRes)) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.conversation_group_name)) },
                        placeholder = group?.takeIf { it.builtInLabel != null }?.let { builtInGroup ->
                            { Text(groupDisplayName(builtInGroup)) }
                        },
                        singleLine = true
                    )

                    var typeExpanded by remember { mutableStateOf(false) }
                    Column {
                        Text(stringResource(R.string.conversation_group_mode), style = MaterialTheme.typography.labelSmall)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { typeExpanded = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = when (type) {
                                    GroupType.MANUAL -> stringResource(R.string.conversation_group_mode_manual)
                                    GroupType.PRESET_UNREAD -> stringResource(R.string.conversation_group_mode_unread)
                                    GroupType.PRESET_GROUPS -> stringResource(R.string.conversation_group_mode_groups)
                                    GroupType.PRESET_FRIENDS -> stringResource(R.string.conversation_group_mode_friends)
                                    GroupType.PRESET_OFFICIALS -> stringResource(R.string.conversation_group_mode_officials)
                                    GroupType.SQL -> stringResource(R.string.conversation_group_mode_sql)
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_manual)) },
                                onClick = {
                                    type = GroupType.MANUAL
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_unread)) },
                                onClick = {
                                    type = GroupType.PRESET_UNREAD
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_groups)) },
                                onClick = {
                                    type = GroupType.PRESET_GROUPS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_friends)) },
                                onClick = {
                                    type = GroupType.PRESET_FRIENDS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_officials)) },
                                onClick = {
                                    type = GroupType.PRESET_OFFICIALS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_sql)) },
                                onClick = {
                                    type = GroupType.SQL
                                    typeExpanded = false
                                }
                            )
                        }
                    }

                    when (type) {
                        GroupType.MANUAL -> {
                            Text(stringResource(R.string.conversation_group_selected_count, matchedCount))
                            val context = LocalContext.current
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(R.string.conversation_group_select_conversations),
                                            contacts = remember { WeDatabaseApi.getContacts() },
                                            initialSelectedWxIds = members,
                                            onDismiss = this.onDismiss,
                                            onConfirm = {
                                                members = it
                                                this.onDismiss()
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.conversation_group_select_conversations))
                            }
                        }

                        GroupType.PRESET_UNREAD -> {
                            Text(stringResource(R.string.conversation_group_unread_match_count, matchedCount))
                        }

                        GroupType.PRESET_GROUPS -> {
                            Text(stringResource(R.string.conversation_group_groups_match_count, matchedCount))
                        }

                        GroupType.PRESET_FRIENDS -> {
                            Text(stringResource(R.string.conversation_group_friends_match_count, matchedCount))
                        }

                        GroupType.PRESET_OFFICIALS -> {
                            Text(stringResource(R.string.conversation_group_officials_match_count, matchedCount))
                        }

                        GroupType.SQL -> {
                            OutlinedTextField(
                                value = selectFields,
                                onValueChange = { selectFields = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.conversation_group_select_fields)) },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = whereClause,
                                onValueChange = { whereClause = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.conversation_group_where_clause)) },
                                singleLine = false,
                                maxLines = 4
                            )
                            Text(
                                text = stringResource(R.string.conversation_group_match_count, matchedCount),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.conversation_group_sql_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            dismissButton = {
                if (onDelete != null) {
                    TextButton(onDelete) { Text(stringResource(R.string.conversation_group_action_delete)) }
                }
                TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() || group?.builtInLabel != null,
                    onClick = {
                        val next = ChatGroup(
                            id = groupId,
                            name = name.trim(),
                            members = members.toList().sorted(),
                            type = type,
                            selectFields = selectFields.trim(),
                            whereClause = whereClause.trim(),
                            builtInLabel = group?.builtInLabel,
                        )
                        onSave(next)
                        showToast(localizedContext.getString(R.string.conversation_group_saved))
                    }
                ) { Text(stringResource(R.string.dialog_confirm)) }
            }
        )
    }

    // ----------------------------------------------------------------------------------------------
    // Member resolution & persistence (adapted from AggregateChats)
    // ----------------------------------------------------------------------------------------------

    private fun resolveGroupMembers(group: ChatGroup): List<String> {
        return when (group.type) {
            GroupType.MANUAL -> group.members
            GroupType.PRESET_UNREAD -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT c.username FROM rconversation c WHERE c.unReadCount > 0 OR c.unReadMuteCount > 0"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset unread", it)
                    emptyList()
                }
            }

            GroupType.PRESET_GROUPS -> {
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

            GroupType.PRESET_FRIENDS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username NOT LIKE '%@chatroom' AND r.username NOT LIKE 'gh_%'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset friends", it)
                    emptyList()
                }
            }

            GroupType.PRESET_OFFICIALS -> {
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

            GroupType.SQL -> {
                runCatching {
                    val select = group.selectFields.ifBlank { "r.username" }
                    val where = group.whereClause.ifBlank { "1=1" }
                    val query =
                        "SELECT $select FROM rcontact r LEFT JOIN img_flag i ON r.username = i.username LEFT JOIN rconversation c ON r.username = c.username WHERE $where"
                    val result = WeDatabaseApi.executeQuery(query)
                    result.mapNotNull { row ->
                        val username = row["username"]?.toString()
                        if (username != null) return@mapNotNull username
                        row.values.firstOrNull()?.toString()
                    }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query custom sql for group ${group.id}", it)
                    emptyList()
                }
            }
        }
    }

    private fun getGroupMembers(group: ChatGroup): List<String> {
        if (group.type == GroupType.MANUAL) {
            return group.members
        }
        val cached = groupMembersCache[group.id]
        if (cached != null) return cached

        if (!WeDatabaseApi.isReady) {
            return emptyList()
        }
        val resolved = resolveGroupMembers(group)
        if (resolved.isNotEmpty()) {
            groupMembersCache[group.id] = resolved
        }
        return resolved
    }

    private fun loadGroups(): List<ChatGroup> {
        groupsCache?.let { return it }
        val file = groupsFile
        // First run (no config yet): seed the groups that used to be the built-in tabs so the tab
        // bar isn't empty out of the box, then persist them so they're editable / deletable.
        if (!file.exists()) {
            val defaults = defaultGroups()
            saveGroups(defaults)
            return defaults
        }
        val groups = runCatching {
            val raw = file.readText()
            DefaultJson.decodeFromString<List<ChatGroup>>(raw)
                .map { group ->
                    group.copy(members = group.members.filter { it.isNotBlank() })
                }
                .map(::migrateLegacyBuiltInLabel)
                .filter {
                    (isGroupId(it.id) || isAllTab(it.id)) &&
                        (isAllTab(it.id) || it.name.isNotBlank() || it.builtInLabel != null)
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode groups config from $groupsFile", it)
        }.getOrDefault(emptyList())
        // Guarantee the fixed "全部" tab is present. Configs written before this tab was orderable
        // won't contain it, so inject it at the front; once the user reorders, its slot persists.
        val withAll = if (groups.any { isAllTab(it.id) }) groups else listOf(allTab()) + groups
        groupsCache = withAll
        return withAll
    }

    private fun migrateLegacyBuiltInLabel(group: ChatGroup): ChatGroup {
        if (isAllTab(group.id)) return group.copy(name = "")
        if (group.builtInLabel != null) return group
        val label = when (group.type) {
            GroupType.PRESET_UNREAD if group.name == "未读" -> BuiltInGroupLabel.UNREAD
            GroupType.PRESET_GROUPS if group.name == "群聊" -> BuiltInGroupLabel.GROUPS
            GroupType.PRESET_FRIENDS if group.name == "好友" -> BuiltInGroupLabel.FRIENDS
            GroupType.PRESET_OFFICIALS if group.name == "公众号" -> BuiltInGroupLabel.OFFICIALS
            else -> null
        }
        return if (label == null) group else group.copy(name = "", builtInLabel = label)
    }

    // The groups seeded on first run, matching the fixed categories while keeping every category
    // editable and reorderable except the non-deletable 全部 tab.
    private fun defaultGroups(): List<ChatGroup> {
        // Distinct ids so each row is independently editable / deletable. The fixed "全部" tab leads
        // by default but can be dragged elsewhere.
        val base = System.currentTimeMillis()
        return listOf(
            allTab(),
            ChatGroup(
                id = "$GROUP_PREFIX${base}",
                type = GroupType.PRESET_UNREAD,
                builtInLabel = BuiltInGroupLabel.UNREAD,
            ),
            ChatGroup(
                id = "$GROUP_PREFIX${base + 1}",
                type = GroupType.PRESET_GROUPS,
                builtInLabel = BuiltInGroupLabel.GROUPS,
            ),
            ChatGroup(
                id = "$GROUP_PREFIX${base + 2}",
                type = GroupType.PRESET_FRIENDS,
                builtInLabel = BuiltInGroupLabel.FRIENDS,
            ),
            ChatGroup(
                id = "$GROUP_PREFIX${base + 3}",
                type = GroupType.PRESET_OFFICIALS,
                builtInLabel = BuiltInGroupLabel.OFFICIALS,
            ),
        )
    }

    private fun saveGroups(groups: List<ChatGroup>) {
        groupsCache = groups
        groupMembersCache.clear()
        runCatching {
            val raw = DefaultJson.encodeToString(groups)
            groupsFile.writeText(raw)
        }.onFailure {
            WeLogger.w(TAG, "failed to save groups to $groupsFile", it)
        }
    }

    private fun groupById(groupId: String): ChatGroup? {
        return loadGroups().firstOrNull { it.id == groupId }
    }

    private fun newGroupId(): String = "$GROUP_PREFIX${System.currentTimeMillis()}"

    private fun isGroupId(value: String): Boolean = value.startsWith(GROUP_PREFIX)

    enum class GroupType {
        MANUAL,
        PRESET_UNREAD,
        PRESET_GROUPS,
        PRESET_FRIENDS,
        PRESET_OFFICIALS,
        SQL
    }

    private enum class AdapterStorage {
        LEGACY_CURSOR,
        MVVM_LIST,
    }

    @Serializable
    private enum class BuiltInGroupLabel {
        UNREAD,
        GROUPS,
        FRIENDS,
        OFFICIALS,
    }

    @Serializable
    private data class ChatGroup(
        val id: String = "",
        val name: String = "",
        val members: List<String> = emptyList(),
        val type: GroupType = GroupType.MANUAL,
        val selectFields: String = "",
        val whereClause: String = "",
        val builtInLabel: BuiltInGroupLabel? = null,
    )
}
