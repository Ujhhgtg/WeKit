package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import java.lang.reflect.Modifier

@Feature(name = "朋友圈菜单增强扩展", categories = ["API"], description = "为朋友圈消息长按菜单提供添加菜单项功能")
object WeMomentsContextMenuApi : ApiFeature(), IResolveDex {

    private val TAG = This.Class.simpleName

    fun interface IMenuItemsProvider {
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
        val text: String, val drawable: Drawable,
        val shouldShow: (context: MomentsContext, itemId: Int) -> Boolean,
        val onClick: (context: MomentsContext) -> Unit
    )

    data class MomentsContext(
        val activity: Activity,
        val snsInfo: Any?,
        val timelineObject: Any?
    )

    private val methodOnCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        matcher {
            usingStrings(
                "MicroMsg.TimelineOnCreateContextMenuListener",
                "onMMCreateContextMenu error"
            )
        }
    }
    private val methodOnItemSelected by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        matcher {
            usingStrings(
                "delete comment fail!!! snsInfo is null",
                "send photo fail, mediaObj is null",
                "mediaObj is null, send failed!"
            )
        }
    }
    private val methodSnsInfoStorage by dexMethod {
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
    private val methodGetSnsInfoStorage by dexMethod {
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

    override fun onEnable() {
        methodOnCreateMenu.method.hookAfter {
            handleCreateMenu(this)
        }

        methodOnItemSelected.method.hookAfter {
            handleSelectMenu(this)
        }
    }

    private fun handleCreateMenu(param: XC_MethodHook.MethodHookParam) {
        val menu = param.args.getOrNull(0) as? ContextMenu? ?: return
        for (item in menuItems.values.flatten()) {
            menu.reflekt()
                .firstMethod {
                    parameters(Int::class, CharSequence::class, Drawable::class)
                }
                .invoke(item.id, item.text, item.drawable)
        }
    }

    private fun handleSelectMenu(param: XC_MethodHook.MethodHookParam) {
        val menuItem = param.args.getOrNull(0) as? android.view.MenuItem ?: return

        param.thisObject.reflekt().apply {
            val activity = firstField {
                type = Activity::class
            }.get()!! as Activity

            val timeLineObject = firstFieldOrNull {
                type = "com.tencent.mm.protocal.protobuf.TimeLineObject"
            }?.get()

            val snsId = firstField {
                type = BString
                modifiers { !it.contains(Modifiers.FINAL) }
            }.get()!! as String

            val targetMethod = methodSnsInfoStorage.method
            val instance = methodGetSnsInfoStorage.method.invoke(null)
            val snsInfo = targetMethod.invoke(instance, snsId)

            val context = MomentsContext(activity, snsInfo, timeLineObject)
            val clickedId = menuItem.itemId

            for (item in menuItems.values.flatten()) {
                try {
                    if (item.id == clickedId) {
                        item.onClick(context)
                        param.result = null
                        return
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "onSelect callback failed", e)
                }
            }
        }
    }
}
