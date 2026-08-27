package dev.ujhhgtg.wekit.features.api.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.BaseAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isGone
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
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

    data class BindContext(
        val position: Int,
        val itemCount: Int,
        val previousConversation: Any?,
        val nextConversation: Any?,
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
        fun snapshot(adapter: BaseAdapter, currentRawPosition: Int): AdapterPositionSnapshot?
    }

    private const val TAG = "WeConversationListViewApi"

    private val listeners = CopyOnWriteArrayList<IBindViewListener>()
    private val positionProviders = CopyOnWriteArrayList<IAdapterPositionProvider>()
    private var latestAdapter: WeakReference<BaseAdapter>? = null
    private var latestListView: WeakReference<ListView>? = null

    internal val methodLegacyGetView by dexMethod(allowFailure = true) {
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
    internal val methodMvvmGetView by dexMethod {
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

    override fun onEnable() {
        hookBinding(methodLegacyGetView)
        hookBinding(methodMvvmGetView)
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
            val listView = latestListView?.get()
            val installedAdapter = listView?.adapter
            val realInstalledAdapter = (installedAdapter as? HeaderViewListAdapter)?.wrappedAdapter
                ?: installedAdapter
            if (realInstalledAdapter != null && realInstalledAdapter !== adapter) return@runOnUiThread
            dividerCoordinator.applyListView(listView)
            adapter.notifyDataSetChanged()
        }
    }

    fun setDividerHidden(owner: Any, hidden: Boolean) {
        dividerCoordinator.setHidden(owner, hidden)
        refresh()
    }

    fun setRowDividerHidden(owner: Any, row: View, hidden: Boolean) {
        dividerCoordinator.setRowHidden(owner, row, hidden)
        dividerCoordinator.apply(row, latestListView?.get())
    }

    fun removeDividerOwner(owner: Any) {
        dividerCoordinator.removeOwner(owner)
        refresh()
    }

    private fun hookBinding(method: DexMethodDelegate) {
        if (method.isPlaceholder) return
        method.hookAfter {
            val row = result as View
            val adapter = thisObject as BaseAdapter
            if (latestAdapter?.get() !== adapter) latestAdapter = WeakReference(adapter)
            (args[2] as? ListView)?.let { listView ->
                if (latestListView?.get() !== listView) latestListView = WeakReference(listView)
            }

            if (listeners.isNotEmpty()) {
                val rawPosition = args[0] as Int
                val snapshot = positionProviders.firstNotNullOfOrNull { provider ->
                    runCatching { provider.snapshot(adapter, rawPosition) }
                        .onFailure { WeLogger.e(TAG, "position provider ${provider.javaClass.name} threw", it) }
                        .getOrNull()
                }
                val currentRawPosition = snapshot?.currentRawPosition ?: rawPosition
                val itemCount = snapshot?.itemCount ?: adapter.count
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
                val conversation = adapter.getItem(currentRawPosition)!!
                val bindContext = BindContext(
                    position = snapshot?.visiblePosition ?: rawPosition,
                    itemCount = itemCount,
                    previousConversation = previousRawPosition?.let(adapter::getItem),
                    nextConversation = nextRawPosition?.let(adapter::getItem),
                )
                for (listener in listeners) {
                    try {
                        listener.onBind(this, row, conversation, bindContext)
                    } catch (error: Exception) {
                        WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", error)
                    }
                }
            }
            dividerCoordinator.apply(row, latestListView?.get())
        }
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
