@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** `setcontactproperty` (cgi 10022) request; mirrors the decompiled `zc6` / `ad6` messages. */
@Serializable
data class SetContactPropertyReqProto(
    @ProtoNumber(2) val userName: String,
    @ProtoNumber(3) val payload: ContactPropertyProto,
    @ProtoNumber(4) val fromRoomName: String? = null,
) {
    @Suppress("DEPRECATION")
    fun encode(): ByteArray = WeProto.encode(this)
}

@Serializable
data class ContactPropertyProto(
    @ProtoNumber(1) val remark: String,
)
