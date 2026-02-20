package moe.ouom.wekit.hooks.item.chat.msg

import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeChatChatContextMenuApi
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.util.common.ModuleRes
import moe.ouom.wekit.util.log.WeLogger

@HookItem(
    path = "聊天与消息/修改消息显示",
    desc = "修改本地消息显示内容"
)
class ModifyMessageDisplayHook : BaseSwitchFunctionHookItem() {

    companion object {
        private const val TAG = "ModifyMessageDisplayHook"
        private const val PREF_ID = 322424
    }

    private val onCreateMenuCallback = WeChatChatContextMenuApi.OnCreateListener { messageInfo ->
        val type = messageInfo["field_type"] as? Int ?: 0
        if (!MsgType.isText(type)) {
            return@OnCreateListener null
        }

        WeChatChatContextMenuApi.MenuInfoItem(
            id = PREF_ID,
            title = "修改信息",
            iconDrawable =  ModuleRes.getDrawable("edit_24px")
        )
    }

    private val onSelectMenuCallback = WeChatChatContextMenuApi.OnSelectListener { id, messageInfo, view ->
        if (id != PREF_ID) return@OnSelectListener false
        val context = view.context ?: return@OnSelectListener false
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
        MaterialDialog(wrappedContext).show {
            title(text = "修改消息")
            input(
                hint = "输入要修改的消息，仅限娱乐。",
                waitForPositiveButton = false,
            )
            positiveButton(text = "确定") { dialog ->
                val inputText = dialog.getInputField().text?.toString() ?: ""
                val setTextMethod = view.javaClass.declaredMethods.first {
                    it.parameterTypes.contentEquals(
                        arrayOf(
                            CharSequence::class.java,
                        )
                    )
                }
                setTextMethod.invoke(view, inputText)
                dialog.dismiss()
            }

            negativeButton(text = "取消")
        }
        return@OnSelectListener true
    }

    override fun entry(classLoader: ClassLoader) {
        try {
            WeChatChatContextMenuApi.addOnCreateListener(onCreateMenuCallback)
            WeChatChatContextMenuApi.addOnSelectListener(onSelectMenuCallback)
            WeLogger.i(TAG, "修改消息显示 Hook 注册成功")
        } catch (e: Exception) {
            WeLogger.e(TAG, "注册失败: ${e.message}", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {
        WeChatChatContextMenuApi.removeOnCreateListener(onCreateMenuCallback)
        WeChatChatContextMenuApi.removeOnSelectListener(onSelectMenuCallback)
        super.unload(classLoader)
    }
}