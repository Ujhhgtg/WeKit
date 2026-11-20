package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnAttach
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.LifecycleOwner
import com.tencent.mm.ui.base.CustomViewPager
import com.tencent.mm.ui.LauncherUI
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Swap_vert
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi.ConversationUnreadState
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanel
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.i18n.HostLocalizedStrings
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.rememberViewBackdrop
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.data.structured.ConversationGroup as ChatGroup
import dev.ujhhgtg.wekit.data.structured.ConversationGroupType as GroupType
import dev.ujhhgtg.wekit.data.structured.BuiltInGroupLabel
import dev.ujhhgtg.wekit.data.JsonDataMigration
import dev.ujhhgtg.wekit.data.WeKitDatabase
import kotlinx.coroutines.runBlocking
import dev.ujhhgtg.wekit.utils.invokeOriginalMethod
import dev.ujhhgtg.wekit.utils.android.baseActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.div
import kotlin.time.Duration.Companion.milliseconds
import java.lang.reflect.Modifier as ReflectModifier

object ConversationGrouping : ClickableFeature(), IResolveDex {

    override val technicalId = "对话分组"
    override val nameRes = R.string.feature_conversation_grouping_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_conversation_grouping_description

    const val GROUP_PREFIX = "wekit_group_"
    // Shared with the native overlay's hit region so transparent margins pass through to the list.
    const val CAPSULE_HORIZONTAL_INSET_DP = 12
    const val CAPSULE_VERTICAL_INSET_DP = 6

    // The fixed "全部" tab. It behaves like a group for ordering purposes — it can be dragged to any
    // position and that position is persisted alongside the real groups. Only its name can be
    // edited; it cannot be deleted, and selecting it applies no filter. It's stored as an
    // ordinary ChatGroup entry (identified solely by this id) so the list order is enough to
    // remember where it sits.
    private const val ALL_TAB_ID = "${GROUP_PREFIX}all"
    private const val TAB_STYLE_KEY = "conversation_grouping_tab_style"
    private const val TAB_STYLE_FULL_WIDTH = 0
    private const val TAB_STYLE_FLOATING = 1

    private var tabStyle by KvStore.prefOption(TAB_STYLE_KEY, TAB_STYLE_FULL_WIDTH)
    private val tabStyleState by lazy { mutableStateOf(tabStyle) }
    private var pinTabs by KvStore.prefOption("conversation_grouping_pin_tabs", true)
    private var takeOverHorizontalScroll by KvStore.prefOption("conversation_grouping_take_over_horizontal_scroll", true)
    private var rememberScrollState by KvStore.prefOption("conversation_grouping_remember_scroll_state", false)
    private var swipeHooksInstalled = false
    private val swipeSessions = WeakHashMap<ViewGroup, WeakReference<ConversationGroupSwipeSession>>()
    private var showUnread by KvStore.prefOption("conversation_grouping_show_unread", true)
    private val showUnreadState by lazy { mutableStateOf(showUnread) }
    private var includeOfficialUnread by KvStore.prefOption("conversation_grouping_include_official_unread", false)
    private val tabHosts = Collections.newSetFromMap(WeakHashMap<ConversationGroupTabsHost, Boolean>())

    private val groupTabHorizontalPadding = 16.dp
    private val selectionPillEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    val usesFloatingTabs: Boolean
        get() = tabStyleState.value == TAB_STYLE_FLOATING

    /** Called once at startup, before any feature UI can change the old beauty preference. */
    fun migrateTabStyle(legacyFloatingTabs: Boolean) {
        if (KvStore.containsKey(TAB_STYLE_KEY)) return
        // Persist the full-width result too: future beauty toggles must never repeat migration.
        updateTabStyle(if (legacyFloatingTabs) TAB_STYLE_FLOATING else TAB_STYLE_FULL_WIDTH)
    }

    private fun updateTabStyle(style: Int) {
        if (KvStore.containsKey(TAB_STYLE_KEY) && tabStyle == style) return
        swipeSessions.values.mapNotNull { it.get() }.forEach { it.cancelImmediately() }
        tabStyle = style
        tabStyleState.value = style
    }

