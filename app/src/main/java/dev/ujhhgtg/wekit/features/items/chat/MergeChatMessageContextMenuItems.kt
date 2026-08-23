package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

/** Actual implementation lives in WeChatMessageContextMenuApi. */
object MergeChatMessageContextMenuItems : SwitchFeature() {
    override val technicalId = "消息长按菜单项合并展示"
    override val nameRes = R.string.feature_merge_chat_message_context_menu_items_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_merge_chat_message_context_menu_items_description
}
