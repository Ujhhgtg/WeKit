package dev.ujhhgtg.wekit.features.api.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.BaseAdapter
import android.widget.ListView
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "会话列表 View 绑定监听服务", categories = ["API"], description = "提供会话列表 View 绑定监听能力")
object WeConversationListViewApi : ApiFeature(), IResolveDex {

    fun interface IBindViewListener {
        fun onBind(param: HookParam, row: View, conversation: Any)
    }

    private const val TAG = "WeConversationListViewApi"

    private val listeners = CopyOnWriteArrayList<IBindViewListener>()
    private var latestAdapter: WeakReference<BaseAdapter>? = null
    private var latestListView: WeakReference<ListView>? = null

    private val methodLegacyGetView by dexMethod()
    private val methodMvvmGetView by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        val hostVersion = DexResolutionContext.host.versionName
        if (hostVersion in setOf("8.0.74", "8.0.76")) {
            methodLegacyGetView.setPlaceholderDescriptor(
                expectedFailure = true,
                reason = "ConversationWithCacheAdapter is absent in WeChat $hostVersion; MVVM adapter remains required",
            )
        } else {
            methodLegacyGetView.find(dexKit) {
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
        }
        methodMvvmGetView.find(dexKit) {
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

    fun refresh() {
        runOnUiThread {
            val adapter = latestAdapter?.get() ?: return@runOnUiThread
            val listView = latestListView?.get()
            if (listView != null && listView.adapter !== adapter) return@runOnUiThread
            dividerCoordinator.applyListView(listView)
            adapter.notifyDataSetChanged()
        }
    }

    fun setDividerHidden(owner: Any, hidden: Boolean) {
        dividerCoordinator.setHidden(owner, hidden)
        refresh()
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
            val position = args[0] as Int
            val conversation = adapter.getItem(position)!!
            latestAdapter = WeakReference(adapter)
            (args[2] as? ListView)?.let { latestListView = WeakReference(it) }

            for (listener in listeners) {
                try {
                    listener.onBind(this, row, conversation)
                } catch (error: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", error)
                }
            }
            dividerCoordinator.apply(row, latestListView?.get())
        }
    }

    private object dividerCoordinator {
        private data class RowDividerState(val originalVisibility: Int)
        private data class ListDividerState(
            val originalDivider: Drawable?,
            val originalDividerHeight: Int,
            val moduleDivider: ColorDrawable,
        )

        private val ownerRequests = Collections.synchronizedMap(IdentityHashMap<Any, Boolean>())
        private val rowStates = WeakHashMap<View, RowDividerState>()
        private val listStates = WeakHashMap<ListView, ListDividerState>()

        fun setHidden(owner: Any, hidden: Boolean) {
            ownerRequests[owner] = hidden
        }

        fun removeOwner(owner: Any) {
            ownerRequests.remove(owner)
        }

        fun apply(row: View, listView: ListView?) {
            applyRowDivider(row)
            applyListView(listView)
        }

        fun applyListView(listView: ListView?) {
            listView ?: return
            if (isHidden()) {
                val state = listStates.getOrPut(listView) {
                    ListDividerState(listView.divider, listView.dividerHeight, ColorDrawable(Color.TRANSPARENT))
                }
                listView.divider = state.moduleDivider
                listView.dividerHeight = 0
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
            if (isHidden()) {
                rowStates.getOrPut(divider) { RowDividerState(divider.visibility) }
                divider.visibility = View.GONE
            } else {
                val state = rowStates.remove(divider) ?: return
                if (divider.visibility == View.GONE) divider.visibility = state.originalVisibility
            }
        }

        private fun isHidden(): Boolean = synchronized(ownerRequests) {
            ownerRequests.values.any { it }
        }
    }
}
