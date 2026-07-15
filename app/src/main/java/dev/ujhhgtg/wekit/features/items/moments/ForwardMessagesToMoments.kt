package dev.ujhhgtg.wekit.features.items.moments

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Camera
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.CameraIcon

@Suppress("DEPRECATION")
@Feature(name = "消息转圈", categories = ["朋友圈"], description = "将一些简单的消息转发到朋友圈")
object ForwardMessagesToMoments : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT, MessageType.QUOTE, MessageType.IMAGE, MessageType.VIDEO
    )

    // TEXT and QUOTE both boil down to a piece of text for a Moments post
    private fun MessageInfo.isTextLike() = type == MessageType.TEXT || type == MessageType.QUOTE

    private fun MessageInfo.momentsText(): String =
        if (type == MessageType.QUOTE) quoteMsgActualContent!! else actualContent

    // a selection maps to a valid Moments post if it is one of:
    //   - any number of texts (concatenated)
    //   - any number of texts + any number of images
    //   - any number of texts + exactly one video
    //   - multiple images (already covered by the text+images case with zero texts)
    private fun isSupportedSelection(msgInfos: List<MessageInfo>): Boolean {
        if (msgInfos.isEmpty()) return false
        // only text/image/video participate; quotes count as text
        if (msgInfos.any { it.type != MessageType.IMAGE && it.type != MessageType.VIDEO && !it.isTextLike() }) {
            return false
        }

        val imageCount = msgInfos.count { it.type == MessageType.IMAGE }
        val videoCount = msgInfos.count { it.type == MessageType.VIDEO }

        // video can't be combined with images and at most one video is allowed
        if (videoCount > 1) return false
        if (videoCount == 1 && imageCount > 0) return false

        return true
    }

    private fun forwardSelectionToMoments(activity: android.app.Activity, msgInfos: List<MessageInfo>) {
        val text = msgInfos.filter { it.isTextLike() }
            .joinToString("\n\n") { it.momentsText() }
            .takeIf { it.isNotBlank() }

        val imageMd5s = msgInfos.filter { it.type == MessageType.IMAGE }
            .map { WeServiceApi.getImageMd5FromMsgInfo(it) }
        val video = msgInfos.firstOrNull { it.type == MessageType.VIDEO }

        when {
            video != null -> {
                val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(video)
                WeMomentsApi.postVideoInUi(activity, mp4Path, mp4Path, text)
            }

            imageMd5s.isNotEmpty() -> {
                WeMomentsApi.postImagesInUi(activity, imageMd5s, text)
            }

            else -> {
                WeMomentsApi.postTextInUi(activity, text ?: "")
            }
        }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777009, "转圈", CameraIcon, MaterialSymbols.Outlined.Camera,
                isSupported = { it.type in SUPPORTED_MSG_TYPES },
                // Moments can post: pure text, text + images, text + a single video, or multiple
                // images. video can't be mixed with images and only one video is allowed. multiple
                // texts are brute-force concatenated with blank lines.
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgInfos -> isSupportedSelection(msgInfos) },
                    onClick = { _, chattingContext, msgInfos ->
                        forwardSelectionToMoments(chattingContext.activity, msgInfos)
                    }
                ),
                onClick = { _, chattingContext, msgInfo ->
                    val activity = chattingContext.activity

                    when (msgInfo.type) {
                        MessageType.TEXT -> {
                            WeMomentsApi.postTextInUi(activity, msgInfo.actualContent)
                        }

                        MessageType.QUOTE -> {
                            WeMomentsApi.postTextInUi(activity, msgInfo.quoteMsgActualContent!!)
                        }

                        MessageType.IMAGE -> {
                            WeMomentsApi.postImagesInUi(activity, listOf(WeServiceApi.getImageMd5FromMsgInfo(msgInfo)))
                        }

                        MessageType.VIDEO -> {
                            val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
                            WeMomentsApi.postVideoInUi(activity, mp4Path, mp4Path)
                        }

                        else -> {}
                    }
                }
            )
        )
    }
}
