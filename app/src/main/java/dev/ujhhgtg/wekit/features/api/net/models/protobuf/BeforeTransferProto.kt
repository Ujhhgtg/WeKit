@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf


import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BeforeTransferProto(
    @ProtoNumber(4) val maskedRealName: String? = null,
    @ProtoNumber(5) val key: String? = null
) {
    companion object {
        fun decode(bytes: ByteArray): BeforeTransferProto = WeProto.decode(bytes)
    }
}

/**
 * Request body for `/cgi-bin/mmpay-bin/beforetransfer` (cgi 2783).
 *
 * Field `2` is the target user's wxId; the optional field `4` scopes the lookup to a group so
 * a member's real name can be resolved. Omitting [groupId] performs a plain contact lookup.
 */
@Serializable
data class BeforeTransferReqProto(
    @ProtoNumber(2) val userName: String = "",
    @ProtoNumber(4) val groupId: String? = null,
) {
    fun encode(): ByteArray = WeProto.encode(this)
}
