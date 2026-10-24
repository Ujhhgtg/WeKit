@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** `cu5` / `SKBuiltinBuffer_t` - length-prefixed byte buffer used by WeChat protos. */
@Serializable
data class SKBuiltinBufferProto(
    @ProtoNumber(1) val length: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(2) val buf: ByteArray? = null,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SKBuiltinBufferProto

        if (length != other.length) return false
        if (!buf.contentEquals(other.buf)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + (buf?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        fun fromBytes(bytes: ByteArray): SKBuiltinBufferProto =
            SKBuiltinBufferProto(length = bytes.size, buf = bytes)
    }
}

typealias OpBufProto = SKBuiltinBufferProto