    fun showFloatingTabsRecommendation(context: Context) {
        if (!isEnabled || usesFloatingTabs) return
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.conversation_grouping_floating_recommend_title)) },
                text = { Text(stringResource(R.string.conversation_grouping_floating_recommend_message)) },
                confirmButton = {
                    Button(onClick = {
                        updateTabStyle(TAB_STYLE_FLOATING)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.conversation_grouping_floating_recommend_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }

    private fun isAllTab(id: String?): Boolean = id == ALL_TAB_ID

    private fun allTab(): ChatGroup = ChatGroup(id = ALL_TAB_ID)

    private data class GroupFilter(val group: ChatGroup, val members: Set<String>)

    @Volatile
    private var activeFilter = GroupFilter(allTab(), emptySet())

    private val activeAdapterGroup: ChatGroup get() = activeFilter.group

    private class PagingFilter(val source: WeakReference<View>, val filter: GroupFilter)
    private val pagingFilters = WeakHashMap<Any, PagingFilter>()

    private fun filterFor(owner: Any): GroupFilter = synchronized(pagingFilters) {
        pagingFilters[owner]?.filter ?: activeFilter
    }

    private fun installPagingFilter(request: PagingRequest) {
        val scoped = PagingFilter(WeakReference(request.source), request.filter)
        synchronized(pagingFilters) {
            pagingFilters[request.adapter] = scoped
            request.list?.let { pagingFilters[it] = scoped }
            request.dataSource?.let { pagingFilters[it] = scoped }
        }
        synchronized(adapterCaches) { adapterCaches.remove(request.adapter) }
    }

    private fun commitPagingGroup(source: View, groupId: String): String {
        val filter = synchronized(pagingFilters) {
            pagingFilters.values.firstOrNull { it.source.get() === source }?.filter
        }?.takeIf { it.group.id == groupId } ?: groupFilter(groupId)
        activeFilter = filter
        return filter.group.id
    }

    private fun releasePagingFilter(source: View) {
        synchronized(pagingFilters) {
            pagingFilters.entries.removeAll { it.value.source.get() === source }
        }
        // Releasing a visual after resize may happen again while the original group is restoring.
        // Cancel only an uncommitted candidate here; the restore must be allowed to finish.
        pagingRequest?.takeIf { it.source === source && it.filter.group.id != activeFilter.group.id }
            ?.let { finishPagingRequest(it, false) }
    }

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
    private val hookedListClickMethods = mutableSetOf<Method>()
    private val adapterSnapshotReader = ConversationAdapterSnapshotReader()
    private val adapterItemFields = ConcurrentHashMap<Class<*>, AdapterItemFields>()
    private val snapshotFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()
    private val bindingAdapter = ThreadLocal<Any?>()
    private val recyclerLists = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
    )
    private val hookedRecyclerAppliedMethods = mutableSetOf<Method>()
    private val pagingHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pagingQueryRequest = ThreadLocal<PagingRequest?>()
    @Volatile
    private var pagingRequest: PagingRequest? = null

    private class PagingRequest(
        val source: View,
        val adapter: Any,
        val filter: GroupFilter,
        val list: Any?,
        val dataSource: Any?,
        val loadCounter: AtomicInteger?,
        val completed: (Boolean) -> Unit,
    ) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        @Volatile var issued = false
        @Volatile var loadId = 0
        @Volatile var expectedNames: Set<String>? = null
        @Volatile var queryCompletedAt = 0L
        var lastIssuedAt = 0L
        var applied = false
        var layoutListener: OneShotPreDrawListener? = null
        var retry: Runnable? = null
    }
    private val adapterPositionProvider = WeConversationListViewApi.IAdapterPositionProvider { adapter, rawPosition ->
        adapterPositionSnapshot(adapter, rawPosition)
    }


    private val groupMembersCache = ConcurrentHashMap<String, List<String>>()
    private val unreadRefreshVersion = MutableStateFlow(0L)
    private val contactUnreadListener = WeDatabaseListenerApi.IUpdateListener { table, values, _, _, _ ->
        // Changing mute settings need not change rconversation's unread counters.
        if (table == "rcontact" && (values.containsKey("type") || values.containsKey("lvbuff"))) {
            unreadRefreshVersion.update { it + 1 }
        }
    }

    private val noUnread = ConversationUnreadState()

    override fun onEnable() {
        loadGroups()
        WeDatabaseListenerApi.addListener(contactUnreadListener)
        WeConversationApi.methodNotifyConversationChanged.hookAfter {
            // This method belongs to the shared storage base; ignore other storage instances.
            if (WeConversationApi.classConversationStorage.clazz.isInstance(thisObject)) {
                unreadRefreshVersion.update { it + 1 }
            }
        }
        hookConversationListAdapter()
        if (takeOverHorizontalScroll || rememberScrollState) ensureSwipeHooks()

        methodOnTabCreate.hookAfter {
            val mainUi = thisObject!!
            val conversationHostView = WeConversationListViewApi.hostView(mainUi)
            if (conversationHostView is ListView) hookListViewFooterClicks(conversationHostView)

            val contentOverlapState = mutableStateOf(false)
            val selectedGroupIdState = mutableStateOf(activeAdapterGroup.id)
            val groupsState = mutableStateOf(loadGroups())
            val backdropSourceState = mutableStateOf(conversationHostView)
            val pagingProgressState = mutableStateOf<ConversationGroupPagingProgress?>(null)
            val groupSortModeState = mutableStateOf(false)
            val activity = conversationHostView.context.baseActivity!! as LauncherUI
            val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)
            var swipeSession: ConversationGroupSwipeSession? = null
            val composeView = ComposeView(conversationHostView.context).apply {
                setLifecycleOwner(lifecycleOwner)

                val context = conversationHostView.context

                // These values get lost when ComposeView becomes invisible, so we have to lift them
                // out of the Composable.
                setContent {
                    InjectedUiTheme {
                        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
                        var selectedGroupId by selectedGroupIdState
                        var groups by groupsState

                        ConversationTabs(
                            sourceView = backdropSourceState.value,
                            lifecycleOwner = lifecycleOwner,
                            hasContentBehind = contentOverlapState.value,
                            pagingProgress = pagingProgressState.value,
                            onSortModeChanged = { groupSortModeState.value = it },
                            groups = groups,
                            selectedGroupId = selectedGroupId,
                            onTabSelected = { groupId ->
                                val handled = (takeOverHorizontalScroll || rememberScrollState) &&
                                    swipeSession?.selectGroup(groupId, animate = takeOverHorizontalScroll) == true
                                if (!handled) {
                                    selectedGroupId = groupId
                                    selectTab(groupId)
                                }
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
                                    deleteGroup(group.id)
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
                                try {
                                    WeKitDatabase.instance.conversationCollectionDao().reorderGroups(orderedIds)
                                } finally {
                                    invalidateGroups()
                                }
                                groups = loadGroups()
                            }
                        )
                    }
                }
            }
            val tabHost = ConversationGroupTabsHost(
                conversationHostView as ViewGroup,
                composeView,
                pinTabs,
                onContentOverlapChanged = { contentOverlapState.value = it },
            )
            tabHost.install(mainUi)
            tabHosts.add(tabHost)
            conversationHostView.doOnAttach {
                val pager = generateSequence(conversationHostView.parent as? View) { it.parent as? View }
                    .filterIsInstance<CustomViewPager>().firstOrNull() ?: return@doOnAttach
                swipeSessions[pager]?.get()?.dispose()
                val session = ConversationGroupSwipeSession(
                    sourceView = conversationHostView,
                    pager = pager,
                    tabHost = tabHost,
                    lifecycleOwner = lifecycleOwner,
                    groupIds = { groupsState.value.map { it.id } },
                    selectedGroupId = { selectedGroupIdState.value },
                    enabled = { isEnabled },
                    swipeEnabled = { takeOverHorizontalScroll },
                    rememberScrollState = { rememberScrollState },
                    canStart = {
                        activity.currentFragmet == null && !groupSortModeState.value &&
                            !HomeSidePanel.blocksConversationGroupSwipe(pager) &&
                            !WeConversationListViewApi.isRecentPageVisible(conversationHostView)
                    },
                    prepareGroup = { groupId, completed ->
                        preparePagingGroup(conversationHostView, groupId, completed)
                    },
                    cancelPreparation = {
                        pagingRequest?.takeIf { it.source === conversationHostView }
                            ?.let { finishPagingRequest(it, false) }
                    },
                    commitGroup = { selectedGroupIdState.value = commitPagingGroup(conversationHostView, it) },
                    onVisualState = { view, progress ->
                        backdropSourceState.value = view ?: conversationHostView
                        pagingProgressState.value = progress
                        if (view == null) releasePagingFilter(conversationHostView)
                    },
                    onFailure = {
                        showToast(HostLocalizedStrings.get(R.string.conversation_grouping_swipe_prepare_failed))
                    },
                )
                swipeSession = session
                swipeSessions[pager] = WeakReference(session)
            }
        }
        WeConversationListViewApi.addPositionProvider(adapterPositionProvider)
    }

    override fun onDisable() {
        swipeSessions.values.mapNotNull { it.get() }.forEach { it.dispose() }
        swipeSessions.clear()
        swipeHooksInstalled = false
        hookedRecyclerAppliedMethods.clear()
        pagingRequest?.let { finishPagingRequest(it, false) }
        synchronized(pagingFilters) { pagingFilters.clear() }
        tabHosts.forEach { it.setPinned(false) }
        tabHosts.clear()
        WeDatabaseListenerApi.removeListener(contactUnreadListener)
        WeConversationListViewApi.removePositionProvider(adapterPositionProvider)
        bindingAdapter.remove()
        synchronized(recyclerLists) { recyclerLists.clear() }
        clearAdapterCaches()
        hookedListClickMethods.clear()
        snapshotFailuresLogged.clear()
    }

    private fun ensureSwipeHooks() {
        if (swipeHooksInstalled || !isActive) return
        val pagerClass = CustomViewPager::class.reflekt()
        pagerClass.firstMethod {
            name = "onInterceptTouchEvent"
            parameters(MotionEvent::class)
        }.hookBefore {
            val session = swipeSessions[thisObject as ViewGroup]?.get() ?: return@hookBefore
            if (session.shouldDeferInterception(args[0] as MotionEvent)) result = false
        }
        val dispatch = pagerClass.firstMethod {
            name = "dispatchTouchEvent"
            parameters(MotionEvent::class)
        }
        dispatch.hookBefore {
            val session = swipeSessions[thisObject as ViewGroup]?.get() ?: return@hookBefore
            if (session.beforeDispatch(args[0] as MotionEvent)) result = true
        }
        dispatch.hookAfter {
            val session = swipeSessions[thisObject as ViewGroup]?.get() ?: return@hookAfter
            val event = args[0] as MotionEvent
            if (session.afterDispatch(event) {
                    val cancel = MotionEvent.obtain(event)
                    try {
                        cancel.action = MotionEvent.ACTION_CANCEL
                        invokeOriginalMethod(args = arrayOf(cancel))
                    } finally {
                        cancel.recycle()
                    }
                }
            ) result = true
        }
        pagerClass.firstMethod {
            name = "onPageScrolled"
            parameters(Int::class, Float::class, Int::class)
            superclass()
        }.hookAfter {
            swipeSessions[thisObject as ViewGroup]?.get()?.onPagerViewportChanged()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, Bundle::class, Boolean::class)
        }.hookBefore {
            swipeSessions.values.mapNotNull { it.get() }.forEach { it.cancelImmediately() }
        }
        ViewGroup::class.reflekt().firstMethod {
            name = "requestDisallowInterceptTouchEvent"
            parameters(Boolean::class)
        }.hookBefore {
            if (args[0] as Boolean) swipeSessions[thisObject as ViewGroup]?.get()?.onChildClaimed()
        }
        swipeHooksInstalled = true
    }

    fun shouldDeferHomeSidePanel(pager: ViewGroup, event: MotionEvent): Boolean {
        val session = swipeSessions[pager]?.get() ?: return false
        session.shouldDeferInterception(event)
        return session.defersHomeSidePanel
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
                getCount = owner.firstMethod {
                    name = "getCount"
                    parameters()
                    returnType = Int::class.java
                    superclass()
                }.self,
                getItem = owner.firstMethod {
                    name = "getItem"
                    parameters(Int::class.java)
                    returnType = Any::class.java
                    superclass()
                }.self,
                getView = getView,
                storage = storage,
            )
        }
        adapterMethods.forEach { methods ->
            methods.getCount.hookAfter {
                val adapter = thisObject!!
                if (isAllTab(filterFor(adapter).group.id)) return@hookAfter
                // The inherited count method is also called by unrelated adapters.
                if (!methods.getView.declaringClass.isInstance(adapter)) return@hookAfter
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
                val adapter = thisObject!!
                if (isAllTab(filterFor(adapter).group.id)) return@hookBefore
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

        if (!WeConversationListViewApi.classConversationRecyclerAdapter.isPlaceholder) {
            hookRecyclerDataSource()
        }
    }

    private fun hookListViewFooterClicks(listView: ListView) {
        val clickMethod = listView.onItemClickListener!!.javaClass.reflekt().firstMethod {
            name = "onItemClick"
            parameters(AdapterView::class, View::class, Int::class, Long::class)
        }.self
        if (clickMethod in hookedListClickMethods) return
        clickMethod.hookBefore {
            val clickedListView = args[0] as ListView
            val adapter = clickedListView.adapter as? HeaderViewListAdapter ?: return@hookBefore
            if (isAllTab(filterFor(adapter.wrappedAdapter).group.id)) return@hookBefore
            if (adapterMethods.none { it.getView.declaringClass.isInstance(adapter.wrappedAdapter) }) {
                return@hookBefore
            }

            // Filtering moves the empty footer into the raw conversation index range. Stop its
            // click before WeChat pairs that tagless view with a real conversation. Host row
            // callbacks may pass raw positions, so locate the clicked view in the visible list.
            val position = clickedListView.getPositionForView(args[1] as View)
            if (position >= adapter.headersCount &&
                adapter.getItemViewType(position) == AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER
            ) {
                result = null
            }
        }
        hookedListClickMethods += clickMethod
    }

    private fun hookRecyclerDataSource() {
        methodRecyclerQueryPage.hookBefore {
            val request = pagingRequest
            pagingQueryRequest.set(request?.takeIf {
                it.issued && it.dataSource === thisObject && it.loadCounter!!.get() == it.loadId
            })
        }
        methodRecyclerQueryPage.hookAfter {
            val request = pagingQueryRequest.get()
            pagingQueryRequest.remove()
            if (!classRecyclerDataSource.clazz.isInstance(thisObject)) return@hookAfter
            if (throwable != null) {
                request?.let { pagingHandler.post { finishPagingRequest(it, false) } }
                return@hookAfter
            }
            val page = result!!
            @Suppress("UNCHECKED_CAST")
            val rows = fieldRecyclerPageItems.field.get(page) as MutableList<Any>
            filterRecyclerRows(rows, filterFor(thisObject!!))
            if (request != null && pagingRequest === request && filterFor(request.adapter) === request.filter &&
                request.loadCounter!!.get() == request.loadId
            ) {
                request.expectedNames = rows.asSequence().filter { classRecyclerRow.clazz.isInstance(it) }
                    .map { adapterItemUsername(fieldRecyclerRowConversation.field.get(it))!! }.toSet()
                request.queryCompletedAt = SystemClock.uptimeMillis()
            }
        }

        WeConversationListViewApi.classConversationRecyclerAdapter.clazz.constructors.forEach { constructor ->
            constructor.hookAfter {
                captureRecyclerList(thisObject!!)
            }
        }
        WeConversationListViewApi.currentAdapter()?.let { adapter ->
            if (WeConversationListViewApi.classConversationRecyclerAdapter.clazz.isInstance(adapter)) {
                captureRecyclerList(adapter)
            }
        }

        methodRecyclerSubmitUiChange.hookBefore {
            if (!recyclerLists.contains(thisObject)) return@hookBefore
            val pendingData = args[0]!!
            @Suppress("UNCHECKED_CAST")
            val rows = fieldRecyclerPendingItems.field.get(pendingData) as MutableList<Any>
            filterRecyclerRows(rows, filterFor(thisObject!!))
        }
    }

    private fun captureRecyclerList(adapter: Any) {
        recyclerLists.add(fieldRecyclerMvvmList.field.get(adapter)!!)
        val applied = adapter.javaClass.reflekt().firstMethod {
            parameters(methodRecyclerSubmitUiChange.method.parameterTypes.single())
            returnType = Void.TYPE
        }.self
        if (hookedRecyclerAppliedMethods.add(applied)) {
            // This adapter callback only runs after ConcurrentMvvmList accepted the pending data.
            // A rejected stale submitUIChange returns before it, including when the group is empty.
            applied.hookAfter {
                val request = pagingRequest ?: return@hookAfter
                if (thisObject !== request.adapter || !request.issued ||
                    filterFor(request.adapter) !== request.filter || request.loadCounter!!.get() != request.loadId
                ) return@hookAfter
                val expected = request.expectedNames ?: return@hookAfter
                @Suppress("UNCHECKED_CAST")
                val rows = fieldRecyclerPendingItems.field.get(args[0]) as List<Any>
                val conversations = rows.filter { classRecyclerRow.clazz.isInstance(it) }
                    .map { fieldRecyclerRowConversation.field.get(it)!! }
                val actual = conversations.map { adapterItemUsername(it)!! }.toSet()
                // The host may merge messages that arrived after the query. Accept those additions
                // only inside the intended group, and require the freshly queried rows to be present.
                if (!actual.containsAll(expected) || conversations.any { !adapterItemMatches(it, request.filter) }) {
                    return@hookAfter
                }
                request.applied = true
                awaitPagingLayout(request)
            }
        }
    }

    private fun filterRecyclerRows(rows: MutableList<Any>, filter: GroupFilter) {
        if (isAllTab(filter.group.id)) return
        rows.removeAll { row ->
            classRecyclerRow.clazz.isInstance(row) &&
                !adapterItemMatches(fieldRecyclerRowConversation.field.get(row), filter)
        }
    }

    private fun refreshRecyclerData(): Boolean {
        val lists = synchronized(recyclerLists) { recyclerLists.toList() }
        if (lists.isEmpty()) return false
        for (list in lists) {
            methodRecyclerRefreshAll.method.invoke(null, list, null, 1, null)
        }
        return true
    }

    private fun rebuildAdapterCache(adapter: Any, rawCount: Int): AdapterCache? {
        synchronized(adapterCaches) {
            // Never let a failed refresh leave an index built for an older backing dataset.
            adapterCaches.remove(adapter)
            val filter = filterFor(adapter)
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
                    if (adapterItemMatches(item, filter)) index else null
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

    private fun adapterItemMatches(item: Any?, filter: GroupFilter): Boolean {
        val group = filter.group
        if (isAllTab(group.id)) return true
        val username = adapterItemUsername(item) ?: return false
        return when (group.type) {
            GroupType.PRESET_UNREAD -> adapterItemUnread(item) > 0
            GroupType.PRESET_GROUPS -> username.endsWith("@chatroom")
            GroupType.PRESET_FRIENDS -> !username.endsWith("@chatroom") && !isOfficialConversation(username)
            GroupType.MANUAL, GroupType.SQL -> filter.members.contains(username)
            GroupType.PRESET_OFFICIALS -> isOfficialConversation(username)
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
        if (!JsonDataMigration.isCompleted("chat", "groups")) {
            showToast(context, context.localizedChatString(R.string.structured_storage_unavailable))
            return
        }
        showComposeDialog(context) {
            var pinTabsEnabled by remember { mutableStateOf(pinTabs) }
            var takeOverScroll by remember { mutableStateOf(takeOverHorizontalScroll) }
            var rememberScrollStateEnabled by remember { mutableStateOf(rememberScrollState) }
            var countOfficialUnread by remember { mutableStateOf(includeOfficialUnread) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_conversation_grouping_name)) },
                textTopSpacing = 0.dp,
                text = {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.conversation_grouping_tab_layout_title),
                                contentPadding = PaddingValues(0.dp),
                                titlePadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                item {
                                    RadioButtonWidget(
                                        title = stringResource(R.string.conversation_grouping_tab_style_full_width),
                                        selected = tabStyleState.value == TAB_STYLE_FULL_WIDTH,
                                        onSelect = { updateTabStyle(TAB_STYLE_FULL_WIDTH) },
                                    )
                                }
                                item {
                                    RadioButtonWidget(
                                        title = stringResource(R.string.conversation_grouping_tab_style_floating),
                                        selected = tabStyleState.value == TAB_STYLE_FLOATING,
                                        onSelect = { updateTabStyle(TAB_STYLE_FLOATING) },
                                    )
                                }
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_pin_tabs),
                                        description = stringResource(R.string.conversation_grouping_pin_tabs_description),
                                        checked = pinTabsEnabled,
                                        onCheckedChange = { checked ->
                                            pinTabs = checked
                                            pinTabsEnabled = checked
                                            tabHosts.forEach { it.setPinned(checked) }
                                        },
                                    )
                                }
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_take_over_horizontal_scroll),
                                        description = stringResource(R.string.conversation_grouping_take_over_horizontal_scroll_description),
                                        checked = takeOverScroll,
                                        onCheckedChange = { checked ->
                                            takeOverScroll = checked
                                            takeOverHorizontalScroll = checked
                                            if (checked) ensureSwipeHooks()
                                            else swipeSessions.values.mapNotNull { it.get() }.forEach { it.cancelImmediately() }
                                        },
                                    )
                                }
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_remember_scroll_state),
                                        description = stringResource(R.string.conversation_grouping_remember_scroll_state_description),
                                        checked = rememberScrollStateEnabled,
                                        onCheckedChange = { checked ->
                                            rememberScrollState = checked
                                            rememberScrollStateEnabled = checked
                                            if (checked) ensureSwipeHooks()
                                            else swipeSessions.values.mapNotNull { it.get() }
                                                .forEach { it.forgetScrollPositions() }
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.conversation_grouping_unread_title),
                                contentPadding = PaddingValues(0.dp),
                                titlePadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_show_unread),
                                        description = stringResource(R.string.conversation_grouping_show_unread_description),
                                        checked = showUnreadState.value,
                                        onCheckedChange = { checked ->
                                            showUnread = checked
                                            showUnreadState.value = checked
                                        },
                                    )
                                }
                                item(animatedVisibility = showUnreadState.value) {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_include_official_unread),
                                        description = stringResource(R.string.conversation_grouping_include_official_unread_description),
                                        enabled = showUnreadState.value,
                                        checked = countOfficialUnread,
                                        onCheckedChange = { checked ->
                                            includeOfficialUnread = checked
                                            countOfficialUnread = checked
                                            unreadRefreshVersion.update { it + 1 }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun groupFilter(groupId: String?): GroupFilter {
        val group = if (groupId == null || isAllTab(groupId)) allTab()
            else groupById(groupId) ?: allTab()
        val members = when (group.type) {
            GroupType.MANUAL, GroupType.SQL -> getGroupMembers(group).toSet()
            else -> emptySet()
        }
        return GroupFilter(group, members)
    }

    private fun selectTab(groupId: String?) {
        activeFilter = groupFilter(groupId)
        clearAdapterCaches()
        refreshConversations()
    }

    private fun preparePagingGroup(source: View, groupId: String, completed: (Boolean) -> Unit) {
        check(pagingRequest == null) { "conversation group preparations must be serialized" }
        if (!isActive) {
            completed(true)
            return
        }
        // Cancelling restores the exact committed filter, including manual/SQL membership.
        // A preview never replaces the global selection seen by a newly created MainUI.
        val filter = activeFilter.takeIf { it.group.id == groupId } ?: groupFilter(groupId)
        if (filter.group.id != groupId) {
            completed(false)
            return
        }
        val adapter = if (source is ListView) {
            val installed = source.adapter
            (installed as? HeaderViewListAdapter)?.wrappedAdapter ?: installed
        } else {
            source.reflekt().firstMethod {
                name = "getAdapter"
                parameters()
                superclass()
            }.invoke()
        }
        if (adapter == null) {
            completed(false)
            return
        }
        val list = if (source is ListView) null else fieldRecyclerMvvmList.field.get(adapter)!!
        val dataSource = list?.reflekt()?.fields {
            type { !it.isPrimitive }
        }?.firstNotNullOfOrNull { field ->
            field.get()?.takeIf { classRecyclerDataSource.clazz.isInstance(it) }
        }
        if (list != null) checkNotNull(dataSource) { "conversation paging data source is absent" }
        val counter = list?.reflekt()?.firstField { type = AtomicInteger::class }?.get() as AtomicInteger?
        val request = PagingRequest(source, adapter, filter, list, dataSource, counter, completed)
        val retry = object : Runnable {
            override fun run() {
                if (pagingRequest !== request) return
                try {
                    val now = SystemClock.uptimeMillis()
                    if (now >= request.deadline || !isActive) {
                        WeLogger.w(TAG, "conversation group page preparation timed out or stopped: $groupId")
                        finishPagingRequest(request, false)
                        return
                    }
                    if (request.list != null && !request.applied) {
                        val loading = fieldRecyclerLoading.field.getBoolean(request.list)
                        val counterChanged = request.issued && request.loadCounter!!.get() != request.loadId
                        val completedWithoutApply = request.source.windowVisibility == View.VISIBLE &&
                            request.queryCompletedAt > 0L && now - request.queryCompletedAt > 500L
                        val startWasIgnored = request.issued && request.expectedNames == null && now - request.lastIssuedAt > 250L
                        if (!loading && (!request.issued || counterChanged || completedWithoutApply || startWasIgnored)) {
                            // The native actor ignores StartLoad while loading. Wait for idle before
                            // issuing/retrying; never increment its load ID on every gesture MOVE.
                            installPagingFilter(request)
                            request.expectedNames = null
                            request.queryCompletedAt = 0L
                            request.lastIssuedAt = now
                            request.loadId = request.loadCounter!!.get() + 1
                            request.issued = true
                            methodRecyclerRefreshAll.method.invoke(null, request.list, null, 1, null)
                            request.loadId = request.loadCounter.get()
                        }
                    }
                    pagingHandler.postDelayed(this, 32L)
                } catch (error: Exception) {
                    WeLogger.e(TAG, "conversation page preparation failed: $groupId", error)
                    finishPagingRequest(request, false)
                }
            }
        }
        request.retry = retry
        pagingRequest = request
        try {
            if (list == null) {
                installPagingFilter(request)
                request.issued = true
                WeConversationListViewApi.refreshContainer(source, resetListViewPosition = true)
                awaitPagingLayout(request)
            }
            if (pagingRequest === request) pagingHandler.post(retry)
        } catch (error: Exception) {
            WeLogger.e(TAG, "conversation page initialization failed: $groupId", error)
            finishPagingRequest(request, false)
        }
    }

    private fun awaitPagingLayout(request: PagingRequest) {
        if (pagingRequest !== request || request.layoutListener != null) return
        if (!request.source.isAttachedToWindow || request.source.windowVisibility != View.VISIBLE) {
            finishPagingRequest(request, true)
            return
        }
        request.layoutListener = OneShotPreDrawListener.add(request.source) {
            request.layoutListener = null
            if (pagingRequest === request) finishPagingRequest(request, true)
        }
        request.source.requestLayout()
        request.source.invalidate()
    }

    private fun finishPagingRequest(request: PagingRequest, success: Boolean) {
        if (pagingRequest !== request) return
        pagingRequest = null
        request.retry?.let(pagingHandler::removeCallbacks)
        request.layoutListener?.removeListener()
        request.layoutListener = null
        request.completed(success)
    }

    private fun refreshConversations() {
        // The paged Recycler adapter must rebuild through its own data source so count, item,
        // bind, click and incremental-update positions stay on the same real list.
        // A new ListView group starts with the recent mini-program header fully collapsed.
        if (!refreshRecyclerData()) WeConversationListViewApi.refresh(resetListViewPosition = true)
    }

    private const val TAG = "ConversationGrouping"

    private val classRecyclerDataSource by dexClass()

    private val methodRecyclerQueryPage by dexMethod()

    private val fieldRecyclerPageItems by dexField()

    private val classRecyclerRow by dexClass()

    private val fieldRecyclerRowConversation by dexField()

    private val methodRecyclerSubmitUiChange by dexMethod()

    private val fieldRecyclerPendingItems by dexField()

    private val fieldRecyclerMvvmList by dexField()

    private val methodRecyclerRefreshAll by dexMethod()

    private val fieldRecyclerLoading by dexField()

    private val methodOnTabCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.conversation.MainUI"
            usingEqStrings("MicroMsg.MainUI", "onTabCreate, %d")
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        DexResolutionContext.ensureResolved(WeConversationListViewApi.classConversationRecyclerAdapter)
        if (WeConversationListViewApi.classConversationRecyclerAdapter.isPlaceholder) {
            val reason = "conversation RecyclerView architecture is absent"
            classRecyclerDataSource.setPlaceholderDescriptor(true, reason)
            methodRecyclerQueryPage.setPlaceholderDescriptor(true, reason)
            fieldRecyclerPageItems.setPlaceholderDescriptor(true, reason)
            classRecyclerRow.setPlaceholderDescriptor(true, reason)
            fieldRecyclerRowConversation.setPlaceholderDescriptor(true, reason)
            methodRecyclerSubmitUiChange.setPlaceholderDescriptor(true, reason)
            fieldRecyclerPendingItems.setPlaceholderDescriptor(true, reason)
            fieldRecyclerMvvmList.setPlaceholderDescriptor(true, reason)
            methodRecyclerRefreshAll.setPlaceholderDescriptor(true, reason)
            fieldRecyclerLoading.setPlaceholderDescriptor(true, reason)
            return
        }

        classRecyclerDataSource.find(dexKit) {
            matcher {
                usingEqStrings(
                    "MicroMsg.ConversationAdapter.ConvRecyclerDataSource",
                    "syncFoldExpandStatus: isShowPlaceTop=",
                )
            }
        }
        methodRecyclerQueryPage.find(dexKit) {
            matcher {
                paramCount = 1
                usingEqStrings(
                    "getConvList: may getContact error, size mismatch",
                    "getConvList ",
                )
            }
        }
        fieldRecyclerPageItems.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerQueryPage.data.returnTypeName)
                type = "java.util.ArrayList"
            }
        }

        val conversationClassName = WeConversationListViewApi.methodAdapterGetItem.data.returnTypeName
        val rowBuilders = methodRecyclerQueryPage.data.invokes.distinctBy { it.descriptor }
            .filter { candidate ->
                candidate.paramTypeNames.firstOrNull() == conversationClassName &&
                    dexKit.getClassData(candidate.returnTypeName)?.fields?.any {
                        it.typeName == conversationClassName
                    } == true
            }
        require(rowBuilders.size == 1) {
            "expected one conversation RecyclerView row builder, found: " +
                rowBuilders.joinToString { it.descriptor }
        }
        val recyclerRow = dexKit.getClassData(rowBuilders.single().returnTypeName)!!
        classRecyclerRow.setDescriptor(recyclerRow)
        fieldRecyclerRowConversation.setDescriptor(recyclerRow.fields.single {
            it.typeName == conversationClassName
        })

        val recyclerAdapterBase =
            WeConversationListViewApi.classConversationRecyclerAdapter.data.superClass!!
        require(recyclerAdapterBase.fields.size == 1) {
            "expected one Recycler adapter base field, found: " +
                recyclerAdapterBase.fields.joinToString { it.descriptor }
        }
        val recyclerMvvmListField = recyclerAdapterBase.fields.single()
        fieldRecyclerMvvmList.setDescriptor(recyclerMvvmListField)

        methodRecyclerSubmitUiChange.find(dexKit) {
            matcher {
                declaredClass(recyclerMvvmListField.typeName)
                paramCount = 1
                returnType = "void"
                usingEqStrings(
                    "submitUIChange callback:",
                    " currentDataListVersion:",
                )
            }
        }
        fieldRecyclerPendingItems.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerSubmitUiChange.data.paramTypeNames.single())
                type = "java.util.List"
                addReadMethod {
                    declaredClass(methodRecyclerSubmitUiChange.data.declaredClassName)
                    paramTypes(methodRecyclerSubmitUiChange.data.paramTypeNames.single())
                    usingEqStrings("submitUIChange callback:", " currentDataListVersion:")
                }
            }
        }
        methodRecyclerRefreshAll.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerSubmitUiChange.data.declaredClassName)
                modifiers(ReflectModifier.STATIC)
                paramTypes(
                    methodRecyclerSubmitUiChange.data.declaredClassName,
                    null,
                    "int",
                    "java.lang.Object",
                )
                returnType = "void"
                usingEqStrings("submitRefreshAll")
            }
        }
        fieldRecyclerLoading.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerSubmitUiChange.data.declaredClassName)
                type = "boolean"
                addReadMethod { usingStrings("already loading, ignore StartLoad type=") }
                addWriteMethod {
                    name = "onStateChanged"
                    paramCount = 2
                    returnType = "void"
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Tab bar UI
    // ----------------------------------------------------------------------------------------------

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ConversationTabs(
        sourceView: View,
        lifecycleOwner: LifecycleOwner,
        hasContentBehind: Boolean,
        pagingProgress: ConversationGroupPagingProgress?,
        onSortModeChanged: (Boolean) -> Unit,
        groups: List<ChatGroup>,
        selectedGroupId: String,
        onTabSelected: (String) -> Unit,
        onCreateGroup: () -> Unit,
        onEditGroup: (ChatGroup) -> Unit,
        onDeleteGroup: (ChatGroup) -> Unit,
        onReorder: suspend (List<String>) -> Unit,
        modifier: Modifier = Modifier,
        containerColor: Color = if (isSystemInDarkTheme()) Color(0xFF111111) else Color(0xFFEDEDED),
    ) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        val commitScope = rememberCoroutineScope()
        var savingOrder by remember { mutableStateOf(false) }
        val capsuleStyle = usesFloatingTabs
        val darkTheme = isSystemInDarkTheme()
        val shadowProgress by animateFloatAsState(
            targetValue = if (capsuleStyle && hasContentBehind) 1f else 0f,
            animationSpec = tween(durationMillis = 180),
            label = "groupIslandShadow",
        )
        val tabContainerColor = if (capsuleStyle) Color.Transparent else containerColor
        // The native list is a sibling of this overlay, so its capture contains the conversations
        // behind the bar without sampling the bar itself. Full-width tabs need no backdrop capture.
        val backdrop = if (capsuleStyle && isRuntimeShaderSupported()) {
            rememberViewBackdrop(sourceView, lifecycleOwner)
        } else null
        val glassTint = if (darkTheme) Color(0xFF1C1C1E).copy(alpha = 0.55f)
            else Color.White.copy(alpha = 0.58f)
        val glassSurface = if (backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = { blur(18.dp.toPx(), 18.dp.toPx()) },
                onDrawSurface = { drawRect(glassTint) },
            )
        } else {
            // Older Android releases retain translucency without requiring RuntimeShader.
            Modifier.background(glassTint, CircleShape)
        }
        var unreadCounts by remember { mutableStateOf<Map<String, ConversationUnreadState>>(emptyMap()) }
        val showUnreadEnabled = showUnreadState.value
        LaunchedEffect(groups, showUnreadEnabled) {
            if (!showUnreadEnabled) {
                unreadCounts = emptyMap()
                return@LaunchedEffect
            }
            unreadRefreshVersion.collect {
                // Coalesce bursts without postponing updates indefinitely during message sync.
                delay(150.milliseconds)
                if (WeDatabaseApi.isReady) {
                    val counts = withContext(Dispatchers.IO) {
                        runCatching { queryGroupUnreadCounts(groups) }
                            .onFailure { WeLogger.e(TAG, "failed to refresh group unread counts", it) }
                            .getOrNull()
                    }
                    if (counts != null) unreadCounts = counts
                }
            }
        }
        var menuForGroupId by remember { mutableStateOf<String?>(null) }
        // Sort (edit) mode: long-press a tab to drag-reorder.
        var sortMode by remember { mutableStateOf(false) }
        LaunchedEffect(sortMode) { onSortModeChanged(sortMode) }
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
                .then(
                    if (capsuleStyle) {
                        Modifier
                            .padding(
                                horizontal = CAPSULE_HORIZONTAL_INSET_DP.dp,
                                vertical = CAPSULE_VERTICAL_INSET_DP.dp,
                            )
                            .then(
                                if (backdrop != null) {
                                    // Fit the soft and contact shadows inside the transparent margins.
                                    Modifier
                                        .dropShadow(
                                            CircleShape,
                                            Shadow(
                                                radius = 4.dp,
                                                offset = DpOffset(0.dp, 1.dp),
                                                color = Color.Black,
                                                alpha = (if (darkTheme) 0.32f else 0.16f) * shadowProgress,
                                            ),
                                        )
                                        .dropShadow(
                                            CircleShape,
                                            Shadow(
                                                radius = 1.dp,
                                                offset = DpOffset(0.dp, 1.dp),
                                                color = Color.Black,
                                                alpha = (if (darkTheme) 0.16f else 0.08f) * shadowProgress,
                                            ),
                                        )
                                } else {
                                    // Platform elevation leaves the translucent interior clear when
                                    // there is no captured backdrop to cover a filled shadow mask.
                                    Modifier.shadow(4.dp * shadowProgress, CircleShape, clip = false)
                                },
                            )
                            .clip(CircleShape)
                            .then(glassSurface)
                            .border(
                                0.5.dp,
                                Color.White.copy(alpha = if (darkTheme) 0.10f else 0.35f),
                                CircleShape,
                            )
                            .padding(3.dp)
                    } else {
                        Modifier.background(containerColor)
                    }
                )
        ) {
            if (sortMode) {
                SortableTabsRow(
                    enabled = !savingOrder,
                    groups = orderedGroups,
                    unreadCounts = unreadCounts,
                    selectedGroupId = selectedGroupId,
                    capsuleStyle = capsuleStyle,
                    onMove = { from, to ->
                        if (!savingOrder) order = order.toMutableList().apply { add(to, removeAt(from)) }
                    }
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val tabWidths = groupTabWidths(orderedGroups, maxWidth, capsuleStyle)
                    val tabs: @Composable () -> Unit = {
                        orderedGroups.forEachIndexed { index, group ->
                            key(group.id) {
                                val allTab = isAllTab(group.id)
                                val label = groupDisplayName(group)
                                Box(Modifier.width(tabWidths[index])) {
                                    GroupTab(
                                        label = label,
                                        unread = unreadCounts[group.id] ?: noUnread,
                                        selected = selectedGroupId == group.id,
                                        capsuleStyle = capsuleStyle,
                                        enabled = pagingProgress == null,
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
                    val selectedTabIndex = orderedGroups.indexOfFirst { it.id == selectedGroupId }
                        .coerceAtLeast(0)
                    val indicator: @Composable TabIndicatorScope.() -> Unit = {
                        if (capsuleStyle) {
                            AnimatedGroupSelectionPill(tabWidths, selectedTabIndex, orderedGroups.map { it.id }, pagingProgress)
                        } else {
                            TabRowDefaults.PrimaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
                                width = Dp.Unspecified,
                            )
                        }
                    }
                    // Plain tabs keep their natural width. Capsules use the assigned proportional
                    // widths when they fit, and retain natural widths with scrolling when they overflow.
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = tabContainerColor,
                        edgePadding = if (capsuleStyle) 0.dp else {
                            ((maxWidth - tabWidths.fold(0.dp) { total, width -> total + width }) / 2)
                                .coerceAtLeast(12.dp)
                        },
                        minTabWidth = 48.dp,
                        indicator = indicator,
                        divider = {},
                        tabs = tabs,
                    )
                }
            }

            // In sort mode the trailing "+" turns into a "✓" that commits the new order. Overlaid on
            // the right so it stays put regardless of how far the row scrolls.
            if (sortMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = if (capsuleStyle) 0.dp else 12.dp)
                        .background(if (capsuleStyle) glassTint else tabContainerColor, CircleShape)
                ) {
                    IconButton(
                        modifier = Modifier.size(if (capsuleStyle) 36.dp else 48.dp),
                        enabled = !savingOrder,
                        onClick = {
                            savingOrder = true
                            commitScope.launch {
                                try {
                                    onReorder(order)
                                    sortMode = false
                                    showToast(localizedContext.getString(R.string.conversation_group_order_saved))
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    WeLogger.e(TAG, "Failed to reorder groups", error)
                                    showToast(localizedContext.getString(R.string.logs_save_failed))
                                } finally { savingOrder = false }
                            }
                        },
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors()
                    ) {
                        if (savingOrder) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp))
                        else Icon(
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
        if (group.name.isNotBlank()) return group.name
        if (isAllTab(group.id)) return context.getString(R.string.conversation_group_all)
        // Keep older nameless manual/SQL groups visible until the user supplies a name on edit.
        val label = builtInLabelFor(group.type) ?: group.builtInLabel
        return label?.let { context.getString(it.nameRes) }.orEmpty()
    }

    private fun builtInLabelFor(type: GroupType): BuiltInGroupLabel? = when (type) {
        GroupType.PRESET_UNREAD -> BuiltInGroupLabel.UNREAD
        GroupType.PRESET_GROUPS -> BuiltInGroupLabel.GROUPS
        GroupType.PRESET_FRIENDS -> BuiltInGroupLabel.FRIENDS
        GroupType.PRESET_OFFICIALS -> BuiltInGroupLabel.OFFICIALS
        GroupType.MANUAL, GroupType.SQL -> null
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

    /** Widths include the label and its existing side padding, which also houses the badge. */
    @Composable
    private fun groupTabWidths(
        groups: List<ChatGroup>,
        availableWidth: Dp,
        capsuleStyle: Boolean,
    ): List<Dp> {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val textStyle = MaterialTheme.typography.titleSmall
        val naturalWidths = groups.map { group ->
            val textWidth = textMeasurer.measure(
                text = groupDisplayName(group),
                style = textStyle,
                maxLines = 1,
                softWrap = false,
            ).size.width
            with(density) {
                (textWidth + groupTabHorizontalPadding.roundToPx() * 2)
                    .coerceAtLeast(48.dp.roundToPx())
            }
        }
        val totalWidth = naturalWidths.sum()
        val availablePixels = with(density) { availableWidth.roundToPx() }
        val expand = capsuleStyle && totalWidth in 1 until availablePixels
        var contentEnd = 0L
        var previousEnd = 0
        return naturalWidths.map { width ->
            // Cumulative pixel boundaries preserve the proportions and fill the row exactly,
            // without rounding each tab into an extra sliver of horizontal scroll.
            contentEnd += width
            val end = if (expand) (contentEnd * availablePixels / totalWidth).toInt()
                else contentEnd.toInt()
            val assignedWidth = end - previousEnd
            previousEnd = end
            with(density) { assignedWidth.toDp() }
        }
    }

    /**
     * Long-press a tab to drag it into a new position. The working order is persisted only when
     * the check button is tapped.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SortableTabsRow(
        enabled: Boolean,
        groups: List<ChatGroup>,
        unreadCounts: Map<String, ConversationUnreadState>,
        selectedGroupId: String,
        capsuleStyle: Boolean,
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

        // Reserve space for the save button outside the scrolling and drag-hit-test area.
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(end = if (capsuleStyle) 44.dp else 56.dp),
        ) {
            val tabWidths = groupTabWidths(groups, maxWidth, capsuleStyle)
            LazyRow(
                state = listState,
                // Keep normal horizontal scrolling while nothing is picked up, so an overflowing tab
                // row can be swiped left/right. Once a tab is picked up the drag consumes the gesture,
                // and the auto-scroll below handles scrolling near the edges.
                userScrollEnabled = enabled && draggingIndex == -1,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
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
                contentPadding = PaddingValues(
                    horizontal = if (capsuleStyle) 0.dp else 12.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(groups.size, key = { groups[it].id }) { index ->
                    val group = groups[index]
                    val dragging = index == draggingIndex
                    val settling = index == settleIndex

                    val scale by animateFloatAsState(
                        targetValue = if (dragging || settling) 1.1f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "dragScale",
                    )
                    Box(
                        modifier = Modifier
                            .width(tabWidths[index])
                            .zIndex(if (dragging || settling) 1f else 0f)
                            .graphicsLayer {
                                translationX = when {
                                    dragging -> offsetForIndex(index)
                                    settling -> settleAnim.value
                                    else -> 0f
                                }
                                scaleX = scale
                                scaleY = scale
                            }
                            .then(if (dragging || settling) Modifier else Modifier.animateItem()),
                    ) {
                        // Sorting keeps the same active group. Its sole pill shares the keyed
                        // item's drag, placement and settle transforms so they cannot drift apart.
                        if (capsuleStyle && selectedGroupId == group.id) {
                            GroupSelectionPill(Modifier.matchParentSize())
                        }
                        GroupTabContent(
                            label = groupDisplayName(group),
                            unread = unreadCounts[group.id] ?: noUnread,
                            selected = selectedGroupId == group.id,
                            capsuleStyle = capsuleStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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

    @Composable
    private fun GroupSelectionPill(modifier: Modifier) {
        Box(modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape))
    }

    @Composable
    private fun TabIndicatorScope.AnimatedGroupSelectionPill(
        widths: List<Dp>,
        selectedIndex: Int,
        groupIds: List<String>,
        pagingProgress: ConversationGroupPagingProgress?,
    ) {
        if (pagingProgress != null) {
            val from = groupIds.indexOf(pagingProgress.fromGroupId)
            val to = groupIds.indexOf(pagingProgress.toGroupId)
            if (from >= 0 && to >= 0) {
                val fromLeft = widths.take(from).fold(0.dp) { left, width -> left + width }
                val toLeft = widths.take(to).fold(0.dp) { left, width -> left + width }
                val left = fromLeft + (toLeft - fromLeft) * pagingProgress.progress
                val width = widths[from] + (widths[to] - widths[from]) * pagingProgress.progress
                PositionedGroupSelectionPill(selectedIndex, { left }, { width })
                return
            }
        }
        val targetLeft = widths.take(selectedIndex).fold(0.dp) { left, width -> left + width }
        val left = animateDpAsState(
            targetValue = targetLeft,
            animationSpec = tween(durationMillis = 320, easing = selectionPillEasing),
            label = "groupSelectionLeft",
        )
        val width = animateDpAsState(
            targetValue = widths[selectedIndex],
            animationSpec = tween(durationMillis = 320, easing = selectionPillEasing),
            label = "groupSelectionWidth",
        )
        PositionedGroupSelectionPill(selectedIndex, { left.value }, { width.value })
    }

    @Composable
    private fun TabIndicatorScope.PositionedGroupSelectionPill(
        selectedIndex: Int,
        left: () -> Dp,
        width: () -> Dp,
    ) {
        GroupSelectionPill(
            Modifier
                .zIndex(-1f)
                .tabIndicatorLayout { measurable, constraints, positions ->
                    val animatedWidth = width().roundToPx()
                    val pill = measurable.measure(
                        constraints.copy(minWidth = animatedWidth, maxWidth = animatedWidth),
                    )
                    // Report the target tab width, not the animated width: ScrollableTabRow's
                    // centering compensation must not shift the pill while its width animates.
                    layout(positions[selectedIndex].width.roundToPx(), pill.height) {
                        pill.placeRelative(left().roundToPx(), 0)
                    }
                }
                .fillMaxHeight(),
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GroupTab(
        label: String,
        unread: ConversationUnreadState,
        selected: Boolean,
        capsuleStyle: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) {
        GroupTabContent(
            label = label,
            unread = unread,
            selected = selected,
            capsuleStyle = capsuleStyle,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (capsuleStyle) Modifier.clip(CircleShape) else Modifier)
                .semantics { this.selected = selected }
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Tab,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        )
    }

    @Composable
    private fun GroupTabContent(
        label: String,
        unread: ConversationUnreadState,
        selected: Boolean,
        capsuleStyle: Boolean,
        modifier: Modifier = Modifier,
    ) {
        Box(
            modifier = modifier
                .heightIn(min = if (capsuleStyle) 36.dp else 48.dp)
                .padding(
                    horizontal = groupTabHorizontalPadding,
                    vertical = if (capsuleStyle) 8.dp else 12.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Layout(
                content = {
                    Text(
                        text = label,
                        color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showUnreadState.value) {
                        if (unread.normalCount > 0) {
                            Badge(containerColor = Color(0xFFFF3B30)) {
                                Text(
                                    text = if (unread.normalCount <= 99) unread.normalCount.toString()
                                        else stringResource(R.string.badge_count_overflow),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                )
                            }
                        } else if (unread.hasMutedUnread) {
                            Badge(containerColor = Color(0xFFFF3B30))
                        }
                    }
                },
            ) { measurables, constraints ->
                val text = measurables[0].measure(constraints)
                val badge = measurables.getOrNull(1)?.measure(
                    constraints.copy(minWidth = 0, minHeight = 0),
                )
                // Only the label determines content size. Center the badge on its top-right corner.
                layout(text.width, text.height) {
                    text.place(0, 0)
                    badge?.place(text.width - badge.width / 2, -badge.height / 2)
                }
            }
        }
    }

    private fun isOfficialConversation(username: String): Boolean =
        username.startsWith("gh_") || username == "officialaccounts" || username == "service_officialaccounts"

    private fun queryGroupUnreadCounts(groups: List<ChatGroup>): Map<String, ConversationUnreadState> {
        val countOfficialUnread = includeOfficialUnread
        // Public-account feed entries own their read/consumed state. Their children may retain
        // unread counters after the feed dot disappears, so count the entry instead of its children.
        // Other containers retain the existing member-based totals, including folded chats.
        val usernames = WeDatabaseApi.rawQuery(
            "SELECT c.username FROM rconversation c " +
                "WHERE (c.unReadCount > 0 OR c.unReadMuteCount > 0) " +
                "AND (c.parentRef IS NULL OR c.parentRef NOT IN ('officialaccounts', 'service_officialaccounts')) " +
                "AND (c.username IN ('officialaccounts', 'service_officialaccounts') " +
                "OR NOT EXISTS (SELECT 1 FROM rconversation child WHERE child.parentRef = c.username))"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val hidden = if (HideContacts.isEnabled) HideContacts.hiddenContacts else emptySet()
        val unreadByUsername = WeMessageApi.getConversationUnreadStates(usernames)
            .filterKeys { it !in hidden && (countOfficialUnread || !isOfficialConversation(it)) }
        return groups.associate { group ->
            val members = when {
                isAllTab(group.id) -> unreadByUsername.keys
                group.type == GroupType.MANUAL -> group.members.toSet()
                group.type == GroupType.SQL -> resolveGroupMembers(group).toSet()
                else -> unreadByUsername.keys.filter { username ->
                    when (group.type) {
                        GroupType.PRESET_UNREAD -> true
                        GroupType.PRESET_GROUPS -> username.endsWith("@chatroom")
                        GroupType.PRESET_FRIENDS -> !username.endsWith("@chatroom") && !isOfficialConversation(username)
                        GroupType.PRESET_OFFICIALS -> isOfficialConversation(username)
                    }
                }
            }
            var normalCount = 0L
            var hasMutedUnread = false
            for (username in members) {
                val unread = unreadByUsername[username] ?: continue
                normalCount += unread.normalCount
                hasMutedUnread = hasMutedUnread || unread.hasMutedUnread
            }
            group.id to ConversationUnreadState(normalCount, hasMutedUnread)
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
                onSavingChanged = { dialog.setCancelable(!it) },
                onSave = { group ->
                    upsertGroup(group)
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
                        deleteGroup(group.id)
                        onGroupDeleted()
                        onDismiss()
                    }
                },
                onSavingChanged = { dialog.setCancelable(!it) },
                onSave = { updated ->
                    upsertGroup(updated)
                    onGroupUpdated()
                    onDismiss()
                }
            )
        }
    }

    private fun showConfirmDeleteGroupDialog(
        context: Context,
        group: ChatGroup,
        onConfirm: suspend () -> Unit,
    ) {
        showComposeDialog(context) {
            val scope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            androidx.compose.runtime.SideEffect { dialog.setCancelable(!saving) }
            val groupName = groupDisplayName(group)
            AlertDialogContent(
                title = { Text(stringResource(R.string.conversation_group_delete_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.conversation_group_delete_message, groupName))
                        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (failed) Text(stringResource(R.string.logs_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(enabled = !saving, onClick = {
                        saving = true
                        scope.launch {
                            try {
                                onConfirm()
                                onDismiss()
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                WeLogger.e(TAG, "Failed to delete group", error)
                                failed = true
                            } finally { saving = false }
                        }
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
        onSavingChanged: (Boolean) -> Unit,
        onSave: suspend (ChatGroup) -> Unit
    ) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        fun submit(value: ChatGroup) {
            saving = true
            onSavingChanged(true)
            failed = false
            scope.launch {
                try {
                    onSave(value)
                    showToast(localizedContext.getString(R.string.conversation_group_saved))
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    WeLogger.e(TAG, "Failed to save group", error)
                    failed = true
                } finally {
                    saving = false
                    onSavingChanged(false)
                }
            }
        }
        val groupId = remember(group) { group?.id ?: newGroupId() }
        var name by remember(group) { mutableStateOf(group?.name ?: "") }

        if (group != null && isAllTab(group.id)) {
            AlertDialogContent(
                title = {
                    Column {
                        Text(stringResource(titleRes))
                        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (failed) Text(stringResource(R.string.logs_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                },
                text = {
                    OutlinedTextField(
                        enabled = !saving,
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.conversation_group_name)) },
                        placeholder = { Text(stringResource(R.string.conversation_group_all)) },
                        singleLine = true
                    )
                },
                dismissButton = {
                    TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(enabled = !saving, onClick = {
                        submit(group.copy(name = name.trim()))
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                }
            )
            return
        }

        var members by remember(group) { mutableStateOf(group?.members?.toSet().orEmpty()) }

        var type by remember(group) { mutableStateOf(group?.type ?: GroupType.MANUAL) }
        val builtInLabel = builtInLabelFor(type)
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
            title = {
                Column {
                    Text(stringResource(titleRes))
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (failed) Text(stringResource(R.string.logs_save_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        enabled = !saving,
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.conversation_group_name)) },
                        placeholder = builtInLabel?.let { label ->
                            { Text(stringResource(label.nameRes)) }
                        },
                        singleLine = true
                    )

                    var typeExpanded by remember { mutableStateOf(false) }
                    Column {
                        Text(stringResource(R.string.conversation_group_mode), style = MaterialTheme.typography.labelSmall)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !saving) { typeExpanded = true }
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
                                enabled = !saving,
                                text = { Text(stringResource(R.string.conversation_group_mode_manual)) },
                                onClick = {
                                    type = GroupType.MANUAL
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                enabled = !saving,
                                text = { Text(stringResource(R.string.conversation_group_mode_unread)) },
                                onClick = {
                                    type = GroupType.PRESET_UNREAD
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                enabled = !saving,
                                text = { Text(stringResource(R.string.conversation_group_mode_groups)) },
                                onClick = {
                                    type = GroupType.PRESET_GROUPS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                enabled = !saving,
                                text = { Text(stringResource(R.string.conversation_group_mode_friends)) },
                                onClick = {
                                    type = GroupType.PRESET_FRIENDS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                enabled = !saving,
                                text = { Text(stringResource(R.string.conversation_group_mode_officials)) },
                                onClick = {
                                    type = GroupType.PRESET_OFFICIALS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                enabled = !saving,
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
                                enabled = !saving,
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
                                enabled = !saving,
                                value = selectFields,
                                onValueChange = { selectFields = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.conversation_group_select_fields)) },
                                singleLine = true
                            )
                            OutlinedTextField(
                                enabled = !saving,
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
                    TextButton(onDelete, enabled = !saving) { Text(stringResource(R.string.conversation_group_action_delete)) }
                }
                TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.dialog_cancel)) }
            },
            confirmButton = {
                Button(
                    enabled = !saving && (name.isNotBlank() || builtInLabel != null),
                    onClick = {
                        val next = ChatGroup(
                            id = groupId,
                            name = name.trim(),
                            members = members.toList().sorted(),
                            type = type,
                            selectFields = selectFields.trim(),
                            whereClause = whereClause.trim(),
                            builtInLabel = builtInLabel,
                        )
                        submit(next)
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
                        "SELECT r.username FROM rcontact r WHERE r.username NOT LIKE '%@chatroom' " +
                            "AND r.username NOT LIKE 'gh_%' AND r.username NOT IN ('officialaccounts', 'service_officialaccounts')"
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
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE 'gh_%' " +
                            "OR r.username IN ('officialaccounts', 'service_officialaccounts')"
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

    @Volatile
    private var groupsCache: List<ChatGroup>? = null

    private fun loadGroups(): List<ChatGroup> {
        groupsCache?.let { return it }
        return runBlocking(Dispatchers.IO) {
            JsonDataMigration.requireCompleted("chat", "groups")
            WeKitDatabase.instance.conversationCollectionDao().getGroups()
        }.also { groupsCache = it }
    }

    private fun invalidateGroups() {
        groupsCache = null
        groupMembersCache.clear()
    }

    private suspend fun upsertGroup(group: ChatGroup) {
        try {
            WeKitDatabase.instance.conversationCollectionDao().putGroup(group)
        } finally {
            invalidateGroups()
        }
    }

    private suspend fun deleteGroup(id: String) {
        try {
            WeKitDatabase.instance.conversationCollectionDao().removeGroup(id)
        } finally {
            invalidateGroups()
        }
    }

    private fun groupById(groupId: String): ChatGroup? {
        return loadGroups().firstOrNull { it.id == groupId }
    }

    private fun newGroupId(): String = "$GROUP_PREFIX${System.currentTimeMillis()}"

    private fun isGroupId(value: String): Boolean = value.startsWith(GROUP_PREFIX)

    private enum class AdapterStorage {
        LEGACY_CURSOR,
        MVVM_LIST,
    }

    private val BuiltInGroupLabel.nameRes: Int
        @StringRes get() = when (this) {
            BuiltInGroupLabel.UNREAD -> R.string.conversation_group_default_unread
            BuiltInGroupLabel.GROUPS -> R.string.conversation_group_default_groups
            BuiltInGroupLabel.FRIENDS -> R.string.conversation_group_default_friends
            BuiltInGroupLabel.OFFICIALS -> R.string.conversation_group_default_officials
        }
}
