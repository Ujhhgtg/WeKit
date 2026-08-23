package dev.ujhhgtg.wekit.features.api.ui

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import java.lang.ref.WeakReference

object WeCurrentConversationApi : ApiFeature() {

    override val technicalId = "当前聊天服务"
    override val nameRes = R.string.feature_we_current_conversation_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_current_conversation_api_description

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
