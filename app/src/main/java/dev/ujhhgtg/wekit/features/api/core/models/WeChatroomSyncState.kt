package dev.ujhhgtg.wekit.features.api.core.models

data class WeChatroomSyncState(
    val roomId: String,
    val memberIds: Set<String>,
    val memberVersion: Int?,
)

sealed interface ChatroomSyncStateReadResult {
    data object MissingRow : ChatroomSyncStateReadResult
    data class Available(val state: WeChatroomSyncState) : ChatroomSyncStateReadResult
    data object Unavailable : ChatroomSyncStateReadResult
}

fun normalizeChatroomMemberIds(memberList: String): Set<String> =
    memberList
        .split(";")
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
