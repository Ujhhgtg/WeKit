package me.hd.wauxv.data.bean.info

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi

@Keep
data class GroupData(
    var roomId: String = "",
    var memberIds: List<String> = emptyList(),
    var memberNames: List<String> = emptyList(),
    var memberCount: Int = 0,
    var membersHash: Map<String, String> = emptyMap(),
    var mineRoomName: String = "",
    var owner: String = "",
    var notice: String = "",
    var noticeEditor: String = "",
    var noticeTime: Long = 0,
) {
    constructor(
        roomId: String,
    ) : this(
        roomId = roomId,
        memberIds = emptyList(),
        memberNames = emptyList(),
        memberCount = 0,
        membersHash = emptyMap(),
        mineRoomName = "",
        owner = "",
        notice = "",
        noticeEditor = "",
        noticeTime = 0L,
    ) {
        try {
            val members = WeDatabaseApi.getGroupMembers(roomId)
            this.memberIds = members.map { it.wxId }
            this.memberNames = members.map { it.nickname }
            this.memberCount = members.size
            this.membersHash = members.associate { it.wxId to it.displayName }

            try {
                val db = WeDatabaseApi.db
                val cursor =
                    db.rawQuery(
                        "SELECT roomowner, chatroomnotice, chatroomnoticePublishTime, chatroomnoticeEditor FROM chatroom WHERE chatroomname = ?",
                        arrayOf(roomId),
                    )
                cursor?.use {
                    if (it.moveToFirst()) {
                        this.owner = it.getString(0) ?: ""
                        this.notice = it.getString(1) ?: ""
                        this.noticeTime = it.getLong(2)
                        this.noticeEditor = it.getString(3) ?: ""
                    }
                }
            } catch (e: UninitializedPropertyAccessException) {
                // DB not initialized yet
            }
        } catch (e: Throwable) {
            // Safe fallback
        }
    }
}
