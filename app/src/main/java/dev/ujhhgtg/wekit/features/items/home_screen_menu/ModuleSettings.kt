package dev.ujhhgtg.wekit.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeSettingsInjector
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.ExtensionIcon
import dev.ujhhgtg.wekit.utils.HookParam

@Feature(
    id = "模块设置",
    nameRes = "feature_module_settings_name",
    categoryIds = [FeatureCategoryIds.HOME_SCREEN_MENU],
    descriptionRes = "feature_module_settings_description",
)
object ModuleSettings : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> =
        listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                0, BuildConfig.TAG, ExtensionIcon
            ) { WeSettingsInjector.openSettingsDialog(LauncherUI.getInstance()!!) }
        )
}
