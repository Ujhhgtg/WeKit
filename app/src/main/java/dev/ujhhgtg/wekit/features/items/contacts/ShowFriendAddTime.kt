package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactHeaderApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShowFriendAddTime : SwitchFeature(), WeContactHeaderApi.Provider {

    override val technicalId = "显示好友添加时间"
    override val nameRes = R.string.feature_show_friend_add_time_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_show_friend_add_time_description

    override fun getHeaderText(activity: Activity): String? {
        val wxId = activity.currentWxId ?: return null
        var isFriend = false
        val time = try {
            // ContactSyncExtension writes the server's ContactCreateTime (seconds)
            // into rcontact.createTime. Verification/chat messages are not this date.
            // Do not filter by time: an existing friend with no date still needs a row.
            WeDatabaseApi.rawQuery(
                """SELECT createTime FROM rcontact
                   WHERE username = ? AND (type & 1) != 0 AND verifyFlag = 0
                   AND username NOT LIKE '%@chatroom'
                   LIMIT 1""".trimIndent(),
                arrayOf(wxId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                isFriend = true
                val seconds = cursor.getLong(0)
                if (seconds > 0) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                        .format(Date(Math.multiplyExact(seconds, 1000L)))
                } else null
            }
        } catch (e: Exception) {
            WeLogger.e("ShowFriendAddTime", "failed to read contact creation time", e)
            null
        }
        // A failed query alone does not establish that this person is a friend.
        if (!isFriend) return null
        return activity.localizedContactsString(
            R.string.contacts_add_time_value,
            time ?: activity.localizedContactsString(R.string.contacts_get_failed),
        )
    }

    override fun onEnable() = WeContactHeaderApi.addProvider(this)

    override fun onDisable() = WeContactHeaderApi.removeProvider(this)
}
