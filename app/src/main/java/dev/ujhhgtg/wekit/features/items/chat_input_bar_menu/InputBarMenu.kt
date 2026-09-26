package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

/** Entry implementation lives in ChatFooterHooks. */
object InputBarMenu : SwitchFeature() {
    override val technicalId = "聊天输入栏菜单"
    override val nameRes = R.string.feature_input_bar_menu_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT_INPUT_BAR_MENU)
    override val descriptionRes = R.string.feature_input_bar_menu_description
    override val displayOrder = 0
    override val defaultEnabled = true
}
