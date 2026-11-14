@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** `AddMsg` of the newsync stream (decompiled `k4`); only the fields WeKit reads. */
@Serializable
data class AddMsgProto(
    @ProtoNumber(1) val msgId: Int = 0,
    @ProtoNumber(2) val fromUserName: UserNameProto = UserNameProto(),
    @ProtoNumber(3) val toUserName: UserNameProto = UserNameProto(),
    @ProtoNumber(4) val msgType: Int = 0,
    @ProtoNumber(5) val content: UserNameProto = UserNameProto(),
    @ProtoNumber(9) val createTime: Int = 0,
    @ProtoNumber(10) val msgSource: String = "",
    @ProtoNumber(12) val newMsgId: Long = 0L,
) {
    companion object {
        fun decode(bytes: ByteArray): AddMsgProto = WeProto.decode(bytes)
    }
}
