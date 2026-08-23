package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.collections.LruCache
import java.lang.reflect.InvocationTargetException

object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "自动语音转文字"
    override val nameRes = R.string.feature_auto_speech_to_text_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_speech_to_text_description

    private val processedMessages = LruCache<Long, Boolean>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.typeCode != MessageType.VOICE.code) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
        val apiMan = chattingContext.reflekt()
            .firstField {
                type = WeServiceApi.apiManagerClass
            }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
        val chatViewItem = api.reflekt()
            .firstMethod {
                parameters(Long::class)
                returnType { clazz ->
                    clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems")
                }
            }
            .invoke(id)

        if (chatViewItem.toString() != "NoTransform") return

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }

        if (WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) as Boolean) return
        try {
            api.reflekt()
                .firstMethod {
                    parameters(
                        WeMessageApi.classMsgInfo.clazz,
                        Boolean::class,
                        Int::class,
                        Int::class
                    )
                    returnType = Void::class.javaPrimitiveType
                }
                .invoke(msgInfo.instance, false, -1, 0)
        } catch (_: InvocationTargetException) {
            // WeChat throws `java.lang.NullPointerException: getImgPath(...) must not be null`,
            // but that's not what we should care about and doesn't affect functionality
        }
    }
}
