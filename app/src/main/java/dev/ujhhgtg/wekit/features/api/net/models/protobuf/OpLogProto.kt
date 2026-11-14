@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Protobuf models for the `/cgi-bin/micromsg-bin/oplog` CGI (funcId 681).
 *
 * WeChat funnels many "settings mutation" operations (delete contact, block contact, set nickname,
 * ...) through a single oplog request. Each request carries a list of [OperationProto]s; every
 * operation wraps a command id plus the serialized bytes of a command-specific payload proto.
 *
 * Mirrors the decompiled classes: `h25` (request), `c50` (list), `b50` (operation) and `cu5`
 * (buffer wrapper).
 */

/** `h25` - the oplog request body. */
@Serializable
data class OpLogReqProto(
    @ProtoNumber(1) val opLogList: OpLogListProto,
)

/**
 * `i25` - the oplog response body. Only [ret] (the server's result code, `0` == success) is
 * modeled; the nested per-operation detail (`j25`) is skipped on decode.
 */
@Serializable
data class OpLogRespProto(
    @ProtoNumber(1) val ret: Int = 0,
) {
    val isSuccess: Boolean
        get() = ret == 0

    companion object {
        fun decode(bytes: ByteArray): OpLogRespProto = WeProto.decode(bytes)
    }
}

/** `c50` - the list of operations to apply. */
@Serializable
data class OpLogListProto(
    @ProtoNumber(1) val count: Int,
    @ProtoNumber(2) val operations: List<OperationProto>,
)

/** `b50` - a single operation: a command id + its serialized payload. */
@Serializable
data class OperationProto(
    @ProtoNumber(1) val cmdId: Int,
    @ProtoNumber(2) val opBuf: OpBufProto,
)

/**
 * `du5` / `SKBuiltinString_t` - a username / string wrapper reused across many command payloads.
 */
@Serializable
data class UserNameProto(
    @ProtoNumber(1) val userName: String = "",
)

/** `xb0` - payload for the delete-contact command (cmd 4). */
@Serializable
data class DelContactProto(
    @ProtoNumber(1) val userName: UserNameProto,
    @ProtoNumber(2) val deleteContactScene: Int = 0,
    @ProtoNumber(3) val isRetainChatHistory: Int = 0,
)

/** `ac0` - payload for the block-contact command (cmd 8). */
@Serializable
data class BlockContactProto(
    @ProtoNumber(1) val userName: UserNameProto,
    @ProtoNumber(2) val maxMsgId: Int = 0,
    @ProtoNumber(3) val newMsgId: Long = 0L,
)

/** `wo4` - payload for the set-nickname command (cmd 64). */
@Serializable
data class SetNicknameProto(
    @ProtoNumber(1) val opType: Int = 1,
    @ProtoNumber(2) val nickname: String = "",
)

/**
 * `fp4` - payload for the modify-profile command (cmd 1).
 *
 * Default values reproduce the native profile reset / clear profile packet when encoded.
 */
@Serializable
data class ModProfileProto(
    @ProtoNumber(1) val bitFlag: Int = 128,
    @ProtoNumber(2) val userName: UserNameProto = UserNameProto(),
    @ProtoNumber(3) val nickName: UserNameProto = UserNameProto(),
    @ProtoNumber(4) val bindUin: Int = 0,
    @ProtoNumber(5) val bindEmail: UserNameProto = UserNameProto(),
    @ProtoNumber(6) val bindMobile: UserNameProto = UserNameProto(),
    @ProtoNumber(7) val status: Int = 0,
    @ProtoNumber(8) val imgLen: Int = 0,
    @ProtoNumber(9) val imgBuf: ByteArray = byteArrayOf(),
    @ProtoNumber(10) val sex: Int = 0,
    @ProtoNumber(11) val province: String = "",
    @ProtoNumber(12) val city: String = "",
    @ProtoNumber(13) val signature: String = "",
    @ProtoNumber(14) val personalCard: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ProtoNumber(15)
    val disturbSetting: DisturbSettingProto? = null,
    @ProtoNumber(16) val pluginFlag: Int = 0,
    @ProtoNumber(17) val verifyFlag: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(18) val verifyInfo: String? = null,
    @ProtoNumber(19) val point: Int = 0,
    @ProtoNumber(20) val experience: Int = 0,
    @ProtoNumber(21) val level: Int = 0,
    @ProtoNumber(22) val levelLowExp: Int = 0,
    @ProtoNumber(23) val levelHighExp: Int = 0,
    @ProtoNumber(24) val weibo: String = "",
    @ProtoNumber(25) val pluginSwitch: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(26) val gmailList: GmailListProto? = null,
    @ProtoNumber(27) val alias: String = "",
    @ProtoNumber(28) val weiboNickname: String = "",
    @ProtoNumber(29) val weiboFlag: Int = 0,
    @ProtoNumber(30) val faceBookFlag: Int = 0,
    @ProtoNumber(31) val fbUserId: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(32) val fbUserName: String? = null,
    @ProtoNumber(33) val albumStyle: Int = 0,
    @ProtoNumber(34) val albumFlag: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(35) val albumBgImgId: String? = null,
    @ProtoNumber(36) val txNewsCategory: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @ProtoNumber(37) val fbToken: String? = null,
    @ProtoNumber(38) val country: String = "",
)

