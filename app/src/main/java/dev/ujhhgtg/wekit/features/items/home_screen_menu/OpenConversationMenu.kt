package dev.ujhhgtg.wekit.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.contacts.showOpenConversationDialog
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.utils.HookParam

@Feature(
    id = "跳转对话菜单",
    nameRes = "feature_open_conversation_menu_name",
    categoryIds = [FeatureCategoryIds.HOME_SCREEN_MENU],
    descriptionRes = "feature_open_conversation_menu_description",
)
object OpenConversationMenu : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777025, localizedHomeMenuString(R.string.home_menu_open_conversation), ChatInfoIcon
            ) {
                showOpenConversationDialog(LauncherUI.getInstance()!!)
            }
        )
    }
}
