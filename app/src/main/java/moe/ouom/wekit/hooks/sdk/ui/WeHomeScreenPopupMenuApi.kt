package moe.ouom.wekit.hooks.sdk.ui

import android.util.SparseArray
import android.widget.BaseAdapter
import androidx.core.util.size
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.createInstance
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/首页菜单服务", desc = "提供向首页右上角菜单添加菜单项的能力")
object WeHomeScreenPopupMenuApi : ApiHookItem(), IDexFind {

    interface IMenuItemsProvider {
        fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawableResourceId: Int,
        val onClick: () -> Unit
    )

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        if (!providers.contains(provider)) {
            providers.add(provider)
            WeLogger.i(TAG, "provider added, current provider count: ${providers.size}")
        } else {
            WeLogger.w(TAG, "provider already exists, ignored")
        }
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        val removed = providers.remove(provider)
        WeLogger.i(
            TAG,
            "provider remove ${if (removed) "succeeded" else "failed"}, current provider count: ${providers.size}"
        )
    }

    private val TAG = nameof(WeHomeScreenPopupMenuApi)

    private val methodAddItem by dexMethod()
    private val methodHandleItemClick by dexMethod()
    private val classMenuItemData by dexClass()
    private val classMenuItemWrapper by dexClass()

    override fun onLoad() {
        methodAddItem.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val thisObj = param.thisObject

                    @Suppress("UNCHECKED_CAST")
                    val items = thisObj.asResolver()
                        .firstField {
                            type = SparseArray::class
                        }
                        .get()!! as SparseArray<Any>
                    val baseAdapter = thisObj.asResolver()
                        .firstField {
                            type { clazz ->
                                BaseAdapter::class.java.isAssignableFrom(clazz)
                            }
                        }
                        .get()!! as BaseAdapter

                    for (provider in providers) {
                        try {
                            for (item in provider.getMenuItems(param)) {
                                val itemData = classMenuItemData.clazz.createInstance(
                                    item.id,
                                    item.text,
                                    "",
                                    item.drawableResourceId,
                                    0
                                )
                                val itemWrapper =
                                    classMenuItemWrapper.clazz.createInstance(itemData)
                                items.put(items.size, itemWrapper)
                            }
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while providing menu items",
                                ex
                            )
                        }
                    }
                    baseAdapter.notifyDataSetChanged()
                }
            }
        }

        methodHandleItemClick.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val thisObj = param.thisObject

                    @Suppress("UNCHECKED_CAST")
                    val items = thisObj.asResolver()
                        .firstField {
                            type = SparseArray::class
                        }
                        .get()!! as SparseArray<Any>
                    val position = param.args[2] as Int
                    val itemWrapper = items.get(position)
                    val itemData = itemWrapper.asResolver()
                        .firstField { type = classMenuItemData.clazz }.get()!!
                    val id = itemData.asResolver()
                        .field { type = Int::class }[1].get()!! as Int

                    for (provider in providers) {
                        for (item in provider.getMenuItems(param)) {
                            if (item.id == id) {
                                try {
                                    item.onClick()
                                    param.result = null
                                    return@beforeIfEnabled
                                } catch (ex: Exception) {
                                    WeLogger.e(
                                        TAG,
                                        "provider ${provider.javaClass.name} threw while handling click event",
                                        ex
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodAddItem.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                usingEqStrings(
                    "MicroMsg.PlusSubMenuHelper",
                    "dyna plus config is null, we use default one"
                )
            }
        }

        methodHandleItemClick.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
            }
        }

        classMenuItemData.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                addFieldForType(String::class.java)
                addFieldForType(Int::class.java)
                addFieldForType(Int::class.java)
                addFieldForType(Int::class.java)
                addFieldForType(String::class.java)
                fieldCount(5)
                methods {
                    add {
                        usingEqStrings("")
                    }
                }
            }
        }

        classMenuItemWrapper.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                addFieldForType(Boolean::class.java)
                addFieldForType(classMenuItemData.clazz)
            }
        }

        return descriptors
    }
}