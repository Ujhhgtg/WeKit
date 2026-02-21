package moe.ouom.wekit.hooks.sdk.ui

import android.util.SparseArray
import androidx.core.util.size
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/首页菜单服务", desc = "提供向首页右上角菜单添加菜单项的能力")
object WeHomePagePopupMenuApi : ApiHookItem(), IDexFind {

    interface IMenuItemsProvider {
        fun getMenuItems(hookParam: XC_MethodHook.MethodHookParam, msgInfoBean: Any): List<MenuItem>
    }

    data class MenuItem(val resourceId: Int,
                        val text: String, val drawableResourceId: Int,
                        val onClickListener: () -> Unit)

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        if (!providers.contains(provider)) {
            providers.add(provider)
            WeLogger.i(TAG, "provider added, current handler count: ${providers.size}")
        } else {
            WeLogger.w(TAG, "provider already exists, ignored")
        }
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        val removed = providers.remove(provider)
        WeLogger.i(TAG, "provider remove ${if (removed) "succeeded" else "failed"}, current listener count: ${providers.size}")
    }

    private const val TAG = "WeHomePagePopupMenuApi"

    private val methodAddItem by dexMethod()
    private val methodHandleItemClick by dexMethod()

    override fun entry(classLoader: ClassLoader) {
        methodAddItem.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val thisObj = param.thisObject
                    val sparseArray = thisObj.asResolver()
                        .firstField {
                            type = SparseArray::class
                        }
                        .get() as SparseArray<*>?
                    WeLogger.d(TAG, "sparseArray size: ${sparseArray?.size ?: "null"}")
                }
            }
        }

        methodHandleItemClick.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val thisObj = param.thisObject
                    val sparseArray = thisObj.asResolver()
                        .firstField {
                            type = SparseArray::class
                        }
                        .get() as SparseArray<*>?
                    WeLogger.d(TAG, "sparseArray size: ${sparseArray?.size ?: "null"}")
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodAddItem.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                usingEqStrings("MicroMsg.PlusSubMenuHelper", "dyna plus config is null, we use default one")
            }
        }

        methodHandleItemClick.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
            }
        }

        return descriptors
    }
}