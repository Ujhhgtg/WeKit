package dev.ujhhgtg.wekit.features.items.home_screen_menu

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.CancelIcon
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.killHost

object KillHostProcess : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "强行停止"
    override val nameRes = R.string.feature_kill_host_process_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_kill_host_process_description

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