/** `df0` - profile disturb setting. */
@Serializable
data class DisturbSettingProto(
    @ProtoNumber(1) val nightSetting: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ProtoNumber(2)
    val nightTime: DisturbTimeProto? = null,
    @ProtoNumber(3) val allDaySetting: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ProtoNumber(4)
    val allDayTime: DisturbTimeProto? = null,
)

/** `ef0` - begin/end time for disturb settings. */
@Serializable
data class DisturbTimeProto(
    @ProtoNumber(1) val beginTime: Int = 0,
    @ProtoNumber(2) val endTime: Int = 0,
)

/** `vt3` - Gmail account list. */
@Serializable
data class GmailListProto(
    @ProtoNumber(1) val count: Int = 0,
    @ProtoNumber(2) val list: List<GmailInfoProto> = emptyList(),
)

/** `ut3` - one Gmail account setting. */
@Serializable
data class GmailInfoProto(
    @ProtoNumber(1) val gmailAcct: String = "",
    @ProtoNumber(2) val gmailSwitch: Int = 0,
    @ProtoNumber(3) val gmailErrCode: Int = 0,
)

/**
 * Helpers for assembling `/cgi-bin/micromsg-bin/oplog` (cgi 681) request bodies from typed command
 * payloads. WeChat writes zero-valued scalar fields explicitly, so [protoBuf] mirrors that to stay
 * byte-compatible with the native client.
 */
object OpLog {

    /** oplog command ids (decompiled `xx0.*` → `super(<id>)`). */
    const val CMD_MOD_PROFILE = 1
    const val CMD_MOD_CONTACT = 2
    const val CMD_DELETE_CONTACT = 4
    const val CMD_BLOCK_CONTACT = 8
    const val CMD_SET_NICKNAME = 64

    /**
     * Wrap a typed command payload as an [OperationProto], serializing it to the length-prefixed
     * [OpBufProto] buffer WeChat expects.
     */
    inline fun <reified T : Any> operation(cmdId: Int, payload: T): OperationProto {
        val bytes = WeProto.encodeWithDefaults(payload)
        return OperationProto(cmdId, OpBufProto.fromBytes(bytes))
    }

    /**
     * Wrap already-serialized payload [bytes] as an [OperationProto].
     *
     * Use this when the payload proto is built by the host itself (e.g. the modContact `tn4` proto
     * assembled by WeChat's own `ContactStorageLogic.toModContactOplog`), so its exact byte layout
     * is reproduced rather than re-encoded from a partial model.
     */
    fun operationRaw(cmdId: Int, bytes: ByteArray): OperationProto =
        OperationProto(cmdId, OpBufProto.fromBytes(bytes))

    /** Build the full oplog request bytes for a set of [operations]. */
    @Suppress("DEPRECATION")
    fun encodeRequest(operations: List<OperationProto>): ByteArray =
        WeProto.encode(
            OpLogReqProto(OpLogListProto(count = operations.size, operations = operations)),
        )

    /** Convenience: build oplog request bytes for a single typed command payload. */
    inline fun <reified T : Any> encodeSingle(cmdId: Int, payload: T): ByteArray =
        encodeRequest(listOf(operation(cmdId, payload)))

    /** Convenience: build oplog request bytes for a single pre-serialized payload. */
    fun encodeSingleRaw(cmdId: Int, bytes: ByteArray): ByteArray =
        encodeRequest(listOf(operationRaw(cmdId, bytes)))
}
