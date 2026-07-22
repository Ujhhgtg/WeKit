package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.widget.ListView
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
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.lang.reflect.Modifier as JavaModifier

@Feature(name = "对话分组", categories = ["聊天"], description = "向主页顶部添加 Tab 栏, 将对话分组\n建议同时启用「界面美化/隐藏主页下滑「最近」页」")
object ConversationGrouping : SwitchFeature(), IResolveDex {

    const val GROUP_PREFIX = "wekit_group_"

    // The fixed "全部" tab. It behaves like a group for ordering purposes — it can be dragged to any
    // position and that position is persisted alongside the real groups — but it can never be
    // edited or deleted, and selecting it applies no filter (null predicate). It's stored as an
    // ordinary ChatGroup entry (identified solely by this id) so the list order is enough to
    // remember where it sits.
    private const val ALL_TAB_ID = "${GROUP_PREFIX}all"

    private fun isAllTab(id: String?): Boolean = id == ALL_TAB_ID

    private fun allTab(): ChatGroup = ChatGroup(id = ALL_TAB_ID, name = "全部")

    // The SQL predicate for the currently selected tab, injected into WeChat's homepage
    // conversation-list query. null = "全部" (no filtering). We resolve the predicate once, when the
    // tab is tapped (on the main thread), so the query hook itself never runs nested DB reads while
    // WeChat is mid-query. Switching tabs then just asks WeChat to reload the cursor.
    @Volatile
    private var activePredicate: String? = null

    private val groupsFile by lazy { KnownPaths.moduleData / "conversation_groups.json" }

    @Volatile
    private var groupsCache: List<ChatGroup>? = null

    private val groupMembersCache = ConcurrentHashMap<String, List<String>>()

    override fun onEnable() {
        hookConversationListQuery()

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
                                saveGroups(loadGroups().filterNot { it.id == group.id })
                                groups = loadGroups()
                                if (selectedGroupId == group.id) {
                                    selectedGroupId = ALL_TAB_ID
                                    selectTab(ALL_TAB_ID)
                                }
                                showToast("已删除「${group.name}」")
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
    }

