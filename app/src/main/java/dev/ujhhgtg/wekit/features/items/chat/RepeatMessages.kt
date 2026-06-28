package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.utils.ExposurePlus1Icon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
@Feature(name = "消息复读", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可复读一些常见消息")
object RepeatMessages : ClickableFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = RepeatMessages::class.java.simpleName

    private var showResultToast by prefOption("repeat_messages_show_result_toast", true)

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("消息复读") },
                text = {
                    var showResultToastInput by remember { mutableStateOf(showResultToast) }

                    ListItem(
                        headlineContent = { Text("显示发送结果提示") },
                        supportingContent = { Text("复读后显示已发送或发送失败 Toast") },
                        trailingContent = {
                            Switch(checked = showResultToastInput, onCheckedChange = null)
                        },
                        modifier = Modifier.clickable {
                            showResultToastInput = !showResultToastInput
                            showResultToast = showResultToastInput
                        }
                    )
                }
            )
        }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777008, "复读", ExposurePlus1Icon,
                shouldShow = { it.isRepeatable },
                onClick = { view, _, msgInfo ->
                    val context = view.context

                    CoroutineScope(Dispatchers.IO).launch {
                        val sent = repeatMessage(msgInfo)
                        if (showResultToast) {
                            withContext(Dispatchers.Main) {
                                showToast(context, if (sent) "已发送" else "发送失败")
                            }
                        }
                    }
                }
            )
        )
    }

    private val MessageInfo.isRepeatable: Boolean
        get() = when (type) {
            MessageType.TEXT,
            MessageType.IMAGE,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.MICRO_VIDEO,
            MessageType.STICKER,
            MessageType.SO_GOU_EMOJI -> true

            else -> false
        }

    private fun repeatMessage(msgInfo: MessageInfo): Boolean {
        return runCatching {
            when (msgInfo.type) {
                MessageType.TEXT -> WeMessageApi.sendText(msgInfo.talker, msgInfo.actualContent)
                MessageType.IMAGE -> repeatImage(msgInfo)
                MessageType.VOICE -> repeatVoice(msgInfo)
                MessageType.VIDEO, MessageType.MICRO_VIDEO -> repeatVideo(msgInfo)
                MessageType.STICKER, MessageType.SO_GOU_EMOJI -> repeatEmoji(msgInfo)
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
