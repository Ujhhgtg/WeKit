package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Exposure_plus_1
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.ExposurePlus1Icon
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object RepeatMessages : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "消息复读"
    override val nameRes = R.string.feature_repeat_messages_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_repeat_messages_description

    private val TAG = RepeatMessages::class.java.simpleName

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    @Suppress("DEPRECATION")
    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT,
        MessageType.QUOTE,
        MessageType.APP,
        MessageType.IMAGE,
        MessageType.VOICE,
        MessageType.MICRO_VIDEO,
        MessageType.STICKER,
        MessageType.SO_GOU_EMOJI
    )

    fun isSupported(msgInfo: MessageInfo): Boolean = msgInfo.type in SUPPORTED_MSG_TYPES

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777008, localizedChatString(R.string.chat_repeat_menu), ExposurePlus1Icon, MaterialSymbols.Outlined.Exposure_plus_1,
                isSupported = ::isSupported,
                onClick = { view, _, msgInfo ->
                    val context = view.context

                    CoroutineScope(Dispatchers.IO).launch {
                        val sent = repeatMessage(msgInfo)
                        if (!sent) showToastSuspend(context, context.localizedChatString(R.string.chat_repeat_failed))
                    }
                }
            )
        )
    }

    @Suppress("DEPRECATION")
    fun repeatMessage(msgInfo: MessageInfo): Boolean {
        return runCatching {
            when (msgInfo.type) {
                MessageType.TEXT -> WeMessageApi.sendText(msgInfo.talker, msgInfo.actualContent)
                MessageType.IMAGE -> repeatImage(msgInfo)
                MessageType.VOICE -> repeatVoice(msgInfo)
                MessageType.VIDEO, MessageType.MICRO_VIDEO -> repeatVideo(msgInfo)
                MessageType.STICKER, MessageType.SO_GOU_EMOJI -> repeatEmoji(msgInfo)
                MessageType.APP -> WeMessageApi.sendXmlAppMsg(msgInfo.talker, msgInfo.actualContent)
                MessageType.QUOTE -> {
                    val quote = msgInfo.toQuoteMessage()!!
                    val content = quote.title
                    WeLogger.i(
                        TAG,
                        "repeat quote: destination=${msgInfo.talker}, messageId=${msgInfo.id}, " +
                            "quotedMsgSvrId=${quote.svrid}, contentLength=${content.length}",
                    )
                    val sent = WeMessageApi.sendQuoteText(msgInfo.talker, quote.svrid, content)
                    WeLogger.i(TAG, "repeat quote result: accepted=$sent")
                    sent
                }
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
        val md5 = msgInfo.stickerMd5 ?: return false
        return WeMessageApi.sendEmojiByMd5(msgInfo.talker, md5)
    }
}
