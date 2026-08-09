package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.View
import androidx.compose.material3.Text
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.FavInfoProto
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.void
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

@Feature(
    id = "转发收藏语音",
    nameRes = "feature_forward_favorite_voices_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_forward_favorite_voices_description",
)
object ForwardFavoriteVoices : SwitchFeature() {

    private data class FavoriteVoice(
        val filePath: String,
        val durationMs: Int
    )

    @OptIn(ExperimentalSerializationApi::class)
    override fun onEnable() {
        "com.tencent.mm.plugin.fav.ui.FavSelectUI".toClass().reflekt().firstMethod { name = "onItemClick" }.hookBefore {
            val view = args[1] as View

            val tag = view.tag

            val a = tag.reflekt().firstField { name = "a"; superclass() }.get()!!

            val voice = getFavoriteVoice(a) ?: return@hookBefore

            val ctx = thisObject as Activity

            showComposeDialog(ctx) {
                AlertDialogContent(
                    title = { Text("转发收藏语音") },
                    text = {
                        Text(
                            "确定发送以下文件?\n" +
                                    voice.filePath
                        )
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                    confirmButton = {
                        TextButton({
                            copyToClipboard(ctx, voice.filePath)
                            showToast(ctx, "已复制")
                        }) { Text("复制路径") }
                        Button({
                            WeMessageApi.sendVoice(
                                WeCurrentConversationApi.value,
                                voice.filePath,
                                voice.durationMs
                            )
                            showToast(ctx, "已发送")
                            onDismiss()
                            getTopMostActivity()?.finish()
                        }) { Text("确定") }
                    })
            }

            result = null
        }

        val favIndexClass = "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI".toClass()
        favIndexClass.reflekt().apply {
            firstMethod {
                modifiers(Modifiers.STATIC)
                returnType = bool
                parameters { args ->
                    args.size in 4..5 &&
                            args[0] == List::class.java &&
                            args[1] == Context::class.java &&
                            args[2] == DialogInterface.OnClickListener::class.java &&
                            args.drop(3).all { it == bool }
                }
            }.hookBefore {
                val context = args[1] as Context
                if (!favIndexClass.isInstance(context)) return@hookBefore

                val favorite = (args[0] as List<*>).singleOrNull() ?: return@hookBefore
                if (getFavoriteVoice(favorite) != null) {
                    result = true
                }
            }

            firstMethod {
                parameters(List::class.java, BString, BString, bool)
                returnType = void
            }.hookBefore {
                val favorite = (args[0] as List<*>).singleOrNull() ?: return@hookBefore
                val voice = getFavoriteVoice(favorite) ?: return@hookBefore
                val recipients = (args[2] as String)
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                if (recipients.isEmpty()) return@hookBefore

                // WeChat filters type 3 into an empty send list and shows "所选内容不可转发".
                result = null
                val customText = args[1] as? String
                var successCount = 0
                recipients.forEach { wxId ->
                    if (WeMessageApi.sendVoice(wxId, voice.filePath, voice.durationMs)) {
                        successCount++
                    }
                    if (!customText.isNullOrBlank()) {
                        WeMessageApi.sendText(wxId, customText)
                    }
                }
                val context = thisObject as Context
                showToast(
                    context,
                    when (successCount) {
                        recipients.size if recipients.size == 1 -> "已发送"
                        recipients.size -> "已转发到 ${recipients.size} 个对象"
                        else -> "已转发 $successCount/${recipients.size} 个对象"
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun getFavoriteVoice(favorite: Any): FavoriteVoice? {
        val type = favorite.reflekt()
            .firstField { name = "field_type"; superclass() }
            .get() as Int
        if (type != 3) return null

        val favProto = favorite.reflekt()
            .firstField { name = "field_favProto"; superclass() }
            .get()!!
        val bytes = favProto.reflekt()
            .firstMethod { name = "getData"; superclass() }
            .invoke() as ByteArray
        val voiceInfo = ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes).voiceInfo
        val cacheName = voiceInfo.fileCacheName
        val bucketId = cacheName.hashCode() and 0xFF
        val cacheDir = RuntimeConfig.userDataDir / "favorite" / bucketId.toString()

        return FavoriteVoice(
            filePath = (cacheDir / "$cacheName.${voiceInfo.fileCacheType}").absolutePathString(),
            durationMs = voiceInfo.duration
        )
    }
}
