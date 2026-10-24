@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

interface ISendEmojiProto

/** Request body for `/cgi-bin/micromsg-bin/sendemoji` (CGI 175). */
@Serializable
data class SendEmojiReqProto(
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(1) val baseRequest: ByteArray? = null,
    @ProtoNumber(2) val count: Int = 0,
    @ProtoNumber(3) val emojiList: List<EmojiItemProto> = emptyList(),
    @ProtoNumber(4) val type: Int = 0,
) : ISendEmojiProto {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SendEmojiReqProto

        if (count != other.count) return false
        if (type != other.type) return false
        if (!baseRequest.contentEquals(other.baseRequest)) return false
        if (emojiList != other.emojiList) return false

        return true
    }

    override fun hashCode(): Int {
        var result = count
        result = 31 * result + type
        result = 31 * result + (baseRequest?.contentHashCode() ?: 0)
        result = 31 * result + emojiList.hashCode()
        return result
    }
}

@Serializable
data class EmojiItemProto(
    @ProtoNumber(1) val md5: String = "",
    @ProtoNumber(2) val startPos: Int = 0,
    @ProtoNumber(3) val totalLen: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(4)
    val emojiBuffer: SKBuiltinBufferProto = SKBuiltinBufferProto(length = 0),
    @ProtoNumber(5) val type: Int = 0,
    @ProtoNumber(6) val toUser: String = "",
    @ProtoNumber(7) val externXml: String = "",
    @ProtoNumber(8) val report: String = "",
    @ProtoNumber(9) val clientMsgId: String = "",
    @ProtoNumber(10) val msgSource: String = "",
    @ProtoNumber(11) val newXmlFlag: Int = 0,
    @ProtoNumber(12) val sendMsgTicket: String = "",
    @ProtoNumber(14) val fromScene: Int = 0,
    @ProtoNumber(16) val msgInfoXml: String = "",
) : ISendEmojiProto
