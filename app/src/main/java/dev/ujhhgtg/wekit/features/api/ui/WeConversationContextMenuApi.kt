package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import android.view.View
import android.widget.AdapterView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import java.lang.reflect.Proxy

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

    /** Which long-press menus an item joins. */
    enum class MenuScope {
        /** The homepage conversation list. */
        NORMAL,

        /**
         * Conversations inside a folder container (WeChat's conversation box, used by aggregation
         * folders).
         */
        AGGREGATED,
        ALL;

        fun includes(scope: MenuScope) = this == ALL || this == scope
    }

    data class MenuItem(
        val id: Int,
        val text: String,
        val drawable: Drawable?,
        val scope: MenuScope = MenuScope.NORMAL,
        val shouldShow: (context: ConversationContext, itemId: Int) -> Boolean,
        val onClick: (context: ConversationContext) -> Unit,
    )

    data class ConversationContext(
        val activity: Activity,
        /** 对话所属用户名 (talker)，如 `wxid_xxx` 或 `xxx@chatroom` */
        val talker: String,
        /** 对话存储对象 (com.tencent.mm.storage.l4)，可能为空 */
        val conversation: Any?,
        /** Where the menu was opened: [MenuScope.NORMAL] or [MenuScope.AGGREGATED]. */
        val scope: MenuScope = MenuScope.NORMAL,
        /** Id of the folder container for [MenuScope.AGGREGATED] menus, from its intent extras. */
        val folderId: String? = null,
    )

    /** Intent extras a folder container (ConvBoxServiceConversationUI) may carry its id in. */
    private val CONTAINER_ID_EXTRAS =
        listOf("Contact_User", "Contact_ChatRoomId", "room_name", "Chat_User")

    // Order pushes our items after the container's own items (which use 0).
    private const val AGGREGATED_MENU_ORDER = 1000

    // com.tencent.mm.ui.conversation.ConversationLongClickListener#onCreateContextMenu
    private val methodOnCreateMenu by
        dexMethod(
            sqlMethod {
                searchPackages("com.tencent.mm.ui.conversation")
                usingStrings(
                    "MicroMsg.ConversationLongClickListener",
                    "onCreateContextMenu, contact is null",
                )
            },
        )

    // com.tencent.mm.ui.conversation.ConversationLongClickListener$1#onMMMenuItemSelected
    private val methodOnItemSelected by
        dexMethod(
            sqlMethod {
                searchPackages("com.tencent.mm.ui.conversation")
                usingStrings("yuanbao_set_top", "yuanbao_set_read", "yuanbao_set_no_display")
            },
        )

    // com.tencent.mm.ui.widget.menu.MMPopupMenu#showMenu(view, pos, id, onCreateListener, selectCb,
    // x, y)
    // The folder container (ConvBoxServiceConversationUI) doesn't use
    // ConversationLongClickListener;
    // its long-press menu goes through this shared popup, so AGGREGATED items are injected here.
    private val methodShowPopupMenu by
        dexMethod(
            sqlMethod {
                returnType = "void"
                paramTypes(
                    "android.view.View",
                    "int",
                    "long",
                    "android.view.View\$OnCreateContextMenuListener",
                    null,
                    "int",
                    "int",
                )
                declaredClass {
                    usingStrings("MicroMsg.MMPopupMenu")
                }
            },
            allowFailure = true,
        )

    private fun items(scope: MenuScope) =
        menuItemProviders.flatMap { it.getMenuItems() }.filter { it.scope.includes(scope) }

    override fun onEnable() {
        methodOnCreateMenu.method.hookAfter {
            handleCreateMenu(this)
        }

        methodOnItemSelected.method.hookAfter {
            handleSelectMenu(this)
        }

        if (methodShowPopupMenu.isFound) {
            methodShowPopupMenu.hookBefore { handleAggregatedMenu(this) }
        }
    }

    /**
     * Wraps the popup's create listener and select callback when it belongs to a folder container.
     */
    private fun handleAggregatedMenu(param: HookParam) {
        val createListener = param.args[3] as? View.OnCreateContextMenuListener ?: return
        val originalSelect = param.args[4] ?: return
        val position = param.args[1] as Int
        val talker = extractContainerTalker(createListener, position) ?: return
        val activity = (param.args[0] as View).context.findActivity()
        val context =
            ConversationContext(
                activity,
                talker,
                null,
                MenuScope.AGGREGATED,
                CONTAINER_ID_EXTRAS.firstNotNullOfOrNull(activity.intent::getStringExtra),
            )
        val shown =
            items(MenuScope.AGGREGATED).filter { item ->
                runCatching { item.shouldShow(context, item.id) }
                    .onFailure { WeLogger.e(TAG, "shouldShow callback failed", it) }
                    .getOrDefault(false)
            }
        if (shown.isEmpty()) return

        param.args[3] = View.OnCreateContextMenuListener { menu, view, menuInfo ->
            createListener.onCreateContextMenu(menu, view, menuInfo)
            shown.forEach { item ->
                menu.add(0, item.id, AGGREGATED_MENU_ORDER, item.text).icon = item.drawable
            }
        }
        // The 5th parameter's declared type is the obfuscated select-callback interface (single
        // method onMMMenuItemSelected); proxy it to intercept our own items.
        val selectCallbackInterface = methodShowPopupMenu.method.parameterTypes[4]
        param.args[4] =
            Proxy.newProxyInstance(
                selectCallbackInterface.classLoader,
                arrayOf(selectCallbackInterface),
            ) { _, method, methodArgs ->
                val params = methodArgs ?: emptyArray()
                val clicked = (params.getOrNull(0) as? android.view.MenuItem)?.itemId
                val item = shown.firstOrNull { it.id == clicked }
                if (method.name == "onMMMenuItemSelected" && item != null) {
                    runCatching { item.onClick(context) }
                        .onFailure { WeLogger.e(TAG, "onSelect callback failed", it) }
                    null
                } else {
                    method.invoke(originalSelect, *params)
                }
            }
    }

    /**
     * Resolves the long-pressed conversation of a folder container from the menu-create listener:
     * createListener -> its OnItemLongClickListener -> the container fragment -> its list adapter
     * -> adapter.getItem(position) (an rconversation row) -> field_username. Null for other popups.
     */
    private fun extractContainerTalker(createListener: Any, position: Int): String? {
        val longClickListener =
            createListener
                .reflekt()
                .firstFieldOrNull {
                    type { it isSubclassOf AdapterView.OnItemLongClickListener::class }
                }
                ?.get() ?: return null

        val fragment =
            longClickListener
                .reflekt()
                .firstFieldOrNull { type { it.name.endsWith("ConvBoxServiceConversationFmUI") } }
                ?.get() ?: return null

        val adapter =
            fragment
                .reflekt()
                .firstFieldOrNull { type { it isSubclassOf android.widget.Adapter::class } }
                ?.get() as android.widget.Adapter

        if (position < 0 || position >= adapter.count) return null
        val conversation = adapter.getItem(position) ?: return null

        return conversation
            .reflekt()
            .firstField {
                name = "field_username"
                superclass()
            }
            .get() as String
    }

    private fun Context.findActivity(): Activity =
        generateSequence(this) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .first()

    private fun handleCreateMenu(param: HookParam) {
        val menu = param.args.getOrNull(0) as? ContextMenu ?: return
        val info = param.args.getOrNull(2) as? AdapterView.AdapterContextMenuInfo
        val groupId = info?.position ?: 0

        val context = resolveContext(param.thisObject!!) ?: return

        for (item in items(MenuScope.NORMAL)) {
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
        val listener =
            param.thisObject!!
                .reflekt()
                .firstFieldOrNull { type = methodOnCreateMenu.method.declaringClass }
                ?.get() ?: return

        val context = resolveContext(listener) ?: return

        for (item in items(MenuScope.NORMAL)) {
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
            val activity =
                firstFieldOrNull { type = Activity::class }?.get() as? Activity ?: return null

            val talker =
                firstFieldOrNull {
                    type = BString
                    modifiers { !it.contains(Modifiers.FINAL) }
                }
                    ?.get() as? String ?: ""

            val conversation = firstFieldOrNull {
                type { it.name.startsWith("com.tencent.mm.storage") }
            }
                ?.get()

            ConversationContext(activity, talker, conversation)
        }
    }
}
