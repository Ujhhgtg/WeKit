package dev.ujhhgtg.wekit.features.items.home_screen_menu

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.CancelIcon
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.killHost

@Feature(
    id = "强行停止",
    nameRes = "feature_kill_host_process_name",
    categoryIds = [FeatureCategoryIds.HOME_SCREEN_MENU],
    descriptionRes = "feature_kill_host_process_description",
)
object KillHostProcess : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777015, localizedHomeMenuString(R.string.home_menu_force_stop), CancelIcon
            ) {
                killHost()
            }
        )
    }
}
