@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Request body for `/cgi-bin/micromsg-bin/newsendmsg` (cgi 522).
 *
 * The native client's signer stamps [NewSendMsgItemProto.createTime] and
 * [NewSendMsgItemProto.clientMsgId]; callers building this proto directly must fill both
 * (see [dev.ujhhgtg.wekit.features.api.net.MsgIdPreviewer.generateClientMsgId]).
 */
@Serializable
data class NewSendMsgReqProto(
    @ProtoNumber(1) val count: Int = 1,
    @ProtoNumber(2) val items: List<NewSendMsgItemProto> = emptyList(),
    @ProtoNumber(4) override val createTime: Int = 0,
    @ProtoNumber(5) override val clientMsgId: Int = 0
) : INewSendMsgProto

interface INewSendMsgProto {
    val createTime: Int
    val clientMsgId: Int
}

@Serializable
data class NewSendMsgItemProto(
    @ProtoNumber(1) val toUser: UserNameProto = UserNameProto(),
    @ProtoNumber(2) val content: String = "",
    @ProtoNumber(3) val type: Int = 1,
    @ProtoNumber(4) override val createTime: Int = 0,
    @ProtoNumber(5) override val clientMsgId: Int = 0,
    @ProtoNumber(6) val msgSource: String = "",
): INewSendMsgProto
