package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.PopupWindow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.MergeChatMessageContextMenuItems
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.ExtensionIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier as JavaModifier

object WeChatMessageContextMenuApi : ApiFeature(), IResolveDex {

    override val technicalId = "聊天界面消息菜单扩展"
    override val nameRes = R.string.feature_we_chat_message_context_menu_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_chat_message_context_menu_api_description

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    // declares how a MenuItem behaves in the multi-select (多选) message menu
    sealed interface MultiSelectSupport {
        // auto-compat: shouldShow is evaluated against every selected message; if all pass, the
        // item's onClick is looped over each selected message individually
        data object Auto : MultiSelectSupport

        // the item is hidden from the multi-select menu entirely (only makes sense for a single message)
        data object Unsupported : MultiSelectSupport

        // the provider natively handles the whole selection at once
        class Adapted(
            val isSupported: (List<MessageInfo>) -> Boolean,
            val onClick: (View, ChattingContext, List<MessageInfo>) -> Unit
        ) : MultiSelectSupport
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val imageVector: ImageVector,
        val isSupported: (MessageInfo) -> Boolean,
        // multi-select behavior; defaults to Auto so existing single-message actions loop transparently
        val multiSelect: MultiSelectSupport = MultiSelectSupport.Auto,
        val onClick: (View, ChattingContext, MessageInfo) -> Unit
    )

    // for clearer semantics; this simply compiles to Object in JVM bytecode
    @JvmInline
    value class ChattingContext(val instance: Any) {
        val activity: Activity
            get() = instance.reflekt()
                .firstMethod {
                    returnType = Activity::class
                }.invoke()!! as Activity
    }

    private const val TAG = "WeChatMessageContextMenuApi"

    // id of the single merged entry shown when MergeChatMessageContextMenuItems is enabled
    private const val MERGED_MENU_ITEM_ID = 777000

