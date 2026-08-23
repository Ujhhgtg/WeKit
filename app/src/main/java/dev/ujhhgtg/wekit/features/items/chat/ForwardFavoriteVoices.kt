package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.FavInfoProto
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
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

object ForwardFavoriteVoices : SwitchFeature() {

    override val technicalId = "转发收藏语音"
    override val nameRes = R.string.feature_forward_favorite_voices_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_forward_favorite_voices_description

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
                    title = { Text(stringResource(R.string.feature_forward_favorite_voices_name)) },
                    text = {
                        Text(
                            stringResource(R.string.chat_forward_favorite_voice_confirm, voice.filePath)
                        )
                    },
                    dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                    confirmButton = {
                        TextButton({
                            copyToClipboard(ctx, voice.filePath)
                            showToast(ctx, ctx.localizedChatString(R.string.chat_path_copied))
                        }) { Text(stringResource(R.string.chat_copy_path)) }
                        Button({
                            WeMessageApi.sendVoice(
                                WeCurrentConversationApi.value,
                                voice.filePath,
                                voice.durationMs
                            )
                            showToast(ctx, ctx.localizedChatString(R.string.chat_sent))
                            onDismiss()
                            getTopMostActivity()?.finish()
                        }) { Text(stringResource(R.string.dialog_confirm)) }
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
                        recipients.size if recipients.size == 1 -> context.localizedChatString(R.string.chat_sent)
                        recipients.size -> context.localizedChatQuantity(R.plurals.chat_forwarded_to_recipients, recipients.size, recipients.size)
                        else -> context.localizedChatQuantity(R.plurals.chat_forwarded_partial_recipients, recipients.size, successCount, recipients.size)
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
