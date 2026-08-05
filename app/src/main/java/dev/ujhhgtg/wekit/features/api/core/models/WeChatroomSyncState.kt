package dev.ujhhgtg.wekit.features.api.core.models

data class WeChatroomSyncState(
    val roomId: String,
    val memberIds: Set<String>,
    val memberVersion: Int?,
)

fun normalizeChatroomMemberIds(memberList: String): Set<String> =
    memberList
        .split(";")
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
