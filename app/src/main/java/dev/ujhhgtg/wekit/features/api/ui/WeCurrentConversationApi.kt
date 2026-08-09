package dev.ujhhgtg.wekit.features.api.ui

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import java.lang.ref.WeakReference

@Feature(
    id = "当前聊天服务",
    nameRes = "feature_we_current_conversation_api_name",
    categoryIds = [FeatureCategoryIds.API],
    descriptionRes = "feature_we_current_conversation_api_description",
)
object WeCurrentConversationApi : ApiFeature() {

    var value: String = ""

    val chatFooter: ChatFooter?
        get() = chatFooterRef?.get()

    private var chatFooterRef: WeakReference<ChatFooter>? = null

    override fun onEnable() {
        ChatFooter::class.reflekt()
            .firstMethod {
                name = "setUserName"
            }.hookAfter {
                chatFooterRef = WeakReference(thisObject as ChatFooter)
                val conv = args[0] as? String
                if (!conv.isNullOrEmpty()) {
                    value = conv
                }
            }
    }
}
