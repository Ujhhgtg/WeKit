package dev.ujhhgtg.wekit.features.api.core.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WeChatroomSyncStateTest {

    @Test
    fun normalizeMemberIdsReturnsEmptySetForEmptyInput() {
        assertEquals(emptySet<String>(), normalizeChatroomMemberIds(""))
    }

    @Test
    fun normalizeMemberIdsTrimsBlanksAndDeduplicates() {
        assertEquals(
            setOf("alice", "bob"),
            normalizeChatroomMemberIds(" alice ;bob;; alice;  "),
        )
    }
}
