package dev.ujhhgtg.wekit.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Forward
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.utils.ForwardIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ForwardMessages : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "转发消息"
    override val nameRes = R.string.feature_forward_messages_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_forward_messages_description

    private const val TAG = "ForwardMessages"

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
                localizedChatString(R.string.chat_forward_menu),
                ForwardIcon,
                MaterialSymbols.Outlined.Forward,
                isSupported = { true },
                // forward every selected message to every chosen contact
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { true },
                    onClick = { view, _, msgInfos ->
                        showForwardDialog(view) { selectedWxIds ->
                            forwardMessages(msgInfos, selectedWxIds)
                        }
                    }
                )
            ) { view, _, msgInfo ->
                showForwardDialog(view) { selectedWxIds ->
                    forwardMessages(listOf(msgInfo), selectedWxIds)
                }
            }
        )
    }

    // shows the contacts picker once; invokes onConfirm with the chosen wxIds (non-empty)
    private fun showForwardDialog(view: android.view.View, onConfirm: (Set<String>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

            withContext(Dispatchers.Main) {
                showComposeDialog(view.context) {
                    ContactsSelector(
                        title = localizedChatString(R.string.chat_forward_select_recipients),
                        contacts = contacts,
                        initialSelectedWxIds = emptySet(),
                        onDismiss = onDismiss,
                        onConfirm = { selectedWxIds ->
                            if (selectedWxIds.isEmpty()) {
                                showToast(localizedChatString(R.string.chat_forward_select_at_least_one))
                                return@ContactsSelector
                            }

                            onDismiss()
                            onConfirm(selectedWxIds)
                        }
                    )
                }
            }
        }
    }

    private fun forwardMessages(msgInfos: List<MessageInfo>, wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            val total = msgInfos.size * wxIds.size
            showToastSuspend(
                localizedChatQuantity(
                    R.plurals.chat_forwarding_messages,
                    msgInfos.size,
                    msgInfos.size,
                    wxIds.size,
                ),
            )

            var success = 0
            wxIds.forEach { wxId ->
                msgInfos.forEach { msgInfo ->
                    if (sendTo(wxId, msgInfo)) success++
                }
            }

            showToastSuspend(
                if (success == total) localizedChatQuantity(R.plurals.chat_forwarded_to_recipients, wxIds.size, wxIds.size)
                else localizedChatQuantity(R.plurals.chat_forwarded_partial_messages, total, success, total)
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
                    showToast(localizedChatString(R.string.chat_forward_untested_type_warning))
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
        val durationMs = XmlUtils.extractXmlAttr(msgInfo.content, "voicelength").toInt()
        return WeMessageApi.sendVoice(toUser, voicePath, durationMs)
    }

    private fun forwardVideo(toUser: String, msgInfo: MessageInfo): Boolean {
        val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
        return WeMessageApi.sendVideo(toUser, mp4Path)
    }

    private fun forwardEmoji(toUser: String, msgInfo: MessageInfo): Boolean {
        val md5 = msgInfo.stickerMd5 ?: return false
        return WeMessageApi.sendEmojiByMd5(toUser, md5)
    }
}
