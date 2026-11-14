package dev.ujhhgtg.wekit.features.api.ui

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isGone
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexClassFrom
import dev.ujhhgtg.wekit.dex.dsl.dexField
import dev.ujhhgtg.wekit.dex.dsl.dexFieldFrom
import dev.ujhhgtg.wekit.dex.dsl.dexGroup
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.postOnUiThread
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

object WeConversationListViewApi : ApiFeature() {

    override val technicalId = "会话列表 View 绑定监听服务"
    override val nameRes = R.string.feature_we_conversation_list_view_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_conversation_list_view_api_description

    enum class Backend {
        LIST_VIEW,
        RECYCLER_VIEW,
    }

    data class BindContext(
        val position: Int,
        val itemCount: Int,
        val previousConversation: Any?,
        val nextConversation: Any?,
        val backend: Backend,
    )

    data class AdapterPositionSnapshot(
        val visiblePosition: Int,
        val itemCount: Int,
        val currentRawPosition: Int,
        val previousRawPosition: Int?,
        val nextRawPosition: Int?,
    )

    fun interface IBindViewListener {
        fun onBind(param: HookParam, row: View, conversation: Any, context: BindContext)
    }

    fun interface IAdapterPositionProvider {
        fun snapshot(adapter: Any, currentRawPosition: Int): AdapterPositionSnapshot?
    }

    private const val TAG = "WeConversationListViewApi"

    private val NEIGHBOR_REBIND_PAYLOAD = Any()
    private val pendingNeighborRebinds = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    private val listeners = CopyOnWriteArrayList<IBindViewListener>()
    private val positionProviders = CopyOnWriteArrayList<IAdapterPositionProvider>()
    private var latestAdapter: WeakReference<Any>? = null
    private var latestContainer: WeakReference<View>? = null
    private var latestBackend: Backend? = null

    val methodLegacyGetView by
        dexMethod(allowFailure = true) {
            searchPackages("com.tencent.mm.ui.conversation")
            name = "getView"
            returnType = "android.view.View"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            usingEqStrings(
                "MicroMsg.ConversationWithCacheAdapter",
                "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
            )
        }
    val methodMvvmGetView by dexMethod {
        name = "getView"
        returnType = "android.view.View"
        paramTypes("int", "android.view.View", "android.view.ViewGroup")
        declaredClass {
            usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter")
            usingEqStrings(
                "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
            )
        }
    }

    private val methodListViewCheckEmptyFooter by dexMethod {
        declaredClass = "com.tencent.mm.ui.conversation.ConversationListView"
        returnType = "void"
        paramCount = 0
        usingEqStrings("[checkEmptyFooter] isRealFull:", "[checkEmptyFooter] setSelection")
    }

    private val fieldListViewActionBar by dexField {
        declaredClass = "com.tencent.mm.ui.conversation.ConversationListView"
        type = "android.view.View"
        readMethod {
            returnType = "void"
            paramCount = 0
            usingEqStrings("resetActionBarView")
        }
    }

    private val recycler = dexGroup(whenFound = { classConversationRecyclerAdapter })

    /** Common host interface implemented by both the ListView and RecyclerView adapters. */
    val classConversationAdapter by recycler.dexClassFrom {
        val mvvmAdapter =
            DexResolutionContext.metadata.classInfoByName(
                methodMvvmGetView.data.declaredClassName,
            )!!
        val recyclerInterfaceNames =
            classConversationRecyclerAdapter.data.interfaces.mapTo(mutableSetOf()) { it.name }
        val adapterInterfaces =
            mvvmAdapter.interfaces
                .filter { it.name in recyclerInterfaceNames }
                .filter { candidate ->
                    candidate.methods.any {
                        it.name == "getCount" &&
                            it.paramTypeNames.isEmpty() &&
                            it.returnTypeName == "int"
                    } &&
                        candidate.methods.any {
                            it.name == "getItem" && it.paramTypeNames == listOf("int")
                        }
                }
        require(adapterInterfaces.size == 1) {
            "expected one common conversation adapter interface, found: " +
                adapterInterfaces.joinToString { it.name }
        }
        adapterInterfaces.single()
    }

    val methodAdapterGetCount by recycler.dexMethodFrom {
        classConversationAdapter.data.methods.single {
            it.name == "getCount" && it.paramTypeNames.isEmpty() && it.returnTypeName == "int"
        }
    }

