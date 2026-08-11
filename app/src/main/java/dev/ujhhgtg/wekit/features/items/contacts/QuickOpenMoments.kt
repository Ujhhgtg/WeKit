package dev.ujhhgtg.wekit.features.items.contacts

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.ConversationAggregation
import dev.ujhhgtg.wekit.ui.utils.CameraIcon
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

@Feature(
    id = "快捷打开朋友圈",
    nameRes = "feature_quick_open_moments_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS],
    descriptionRes = "feature_quick_open_moments_description",
)
object QuickOpenMoments : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777018,
                text = localizedContactsString(R.string.contacts_open_moments),
                drawable = CameraIcon,
                shouldShow = { context, _ ->
                    val talker = context.talker
                    talker.isNotEmpty() &&
                            !talker.isGroupChatWxId &&
                            !talker.startsWith("gh_") &&
                            !talker.endsWith("@app") &&
                            !talker.startsWith(ConversationAggregation.FOLDER_PREFIX)
                },
            ) { context ->
                WeApi.openMoments(context.activity, context.talker)
            }
        )
    }
}
