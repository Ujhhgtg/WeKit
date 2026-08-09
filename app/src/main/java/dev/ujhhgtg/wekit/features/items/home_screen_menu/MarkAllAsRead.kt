package dev.ujhhgtg.wekit.features.items.home_screen_menu

import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.MarkChatReadIcon
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    id = "清空未读",
    nameRes = "feature_mark_all_as_read_name",
    categoryIds = [FeatureCategoryIds.HOME_SCREEN_MENU],
    descriptionRes = "feature_mark_all_as_read_description",
)
object MarkAllAsRead : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777012, "清空未读", MarkChatReadIcon
            ) {
                WeConversationApi.markAllAsRead()
                showToast("已将全部未读消息标为已读")
            }
        )
    }
}
