package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Send_time_extension
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    id = "发送卡片消息",
    nameRes = "feature_send_card_message_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_send_card_message_description",
)
object SendCardMessage : SwitchFeature() {

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_card_message",
                icon = MaterialSymbols.Outlined.Send_time_extension,
                label = "发送卡片消息",
                onClick = { _, chatFooter ->
                    val currentConv = WeCurrentConversationApi.value
                    val content = chatFooter.lastText

                    if (content.isEmpty()) {
                        showToast("输入内容为空!")
                        return@ActionItem
                    }

                    val isSuccess = WeMessageApi.sendXmlAppMsg(currentConv, content)
                    if (!isSuccess) {
                        showToast("发送卡片消息失败, 请检查格式")
                        return@ActionItem
                    }

                    chatFooter.lastText = ""
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
