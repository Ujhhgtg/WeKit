package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.ExposurePlus1Icon
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "消息复读", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可复读一些常见消息")
object RepeatMessages : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = RepeatMessages::class.java.simpleName

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    @Suppress("DEPRECATION")
    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT, MessageType.QUOTE, MessageType.APP, MessageType.IMAGE, MessageType.VOICE, MessageType.MICRO_VIDEO, MessageType.STICKER, MessageType.SO_GOU_EMOJI
    )

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777008, "复读", ExposurePlus1Icon,
                shouldShow = { it.type in SUPPORTED_MSG_TYPES },
                onClick = { view, _, msgInfo ->
                    val context = view.context

                    CoroutineScope(Dispatchers.IO).launch {
                        val sent = repeatMessage(msgInfo)
                        showToastSuspend(context, if (sent) "已复读" else "复读失败!")
                    }
                }
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun repeatMessage(msgInfo: MessageInfo): Boolean {
        return runCatching {
            when (msgInfo.type) {
                MessageType.TEXT -> WeMessageApi.sendText(msgInfo.talker, msgInfo.actualContent)
                MessageType.IMAGE -> repeatImage(msgInfo)
                MessageType.VOICE -> repeatVoice(msgInfo)
                MessageType.VIDEO, MessageType.MICRO_VIDEO -> repeatVideo(msgInfo)
                MessageType.STICKER, MessageType.SO_GOU_EMOJI -> repeatEmoji(msgInfo)
                MessageType.APP -> WeMessageApi.sendXmlAppMsg(msgInfo.talker, msgInfo.actualContent)
                MessageType.QUOTE -> WeMessageApi.sendText(msgInfo.talker, msgInfo.quoteMsgActualContent!!)
                else -> false
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to repeat message: type=${msgInfo.typeCode}", it)
            false
        }
    }

    private fun repeatImage(msgInfo: MessageInfo): Boolean {
        val md5 = WeServiceApi.getImageMd5FromMsgInfo(msgInfo)
        WeMessageApi.sendImageByMd5(msgInfo.talker, md5, null)
        return true
    }

    private fun repeatVoice(msgInfo: MessageInfo): Boolean {
        val encPath = msgInfo.imagePath ?: return false
        val voicePath = WeMessageApi.getVoiceFullPath(encPath)
        val durationMs = AudioUtils.getDurationMs(voicePath).toInt()
        return WeMessageApi.sendVoice(msgInfo.talker, voicePath, durationMs)
    }

    private fun repeatVideo(msgInfo: MessageInfo): Boolean {
        val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
        return WeMessageApi.sendVideo(msgInfo.talker, mp4Path)
    }

    private fun repeatEmoji(msgInfo: MessageInfo): Boolean {
        val md5 = msgInfo.imagePath
            ?: XmlUtils.extractXmlAttr(msgInfo.content, "md5").takeIf { it.isNotBlank() }
            ?: XmlUtils.extractXmlTag(msgInfo.content, "md5").takeIf { it.isNotBlank() }
            ?: return false
        return WeMessageApi.sendEmojiByMd5(msgInfo.talker, md5)
    }
}
