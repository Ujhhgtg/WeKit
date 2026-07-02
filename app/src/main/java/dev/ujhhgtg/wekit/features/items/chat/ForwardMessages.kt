package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.utils.ForwardIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(
    name = "转发消息",
    categories = ["聊天"],
    description = "在消息长按菜单添加转发按钮, 可向好友或群聊批量转发"
)
object ForwardMessages : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = This.Class.simpleName

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777010,
                "转发 [K]",
                ForwardIcon,
                shouldShow = { true }
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

                    withContext(Dispatchers.Main) {
                        showComposeDialog(view.context) {
                            ContactsSelector(
                                title = "选择转发对象",
                                contacts = contacts,
                                initialSelectedWxIds = emptySet(),
                                onDismiss = onDismiss,
                                onConfirm = { selectedWxIds ->
                                    if (selectedWxIds.isEmpty()) {
                                        showToast("请选择至少一个联系人")
                                        return@ContactsSelector
                                    }

                                    onDismiss()
                                    forwardMessage(msgInfo, selectedWxIds)
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    private fun forwardMessage(msgInfo: MessageInfo, wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在转发到 ${wxIds.size} 个对象...")

            var success = 0
            wxIds.forEach { wxId ->
                if (sendTo(wxId, msgInfo)) success++
            }

            showToastSuspend(
                if (success == wxIds.size) "已转发到 ${wxIds.size} 个对象"
                else "已转发到 $success/${wxIds.size} 个对象"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun sendTo(toUser: String, msgInfo: MessageInfo): Boolean {
        return runCatching {
            when (msgInfo.type) {
                MessageType.TEXT -> WeMessageApi.sendText(toUser, msgInfo.actualContent)
                MessageType.IMAGE -> forwardImage(toUser, msgInfo)
                MessageType.VOICE -> forwardVoice(toUser, msgInfo)
                MessageType.VIDEO, MessageType.MICRO_VIDEO -> forwardVideo(toUser, msgInfo)
                MessageType.STICKER, MessageType.SO_GOU_EMOJI -> forwardEmoji(toUser, msgInfo)
                MessageType.APP -> WeMessageApi.sendXmlAppMsg(toUser, msgInfo.actualContent)
                MessageType.QUOTE -> WeMessageApi.sendText(toUser, msgInfo.quoteMsgActualContent!!)
                else -> {
                    showToast("警告: 该消息类型未经过测试, 回退为作为卡片消息发送, 可能失败!")
                    WeMessageApi.sendXmlAppMsg(toUser, msgInfo.actualContent)
                }
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to forward message to $toUser: type=${msgInfo.typeCode}", it)
            false
        }
    }

    private fun forwardImage(toUser: String, msgInfo: MessageInfo): Boolean {
        val md5 = WeServiceApi.getImageMd5FromMsgInfo(msgInfo)
        WeMessageApi.sendImageByMd5(toUser, md5, null)
        return true
    }

    private fun forwardVoice(toUser: String, msgInfo: MessageInfo): Boolean {
        val encPath = msgInfo.imagePath ?: return false
        val voicePath = WeMessageApi.getVoiceFullPath(encPath)
        val durationMs = AudioUtils.getDurationMs(voicePath).toInt()
        return WeMessageApi.sendVoice(toUser, voicePath, durationMs)
    }

    private fun forwardVideo(toUser: String, msgInfo: MessageInfo): Boolean {
        val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
        return WeMessageApi.sendVideo(toUser, mp4Path)
    }

    private fun forwardEmoji(toUser: String, msgInfo: MessageInfo): Boolean {
        val md5 = msgInfo.imagePath
            ?: XmlUtils.extractXmlAttr(msgInfo.content, "md5").takeIf { it.isNotBlank() }
            ?: XmlUtils.extractXmlTag(msgInfo.content, "md5").takeIf { it.isNotBlank() }
            ?: return false
        return WeMessageApi.sendEmojiByMd5(toUser, md5)
    }
}
