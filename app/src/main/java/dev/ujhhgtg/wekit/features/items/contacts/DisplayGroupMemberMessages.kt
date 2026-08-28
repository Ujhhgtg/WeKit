package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.Intent
import dev.ujhhgtg.wekit.R
import com.tencent.mm.chatroom.ui.SelectedMemberChattingRecordUI
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

@Feature(
    id = "查看群成员消息历史",
    nameRes = "feature_display_group_member_messages_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS],
    descriptionRes = "feature_display_group_member_messages_description",
)
object DisplayGroupMemberMessages : SwitchFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    private const val PREF_KEY = "member_msg"

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        if (!WeCurrentConversationApi.value.isGroupChatWxId) return emptyList()
        if (activity.currentWxId!!.isGroupChatWxId) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = PREF_KEY,
                title = activity.localizedContactsString(R.string.contacts_group_message_history),
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val groupId = WeCurrentConversationApi.value
        val memberId = activity.currentWxId ?: return true

        activity.startActivity(Intent(activity, SelectedMemberChattingRecordUI::class.java).apply {
            putExtra("RoomInfo_Id", groupId)
            putExtra("room_member", memberId)
            putExtra(
                "title",
                activity.localizedContactsString(R.string.feature_display_group_member_messages_name),
            )
        })

        return true
    }
}
