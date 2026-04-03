package dev.ujhhgtg.wekit.hooks.api.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@HookItem(path = "API/朋友圈菜单增强扩展", desc = "为朋友圈消息长按菜单提供添加菜单项功能")
object WeMomentsContextMenuApi : ApiHookItem(), IResolvesDex {

    private val TAG = nameOf(WeMomentsContextMenuApi)

    interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    private val menuItems = mutableMapOf<String, List<MenuItem>>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider.javaClass.name] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider.javaClass.name)
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: () -> Drawable,
        val shouldShow: (context: MomentsContext, itemId: Int) -> Boolean,
        val onClick: (context: MomentsContext) -> Unit
    )

    data class MomentsContext(
        val activity: Activity,
        val snsInfo: Any?,
        val timeLineObject: Any?
    )

    private val methodOnCreateMenu by dexMethod()
    private val methodOnItemSelected by dexMethod()
    private val methodSnsInfoStorage by dexMethod()
    private val methodGetSnsInfoStorage by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodOnCreateMenu.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.sns.ui.listener")
            matcher {
                usingStrings(
                    "MicroMsg.TimelineOnCreateContextMenuListener",
                    "onMMCreateContextMenu error"
                )
            }
        }

        methodOnItemSelected.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.sns.ui.listener")
            matcher {
                usingStrings(
                    "delete comment fail!!! snsInfo is null",
                    "send photo fail, mediaObj is null",
                    "mediaObj is null, send failed!"
                )
            }
        }

        methodSnsInfoStorage.find(dexKit) {
            matcher {
                paramCount(1)
                paramTypes("java.lang.String")
                usingStrings(
                    "getByLocalId",
                    "com.tencent.mm.plugin.sns.storage.SnsInfoStorage"
                )
                returnType("com.tencent.mm.plugin.sns.storage.SnsInfo")
            }
        }

        methodGetSnsInfoStorage.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.sns.model")
            matcher {
                modifiers = Modifier.STATIC
                returnType(methodSnsInfoStorage.method.declaringClass)
                paramCount(0)
                usingStrings(
                    "com.tencent.mm.plugin.sns.model.SnsCore",
                    "getSnsInfoStorage"
                )
            }
        }
    }

    override fun onEnable() {
        methodOnCreateMenu.method.hookAfter { param ->
            handleCreateMenu(param)
        }

        methodOnItemSelected.method.hookAfter { param ->
            handleSelectMenu(param)
        }
    }

    private fun handleCreateMenu(param: XC_MethodHook.MethodHookParam) {
        val menu = param.args.getOrNull(0) as? ContextMenu? ?: return
        for (item in menuItems.values.flatten()) {
            menu.asResolver()
                .firstMethod {
                    parameters(Int::class, CharSequence::class, Drawable::class)
                }
                .invoke(item.id, item.text, item.drawable())
        }
    }

    private fun handleSelectMenu(param: XC_MethodHook.MethodHookParam) {
        val menuItem = param.args.getOrNull(0) as? android.view.MenuItem ?: return
        val hookedObject = param.thisObject
        val fields = hookedObject.javaClass.declaredFields

        val activity = fields.firstOrNull { it.type == Activity::class.java }
            ?.apply { isAccessible = true }?.get(hookedObject) as Activity

        val timeLineObject = fields.firstOrNull {
            it.type.name == "com.tencent.mm.protocal.protobuf.TimeLineObject"
        }?.apply { isAccessible = true }?.get(hookedObject)

        val snsID = fields.firstOrNull {
            it.type == String::class.java && !Modifier.isFinal(it.modifiers)
        }?.apply { isAccessible = true }?.get(hookedObject) as String
        val targetMethod = methodSnsInfoStorage.method
        val instance = methodGetSnsInfoStorage.method.invoke(null)
        val snsInfo = targetMethod.invoke(instance, snsID)

        val context = MomentsContext(activity, snsInfo, timeLineObject)
        val clickedId = menuItem.itemId

        for (item in menuItems.values.flatten()) {
            try {
                if (item.id == clickedId) {
                    item.onClick(context)
                }
                param.result = null
                return
            } catch (e: Throwable) {
                WeLogger.e(TAG, "OnSelect 回调执行异常", e)
            }
        }
    }
}
