package moe.ouom.wekit.hooks.sdk.ui

import android.content.Context
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeMessageApi
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/聊天界面消息长按菜单扩展", desc = "为聊天界面消息长按菜单提供自定义菜单项功能")
object WeChatMessageLongPressMenuApi : ApiHookItem(), IDexFind {

    interface IMenuItemsProvider {
        fun getMenuItems(hookParam: XC_MethodHook.MethodHookParam, msgInfoBean: Any): List<MenuItem>
    }

    data class MenuItem(val resourceId: Int,
                        val text: String, val drawableResourceId: Int,
                        val onClickListener: (Any, Any) -> Unit /* ChattingContext, MsgInfoBean */)

    private const val TAG: String = "WeChatMessageLongPressMenuApi"

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

    private val classChattingContext by dexClass()
    private val methodApiManagerGetApi by dexMethod()
    private val methodCreateMenu by dexMethod()
    private val methodSelectMenu by dexMethod()
    private val classChattingMessBox by dexClass()
    private val classChattingDataAdapter by dexClass()

    override fun entry(classLoader: ClassLoader) {
        methodCreateMenu.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val arg0 = param.args[0]
                    val context = arg0.asResolver()
                        .firstField {
                            type = Context::class
                        }
                        .get() as Context
                    val resources = context.resources
                    if (resources != null) {
                        WeLogger.d(TAG, "we should inject module assets here")
//                        resources.assets.asResolver()
//                            .firstMethod { name = "addAssetsPath" }
//                            .invoke(modulePath)
                    }

                    val view = param.args[1] as View
                    val tag = view.tag
                    val num = tag.asResolver()
                        .firstMethod {
                            returnType = Int::class
                            parameterCount(0)
                            superclass()
                        }
                        .invoke()

                    val msgInfoBean = tag.asResolver()
                        .firstMethod {
                            returnType = WeMessageApi.classMsgInfo.clazz
                            parameterCount(0)
                            superclass()
                        }
                        .invoke()!!
                    for (provider in providers) {
                        try {
                            for (item in provider.getMenuItems(param, msgInfoBean)) {
                                arg0.asResolver()
                                    .firstMethod {
                                        parameters(
                                            Int::class,
                                            Int::class,
                                            Int::class,
                                            CharSequence::class,
                                            Int::class
                                        )
                                        returnType = android.view.MenuItem::class
                                    }
                                    .invoke(num, item.resourceId, 0, item.text, item.drawableResourceId)
                                WeLogger.i(TAG, "added menu item ${item.text}")
                            }
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "provider threw an exception", e)
                        }
                    }
                }
            }
        }


        methodSelectMenu.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val thisObj = param.thisObject
                    val viewOnLongClickListener = thisObj.asResolver()
                        .firstField {
                            type {
                                View.OnLongClickListener::class.java.isAssignableFrom(it)
                            }
                        }
                        .get() as View.OnLongClickListener
                    val chattingContext = viewOnLongClickListener.asResolver()
                        .firstField {
                            type = classChattingContext.clazz
                            superclass()
                        }
                        .get()!!
                    val apiManager = chattingContext.asResolver()
                        .firstField {
                            type = methodApiManagerGetApi.method.declaringClass
                        }
                        .get()!!
                    val api = methodApiManagerGetApi.method.invoke(
                        apiManager,
                        classChattingMessBox.clazz.interfaces[0]
                    )
                    val chattingContext2 = api.asResolver()
                        .firstField {
                            type = classChattingContext.clazz
                            superclass()
                        }
                        .get()!!
                    val apiManager2 = chattingContext2.asResolver()
                        .firstField {
                            type = methodApiManagerGetApi.method.declaringClass
                        }
                        .get()!!
                    val api2 = methodApiManagerGetApi.method.invoke(
                        apiManager2,
                        classChattingDataAdapter.clazz.interfaces[0]
                    )

                    val menuItem = param.args[0] as android.view.MenuItem
                    val msgInfoBean = api2.asResolver()
                        .firstMethod {
                            name = "getItem"
                        }
                        .invoke(menuItem.groupId)!!
                    for (provider in providers) {
                        for (item in provider.getMenuItems(param, msgInfoBean)) {
                            if (item.resourceId == menuItem.itemId) {
                                try {
                                    item.onClickListener.invoke(
                                        chattingContext,
                                        msgInfoBean
                                    )
                                } catch (e: Throwable) {
                                    WeLogger.e(TAG, "onClickListener threw an exception", e)
                                }
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classChattingContext.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
            }
        }

        methodApiManagerGetApi.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.manager")
            matcher {
                usingEqStrings("[get] ", " is not a interface!")
            }
        }

        methodCreateMenu.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
            }
        }

        methodSelectMenu.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings("MicroMsg.ChattingItem", "context item select failed, null dataTag")
            }
        }

        classChattingMessBox.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                usingEqStrings("MicroMsg.ChattingUI.FootComponent", "onNotifyChange event %s talker %s")
            }
        }

        classChattingDataAdapter.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.ChattingDataAdapterV3", "[handleMsgChange] isLockNotify:")
            }
        }

        return descriptors
    }
}