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
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.postOnUiThread
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import dev.ujhhgtg.wekit.dexkit.matcher.StringSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameMatch
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.dsl.dexClassQuery

object WeHomeScreenPopupMenuApi : ApiFeature(), IResolveDex {

    override val technicalId = "首页菜单服务"
    override val nameRes = R.string.feature_we_home_screen_popup_menu_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_home_screen_popup_menu_api_description

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

    val methodAddItem by dexMethod(
        sqlMethod {
            searchPackages("com.tencent.mm.ui")
            usingEqStrings("MicroMsg.PlusSubMenuHelper", "dyna plus config is null, we use default one")
        },
    )
    val methodHandleItemClick by dexMethod(
        sqlMethod {
            searchPackages("com.tencent.mm.ui")
            usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
        },
    )
    private val classMenuItemData by dexClassQuery {
        uniqueClass(
            ClassSpec(
                fields = listOf(
                    FieldSpec(type = ClassSpec(className = NameSpec("java.lang.String"))),
                    FieldSpec(type = ClassSpec(className = NameSpec("int"))),
                    FieldSpec(type = ClassSpec(className = NameSpec("int"))),
                    FieldSpec(type = ClassSpec(className = NameSpec("int"))),
                    FieldSpec(type = ClassSpec(className = NameSpec("java.lang.String"))),
                ),
                fieldCount = 5..5,
                methods = listOf(MethodSpec(usingStrings = listOf(StringSpec("", NameMatch.Equals)))),
            ),
            packages = listOf("com.tencent.mm.ui"),
        )
    }
    private val classMenuItemWrapper by dexClassQuery {
        uniqueClass(
            ClassSpec(
                fields = listOf(
                    FieldSpec(type = ClassSpec(className = NameSpec("boolean"))),
                    FieldSpec(type = ClassSpec(className = NameSpec(classMenuItemData.data.name))),
                ),
            ),
            packages = listOf("com.tencent.mm.ui"),
        )
    }

    // adapter 只有在菜单构建时才能拿到，所以 getView 的 Hook 没法在 onEnable 里注册；
    // 这里按 Method 去重，避免每打开一次菜单就往 getView 上再叠一层 Hook
    // (那会让每次 getView 都反复安装/卸载 N 个全局的 ImageView.setImageResource Hook)
    private val hookedGetViewMethods = ConcurrentHashMap.newKeySet<Method>()

    private fun hookAdapterGetViewOnce(baseAdapter: BaseAdapter) {
        val getView = baseAdapter.reflekt().firstMethod {
            name = "getView"
        }
        if (!hookedGetViewMethods.add(getView.self)) return

        var unhook: HookHandle? = null

        getView.hookBefore {
            unhook = ImageView::class.reflekt().firstMethod {
                name = "setImageResource"
            }.hookBeforeDirectly {
                val fakeResId = args[0] as Int
                val imageView = thisObject as ImageView
                imageView.setImageDrawable(fakeResIdToResMap[fakeResId] ?: return@hookBeforeDirectly)
                result = null
            }
        }

        getView.hookAfter {
            unhook?.unhook()
            unhook = null
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

            hookAdapterGetViewOnce(baseAdapter)

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

                        postOnUiThread {
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

            postOnUiThread {
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

    override fun onDisable() {
        hookedGetViewMethods.clear()
    }
}
