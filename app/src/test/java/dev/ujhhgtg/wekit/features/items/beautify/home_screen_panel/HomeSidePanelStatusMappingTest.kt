package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class HomeSidePanelStatusMappingTest {

    @Test
    fun missingStatusMapsToOnlineState() {
        assertEquals(HomeSidePanelStatusUiState.NoStatus, mapStatusRecord(null))
    }

    @Test
    fun blankOrExpiredStatusMapsToOnlineState() {
        assertEquals(
            HomeSidePanelStatusUiState.NoStatus,
            mapStatusRecord(
                StatusRecordValues(
                    statusId = "",
                    description = "不会显示",
                    iconId = "1",
                ),
            ),
        )
        assertEquals(
            HomeSidePanelStatusUiState.NoStatus,
            mapStatusRecord(
                StatusRecordValues(
                    statusId = "expired",
                    description = "old",
                    iconId = "1",
                    expireTime = 100L,
                ),
                nowEpochSeconds = 101L,
            ),
        )
    }

    @Test
    fun statusRecordMapsDescriptionIconAndEmoji() {
        val emojiInfo = ProtoBuf.encodeToByteArray(
            HomeSidePanelStatusEmojiProto(
                md5 = "emoji-md5",
                thumbUrl = "https://example.invalid/thumb.webp",
                attachedText = "忙碌",
            ),
        )

        val state = mapStatusRecord(
            StatusRecordValues(
                statusId = "status-1",
                description = "忙碌中",
                iconId = "1065",
                emojiInfo = emojiInfo,
            ),
        ) as HomeSidePanelStatusUiState.Ready

        assertEquals("忙碌中", state.status.description)
        assertEquals("1065", state.status.iconId)
        assertEquals("emoji-md5", state.status.emoji?.md5)
        assertEquals("https://example.invalid/thumb.webp", state.status.emoji?.thumbUrl)
        assertEquals("忙碌", state.status.emoji?.attachedText)
    }

    @Test
    fun malformedEmojiInfoDoesNotHideAnOtherwiseValidStatus() {
        val state = mapStatusRecord(
            StatusRecordValues(
                statusId = "status-1",
                description = "忙碌中",
                iconId = "1065",
                emojiInfo = byteArrayOf(0x7f),
            ),
        ) as HomeSidePanelStatusUiState.Ready

        assertNull(state.status.emoji)
    }
}