    val methodAdapterGetItem by recycler.dexMethodFrom {
        classConversationAdapter.data.methods.single {
            it.name == "getItem" && it.paramTypeNames == listOf("int")
        }
    }

    /** MainUI's common list-host wrapper, used to reach whichever view backend is active. */
    private val classConversationListHost by
        dexClass(allowFailure = true) {
            searchPackages("com.tencent.mm.ui.conversation")
            modifiers = 1537
            method {
                name = "addHeaderView"
                returnType = "void"
                paramTypes("android.view.View")
            }
            method {
                name = "removeHeaderView"
                returnType = "void"
                paramTypes("android.view.View")
            }
        }

    private val fieldMainUiListHost by classConversationListHost.dexField {
        declaredClass {
            className = "com.tencent.mm.ui.conversation.MainUI"
        }
        type = classConversationListHost.data.name
    }

    private val methodListHostAddHeaderView by classConversationListHost.dexMethod {
        declaredClass {
            className = classConversationListHost.data.name
        }
        name = "addHeaderView"
        paramTypes(View::class.java.name)
        returnType = "void"
    }

    private val methodListHostView by classConversationListHost.dexMethod {
        declaredClass {
            className = classConversationListHost.data.name
        }
        paramCount = 0
        returnType = View::class.java.name
    }

    /** 8.0.78's optional RecyclerView conversation-list path. */
    val classConversationRecyclerAdapter by
        dexClass(allowFailure = true) {
            usingEqStrings("MicroMsg.ConversationRecyclerAdapter", "loadFirstPage size:")
        }

    private val methodRecyclerConversationBind by recycler.dexMethod {
        paramTypes(null, null, "int", "int", "boolean", "java.util.List")
        returnType = "void"
        usingEqString("GROUP_KEY_TOP")
    }

    private val fieldRecyclerHolderAdapter by recycler.dexFieldFrom {
        val holderType = methodRecyclerConversationBind.data.paramTypeNames[0]
        val recyclerAdapterFrameworkClass =
            generateSequence(
                    DexResolutionContext.metadata.classInfoByName(
                        classConversationRecyclerAdapter.data.name,
                    ),
                ) {
                    it.superClass
                }
                .first {
                    it.name.startsWith("androidx.recyclerview.widget.")
                }
        val holderClass = DexResolutionContext.metadata.classInfoByName(holderType)!!
        holderClass.fields.single {
            it.typeName == recyclerAdapterFrameworkClass.name
        }
    }

    private val fieldRecyclerHolderView by recycler.dexFieldFrom {
        DexResolutionContext.metadata
            .classInfoByName(
                methodRecyclerConversationBind.data.paramTypeNames[0],
            )!!
            .fields
            .single {
                it.typeName == "androidx.recyclerview.widget.RecyclerView"
            }
    }

    val classConversationRecyclerView by recycler.dexClass {
        usingEqString("MicroMsg.ConversationRecyclerView")
        usingEqString("[flushPendingHeaders] flushing %d headers")
    }
    private val fieldRecyclerActionBar by recycler.dexField {
        declaredClass {
            className = classConversationRecyclerView.data.name
        }
        type = "android.view.View"
        readMethod {
            paramCount = 0
            returnType = "void"
            usingEqString("resetActionBarView\$app_release")
        }
    }
    val methodRecyclerAddHeaderView by recycler.dexMethod {
        declaredClass {
            className = classConversationRecyclerView.data.name
        }
        name = "addHeaderView"
        paramTypes(View::class.java.name)
        returnType = "void"
    }
    val methodRecyclerAttachViewToParent by recycler.dexMethod {
        declaredClass {
            className = classConversationRecyclerView.data.name
        }
        name = "attachViewToParent"
        paramTypes(View::class.java.name, "int", ViewGroup.LayoutParams::class.java.name)
        returnType = "void"
    }
    val methodRecyclerSetFoldBanner by recycler.dexMethod {
        declaredClass {
            className = classConversationRecyclerView.data.name
        }
        name = "setFoldBanner"
        paramTypes(View::class.java.name)
        returnType = "void"
    }
    val methodRecyclerFirstVisiblePosition by recycler.dexMethod {
        declaredClass {
            className = classConversationRecyclerView.data.name
        }
        name = "getFirstVisiblePosition"
        paramCount = 0
        returnType = "int"
    }
    val methodRecyclerOnScrolled by recycler.dexMethod {
        paramTypes("androidx.recyclerview.widget.RecyclerView", "int", "int")
        returnType = "void"
        usingEqString("MicroMsg.ConversationRecyclerView")
        usingEqString(
            "[onScrolled] stop fling at HC edge: dy=%d firstView.top=%d scrollOffset=%d",
        )
    }
    val methodRecyclerOnScrollStateChanged by recycler.dexMethod {
        declaredClass {
            className = methodRecyclerOnScrolled.data.declaredClassName
        }
        name = "onScrollStateChanged"
        paramTypes("androidx.recyclerview.widget.RecyclerView", "int")
        returnType = "void"
    }
    private val methodRecyclerCalculateEmptyFooter by recycler.dexMethod {
        declaredClass {
            className = classConversationRecyclerView.data.name
        }
        paramCount = 0
        returnType = "float"
        usingEqString("[calculateEmptyFooterHeight] read fold data failed")
    }
    private val methodRecyclerApplyEmptyFooter by recycler.dexMethod {
        declaredClass {
            className = classConversationRecyclerView.data.name
        }
        paramTypes("float", "boolean")
        usingString("[applyEmptyFooterHeight] fromUpdate:")
    }