    private val providers = mutableSetOf<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        providers += provider
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        providers -= provider
    }

    private fun currentMenuItems(): List<MenuItem> =
        providers.flatMap(IMenuItemsProvider::getMenuItems)

    internal val methodCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
        }
    }
    private val methodSelectMenuItem by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings("MicroMsg.ChattingItem", "context item select failed, null dataTag")
        }
    }

    private val methodMultiCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            name = "onCreateMMMenu"
            addInvoke {
                declaredClass = "com.tencent.wework.api.WWAPIFactory"
                usingEqStrings("com.tencent.mm", "com.tencent.wemeet.app")
            }
        }
    }
    private val methodMultiSelectMenuItem by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            name = "onMMMenuItemSelected"
            usingEqStrings("MicroMsg.ChattingForwardMultiMsgLogic", "sendMultiMsg fromTalker:")
        }
    }

    // 只弱引用被长按的聊天条目 View：菜单被返回键取消时不会触发选中回调，
    // 强引用会一路把 View → parent → ChattingUI 留到进程结束
    private var currentViewRef: WeakReference<View>? = null

    // 用返回键/点外部关掉菜单时不会走选中回调，所以还要在菜单关闭时把记录清掉。
    // 建菜单的对象持有长按监听器，长按监听器上有一个声明类型正好是
    // PopupWindow.OnDismissListener 的字段，所有关闭路径最终都汇聚到它的 onDismiss()。
    // 这是 Method 级别的 Hook，覆盖全部实例，因此只注册一次。
    private var menuDismissHooked = false

    private fun hookMenuDismissOnce(menuBuilder: Any) {
        if (menuDismissHooked) return

        val longClickListener = menuBuilder.reflekt()
            .firstFieldOrNull {
                type {
                    it isSubclassOf View.OnLongClickListener::class
                }
            }
            ?.get() ?: return
        val dismissListener = longClickListener.reflekt()
            .firstFieldOrNull { type = PopupWindow.OnDismissListener::class }
            ?.get() ?: return

        // 先置位，解析失败时也不要每次建菜单都重试并刷日志
        menuDismissHooked = true

        dismissListener.javaClass.reflekt()
            .firstMethod { name = "onDismiss"; parameterCount = 0 }
            .hookAfter {
                currentViewRef = null
            }
    }

    private fun getLongClickListenerFromOnSelectHandler(thisObject: Any): View.OnLongClickListener {
        return thisObject.reflekt()
            .firstField {
                type {
                    it isSubclassOf View.OnLongClickListener::class
                }
            }
            .get() as View.OnLongClickListener
    }

    private fun getChattingContextFromLongClickListener(
        viewOnLongClickListener: View.OnLongClickListener
    ): ChattingContext {
        val ctx = viewOnLongClickListener.reflekt()
            .firstField {
                type = WeMessageApi.classChattingContext.clazz
                superclass()
            }
            .get()!!
        return ChattingContext(ctx)
    }

    private fun finishMenuSelection(viewOnLongClickListener: View.OnLongClickListener) {
        val cleanupFields = viewOnLongClickListener.reflekt().fields {
            type { type ->
                !type.isInterface &&
                    JavaModifier.isAbstract(type.modifiers) &&
                    type.declaredMethods.count { JavaModifier.isAbstract(it.modifiers) } == 1 &&
                    type.declaredMethods.single { JavaModifier.isAbstract(it.modifiers) }.let {
                        it.parameterCount == 0 && it.returnType == Void.TYPE
                    }
            }
        }
        check(cleanupFields.size == 2) {
            "expected two message menu cleanup callbacks, found ${cleanupFields.size}"
        }
        for (field in cleanupFields) {
            val callback = field.get() ?: continue
            callback.reflekt().firstMethod {
                parameterCount = 0
                returnType = Void.TYPE
            }.invoke()
        }
    }

    // the multi-select handler (q4) has no OnLongClickListener; instead it reaches the
    // ChattingContext (k45.c) through nested component references (q4 -> o4 -> d4 -> f196570d).
    // walk reference fields breadth-first to find the first ChattingContext instance, staying
    // resilient to obfuscated field/class names across WeChat versions.
    private fun resolveChattingContextByWalk(root: Any): ChattingContext {
        val target = WeMessageApi.classChattingContext.clazz
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Any>()
        queue.add(root)
        visited.add(root)

        var depth = 0
        while (queue.isNotEmpty() && depth < 8) {
            repeat(queue.size) {
                val current = queue.removeFirst()
                var clazz: Class<*>? = current.javaClass
                while (clazz != null && clazz != Any::class.java) {
                    for (field in clazz.declaredFields) {
                        val type = field.type
                        // skip primitives, arrays and framework types that can't hold the context
                        if (type.isPrimitive || type.isArray) continue
                        val pkg = type.name
                        if (pkg.startsWith("java.") || pkg.startsWith("android.") ||
                            pkg.startsWith("kotlin.")
                        ) continue

                        val value = runCatching {
                            field.isAccessible = true
                            field.get(current)
                        }.getOrNull() ?: continue

                        if (target.isInstance(value)) {
                            return ChattingContext(value)
                        }
                        if (visited.add(value)) {
                            queue.add(value)
                        }
                    }
                    clazz = clazz.superclass
                }
            }
            depth++
        }
        error("could not resolve ChattingContext from multi-select handler")
    }

    @Suppress("UNCHECKED_CAST")
    private fun getSelectedMsgInfos(thisObject: Any): List<MessageInfo> {
        val list = thisObject.reflekt().firstField {
            type = List::class.java
        }.get()!! as List<*>
        return list.filterNotNull().map { MessageInfo(it) }
    }

    private fun getViewFromMultiSelectHandler(thisObject: Any): View {
        return thisObject.reflekt().firstField {
            type {
                it isSubclassOf View::class
            }
        }.get() as View
    }

    override fun onEnable() {
        methodCreateMenu.hookBefore {
            val menu = args[0]!!

            val curView = args[1] as View
            val tag = curView.tag
            currentViewRef = WeakReference(curView)

            val msgInfo = WeMessageApi.getMsgInfoFromTag(tag)
            val msgInfoWrapper = MessageInfo(msgInfo)

            try {
                val addMenuItem = menu.reflekt()
                    .firstMethod {
                        parameters(Int::class, CharSequence::class, Drawable::class)
                        returnType = android.view.MenuItem::class
                    }

                val applicableItems = currentMenuItems()
                    .filter { it.isSupported(msgInfoWrapper) }

                if (MergeChatMessageContextMenuItems.isEnabled) {
                    // collapse everything into a single "WeKit" entry backed by a Compose dialog
                    if (applicableItems.isNotEmpty()) {
                        addMenuItem.invoke(MERGED_MENU_ITEM_ID, "WeKit", ExtensionIcon)
                    }
                } else {
                    for (item in applicableItems) {
                        addMenuItem.invoke(item.id, "${item.text} [K]", item.drawable)
                    }
                }
            } catch (ex: Throwable) {
                WeLogger.e(
                    TAG,
                    "exception occurred threw while providing menu items",
                    ex
                )
            }

            // 放在最后，即使解析失败也不会影响菜单项注入
            try {
                hookMenuDismissOnce(thisObject!!)
            } catch (ex: Throwable) {
                WeLogger.e(TAG, "failed to hook context menu dismiss", ex)
            }
        }

        methodSelectMenuItem.hookBefore {
            // 没有配对的建菜单回调(或 View 已被回收)时直接放行，交回微信自己处理
            val curView = currentViewRef?.get()
            currentViewRef = null
            if (curView == null) {
                WeLogger.w(TAG, "menu item selected without a recorded chat item view, ignoring")
                return@hookBefore
            }
            val tag = curView.tag
            val msgInfo = WeMessageApi.getMsgInfoFromTag(tag)

            val menuItem = args[0] as android.view.MenuItem
            val msgInfoWrapper = MessageInfo(msgInfo)
            val longClickListener = getLongClickListenerFromOnSelectHandler(thisObject!!)
            val context = getChattingContextFromLongClickListener(longClickListener)
            try {
                if (menuItem.itemId == MERGED_MENU_ITEM_ID) {
                    val applicableItems = currentMenuItems()
                        .filter { it.isSupported(msgInfoWrapper) }
                    showMergedMenuDialog(curView, context, msgInfoWrapper, applicableItems)
                    finishMenuSelection(longClickListener)
                    result = null
                    return@hookBefore
                }

                for (item in currentMenuItems()) {
                    if (item.id == menuItem.itemId) {
                        item.onClick(curView, context, msgInfoWrapper)
                        finishMenuSelection(longClickListener)
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

        methodMultiCreateMenu.hookBefore {
            val menu = args[0]!!

            try {
                // nothing to offer if no provider supports multi-select
                val hasMultiSelectItem = currentMenuItems()
                    .any { it.multiSelect !is MultiSelectSupport.Unsupported }
                if (!hasMultiSelectItem) return@hookBefore

                val addMenuItem = menu.reflekt()
                    .firstMethod {
                        parameters(Int::class, CharSequence::class, Drawable::class)
                        returnType = android.view.MenuItem::class
                    }

                // collapse everything into a single "WeKit" entry backed by a Compose dialog
                addMenuItem.invoke(MERGED_MENU_ITEM_ID, "WeKit", ExtensionIcon)
            } catch (ex: Throwable) {
                WeLogger.e(
                    TAG,
                    "exception occurred threw while providing merged menu item",
                    ex
                )
            }
        }

        methodMultiSelectMenuItem.hookBefore {
            val menuItem = args[0] as android.view.MenuItem
            // let WeChat handle its own multi-select actions (forward / delete / etc.)
            if (menuItem.itemId != MERGED_MENU_ITEM_ID) return@hookBefore

            try {
                val msgInfos = getSelectedMsgInfos(thisObject!!)
                val chattingContext = resolveChattingContextByWalk(thisObject!!)
                val view = getViewFromMultiSelectHandler(thisObject!!)

                showMultiSelectMenuDialog(view, chattingContext, msgInfos)
                result = null
            } catch (ex: Throwable) {
                WeLogger.e(
                    TAG,
                    "exception occurred while handling multi-select click event",
                    ex
                )
            }
        }
    }

    // shows every applicable provider item in one dialog; clicking a row dismisses the dialog
    // and dispatches to that item's onClick, mirroring a native menu selection
    private fun showMergedMenuDialog(
        view: View,
        chattingContext: ChattingContext,
        msgInfo: MessageInfo,
        items: List<MenuItem>
    ) {
        showComposeDialog(view.context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.app_name)) },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                    ) {
                        items(items) { item ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    try {
                                        item.onClick(view, chattingContext, msgInfo)
                                    } catch (ex: Throwable) {
                                        WeLogger.e(
                                            TAG,
                                            "exception occurred while handling click event",
                                            ex
                                        )
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = item.imageVector,
                                        contentDescription = item.text
                                    )
                                },
                                content = { Text(item.text) },
                            )
                        }
                    }
                },
                confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    // a single actionable row in the multi-select dialog, already resolved to a concrete action
    private class MultiSelectRow(
        val text: String,
        val imageVector: ImageVector,
        val onClick: () -> Unit
    )

    // shows applicable provider items for a multi-message selection, split into two sections:
    //   1. 已适配多选消息 — providers that natively handle the whole selection at once
    //   2. 自动兼容多选消息 — single-message providers whose action is applied to every message
    private fun showMultiSelectMenuDialog(
        view: View,
        chattingContext: ChattingContext,
        msgInfos: List<MessageInfo>
    ) {
        val allItems = currentMenuItems()

        val adaptedRows = allItems.mapNotNull { item ->
            val support = item.multiSelect as? MultiSelectSupport.Adapted ?: return@mapNotNull null
            if (!support.isSupported(msgInfos)) return@mapNotNull null
            MultiSelectRow(item.text, item.imageVector) {
                support.onClick(view, chattingContext, msgInfos)
            }
        }

        val autoRows = allItems.mapNotNull { item ->
            if (item.multiSelect !is MultiSelectSupport.Auto) return@mapNotNull null
            if (msgInfos.isEmpty() || !msgInfos.all { item.isSupported(it) }) return@mapNotNull null
            MultiSelectRow(item.text, item.imageVector) {
                for (msgInfo in msgInfos) {
                    try {
                        item.onClick(view, chattingContext, msgInfo)
                    } catch (ex: Throwable) {
                        WeLogger.e(TAG, "exception while applying '${item.text}' to a message", ex)
                    }
                }
            }
        }

        if (adaptedRows.isEmpty() && autoRows.isEmpty()) {
            showToast(
                view.context,
                view.context.localizedChatString(R.string.noncompose_message_menu_no_actions),
            )
            return
        }

        showComposeDialog(view.context) {
            AlertDialogContent(
                title = {
                    Text(
                        pluralStringResource(
                            R.plurals.noncompose_message_menu_selected_title,
                            msgInfos.size,
                            msgInfos.size,
                        ),
                    )
                },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                    ) {
                        if (adaptedRows.isNotEmpty()) {
                            item {
                                MultiSelectSectionHeader(
                                    stringResource(R.string.noncompose_message_menu_adapted_section),
                                )
                            }
                            items(adaptedRows) { row ->
                                MultiSelectMenuRow(row) { onDismiss() }
                            }
                        }
                        if (autoRows.isNotEmpty()) {
                            item {
                                MultiSelectSectionHeader(
                                    stringResource(R.string.noncompose_message_menu_automatic_section),
                                )
                            }
                            items(autoRows) { row ->
                                MultiSelectMenuRow(row) { onDismiss() }
                            }
                        }
                    }
                },
                confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    @Composable
    private fun MultiSelectSectionHeader(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    @Composable
    private fun MultiSelectMenuRow(row: MultiSelectRow, onDismiss: () -> Unit) {
        ListItem(
            modifier = Modifier.clickable {
                onDismiss()
                try {
                    row.onClick()
                } catch (ex: Throwable) {
                    WeLogger.e(TAG, "exception occurred while handling multi-select click", ex)
                }
            },
            leadingContent = {
                Icon(imageVector = row.imageVector, contentDescription = row.text)
            },
            content = { Text(row.text) },
        )
    }

    override fun onDisable() {
        currentViewRef = null
        // Hook 已被 unhookAll 撤销，重新启用时需要允许再次注册
        menuDismissHooked = false
    }
}
