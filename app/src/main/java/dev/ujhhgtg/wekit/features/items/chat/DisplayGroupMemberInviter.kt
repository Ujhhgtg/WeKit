package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DisplayGroupMemberInviter : SwitchFeature(), IContactInfoProvider {

    override val technicalId = "查看群成员邀请者"
    override val nameRes = R.string.feature_display_group_member_inviter_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_display_group_member_inviter_description

    private const val TAG = "DisplayGroupMemberInviter"

    private const val PREF_KEY = "member_inviter"

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ── IContactInfoProvider ──────────────────────────────────────────────────

    /** Only shown for an individual group member (not the group itself), inside a group chat. */
    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        if (!WeCurrentConversationApi.value.isGroupChatWxId) return emptyList()
        val memberId = activity.currentWxId ?: return emptyList()
        if (memberId.isGroupChatWxId) return emptyList()

        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = activity.localizedChatString(R.string.chat_member_inviter_title),
                summary = activity.localizedChatString(R.string.chat_contact_tap_to_view),
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val groupId = WeCurrentConversationApi.value.takeIf { it.isGroupChatWxId } ?: return true
        val memberId = activity.currentWxId ?: return true

        showToast(activity, activity.localizedChatString(R.string.chat_member_inviter_querying))
        CoroutineScope(Dispatchers.IO).launch {
            val inviterId = runCatching { WeDatabaseApi.getGroupMemberInviter(groupId, memberId) }
                .onFailure { WeLogger.e(TAG, "failed to resolve inviter for $memberId in $groupId", it) }
                .getOrDefault("")

            val message = when {
                inviterId.isEmpty() -> activity.localizedChatString(R.string.chat_member_inviter_no_record)
                inviterId == memberId -> activity.localizedChatString(R.string.chat_member_inviter_self_joined)
                else -> {
                    val inviterName = runCatching { WeDatabaseApi.getDisplayName(inviterId) }
                        .getOrDefault(inviterId)
                    val groupNick = runCatching {
                        WeDatabaseApi.getGroupMemberDisplayName(groupId, inviterId)
                    }.getOrDefault("")

                    val nameLabel = if (groupNick.isNotBlank() && groupNick != inviterName) {
                        "$inviterName ($groupNick)"
                    } else {
                        inviterName
                    }
                    activity.localizedChatString(R.string.chat_member_inviter_result, nameLabel)
                }
            }

            showToastSuspend(activity, message)
        }
        return true
    }
}
