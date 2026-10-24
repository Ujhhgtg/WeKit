@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

interface IAppMsgProto

/** Request body for `/cgi-bin/micromsg-bin/sendappmsg` (CGI 222). */
@Serializable
data class SendAppMsgReqProto(
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(1) val baseRequest: ByteArray? = null,
    @ProtoNumber(2) val msg: AppMsgItemProto = AppMsgItemProto(),
    @ProtoNumber(3) val commentUrl: String = "",
    @ProtoNumber(4) val reqTime: Int = 0,
    @ProtoNumber(5) val md5: String = "",
    @ProtoNumber(6) val fileType: Int = 0,
    @ProtoNumber(7) val signature: String = "",
    @ProtoNumber(8) val fromSence: String = "",
    @ProtoNumber(9) val hitMd5: Int = 0,
    @ProtoNumber(10) val crc32: Int = 0,
    @ProtoNumber(11) val msgForwardType: Int = 0,
    @ProtoNumber(12) val directShare: Int = 0,
    @ProtoNumber(13) val sendMsgTicket: String = "",
) : IAppMsgProto {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SendAppMsgReqProto

        if (reqTime != other.reqTime) return false
        if (fileType != other.fileType) return false
        if (hitMd5 != other.hitMd5) return false
        if (crc32 != other.crc32) return false
        if (msgForwardType != other.msgForwardType) return false
        if (directShare != other.directShare) return false
        if (!baseRequest.contentEquals(other.baseRequest)) return false
        if (msg != other.msg) return false
        if (commentUrl != other.commentUrl) return false
        if (md5 != other.md5) return false
        if (signature != other.signature) return false
        if (fromSence != other.fromSence) return false
        if (sendMsgTicket != other.sendMsgTicket) return false

        return true
    }

    override fun hashCode(): Int {
        var result = reqTime
        result = 31 * result + fileType
        result = 31 * result + hitMd5
        result = 31 * result + crc32
        result = 31 * result + msgForwardType
        result = 31 * result + directShare
        result = 31 * result + (baseRequest?.contentHashCode() ?: 0)
        result = 31 * result + msg.hashCode()
        result = 31 * result + commentUrl.hashCode()
        result = 31 * result + md5.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + fromSence.hashCode()
        result = 31 * result + sendMsgTicket.hashCode()
        return result
    }
}

@Serializable
data class AppMsgItemProto(
    @ProtoNumber(1) val fromUserName: String = "",
    @ProtoNumber(2) val appId: String = "",
    @ProtoNumber(3) val sdkVersion: Int = 0,
    @ProtoNumber(4) val toUserName: String = "",
    @ProtoNumber(5) val type: Int = 0,
    @ProtoNumber(6) val content: String = "",
    @ProtoNumber(7) val createTime: Int = 0,
    @ProtoNumber(8) val clientMsgId: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ProtoNumber(9)
    val thumb: SKBuiltinBufferProto? = null,
    @ProtoNumber(10) val source: Int = 0,
    @ProtoNumber(11) val remindId: Int = 0,
    @ProtoNumber(12) val msgSource: String = "",
    @ProtoNumber(13) val shareUrlOriginal: String = "",
    @ProtoNumber(14) val shareUrlOpen: String = "",
    @ProtoNumber(15) val jsAppId: String = "",
    @ProtoNumber(16) val tokenValid: Int = 0,
    @ProtoNumber(17) val packageName: String = "",
) : IAppMsgProto {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppMsgItemProto) return false
        return fromUserName == other.fromUserName &&
            appId == other.appId &&
            sdkVersion == other.sdkVersion &&
            toUserName == other.toUserName &&
            type == other.type &&
            content == other.content &&
            createTime == other.createTime &&
            clientMsgId == other.clientMsgId &&
            thumb == other.thumb &&
            source == other.source &&
            remindId == other.remindId &&
            msgSource == other.msgSource &&
            shareUrlOriginal == other.shareUrlOriginal &&
            shareUrlOpen == other.shareUrlOpen &&
            jsAppId == other.jsAppId &&
            tokenValid == other.tokenValid &&
            packageName == other.packageName
    }

    override fun hashCode(): Int {
        var result = fromUserName.hashCode()
        result = 31 * result + appId.hashCode()
        result = 31 * result + sdkVersion
        result = 31 * result + toUserName.hashCode()
        result = 31 * result + type
        result = 31 * result + content.hashCode()
        result = 31 * result + createTime
        result = 31 * result + clientMsgId.hashCode()
        result = 31 * result + (thumb?.hashCode() ?: 0)
        result = 31 * result + source
        result = 31 * result + remindId
        result = 31 * result + msgSource.hashCode()
        result = 31 * result + shareUrlOriginal.hashCode()
        result = 31 * result + shareUrlOpen.hashCode()
        result = 31 * result + jsAppId.hashCode()
        result = 31 * result + tokenValid
        result = 31 * result + packageName.hashCode()
        return result
    }
}