    private fun selectTab(groupId: String?) {
        // Resolve the predicate here, on the main thread, NOT inside the query hook: preset/SQL
        // groups need a DB read to materialize their member list, and doing that while WeChat is
        // already running the list query would nest reads on the same path.
        // The "全部" tab (or a null id) applies no filter.
        activePredicate = if (groupId == null || isAllTab(groupId)) {
            null
        } else {
            buildGroupPredicate(groupById(groupId))
        }
        // No DB writes: reloadConversations re-runs the list query on the main thread, and our
        // query hook injects the new filter, so the visible rows change without touching any row.
        WeConversationApi.reloadConversations()
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
        if (methodSqliteWrapperRawQuery.isPlaceholder) {
            WeLogger.w(TAG, "SQLite wrapper query method not resolved; tab filtering disabled")
            return
        }
        methodSqliteWrapperRawQuery.hookBefore {
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
    private val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }

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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 8.dp,
                        alignment = Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(orderedGroups, key = { it.id }) { group ->
                        val allTab = isAllTab(group.id)
                        Box {
                            GroupTab(
                                label = group.name,
                                selected = selectedGroupId == group.id,
                                onClick = { onTabSelected(group.id) },
                                onLongClick = { menuForGroupId = group.id }
                            )

                            DropdownMenu(
                                expanded = menuForGroupId == group.id,
                                onDismissRequest = { menuForGroupId = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("新建") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Add,
                                            contentDescription = "新建分组",
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
                                        text = { Text("编辑") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Edit,
                                                contentDescription = "编辑",
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
                                    text = { Text("排序") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Swap_vert,
                                            contentDescription = "排序",
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
                                        text = { Text("删除") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Delete,
                                                contentDescription = "删除",
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
                            showToast("已保存排序")
                        },
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors()
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check,
                            contentDescription = "保存排序",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

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
            // Reordering replaces scrolling in sort mode; disabling user scroll stops the drag from
            // fighting the list's own horizontal scroll gesture. We auto-scroll near the edges below.
            userScrollEnabled = false,
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
                    label = group.name,
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
                title = "新建分组",
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
                title = "编辑分组",
                group = group,
                onDismiss = onDismiss,
                onDelete = {
                    val current = loadGroups()
                    saveGroups(current.filterNot { it.id == group.id })
                    onGroupDeleted()
                    onDismiss()
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

    @Composable
    private fun GroupEditorDialog(
        title: String,
        group: ChatGroup?,
        onDismiss: () -> Unit,
        onDelete: (() -> Unit)? = null,
        onSave: (ChatGroup) -> Unit
    ) {
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
            getGroupMembers(temp).size
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
                        label = { Text("分组名称") },
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
                                    GroupType.MANUAL -> "手动选择"
                                    GroupType.PRESET_UNREAD -> "自动所有未读"
                                    GroupType.PRESET_GROUPS -> "自动所有群聊"
                                    GroupType.PRESET_OFFICIALS -> "自动所有公众号"
                                    GroupType.SQL -> "自定义 SQL 规则"
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
                                    type = GroupType.MANUAL
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有未读") },
                                onClick = {
                                    type = GroupType.PRESET_UNREAD
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有群聊") },
                                onClick = {
                                    type = GroupType.PRESET_GROUPS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有公众号") },
                                onClick = {
                                    type = GroupType.PRESET_OFFICIALS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自定义 SQL 规则") },
                                onClick = {
                                    type = GroupType.SQL
                                    typeExpanded = false
                                }
                            )
                        }
                    }

                    when (type) {
                        GroupType.MANUAL -> {
                            Text("已选择 $matchedCount 个对话")
                            val context = LocalContext.current
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = "选择对话",
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
                                Text("选择对话")
                            }
                        }

                        GroupType.PRESET_UNREAD -> {
                            Text("自动归拢所有未读对话（当前匹配到 $matchedCount 个对话）")
                        }

                        GroupType.PRESET_GROUPS -> {
                            Text("自动归拢所有群聊（当前匹配到 $matchedCount 个对话）")
                        }

                        GroupType.PRESET_OFFICIALS -> {
                            Text("自动归拢所有公众号（当前匹配到 $matchedCount 个对话）")
                        }

                        GroupType.SQL -> {
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
                        val next = ChatGroup(
                            id = groupId,
                            name = name.trim(),
                            members = members.toList().sorted(),
                            type = type,
                            selectFields = selectFields.trim(),
                            whereClause = whereClause.trim()
                        )
                        onSave(next)
                        showToast("已保存")
                    }
                ) { Text("确定") }
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
                .filter { (isGroupId(it.id) || isAllTab(it.id)) && it.name.isNotBlank() }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode groups config from $groupsFile", it)
        }.getOrDefault(emptyList())
        // Guarantee the fixed "全部" tab is present. Configs written before this tab was orderable
        // won't contain it, so inject it at the front; once the user reorders, its slot persists.
        val withAll = if (groups.any { isAllTab(it.id) }) groups else listOf(allTab()) + groups
        groupsCache = withAll
        return withAll
    }

    // The groups seeded on first run, matching the tabs this feature used to hardcode
    // (minus 全部, which is the fixed non-deletable tab, and 好友).
    private fun defaultGroups(): List<ChatGroup> {
        // Distinct ids so each row is independently editable / deletable. The fixed "全部" tab leads
        // by default but can be dragged elsewhere.
        val base = System.currentTimeMillis()
        return listOf(
            allTab(),
            ChatGroup(id = "$GROUP_PREFIX${base}", name = "未读", type = GroupType.PRESET_UNREAD),
            ChatGroup(id = "$GROUP_PREFIX${base + 1}", name = "群聊", type = GroupType.PRESET_GROUPS),
            ChatGroup(id = "$GROUP_PREFIX${base + 2}", name = "公众号", type = GroupType.PRESET_OFFICIALS)
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
        PRESET_OFFICIALS,
        SQL
    }

    @Serializable
    private data class ChatGroup(
        val id: String = "",
        val name: String = "",
        val members: List<String> = emptyList(),
        val type: GroupType = GroupType.MANUAL,
        val selectFields: String = "",
        val whereClause: String = ""
    )
}
