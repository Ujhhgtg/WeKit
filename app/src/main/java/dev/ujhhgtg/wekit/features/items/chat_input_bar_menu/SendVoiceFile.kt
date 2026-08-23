package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Voice_chat
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.panel.selectAndSendVoice

object SendVoiceFile : SwitchFeature() {

    override val technicalId = "发送语音文件"
    override val nameRes = R.string.feature_send_voice_file_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_send_voice_file_description

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_voice_file",
                icon = MaterialSymbols.Outlined.Voice_chat,
                label = localizedChatInputString(R.string.feature_send_voice_file_name),
                onClick = { context, _ ->
                    selectAndSendVoice(context, WeCurrentConversationApi.value)
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
