package moe.ouom.wekit.hooks.sdk.ui

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.MenuItem
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/聊天右键菜单增强")
class WeChatChatContextMenuApi : ApiHookItem(), IDexFind {

    private val dexMethodOnCreateMenu by dexMethod()
    private val dexMethodOnItemSelected by dexMethod()

    companion object {
        private const val TAG = "ChatMenuApi"
        private lateinit var thisView: View
        private lateinit var rawMessage: Map<String, Any?>
        val onCreateCallbacks = CopyOnWriteArrayList<OnCreateListener>()
        val onSelectCallbacks = CopyOnWriteArrayList<OnSelectListener>()

        fun addOnCreateListener(listener: OnCreateListener) {
            onCreateCallbacks.add(listener)
        }

        fun removeOnCreateListener(listener: OnCreateListener) {
            onCreateCallbacks.remove(listener)
        }

        fun addOnSelectListener(listener: OnSelectListener) {
            onSelectCallbacks.add(listener)
        }

        fun removeOnSelectListener(listener: OnSelectListener) {
            onSelectCallbacks.remove(listener)
        }
    }

    /**
     * 接口：创建菜单后触发
     */
    fun interface OnCreateListener {
        fun onCreated(messageInfo: Map<String, Any?>): MenuInfoItem?
    }

    /**
     * 接口：选中菜单时触发
     */
    fun interface OnSelectListener {
        fun onSelected(id: Int, messageInfo: Map<String, Any?>, view: View): Boolean
    }

    data class MenuInfoItem(val id: Int, val title: String, val iconDrawable: Drawable)

    @SuppressLint("NonUniqueDexKitData")
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        dexMethodOnCreateMenu.find(dexKit, allowMultiple = false, descriptors = descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingStrings(listOf("MicroMsg.ChattingItem", "msg is null!"), StringMatchType.Equals, false)
            }
        }

        dexMethodOnItemSelected.find(dexKit, allowMultiple = false, descriptors = descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingStrings(
                    "MicroMsg.ChattingItem", "context item select failed, null dataTag"
                )
            }
        }

        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        // Hook OnCreate
        hookAfter(dexMethodOnCreateMenu.method) { param ->
            handleCreateMenu(param)
        }

        // Hook OnSelected
        hookAfter(dexMethodOnItemSelected.method) { param ->
            handleSelectMenu(param)
        }
    }

    private fun getRawMessage(item: Any): Map<String, Any?> = runCatching {
        // 可能会变更
        val messageTagClass = item::class.java.superclass
        val messageHolderClass = messageTagClass.superclass
        val messageField = messageHolderClass.getField("a")
        val messageObject = messageField.get(item)
        val messageImplClass = messageObject::class.java
        val messageWrapperClass = messageImplClass.superclass
        val databaseMappingClass = messageWrapperClass.superclass
        val databaseFields = databaseMappingClass.declaredFields.filter { field -> field.name.startsWith("field_") }
            .onEach { field -> field.isAccessible = true }.associate { field ->
                field.name to field.get(messageObject)
            }

        databaseFields
    }.onFailure { WeLogger.e(TAG, "获取rawMessage失败: ${it.message}") }.getOrDefault(emptyMap())

    private fun handleCreateMenu(param: XC_MethodHook.MethodHookParam) {
        try {
            val chatContextMenu = param.args[0]
            val addMethod = chatContextMenu::class.java.declaredMethods.first {
                it.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.java,
                        CharSequence::class.java,
                        Drawable::class.java
                    )
                ) && it.returnType == MenuItem::class.java
            }

            thisView = param.args[1] as View
            val item = thisView.tag
            /*
            val messageTagClass = item::class.java.superclass
            val positionMethod = messageTagClass.declaredMethods.first {
                it.returnType === Int::class.java
            }
            val position = positionMethod.invoke(item)
            */

            rawMessage = getRawMessage(item)
            for (listener in onCreateCallbacks) {
                val item = listener.onCreated(rawMessage)
                try {
                    item?.let { addMethod.invoke(chatContextMenu, it.id, it.title, it.iconDrawable) }
                } catch (e: Exception) {
                    WeLogger.e(TAG, "添加条目失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "handleCreateMenu 失败", e)
        }
    }

    private fun handleSelectMenu(param: XC_MethodHook.MethodHookParam) {
        try {
            val menuItem = param.args[0] as MenuItem
            val id = menuItem.itemId
            for (listener in onSelectCallbacks) {
                try {
                    val handled = listener.onSelected(id, rawMessage, thisView)
                    if (handled) {
                        WeLogger.d(TAG, "菜单项已被动态回调处理")
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "OnSelect 回调执行异常", e)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "handleSelectMenu 失败", e)
        }
    }
}