package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.utils.android.getSystemService

@Feature(name = "一键撤回并重新编辑", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可快捷撤回消息并将文本内容加入输入框")
object QuickRevokeAndEdit : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777016, "编辑", EditIcon, MaterialSymbols.Outlined.Edit,
                isSupported = {
                    it.type?.isText == true
                },
                // revokes then loads one message's text into the input box; single-message only
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                quickRevokeAndEdit(view.context, msgInfo)
            }
        )
    }

    fun quickRevokeAndEdit(context: Context, msgInfo: MessageInfo) {
        val chatFooter = WeCurrentConversationApi.chatFooter ?: return
        WeMessageApi.revokeMsg(msgInfo)
        if (msgInfo.type == MessageType.QUOTE) {
            chatFooter.lastText = msgInfo.quoteMsgActualContent ?: ""
            WeMessageApi.setReferringMessage(chatFooter,
                WeMessageApi.getMsgInfoInstanceByMsgSvrId(msgInfo.toQuoteMessage()!!.svrid, msgInfo.talker))
        } else {
            chatFooter.lastText = msgInfo.actualContent
        }

        chatFooter.setMode(1)
        val toSendEt = chatFooter.reflekt().invokeMethod("getToSendEt")!!

        val etView = toSendEt.reflekt().firstMethod {
            returnType = View::class
        }.invoke()!! as View

        etView.requestFocus()
        val im = context.getSystemService<InputMethodManager>()
        etView.post {
            im.showSoftInput(etView, 0)
        }
    }
}
