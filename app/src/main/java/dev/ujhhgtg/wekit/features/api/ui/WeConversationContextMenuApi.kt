package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import android.widget.AdapterView
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString

@Feature(
    name = "对话菜单增强扩展",
    categories = ["API"],
    description = "为主页对话列表长按菜单提供添加菜单项功能"
)
object WeConversationContextMenuApi : ApiFeature(), IResolveDex {

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
        val shouldShow: (context: ConversationContext, itemId: Int) -> Boolean,
        val onClick: (context: ConversationContext) -> Unit
    )

    data class ConversationContext(
        val activity: Activity,
        /** 对话所属用户名 (talker)，如 `wxid_xxx` 或 `xxx@chatroom` */
        val talker: String,
        /** 对话存储对象 (com.tencent.mm.storage.l4)，可能为空 */
        val conversation: Any?
    )

    // com.tencent.mm.ui.conversation.ConversationLongClickListener#onCreateContextMenu
    private val methodOnCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingStrings(
                "MicroMsg.ConversationLongClickListener",
                "onCreateContextMenu, contact is null"
            )
        }
    }

    // com.tencent.mm.ui.conversation.ConversationLongClickListener$1#onMMMenuItemSelected
    private val methodOnItemSelected by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingStrings(
                "yuanbao_set_top",
                "yuanbao_set_read",
                "yuanbao_set_no_display"
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
        val menu = param.args.getOrNull(0) as? ContextMenu ?: return
        val info = param.args.getOrNull(2) as? AdapterView.AdapterContextMenuInfo
        val groupId = info?.position ?: 0

        val context = resolveContext(param.thisObject) ?: return

        for (item in menuItems.values.flatten()) {
            try {
                if (!item.shouldShow(context, item.id)) continue
                menu.add(groupId, item.id, 0, item.text).icon = item.drawable
            } catch (e: Throwable) {
                WeLogger.e(TAG, "shouldShow/add callback failed", e)
            }
        }
    }

    private fun handleSelectMenu(param: XC_MethodHook.MethodHookParam) {
        val menuItem = param.args.getOrNull(0) as? android.view.MenuItem ?: return
        val clickedId = menuItem.itemId

        // thisObject is ConversationLongClickListener$1, which holds the outer
        // ConversationLongClickListener instance in its only field of that type.
        val listener = param.thisObject.reflekt()
            .firstFieldOrNull { type = methodOnCreateMenu.method.declaringClass }
            ?.get() ?: return

        val context = resolveContext(listener) ?: return

        for (item in menuItems.values.flatten()) {
            try {
                if (item.id == clickedId) {
                    item.onClick(context)
                    return
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "onSelect callback failed", e)
            }
        }
    }

    /** Extracts the [ConversationContext] from a ConversationLongClickListener instance. */
    private fun resolveContext(listener: Any): ConversationContext? {
        return listener.reflekt().run {
            val activity = firstFieldOrNull { type = Activity::class }?.get() as? Activity
                ?: return null

            val talker = firstFieldOrNull {
                type = BString
                modifiers { !it.contains(Modifiers.FINAL) }
            }?.get() as? String ?: ""

            val conversation = firstFieldOrNull {
                type { it.name.startsWith("com.tencent.mm.storage") }
            }?.get()

            ConversationContext(activity, talker, conversation)
        }
    }
}
