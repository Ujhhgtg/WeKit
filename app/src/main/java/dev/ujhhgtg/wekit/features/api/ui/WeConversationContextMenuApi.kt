package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import android.widget.AdapterView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString

object WeConversationContextMenuApi : ApiFeature(), IResolveDex {

    override val technicalId = "对话菜单增强扩展"
    override val nameRes = R.string.feature_we_conversation_context_menu_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_conversation_context_menu_api_description

    private const val TAG = "WeConversationContextMenuApi"

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    private val menuItemProviders = mutableSetOf<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItemProviders += provider
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItemProviders -= provider
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

    private fun handleCreateMenu(param: HookParam) {
        val menu = param.args.getOrNull(0) as? ContextMenu ?: return
        val info = param.args.getOrNull(2) as? AdapterView.AdapterContextMenuInfo
        val groupId = info?.position ?: 0

        val context = resolveContext(param.thisObject!!) ?: return

        for (item in menuItemProviders.flatMap { it.getMenuItems() }) {
            try {
                if (!item.shouldShow(context, item.id)) continue
                menu.add(groupId, item.id, 0, item.text).icon = item.drawable
            } catch (e: Throwable) {
                WeLogger.e(TAG, "shouldShow/add callback failed", e)
            }
        }
    }

    private fun handleSelectMenu(param: HookParam) {
        val menuItem = param.args.getOrNull(0) as? android.view.MenuItem ?: return
        val clickedId = menuItem.itemId

        // thisObject is ConversationLongClickListener$1, which holds the outer
        // ConversationLongClickListener instance in its only field of that type.
        val listener = param.thisObject!!.reflekt()
            .firstFieldOrNull { type = methodOnCreateMenu.method.declaringClass }
            ?.get() ?: return

        val context = resolveContext(listener) ?: return

        for (item in menuItemProviders.flatMap { it.getMenuItems() }) {
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
