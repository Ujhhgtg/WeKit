@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

interface ISendPatProto

/** Request body for `/cgi-bin/micromsg-bin/sendpat` (CGI 849). */
@Serializable
data class SendPatReqProto(
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(1) val baseRequest: ByteArray? = null,
    @ProtoNumber(2) val fromUser: String = "",
    @ProtoNumber(3) val chatUserName: String = "",
    @ProtoNumber(4) val pattedUser: String = "",
    @ProtoNumber(5) val msgPointer: String = "",
    @ProtoNumber(6) val scene: Int = 0,
) : ISendPatProto {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SendPatReqProto

        if (scene != other.scene) return false
        if (!baseRequest.contentEquals(other.baseRequest)) return false
        if (fromUser != other.fromUser) return false
        if (chatUserName != other.chatUserName) return false
        if (pattedUser != other.pattedUser) return false
        if (msgPointer != other.msgPointer) return false

        return true
    }

    override fun hashCode(): Int {
        var result = scene
        result = 31 * result + (baseRequest?.contentHashCode() ?: 0)
        result = 31 * result + fromUser.hashCode()
        result = 31 * result + chatUserName.hashCode()
        result = 31 * result + pattedUser.hashCode()
        result = 31 * result + msgPointer.hashCode()
        return result
    }
}
