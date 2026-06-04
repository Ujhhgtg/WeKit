package dev.ujhhgtg.wekit.hooks.items.chat

import android.app.Activity
import android.view.View
import androidx.compose.material3.Text
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.coerceToInt
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

@HookItem(path = "聊天/转发收藏语音", description = "在聊天菜单的「收藏」中允许转发语音")
object ForwardFavoriteVoices : SwitchHookItem() {

    @OptIn(ExperimentalSerializationApi::class)
    override fun onEnable() {
        "com.tencent.mm.plugin.fav.ui.FavSelectUI".toClass().asResolver().firstMethod { name = "onItemClick" }
        .hookBefore {
            val view = args[1] as View
            val tag = view.tag

            val a = tag.asResolver().firstField { name = "a"; superclass() }.get()!!
            val type = a.asResolver().firstField { name = "field_type"; superclass() }.get()!! as Int
            if (type != 3) return@hookBefore

            val favPhoto = a.asResolver().firstField { name = "field_favProto"; superclass() }.get()!!
            val bytes = favPhoto.asResolver().firstMethod { name = "getData"; superclass() }.invoke()!! as ByteArray

            val favInfo = ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes)
            val voiceInfo = favInfo.voiceInfo

            var voiceFilePath = voiceInfo.filePath
            if (voiceFilePath == null) {
                val baseStorageDir = RuntimeConfig.userDataDir
                val cacheName = voiceInfo.fileCacheName
                val bucketId = cacheName.hashCode() and 0xFF

                voiceFilePath = (baseStorageDir / "favorite" / bucketId.toString() / "$cacheName.${voiceInfo.fileCacheType}").absolutePathString()
            }

            val ctx = thisObject as Activity

            showComposeDialog(ctx) {
                AlertDialogContent(title = { Text("转发收藏语音") },
                    text = {
                        Text("确定发送以下文件?\n" +
                                voiceFilePath)
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                    confirmButton = {
                        TextButton({
                            copyToClipboard(ctx, voiceFilePath)
                        }) { Text("复制") }
                        Button({
                            WeMessageApi.sendVoice(ChatInputBarEnhancements.currentConv, voiceFilePath, AudioUtils.getDurationMs(voiceFilePath).coerceToInt())
                            showToast(ctx, "已发送")
                            onDismiss()
                            getTopMostActivity()?.finish()
                        }) { Text("确定") }
                    })
            }

            result = null
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FavInfoProto(
    @ProtoNumber(1)
    val chatInfo: ChatInfoProto,

    @ProtoNumber(2)
    val voiceInfo: VoiceInfoProto
) {

    @Serializable
    data class ChatInfoProto(
        @ProtoNumber(2)
        val senderId: String
    )

    @Serializable
    data class VoiceInfoProto(
        @ProtoNumber(10)
        val duration: Int,

        @ProtoNumber(16)
        val fileCacheType: String,

        @ProtoNumber(17)
        val md5Checksum: String,

        @ProtoNumber(19)
        val fileSize: Int,

        @ProtoNumber(20)
        val fileCacheName: String,

        @ProtoNumber(21)
        val filePath: String? = null
    )
}
