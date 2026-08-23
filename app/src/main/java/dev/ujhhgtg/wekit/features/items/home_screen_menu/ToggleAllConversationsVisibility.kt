package dev.ujhhgtg.wekit.features.items.home_screen_menu

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.VisibilityIcon
import dev.ujhhgtg.wekit.ui.utils.VisibilityOffIcon
import dev.ujhhgtg.wekit.utils.HookParam

object ToggleAllConversationsVisibility : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "显隐全部对话"
    override val nameRes = R.string.feature_toggle_all_conversations_visibility_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_toggle_all_conversations_visibility_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777010, localizedHomeMenuString(R.string.home_menu_show_conversations), VisibilityIcon
            ) {
                WeConversationApi.setAllConversationVisibility(true)
            },
            WeHomeScreenPopupMenuApi.MenuItem(
                777011, localizedHomeMenuString(R.string.home_menu_hide_conversations), VisibilityOffIcon
            ) {
                WeConversationApi.setAllConversationVisibility(false)
            },
        )
    }
}
