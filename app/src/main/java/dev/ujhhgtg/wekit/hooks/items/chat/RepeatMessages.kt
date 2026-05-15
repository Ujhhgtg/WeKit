package dev.ujhhgtg.wekit.hooks.items.chat

import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageType
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.utils.ExposurePlus1Icon
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.removeWxIdPrefix

@Suppress("DEPRECATION")
@HookItem(path = "聊天/消息复读", description = "复读一些简单的消息")
object RepeatMessages : SwitchHookItem(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT, MessageType.QUOTE, MessageType.APP
    )

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777008, "复读", ExposurePlus1Icon,
                shouldShow = { it.type in SUPPORTED_MSG_TYPES },
                onClick = { view, _, msgInfo ->
                    val context = view.context

                    when (msgInfo.type) {
                        MessageType.TEXT -> {
                            WeMessageApi.sendText(msgInfo.talker, msgInfo.actualContent)
                            showToast(context, "已发送")
                        }

                        MessageType.QUOTE -> {
                            val quoteMsg = msgInfo.toQuoteMessage() ?: return@MenuItem

                            var text = quoteMsg.title
                            if (msgInfo.isInGroupChat) {
                                text = text.removeWxIdPrefix()
                            }

                            WeMessageApi.sendText(msgInfo.talker, text)
                            showToast(context, "已发送")
                        }

                        MessageType.APP -> {
                            WeMessageApi.sendXmlAppMsg(msgInfo.talker, msgInfo.actualContent)
                            showToast(context, "已发送")
                        }

                        // FIXME
//                        MessageType.IMAGE -> {
//                            val md5 = WeServiceApi.getImageMsgInfoMd5(msgInfo)
//                            WeMessageApi.sendImageByMd5(msgInfo.talker, md5, null)
//                            showToast(view.context, "已发送")
//                        }

                        else -> {}
                    }
                }
            )
        )
    }
}
