package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
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

@Feature(
    id = "查看群成员邀请者",
    nameRes = "feature_display_group_member_inviter_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS],
    descriptionRes = "feature_display_group_member_inviter_description",
)
object DisplayGroupMemberInviter : SwitchFeature(), IContactInfoProvider {

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
                title = "查看进群邀请者",
                summary = "点击查看",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val groupId = WeCurrentConversationApi.value.takeIf { it.isGroupChatWxId } ?: return true
        val memberId = activity.currentWxId ?: return true

        showToast(activity, "正在查询...")
        CoroutineScope(Dispatchers.IO).launch {
            val inviterId = runCatching { WeDatabaseApi.getGroupMemberInviter(groupId, memberId) }
                .onFailure { WeLogger.e(TAG, "failed to resolve inviter for $memberId in $groupId", it) }
                .getOrDefault("")

            val message = when {
                inviterId.isEmpty() -> "无邀请者记录 (可能是群主/前群主/早期成员)"
                inviterId == memberId -> "该成员为扫码/自行进群"
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
                    "邀请者: $nameLabel"
                }
            }

            showToastSuspend(activity, message)
        }
        return true
    }
}
