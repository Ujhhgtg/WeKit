package dev.ujhhgtg.wekit.features.items.home_screen_menu

import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.VisibilityIcon
import dev.ujhhgtg.wekit.ui.utils.VisibilityOffIcon
import dev.ujhhgtg.wekit.utils.HookParam

@Feature(
    id = "显隐全部对话",
    nameRes = "feature_toggle_all_conversations_visibility_name",
    categoryIds = [FeatureCategoryIds.HOME_SCREEN_MENU],
    descriptionRes = "feature_toggle_all_conversations_visibility_description",
)
object ToggleAllConversationsVisibility : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777010, "显示对话", VisibilityIcon
            ) {
                WeConversationApi.setAllConversationVisibility(true)
            },
            WeHomeScreenPopupMenuApi.MenuItem(
                777011, "隐藏对话", VisibilityOffIcon
            ) {
                WeConversationApi.setAllConversationVisibility(false)
            },
        )
    }
}
