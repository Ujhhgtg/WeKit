package dev.ujhhgtg.wekit.features.items.beautify

import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    name = "隐藏对话列表分割线",
    categories = ["聊天", "界面美化"],
    description = "隐藏主页对话列表里对话间的分割线",
)
object HideConversationListDividers : SwitchFeature() {
    override fun onEnable() {
        WeConversationListViewApi.setDividerHidden(this, true)
    }

    override fun onDisable() {
        WeConversationListViewApi.removeDividerOwner(this)
    }
}
