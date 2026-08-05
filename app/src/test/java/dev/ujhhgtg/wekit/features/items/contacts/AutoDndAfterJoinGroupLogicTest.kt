package dev.ujhhgtg.wekit.features.items.contacts

import dev.ujhhgtg.wekit.features.api.core.models.WeChatroomSyncState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoDndAfterJoinGroupLogicTest {

    private val selfWxId = "wxid_self"

    @Test
    fun mutesWhenMissingOldRowAndNewStateContainsSelf() {
        assertTrue(
            shouldMuteJoinedGroup(
                oldState = null,
                newState = state(memberIds = setOf(selfWxId)),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun mutesWhenExistingRoomChangesFromSelfAbsentToPresent() {
        assertTrue(
            shouldMuteJoinedGroup(
                oldState = state(memberIds = setOf("wxid_other")),
                newState = state(memberIds = setOf("wxid_other", selfWxId)),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun doesNotMuteWhenSelfWasAlreadyPresent() {
        assertFalse(
            shouldMuteJoinedGroup(
                oldState = state(memberIds = setOf("wxid_other", selfWxId)),
                newState = state(memberIds = setOf("wxid_other", selfWxId, "wxid_new")),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun doesNotMuteWhenAnotherMemberIsAddedAndSelfRemainsAbsent() {
        assertFalse(
            shouldMuteJoinedGroup(
                oldState = state(memberIds = setOf("wxid_other")),
                newState = state(memberIds = setOf("wxid_other", "wxid_new")),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun doesNotMuteWhenNewStateDoesNotContainSelf() {
        assertFalse(
            shouldMuteJoinedGroup(
                oldState = state(memberIds = emptySet()),
                newState = state(memberIds = emptySet()),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun mutesWhenExistingEmptyMemberListChangesToSelfPresent() {
        assertTrue(
            shouldMuteJoinedGroup(
                oldState = state(memberIds = emptySet()),
                newState = state(memberIds = setOf(selfWxId)),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun mutesWhenSelfRejoinsPreviouslyCreatedGroupThroughPersistedTransition() {
        assertTrue(
            shouldMuteJoinedGroup(
                oldState = state(memberIds = setOf("wxid_other")),
                newState = state(memberIds = setOf("wxid_other", selfWxId)),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun doesNotMuteForUnsupportedRoomSuffix() {
        assertFalse(
            shouldMuteJoinedGroup(
                oldState = state(roomId = "contact", memberIds = emptySet()),
                newState = state(roomId = "contact", memberIds = setOf(selfWxId)),
                selfWxId = selfWxId,
            ),
        )
    }

    @Test
    fun dedupKeyUsesRoomIdAndMemberVersionWhenAvailable() {
        assertEquals(
            "room@chatroom:42",
            dedupKey(state(memberVersion = 42)),
        )
    }

    @Test
    fun dedupKeyWithoutVersionIsStableAcrossMemberIterationOrder() {
        assertEquals(
            dedupKey(state(memberIds = linkedSetOf("wxid_b", "wxid_a"))),
            dedupKey(state(memberIds = linkedSetOf("wxid_a", "wxid_b"))),
        )
    }

    @Test
    fun dedupKeyWithoutVersionChangesWhenNormalizedMembersChange() {
        assertNotEquals(
            dedupKey(state(memberIds = setOf("wxid_a"))),
            dedupKey(state(memberIds = setOf("wxid_b"))),
        )
    }

    private fun state(
        roomId: String = "room@chatroom",
        memberIds: Set<String> = emptySet(),
        memberVersion: Int? = null,
    ) = WeChatroomSyncState(roomId, memberIds, memberVersion)
}
