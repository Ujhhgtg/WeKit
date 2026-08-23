package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "消息长按菜单项合并展示",
    nameRes = "feature_merge_chat_message_context_menu_items_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_merge_chat_message_context_menu_items_description",
)
object MergeChatMessageContextMenuItems : SwitchFeature() // actual implementation in WeChatMessageContextMenuApi
