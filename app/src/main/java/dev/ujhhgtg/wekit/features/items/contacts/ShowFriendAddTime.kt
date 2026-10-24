package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactHeaderApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShowFriendAddTime : SwitchFeature(),
    WeContactHeaderApi.Provider,
    WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "显示好友添加时间"
    override val nameRes = R.string.feature_show_friend_add_time_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_show_friend_add_time_description

    override fun getHeaderText(activity: Activity): String? {
        val addTime = readContactAddTime(activity) ?: return null
        return activity.localizedContactsString(R.string.contacts_add_time_value, addTime)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        // ContactInfoUI has the profile header. Preference rows are only needed for
        // ChatroomInfoUI, which has no NormalProfileHeaderPreference.
        val wxId = activity.currentWxId
        if (activity.javaClass.name != ChatroomInfoUI::class.java.name) {
            return emptyList()
        }
        val addTime = readContactAddTime(activity) ?: return emptyList()
        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                title = activity.localizedContactsString(
                    R.string.contacts_add_time_value,
                    addTime,
                ),
                position = 1,
            )
        )
    }

    private fun readContactAddTime(activity: Activity): String? {
        val wxId = activity.currentWxId ?: return null
        val millis = when {
            wxId.endsWith("@chatroom") -> readGroupAddTime(wxId)
            wxId.startsWith("gh_") -> readOfficialAccountAddTime(wxId)
            else -> readFriendAddTime(wxId)
        } ?: return null
        if (millis == 0L) return activity.localizedContactsString(R.string.contacts_get_failed)
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(millis))
    }

    private fun readFriendAddTime(wxId: String): Long? {
        return try {
            WeDatabaseApi.rawQuery(
                """SELECT createTime FROM rcontact
                   WHERE username = ? AND encryptUsername != ''
                   AND (type & 1) != 0 AND (type & 8) = 0
                   AND (type & 32) = 0 AND verifyFlag = 0
                   AND username NOT LIKE '%@%' LIMIT 1""".trimIndent(),
                arrayOf<Any>(wxId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.getLong(0).let { seconds ->
                    if (seconds > 0) Math.multiplyExact(seconds, 1000L) else 0L
                }
            }
        } catch (e: Exception) {
            WeLogger.e("ShowFriendAddTime", "failed to read contact creation time", e)
            null
        }
    }

    private fun readOfficialAccountAddTime(wxId: String): Long? {
        return try {
            WeDatabaseApi.rawQuery(
                "SELECT createTime FROM rcontact WHERE username = ? LIMIT 1",
                arrayOf<Any>(wxId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.getLong(0).let { seconds ->
                    if (seconds > 0) Math.multiplyExact(seconds, 1000L) else 0L
                }
            }
        } catch (e: Exception) {
            WeLogger.e("ShowFriendAddTime", "failed to read official account add time", e)
            null
        }
    }

    private fun readGroupAddTime(wxId: String): Long? {
        return try {
            val selfWxId = WeApi.selfWxId
            // roomowner changes when ownership is transferred. The member's inviter field is
            // historical and therefore remains useful for distinguishing an invite from a room
            // created by us. A missing inviter record is treated as a self-created room; old
            // databases may not retain the complete roomdata blob, so this is best effort.
            val inviterWxId = WeDatabaseApi.getGroupMemberInviter(wxId, selfWxId)
            val selfWasInvited = inviterWxId.isNotEmpty() && inviterWxId != selfWxId
            val (where, args) = if (selfWasInvited) {
                "content LIKE '你加入了群聊%' OR content LIKE '你通过%加入群聊%' " +
                    "OR content LIKE '%邀请你%加入了群聊%'" to arrayOf<Any>(wxId)
            } else {
                "content LIKE '你创建了群聊%' OR content LIKE '你邀请%加入了群聊%' " +
                    "OR content LIKE '%你邀请%加入了群聊%'" to arrayOf<Any>(wxId)
            }
            WeDatabaseApi.rawQuery(
                """SELECT ${if (selfWasInvited) "MAX" else "MIN"}(createTime)
                   FROM message WHERE talker = ? AND type IN (10000, 570425393)
                   AND ($where)""".trimIndent(),
                args,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return null
                cursor.getLong(0).takeIf { it > 0 }?.let { timestamp ->
                    if (timestamp < 100_000_000_000L) Math.multiplyExact(timestamp, 1000L) else timestamp
                }
            }
        } catch (e: Exception) {
            WeLogger.e("ShowFriendAddTime", "failed to read group add time", e)
            null
        }
    }

    override fun onEnable() {
        WeContactHeaderApi.addProvider(this)
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactHeaderApi.removeProvider(this)
        WeContactPrefsScreenApi.removeProvider(this)
    }
}
