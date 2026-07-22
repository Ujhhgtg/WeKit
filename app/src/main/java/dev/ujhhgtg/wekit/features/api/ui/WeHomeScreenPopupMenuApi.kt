package dev.ujhhgtg.wekit.features.api.ui

import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.widget.BaseAdapter
import android.widget.ImageView
import androidx.collection.mutableIntObjectMapOf
import androidx.core.util.size
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.BBool
import dev.ujhhgtg.wekit.utils.reflection.BInt
import dev.ujhhgtg.wekit.utils.reflection.BString
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "首页菜单服务", categories = ["API"], description = "提供向首页右上角菜单添加菜单项的能力")
object WeHomeScreenPopupMenuApi : ApiFeature(), IResolveDex {

    interface IMenuItemsProvider {
        fun getMenuItems(param: HookParam): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: () -> Unit
    ) {
        val fakeResId get() = id + text.hashCode()
    }

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        providers.remove(provider)
    }

    private const val TAG = "WeHomeScreenPopupMenuApi"

    private val fakeResIdToResMap = mutableIntObjectMapOf<Drawable>()

    private val methodAddItem by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings(
                "MicroMsg.PlusSubMenuHelper",
                "dyna plus config is null, we use default one"
            )
        }
    }
    private val methodHandleItemClick by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
        }
    }
    private val classMenuItemData by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(BString)
            addFieldForType(BInt)
            addFieldForType(BInt)
            addFieldForType(BInt)
            addFieldForType(BString)
            fieldCount(5)
            methods {
                add {
                    usingEqStrings("")
                }
            }
        }
    }
    private val classMenuItemWrapper by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(BBool)
            addFieldForType(classMenuItemData.clazz)
        }
    }

    override fun onEnable() {
        // WeChat 8.0.70 moved this to com.tencent.mm.ui.HomeUI
        methodAddItem.hookAfter {
            var thisObj = thisObject!!

            if (thisObj.javaClass.simpleName == "HomeUI") {
                thisObj = thisObj.reflekt()
                    .firstField { type = methodHandleItemClick.method.declaringClass }
                    .get()!!
            }

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val baseAdapter = thisObj.reflekt()
                .firstField {
                    type { it isSubclassOf BaseAdapter::class }
                }
                .get()!! as BaseAdapter

            baseAdapter.reflekt().firstMethod {
                name = "getView"
            }.apply {
                var unhook: HookHandle? = null

                hookBefore {
                    unhook = ImageView::class.reflekt().firstMethod {
                        name = "setImageResource"
                    }.hookBeforeDirectly {
                        val fakeResId = args[0] as Int
                        val imageView = thisObject as ImageView
                        imageView.setImageDrawable(fakeResIdToResMap[fakeResId] ?: return@hookBeforeDirectly)
                        result = null
                    }
                }

                hookAfter {
                    unhook!!.unhook()
                }
            }

            for (provider in providers) {
                try {
                    for (item in provider.getMenuItems(this)) {
                        fakeResIdToResMap[item.fakeResId] = item.drawable

                        val itemData = classMenuItemData.clazz.createInstance(
                            item.id,
                            item.text,
                            "",
                            item.fakeResId,
                            0
                        )
                        val itemWrapper =
                            classMenuItemWrapper.clazz.createInstance(itemData)
                        items.put(items.size, itemWrapper)

                        runOnUiThread {
                            baseAdapter.notifyDataSetChanged()
                        }
                    }
                } catch (ex: Exception) {
                    WeLogger.e(
                        TAG,
                        "provider ${provider.javaClass.name} threw while providing menu items",
                        ex
                    )
                }
            }

            runOnUiThread {
                baseAdapter.notifyDataSetChanged()
            }
        }

        methodHandleItemClick.hookBefore {
            val thisObj = thisObject!!

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val position = args[2] as Int
            val itemWrapper = items.get(position)
            val itemData = itemWrapper.reflekt()
                .firstField { type = classMenuItemData.clazz }.get()!!
            val id = itemData.reflekt()
                .fields { type = Int::class }[1].get()!! as Int

            for (provider in providers) {
                for (item in provider.getMenuItems(this)) {
                    if (item.id == id) {
                        try {
                            item.onClick()
                            return@hookBefore
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
