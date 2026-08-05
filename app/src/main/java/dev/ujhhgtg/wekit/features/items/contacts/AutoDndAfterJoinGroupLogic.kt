package dev.ujhhgtg.wekit.features.items.contacts

import dev.ujhhgtg.wekit.features.api.core.models.WeChatroomSyncState
import java.security.MessageDigest

fun shouldMuteJoinedGroup(
    oldState: WeChatroomSyncState?,
    newState: WeChatroomSyncState,
    selfWxId: String,
): Boolean =
    oldState != null &&
        newState.roomId.isChatroomId() &&
        selfWxId !in oldState.memberIds &&
        selfWxId in newState.memberIds

fun dedupKey(state: WeChatroomSyncState): String =
    state.memberVersion?.let { "${state.roomId}:$it" }
        ?: "${state.roomId}:${state.memberIds.normalizedMemberHash()}"

private fun String.isChatroomId(): Boolean =
    endsWith("@chatroom") || endsWith("@im.chatroom")

private fun Set<String>.normalizedMemberHash(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(
            asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .sorted()
                .joinToString("\u0000")
                .toByteArray(),
        ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
