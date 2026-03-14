package moe.ouom.wekit.hooks.sdk.ui

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeMessageApi
import moe.ouom.wekit.hooks.sdk.base.model.MessageInfo
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@SuppressLint("StaticFieldLeak")
@HookItem(path = "API/聊天界面消息菜单扩展", desc = "为聊天界面消息长按菜单提供添加菜单项功能")
object WeChatMessageContextMenuApi : ApiHookItem(), IDexFind {

    interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Lazy<Drawable>,
        val shouldShow: (MessageInfo) -> Boolean,
        val onClick: (View, Any, MessageInfo) -> Unit /* 2: ChattingContext */
    )

    private const val TAG: String = "WeChatMessageContextMenuApi"

    private val menuItems = mutableMapOf<String, List<MenuItem>>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider.javaClass.name] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider.javaClass.name)
    }

    private val methodCreateMenu by dexMethod()
    private val methodSelectMenuItem by dexMethod()
    private val classChattingMessBox by dexClass()
    private var currentView: View? =
        null // selectMenu is guaranteed to be called after createMenu, so this will not cause NPE

    override fun onLoad() {
        methodCreateMenu.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val menu = param.args[0]

                    currentView = param.args[1] as View
                    val tag = currentView!!.tag

                    val msgInfo = tag.asResolver()
                        .firstMethod {
                            returnType = WeMessageApi.classMsgInfo.clazz
                            parameterCount(0)
                            superclass()
                        }
                        .invoke()!!

                    try {
                        for (item in menuItems.values.flatten()) {
                            if (item.shouldShow(MessageInfo(msgInfo))) {
                                menu.asResolver()
                                    .firstMethod {
                                        parameters(Int::class, CharSequence::class, Drawable::class)
                                        returnType = android.view.MenuItem::class
                                    }
                                    .invoke(item.id, item.text, item.drawable.value)
                            }
                        }
                    } catch (ex: Throwable) {
                        WeLogger.e(
                            TAG,
                            "exception occurred threw while providing menu items",
                            ex
                        )
                    }
                }
            }
        }

        methodSelectMenuItem.toDexMethod {
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
                            type = WeMessageApi.classChattingContext.clazz
                            superclass()
                        }
                        .get()!!
//                    val apiManager = chattingContext.asResolver()
//                        .firstField {
//                            type = WeServiceApi.methodApiManagerGetApi.method.declaringClass
//                        }
//                        .get()!!
//                    val api = WeServiceApi.methodApiManagerGetApi.method.invoke(
//                        apiManager,
//                        classChattingMessBox.clazz.interfaces[0]
//                    )
//                    val chattingContext2 = api.asResolver()
//                        .firstField {
//                            type = WeMessageApi.classChattingContext.clazz
//                            superclass()
//                        }
//                        .get()!!
//                    val apiManager2 = chattingContext2.asResolver()
//                        .firstField {
//                            type = WeServiceApi.methodApiManagerGetApi.method.declaringClass
//                        }
//                        .get()!!
//                    val api2 = WeServiceApi.methodApiManagerGetApi.method.invoke(
//                        apiManager2,
//                        WeMessageApi.classChattingDataAdapter.clazz.interfaces[0]
//                    )
                    val tag = currentView!!.tag

                    val msgInfo = tag.asResolver()
                        .firstMethod {
                            returnType = WeMessageApi.classMsgInfo.clazz
                            parameterCount(0)
                            superclass()
                        }
                        .invoke()!!

                    val menuItem = param.args[0] as android.view.MenuItem
//                    val msgInfo = api2.asResolver()
//                        .firstMethod {
//                            name = "getItem"
//                        }
//                        .invoke(menuItem.groupId)!!
                    val msgInfoWrapper = MessageInfo(msgInfo)
                    try {
                        for (item in menuItems.values.flatten()) {
                            if (item.id == menuItem.itemId) {
                                item.onClick(
                                    currentView!!,
                                    chattingContext,
                                    msgInfoWrapper
                                )
                                param.result = null
                                return@beforeIfEnabled
                            }
                        }
                    } catch (ex: Throwable) {
                        WeLogger.e(
                            TAG,
                            "exception occurred while handling click event",
                            ex
                        )
                    }
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodCreateMenu.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
            }
        }

        methodSelectMenuItem.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings("MicroMsg.ChattingItem", "context item select failed, null dataTag")
            }
        }

        classChattingMessBox.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                usingEqStrings(
                    "MicroMsg.ChattingUI.FootComponent",
                    "onNotifyChange event %s talker %s"
                )
            }
        }

        return descriptors
    }
}