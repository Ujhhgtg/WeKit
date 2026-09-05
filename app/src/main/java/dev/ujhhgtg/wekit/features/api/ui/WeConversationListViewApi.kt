package dev.ujhhgtg.wekit.features.api.ui

import android.graphics.Color
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
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

object WeConversationListViewApi : ApiFeature(), IResolveDex {

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

    private val listeners = CopyOnWriteArrayList<IBindViewListener>()
    private val positionProviders = CopyOnWriteArrayList<IAdapterPositionProvider>()
    private var latestAdapter: WeakReference<Any>? = null
    private var latestContainer: WeakReference<View>? = null
    private var latestBackend: Backend? = null

    val methodLegacyGetView by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType = "android.view.View"
            usingEqStrings(
                "MicroMsg.ConversationWithCacheAdapter",
                "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
            )
        }
    }
    val methodMvvmGetView by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings(
                    "MicroMsg.ConversationAdapter.MvvmConversationAdapter",
                    "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
                )
            }
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType = "android.view.View"
        }
    }

    /** Common host interface implemented by both the ListView and RecyclerView adapters. */
    val classConversationAdapter by dexClass()

    val methodAdapterGetCount by dexMethod()

    val methodAdapterGetItem by dexMethod()

    /** MainUI's common list-host wrapper, used to reach whichever view backend is active. */
    private val classConversationListHost by dexClass(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            modifiers(Modifier.PUBLIC or Modifier.ABSTRACT or Modifier.INTERFACE)
            methods {
                add {
                    name = "addHeaderView"
                    paramTypes(View::class.java)
                    returnType = "void"
                }
                add {
                    name = "removeHeaderView"
                    paramTypes(View::class.java)
                    returnType = "void"
                }
            }
        }
    }

    private val fieldMainUiListHost by dexField()

    private val methodListHostAddHeaderView by dexMethod()

    private val methodListHostView by dexMethod()

    /** 8.0.78's optional RecyclerView conversation-list path. */
    val classConversationRecyclerAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.ConversationRecyclerAdapter",
                "loadFirstPage size:",
            )
        }
    }

    private val methodRecyclerConversationBind by dexMethod()

    private val fieldRecyclerHolderAdapter by dexField()

    private val fieldRecyclerHolderView by dexField()

    val classConversationRecyclerView by dexClass()

    val methodRecyclerAddHeaderView by dexMethod()

    val methodRecyclerAttachViewToParent by dexMethod()

    val methodRecyclerSetFoldBanner by dexMethod()

    val methodRecyclerFirstVisiblePosition by dexMethod()

    val methodRecyclerOnScrolled by dexMethod()

    val methodRecyclerOnScrollStateChanged by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        if (classConversationListHost.isPlaceholder) {
            val reason = "common conversation list host is absent"
            fieldMainUiListHost.setPlaceholderDescriptor(true, reason)
            methodListHostAddHeaderView.setPlaceholderDescriptor(true, reason)
            methodListHostView.setPlaceholderDescriptor(true, reason)
        } else {
            fieldMainUiListHost.find(dexKit) {
                matcher {
                    declaredClass = "com.tencent.mm.ui.conversation.MainUI"
                    type(classConversationListHost.data.name)
                }
            }
            methodListHostAddHeaderView.find(dexKit) {
                matcher {
                    declaredClass(classConversationListHost.data.name)
                    name = "addHeaderView"
                    paramTypes(View::class.java)
                    returnType = "void"
                }
            }
            methodListHostView.find(dexKit) {
                matcher {
                    declaredClass(classConversationListHost.data.name)
                    paramCount = 0
                    returnType(View::class.java)
                }
            }
        }

        if (classConversationRecyclerAdapter.isPlaceholder) {
            val reason = "conversation RecyclerView architecture is absent"
            classConversationAdapter.setPlaceholderDescriptor(true, reason)
            methodAdapterGetCount.setPlaceholderDescriptor(true, reason)
            methodAdapterGetItem.setPlaceholderDescriptor(true, reason)
            methodRecyclerConversationBind.setPlaceholderDescriptor(true, reason)
            fieldRecyclerHolderAdapter.setPlaceholderDescriptor(true, reason)
            fieldRecyclerHolderView.setPlaceholderDescriptor(true, reason)
            classConversationRecyclerView.setPlaceholderDescriptor(true, reason)
            methodRecyclerAddHeaderView.setPlaceholderDescriptor(true, reason)
            methodRecyclerAttachViewToParent.setPlaceholderDescriptor(true, reason)
            methodRecyclerSetFoldBanner.setPlaceholderDescriptor(true, reason)
            methodRecyclerFirstVisiblePosition.setPlaceholderDescriptor(true, reason)
            methodRecyclerOnScrolled.setPlaceholderDescriptor(true, reason)
            methodRecyclerOnScrollStateChanged.setPlaceholderDescriptor(true, reason)
            return
        }

        val mvvmAdapter = dexKit.getClassData(methodMvvmGetView.data.declaredClassName)!!
        val recyclerAdapter = classConversationRecyclerAdapter.data
        val recyclerInterfaceNames = recyclerAdapter.interfaces.mapTo(mutableSetOf()) { it.name }
        val adapterInterfaces = mvvmAdapter.interfaces
            .filter { it.name in recyclerInterfaceNames }
            .filter { candidate ->
                candidate.methods.any {
                    it.methodName == "getCount" &&
                        it.paramTypeNames.isEmpty() &&
                        it.returnTypeName == "int"
                } && candidate.methods.any {
                    it.methodName == "getItem" && it.paramTypeNames == listOf("int")
                }
            }
        require(adapterInterfaces.size == 1) {
            "expected one common conversation adapter interface, found: " +
                adapterInterfaces.joinToString { it.name }
        }
        val adapterInterface = adapterInterfaces.single()
        classConversationAdapter.setDescriptor(adapterInterface)
        methodAdapterGetCount.setDescriptor(adapterInterface.methods.single {
            it.methodName == "getCount" &&
                it.paramTypeNames.isEmpty() &&
                it.returnTypeName == "int"
        })
        methodAdapterGetItem.setDescriptor(adapterInterface.methods.single {
            it.methodName == "getItem" && it.paramTypeNames == listOf("int")
        })

        methodRecyclerConversationBind.find(dexKit) {
            matcher {
                paramTypes(null, null, "int", "int", "boolean", "java.util.List")
                returnType = "void"
                usingEqStrings("GROUP_KEY_TOP")
            }
        }
        val holderType = methodRecyclerConversationBind.data.paramTypeNames[0]
        val recyclerAdapterFrameworkClass = generateSequence(
            dexKit.getClassData(classConversationRecyclerAdapter.data.name)
        ) { it.superClass }.first {
            it.name.startsWith("androidx.recyclerview.widget.")
        }
        val holderClass = dexKit.getClassData(holderType)!!
        fieldRecyclerHolderAdapter.setDescriptor(holderClass.fields.single {
            it.typeName == recyclerAdapterFrameworkClass.name
        })
        fieldRecyclerHolderView.setDescriptor(holderClass.fields.single {
            it.typeName == "androidx.recyclerview.widget.RecyclerView"
        })

        classConversationRecyclerView.find(dexKit) {
            matcher {
                usingEqStrings(
                    "MicroMsg.ConversationRecyclerView",
                    "[flushPendingHeaders] flushing %d headers",
                )
            }
        }
        methodRecyclerAddHeaderView.find(dexKit) {
            matcher {
                declaredClass(classConversationRecyclerView.data.name)
                name = "addHeaderView"
                paramTypes(View::class.java)
                returnType = "void"
            }
        }
        methodRecyclerAttachViewToParent.find(dexKit) {
            matcher {
                declaredClass(classConversationRecyclerView.data.name)
                name = "attachViewToParent"
                paramTypes(
                    View::class.java.name,
                    "int",
                    ViewGroup.LayoutParams::class.java.name,
                )
                returnType = "void"
            }
        }
        methodRecyclerSetFoldBanner.find(dexKit) {
            matcher {
                declaredClass(classConversationRecyclerView.data.name)
                name = "setFoldBanner"
                paramTypes(View::class.java)
                returnType = "void"
            }
        }
        methodRecyclerFirstVisiblePosition.find(dexKit) {
            matcher {
                declaredClass(classConversationRecyclerView.data.name)
                name = "getFirstVisiblePosition"
                paramCount = 0
                returnType = "int"
            }
        }
        methodRecyclerOnScrolled.find(dexKit) {
            matcher {
                paramTypes("androidx.recyclerview.widget.RecyclerView", "int", "int")
                returnType = "void"
                usingEqStrings(
                    "MicroMsg.ConversationRecyclerView",
                    "[onScrolled] stop fling at HC edge: dy=%d firstView.top=%d scrollOffset=%d",
                )
            }
        }
        methodRecyclerOnScrollStateChanged.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerOnScrolled.data.declaredClassName)
                name = "onScrollStateChanged"
                paramTypes("androidx.recyclerview.widget.RecyclerView", "int")
                returnType = "void"
            }
        }
    }

    override fun onEnable() {
        hookListBinding(methodLegacyGetView)
        hookListBinding(methodMvvmGetView)
        if (!methodRecyclerConversationBind.isPlaceholder) hookRecyclerBinding()
    }

    fun addListener(listener: IBindViewListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: IBindViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(TAG, "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}")
    }

    fun addPositionProvider(provider: IAdapterPositionProvider) {
        if (!positionProviders.contains(provider)) positionProviders.add(provider)
    }

    fun removePositionProvider(provider: IAdapterPositionProvider) {
        positionProviders.remove(provider)
    }

    fun refresh() {
        runOnUiThread {
            val adapter = latestAdapter?.get() ?: return@runOnUiThread
            when (latestBackend) {
                Backend.LIST_VIEW -> {
                    val listView = latestContainer?.get() as? ListView
                    val installedAdapter = listView?.adapter
                    val realInstalledAdapter =
                        (installedAdapter as? HeaderViewListAdapter)?.wrappedAdapter ?: installedAdapter
                    if (realInstalledAdapter != null && realInstalledAdapter !== adapter) {
                        return@runOnUiThread
                    }
                    dividerCoordinator.applyListView(listView)
                    (adapter as BaseAdapter).notifyDataSetChanged()
                }

                Backend.RECYCLER_VIEW -> {
                    notifyAdapterChanged(adapter)
                }

                null -> return@runOnUiThread
            }
        }
    }

    fun notifyAdapterChanged(adapter: Any) {
        adapter.reflekt().firstMethod {
            name = "notifyDataSetChanged"
            parameterCount = 0
            superclass()
        }.invoke()
    }

    fun currentAdapter(): Any? = latestAdapter?.get()

    fun hostView(mainUi: Any): View {
        if (classConversationListHost.isPlaceholder) {
            return mainUi.reflekt().firstField {
                type = "com.tencent.mm.ui.conversation.ConversationListView"
            }.get()!! as View
        }
        val host = fieldMainUiListHost.field.get(mainUi)!!
        return methodListHostView.method.invoke(host) as View
    }

    fun addHeaderView(mainUi: Any, header: View) {
        if (classConversationListHost.isPlaceholder) {
            (hostView(mainUi) as ListView).addHeaderView(header)
            return
        }
        val host = fieldMainUiListHost.field.get(mainUi)!!
        methodListHostAddHeaderView.method.invoke(host, header)
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
        if (method.isPlaceholder) return
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
                    .onFailure { WeLogger.e(TAG, "position provider ${provider.javaClass.name} threw", it) }
                    .getOrNull()
            }
            val currentRawPosition = snapshot?.currentRawPosition ?: rawPosition
            val itemCount = snapshot?.itemCount ?: when (backend) {
                Backend.LIST_VIEW -> (adapter as BaseAdapter).count
                Backend.RECYCLER_VIEW -> methodAdapterGetCount.method.invoke(adapter) as Int
            }
            val previousRawPosition = if (snapshot != null) {
                snapshot.previousRawPosition
            } else {
                (currentRawPosition - 1).takeIf { it >= 0 }
            }
            val nextRawPosition = if (snapshot != null) {
                snapshot.nextRawPosition
            } else {
                (currentRawPosition + 1).takeIf { it < itemCount }
            }
            fun itemAt(position: Int): Any? = when (backend) {
                Backend.LIST_VIEW -> (adapter as BaseAdapter).getItem(position)
                Backend.RECYCLER_VIEW -> methodAdapterGetItem.method.invoke(adapter, position)
            }
            val conversation = itemAt(currentRawPosition)!!
            val bindContext = BindContext(
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

        private val hiddenOwners = Collections.synchronizedSet(
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
                (owners ?: Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also {
                    rowHiddenOwners[row] = it
                }).add(owner)
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
                val state = listStates.getOrPut(listView) {
                    ListDividerState(listView.divider, listView.dividerHeight, Color.TRANSPARENT.toDrawable())
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
            val divider = row.findViewByChildIndexes(0, 1, 1, 1)
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