    override fun onEnable() {
        hookListBinding(methodLegacyGetView)
        hookListBinding(methodMvvmGetView)
        if (methodRecyclerConversationBind.isFound) {
            hookRecyclerBinding()
            hookRecyclerNeighborRebinding()
        }
    }

    fun addListener(listener: IBindViewListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: IBindViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}",
        )
    }

    fun addPositionProvider(provider: IAdapterPositionProvider) {
        if (!positionProviders.contains(provider)) positionProviders.add(provider)
    }

    fun removePositionProvider(provider: IAdapterPositionProvider) {
        positionProviders.remove(provider)
    }

    fun refresh(resetListViewPosition: Boolean = false) {
        postOnUiThread {
            val adapter = latestAdapter?.get() ?: return@postOnUiThread
            when (latestBackend) {
                Backend.LIST_VIEW -> {
                    val listView = latestContainer?.get() as? ListView
                    val installedAdapter = listView?.adapter
                    val realInstalledAdapter =
                        (installedAdapter as? HeaderViewListAdapter)?.wrappedAdapter
                            ?: installedAdapter
                    if (realInstalledAdapter != null && realInstalledAdapter !== adapter) {
                        return@postOnUiThread
                    }
                    dividerCoordinator.applyListView(listView)
                    (adapter as BaseAdapter).notifyDataSetChanged()
                    if (resetListViewPosition && listView != null) {
                        // Shorter datasets need their filler height before ListView lays out;
                        // otherwise bottom alignment can pull the recent mini-program header down.
                        methodListViewCheckEmptyFooter.method.invoke(listView)
                        // WeChat's override chooses the first visible banner and its native offset.
                        listView.setSelection(0)
                    }
                }

                Backend.RECYCLER_VIEW -> {
                    notifyAdapterChanged(adapter)
                }

                null -> return@postOnUiThread
            }
        }
    }

    fun notifyAdapterChanged(adapter: Any) {
        adapter
            .reflekt()
            .firstMethod {
                name = "notifyDataSetChanged"
                parameterCount = 0
                superclass()
            }
            .invoke()
    }

    fun currentAdapter(): Any? = latestAdapter?.get()

    fun currentContainer(): View? = latestContainer?.get()

    /**
     * UI-thread refresh of a specific host, without relying on the last row that happened to bind.
     */
    fun refreshContainer(container: View, resetListViewPosition: Boolean = false) {
        if (container is ListView) {
            val installed = container.adapter ?: return
            val adapter = (installed as? HeaderViewListAdapter)?.wrappedAdapter ?: installed
            dividerCoordinator.applyListView(container)
            (adapter as BaseAdapter).notifyDataSetChanged()
            if (resetListViewPosition) {
                methodListViewCheckEmptyFooter.method.invoke(container)
                container.setSelection(0)
            }
        } else {
            val adapter =
                container
                    .reflekt()
                    .firstMethod {
                        name = "getAdapter"
                        parameters()
                        superclass()
                    }
                    .invoke() ?: return
            notifyAdapterChanged(adapter)
        }
    }

    /**
     * Recomputes the Recycler filler that lets the recent mini-program header scroll away. WeChat
     * only does this on layout while its previous row count was below 16, so a long list replaced
     * by a short one keeps a filler too small to hide the header. Passing `fromUpdate = true`
     * updates only the height, like the host's fold helper; the caller owns the scroll position.
     */
    fun updateRecyclerEmptyFooter(container: View) {
        val extra = methodRecyclerCalculateEmptyFooter.method.invoke(container) as Float
        methodRecyclerApplyEmptyFooter.method.invoke(container, extra, true)
    }

    /**
     * Re-runs the host's internal scroll listener against the settled layout. It derives the action
     * bar's translation, status-bar mask and recent-page progress from the list position, but
     * RecyclerView dispatches no onScrolled for a programmatic jump that keeps the same child
     * range, leaving them at the values of an intermediate layout.
     */
    fun dispatchRecyclerScrolled(container: View) {
        val onScrolled = methodRecyclerOnScrolled.method
        val listener = container.reflekt().firstField { type(onScrolled.declaringClass) }.get()
        onScrolled.invoke(listener, container, 0, 0)
    }

    /** Called only when deciding a gesture, not during scrolling or drawing. */
    fun isRecentPageVisible(container: View): Boolean {
        val header =
            container
                .reflekt()
                .fields {
                    type { it.name.startsWith("com.tencent.mm.plugin.taskbar.ui.") }
                }
                .firstNotNullOfOrNull { field ->
                    (field.get() as? View)?.takeIf {
                        it.javaClass.name == "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer"
                    }
                } ?: return false
        val visible = Rect()
        if (!header.isShown || !header.getGlobalVisibleRect(visible)) return false
        val actionBar = actionBarView(container) ?: return true
        // A fully open page hides the action bar and translates it to the page's bottom, where the
        // header's bottom edge no longer passes it. getGlobalVisibleRect ignores visibility.
        if (!actionBar.isShown) return true
        val actionBarBounds = Rect()
        return !actionBar.getGlobalVisibleRect(actionBarBounds) ||
            visible.bottom > actionBarBounds.bottom
    }

    /** Empty spacer footers, including RecyclerView's fixed footer before it is attached. */
    fun emptyFooterViews(container: View): List<ViewGroup> {
        val candidates =
            if (container is ListView) {
                val adapter = container.adapter as? HeaderViewListAdapter ?: return emptyList()
                val count = adapter.count
                // These positions return stored FixedViewInfo views without binding conversation
                // rows.
                (count - adapter.footersCount until count).map { position ->
                    adapter.getView(position, null, container)
                }
            } else {
                // ConversationRecyclerView creates and retains its empty footer in its constructor.
                // Inspect only its own View fields; no adapter-position lookup or attach polling is
                // needed.
                container.reflekt().fields { type = View::class }.mapNotNull { it.get() as View? }
            }
        return candidates.mapNotNull { view ->
            (view as? ViewGroup)?.takeIf {
                it.childCount == 1 && it.getChildAt(0).javaClass == View::class.java
            }
        }
    }

    fun hostView(mainUi: Any): View {
        if (!classConversationListHost.isFound) {
            return mainUi
                .reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.conversation.ConversationListView"
                }
                .get()!!
                as View
        }
        val host = fieldMainUiListHost.field.get(mainUi)!!
        return methodListHostView.method.invoke(host) as View
    }

    fun addHeaderView(mainUi: Any, header: View) {
        if (!classConversationListHost.isFound) {
            (hostView(mainUi) as ListView).addHeaderView(header)
            return
        }
        val host = fieldMainUiListHost.field.get(mainUi)!!
        methodListHostAddHeaderView.method.invoke(host, header)
    }

    fun actionBarView(hostView: View): View? =
        (if (hostView is ListView) fieldListViewActionBar else fieldRecyclerActionBar)
            .field
            .get(hostView) as View?

    fun headerCount(hostView: View): Int =
        if (hostView is ListView) {
            hostView.headerViewsCount
        } else {
            hostView
                .reflekt()
                .firstMethod {
                    name = "getHeaderViewsCount"
                    parameters()
                }
                .invoke() as Int
        }

    fun firstVisiblePosition(hostView: View): Int =
        if (hostView is ListView) {
            hostView.firstVisiblePosition
        } else {
            methodRecyclerFirstVisiblePosition.method.invoke(hostView) as Int
        }

    fun setDividerHidden(owner: Any, hidden: Boolean) {
        dividerCoordinator.setHidden(owner, hidden)
        refresh()
    }

    fun setRowDividerHidden(owner: Any, row: View, hidden: Boolean) {
        dividerCoordinator.setRowHidden(owner, row, hidden)
        dividerCoordinator.apply(row, latestContainer?.get() as? ListView)
    }

    fun removeDividerOwner(owner: Any) {
        dividerCoordinator.removeOwner(owner)
        refresh()
    }

    private fun hookListBinding(method: DexMethodDelegate) {
        if (!method.isFound) return
        method.hookAfter {
            val row = result as View
            val adapter = thisObject as BaseAdapter
            updateActiveBinding(adapter, args[2] as? ListView, Backend.LIST_VIEW)
            dispatchBinding(this, row, adapter, args[0] as Int, Backend.LIST_VIEW)
        }
    }

    private fun hookRecyclerBinding() {
        methodRecyclerConversationBind.hookAfter {
            val holder = args[0]!!
            val adapter = fieldRecyclerHolderAdapter.field.get(holder)!!
            val row = holder.reflekt().getField("itemView", true) as View
            val recyclerView = fieldRecyclerHolderView.field.get(holder) as View?
            updateActiveBinding(adapter, recyclerView, Backend.RECYCLER_VIEW)
            dispatchBinding(this, row, adapter, args[2] as Int, Backend.RECYCLER_VIEW)
        }
    }

    /**
     * Row shapes depend on the neighbouring conversations, but RecyclerView binds only rows whose
     * own item changed. After a batch of adapter updates, rebind the conversation rows so every
     * [BindContext] is current. Positions inside a batch refer to intermediate states, so one
     * posted range in the final coordinates replaces per-operation neighbour math. The host's row
     * binder ignores payloads and binds fully; a payload keeps RecyclerView from cross-fading.
     */
    private fun hookRecyclerNeighborRebinding() {
        val adapterClass = classConversationRecyclerAdapter.clazz
        fun hookNotify(name: String, vararg parameters: KClass<*>) =
            adapterClass
                .reflekt()
                .firstMethod {
                    this.name = name
                    parameters(*parameters)
                    superclass()
                }
                .self
                .hookAfter {
                    val adapter = thisObject!!
                    if (
                        !adapterClass.isInstance(adapter) ||
                            args.size == 3 && args[2] === NEIGHBOR_REBIND_PAYLOAD
                    ) {
                        return@hookAfter
                    }
                    if (pendingNeighborRebinds.add(adapter)) {
                        postOnUiThread {
                            pendingNeighborRebinds.remove(adapter)
                            rebindRecyclerRows(adapter)
                        }
                    }
                }
        hookNotify("notifyItemRangeInserted", Int::class, Int::class)
        hookNotify("notifyItemRangeRemoved", Int::class, Int::class)
        hookNotify("notifyItemMoved", Int::class, Int::class)
        // A changed row can change group (e.g. pinning), which changes its neighbours' shapes.
        hookNotify("notifyItemRangeChanged", Int::class, Int::class, Any::class)
    }

    private fun rebindRecyclerRows(adapter: Any) {
        val container = latestContainer?.get() ?: return
        val installed =
            container
                .reflekt()
                .firstMethod {
                    name = "getAdapter"
                    parameters()
                    superclass()
                }
                .invoke()
        if (installed !== adapter) return
        val first = headerCount(container)
        val end =
            adapter
                .reflekt()
                .firstMethod {
                    name = "getItemCount"
                    parameters()
                    superclass()
                }
                .invoke() as Int -
                container
                    .reflekt()
                    .firstMethod {
                        name = "getFooterViewsCount"
                        parameters()
                    }
                    .invoke() as Int
        if (end <= first) return
        adapter
            .reflekt()
            .firstMethod {
                name = "notifyItemRangeChanged"
                parameters(Int::class, Int::class, Any::class)
                superclass()
            }
            .invoke(first, end - first, NEIGHBOR_REBIND_PAYLOAD)
    }

    private fun updateActiveBinding(adapter: Any, container: View?, backend: Backend) {
        if (latestAdapter?.get() !== adapter) latestAdapter = WeakReference(adapter)
        if (container != null && latestContainer?.get() !== container) {
            latestContainer = WeakReference(container)
        }
        latestBackend = backend
    }

    private fun dispatchBinding(
        param: HookParam,
        row: View,
        adapter: Any,
        rawPosition: Int,
        backend: Backend,
    ) {
        if (listeners.isNotEmpty()) {
            val snapshot = positionProviders.firstNotNullOfOrNull { provider ->
                runCatching { provider.snapshot(adapter, rawPosition) }
                    .onFailure {
                        WeLogger.e(TAG, "position provider ${provider.javaClass.name} threw", it)
                    }
                    .getOrNull()
            }
            val currentRawPosition = snapshot?.currentRawPosition ?: rawPosition
            val itemCount =
                snapshot?.itemCount
                    ?: when (backend) {
                        Backend.LIST_VIEW -> (adapter as BaseAdapter).count
                        Backend.RECYCLER_VIEW -> methodAdapterGetCount.method.invoke(adapter) as Int
                    }
            val previousRawPosition =
                if (snapshot != null) {
                    snapshot.previousRawPosition
                } else {
                    (currentRawPosition - 1).takeIf { it >= 0 }
                }
            val nextRawPosition =
                if (snapshot != null) {
                    snapshot.nextRawPosition
                } else {
                    (currentRawPosition + 1).takeIf { it < itemCount }
                }
            fun itemAt(position: Int): Any? =
                when (backend) {
                    Backend.LIST_VIEW -> (adapter as BaseAdapter).getItem(position)
                    Backend.RECYCLER_VIEW -> methodAdapterGetItem.method.invoke(adapter, position)
                }
            val conversation = itemAt(currentRawPosition)!!
            val bindContext =
                BindContext(
                    position = snapshot?.visiblePosition ?: rawPosition,
                    itemCount = itemCount,
                    previousConversation = previousRawPosition?.let(::itemAt),
                    nextConversation = nextRawPosition?.let(::itemAt),
                    backend = backend,
                )
            for (listener in listeners) {
                try {
                    listener.onBind(param, row, conversation, bindContext)
                } catch (error: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", error)
                }
            }
        }
        dividerCoordinator.apply(row, latestContainer?.get() as? ListView)
    }

    @Suppress("ClassName")
    private object dividerCoordinator {
        private data class RowDividerState(val originalVisibility: Int)

        private data class ListDividerState(
            val originalDivider: Drawable?,
            val originalDividerHeight: Int,
            val moduleDivider: ColorDrawable,
        )

        private val hiddenOwners =
            Collections.synchronizedSet(
                Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            )
        private val rowStates = WeakHashMap<View, RowDividerState>()
        private val rowHiddenOwners = WeakHashMap<View, MutableSet<Any>>()
        private val listStates = WeakHashMap<ListView, ListDividerState>()

        fun setHidden(owner: Any, hidden: Boolean) {
            if (hidden) hiddenOwners.add(owner) else hiddenOwners.remove(owner)
        }

        fun setRowHidden(owner: Any, row: View, hidden: Boolean) {
            val owners = rowHiddenOwners[row]
            if (hidden) {
                (owners
                        ?: Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also {
                            rowHiddenOwners[row] = it
                        })
                    .add(owner)
            } else {
                owners?.remove(owner)
                if (owners != null && owners.isEmpty()) rowHiddenOwners.remove(row)
            }
        }

        fun removeOwner(owner: Any) {
            hiddenOwners.remove(owner)
            val iterator = rowHiddenOwners.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.value.remove(owner)
                if (entry.value.isEmpty()) iterator.remove()
            }
        }

        fun apply(row: View, listView: ListView?) {
            applyRowDivider(row)
            applyListView(listView)
        }

        fun applyListView(listView: ListView?) {
            listView ?: return
            if (hiddenOwners.isNotEmpty()) {
                val state =
                    listStates.getOrPut(listView) {
                        ListDividerState(
                            listView.divider,
                            listView.dividerHeight,
                            Color.TRANSPARENT.toDrawable(),
                        )
                    }
                if (listView.divider !== state.moduleDivider) listView.divider = state.moduleDivider
                if (listView.dividerHeight != 0) listView.dividerHeight = 0
            } else {
                val state = listStates.remove(listView) ?: return
                if (listView.divider === state.moduleDivider) {
                    listView.divider = state.originalDivider
                    listView.dividerHeight = state.originalDividerHeight
                }
            }
        }

        private fun applyRowDivider(row: View) {
            val divider =
                row.findViewByChildIndexes(0, 1, 1, 1)
                    ?: row.findViewByChildIndexes(0, 1, 1)
                    ?: return
            if (isHidden(row)) {
                rowStates.getOrPut(divider) { RowDividerState(divider.visibility) }
                if (divider.visibility != View.GONE) divider.visibility = View.GONE
            } else {
                val state = rowStates.remove(divider) ?: return
                if (divider.isGone) divider.visibility = state.originalVisibility
            }
        }

        private fun isHidden(row: View): Boolean =
            hiddenOwners.isNotEmpty() || rowHiddenOwners[row]?.isNotEmpty() == true
    }
}
