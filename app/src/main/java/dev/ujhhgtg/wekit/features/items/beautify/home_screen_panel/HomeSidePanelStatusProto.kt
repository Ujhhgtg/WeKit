package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

internal data class StatusRecordValues(
    val statusId: String?,
    val description: String?,
    val iconId: String?,
    val expireTime: Long = Long.MAX_VALUE,
    val emojiInfo: ByteArray? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class HomeSidePanelStatusEmojiProto(
    @ProtoNumber(1) val md5: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val thumbUrl: String = "",
    @ProtoNumber(11) val attachedText: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
internal fun mapStatusRecord(
    record: StatusRecordValues?,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
): HomeSidePanelStatusUiState {
    if (record == null || record.statusId.isNullOrBlank() || record.expireTime <= nowEpochSeconds) {
        return HomeSidePanelStatusUiState.NoStatus
    }

    return HomeSidePanelStatusUiState.Ready(
        HomeSidePanelStatus(
            statusId = record.statusId,
            description = record.description.orEmpty(),
            iconId = record.iconId.orEmpty(),
            emoji = parseStatusEmojiInfo(record.emojiInfo),
        ),
    )
}

@OptIn(ExperimentalSerializationApi::class)
internal fun parseStatusEmojiInfo(bytes: ByteArray?): HomeSidePanelStatusEmoji? {
    val payload = bytes ?: return null
    if (payload.isEmpty()) return null
    return runCatching {
        ProtoBuf.decodeFromByteArray<HomeSidePanelStatusEmojiProto>(payload)
    }.onFailure {
        WeLogger.w("HomeSidePanelStatusProto", "failed to decode TextStatus EmojiInfo", it)
    }.getOrNull()?.let { proto ->
        if (proto.md5.isBlank() && proto.url.isBlank() &&
            proto.thumbUrl.isBlank() && proto.attachedText.isBlank()
        ) {
            null
        } else {
            HomeSidePanelStatusEmoji(
                md5 = proto.md5.ifBlank { null },
                url = proto.url.ifBlank { null },
                thumbUrl = proto.thumbUrl.ifBlank { null },
                attachedText = proto.attachedText.ifBlank { null },
            )
        }
    }
}
