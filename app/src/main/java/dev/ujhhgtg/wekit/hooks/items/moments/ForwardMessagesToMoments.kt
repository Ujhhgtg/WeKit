package dev.ujhhgtg.wekit.hooks.items.moments

import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.hooks.api.core.WeServiceApi
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageType
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.utils.CameraIcon
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.removeWxIdPrefix

@Suppress("DEPRECATION")
@HookItem(path = "朋友圈/消息转圈", description = "将一些简单的消息转发到朋友圈")
object ForwardMessagesToMoments : SwitchHookItem(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT, MessageType.QUOTE, MessageType.IMAGE, MessageType.VIDEO
    )

    private const val MOMENTS_CLASS = "${PackageNames.WECHAT}.plugin.sns.ui.SnsUploadUI"

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777009, "转圈", CameraIcon,
                shouldShow = { it.type in SUPPORTED_MSG_TYPES },
                onClick = { _, chattingContext, msgInfo ->
                    val activity = chattingContext.activity

                    when (msgInfo.type) {
                        MessageType.TEXT -> {
                            activity.startActivity(Intent {
                                setClassName(activity, MOMENTS_CLASS)
                                putExtra("Ksnsupload_type", 9)
                                putExtra("Kdescription", msgInfo.actualContent)
                            })
                        }

                        MessageType.QUOTE -> {
                            val quoteMsg = msgInfo.toQuoteMessage() ?: return@MenuItem

                            var text = quoteMsg.title
                            if (msgInfo.isInGroupChat) {
                                text = text.removeWxIdPrefix()
                            }

                            activity.startActivity(Intent {
                                setClassName(activity, MOMENTS_CLASS)
                                putExtra("Ksnsupload_type", 9)
                                putExtra("Kdescription", text)
                            })
                        }

                        MessageType.IMAGE -> {
                            activity.startActivity(Intent {
                                setClassName(activity, MOMENTS_CLASS)
                                putStringArrayListExtra(
                                    "sns_kemdia_path_list", arrayListOf(WeServiceApi.getImageMd5FromMsgInfo(msgInfo))
                                )
                                putExtra("Kdescription", "")
                            })
                        }

                        MessageType.VIDEO -> {
                            val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
                            activity.startActivity(Intent {
                                setClassName(activity, MOMENTS_CLASS)
                                putExtra("Ksnsupload_type", 14)
                                putExtra("KSightPath", mp4Path)
                                putExtra("KSightThumbPath", mp4Path)
                                putExtra("Kdescription", "")
                            })
                        }

                        else -> {}
                    }
                }
            )
        )
    }
}
