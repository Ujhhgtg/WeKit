package dev.ujhhgtg.wekit.hooks.api.ui

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.isSubclassOf
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.model.MessageInfo
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@SuppressLint("StaticFieldLeak")
@HookItem(path = "API/聊天界面消息菜单扩展", description = "为聊天界面消息长按菜单提供添加菜单项功能")
object WeChatMessageContextMenuApi : ApiHookItem(), IResolvesDex {

    interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: () -> Drawable,
        val shouldShow: (MessageInfo) -> Boolean,
        val onClick: (View, Any, MessageInfo) -> Unit /* 2: ChattingContext */
    )

    private val TAG = This.Class.simpleName

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
    private lateinit var currentView: View

    override fun onEnable() {
        methodCreateMenu.hookBefore {
            val menu = args[0]

            currentView = args[1] as View
            val tag = currentView.tag

            val msgInfo = WeMessageApi.getMsgInfoInstanceFromTag(tag)

            try {
                for (item in menuItems.values.flatten()) {
                    if (item.shouldShow(MessageInfo(msgInfo))) {
                        menu.asResolver()
                            .firstMethod {
                                parameters(Int::class, CharSequence::class, Drawable::class)
                                returnType = android.view.MenuItem::class
                            }
                            .invoke(item.id, item.text, item.drawable())
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

        methodSelectMenuItem.hookBefore {
            val thisObj = thisObject
            val viewOnLongClickListener = thisObj.asResolver()
                .firstField {
                    type {
                        it isSubclassOf View.OnLongClickListener::class
                    }
                }
                .get() as View.OnLongClickListener
            val chattingContext = viewOnLongClickListener.asResolver()
                .firstField {
                    type = WeMessageApi.classChattingContext.clazz
                    superclass()
                }
                .get()!!

            val tag = currentView.tag
            val msgInfo = WeMessageApi.getMsgInfoInstanceFromTag(tag)

            val menuItem = args[0] as android.view.MenuItem
            val msgInfoWrapper = MessageInfo(msgInfo)
            try {
                for (item in menuItems.values.flatten()) {
                    if (item.id == menuItem.itemId) {
                        item.onClick(
                            currentView,
                            chattingContext,
                            msgInfoWrapper
                        )
                        result = null
                        return@hookBefore
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

    override fun resolveDex(dexKit: DexKitBridge) {
        methodCreateMenu.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
            }
        }

        methodSelectMenuItem.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings("MicroMsg.ChattingItem", "context item select failed, null dataTag")
            }
        }

        classChattingMessBox.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                usingEqStrings(
                    "MicroMsg.ChattingUI.FootComponent",
                    "onNotifyChange event %s talker %s"
                )
            }
        }
    }
}
