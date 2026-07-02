@file:OptIn(ExperimentalSerializationApi::class)

package dev.ujhhgtg.wekit.features.api.net.models.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Protobuf models for the `/cgi-bin/micromsg-bin/oplog` CGI (funcId 681).
 *
 * WeChat funnels many "settings mutation" operations (delete contact, block contact,
 * set nickname, ...) through a single oplog request. Each request carries a list of
 * [OperationProto]s; every operation wraps a command id plus the serialized bytes of a
 * command-specific payload proto.
 *
 * Mirrors the decompiled classes: `jv4` (request), `o30` (list), `n30` (operation) and
 * `xm5` (buffer wrapper).
 */

/** `jv4` – the oplog request body. */
@Serializable
data class OpLogReqProto(
    @ProtoNumber(1) val opLogList: OpLogListProto,
)

/**
 * `kv4` – the oplog response body. Only [ret] (the server's result code, `0` == success) is
 * modeled; the nested per-operation detail (`lv4`) is skipped on decode.
 */
@Serializable
data class OpLogRespProto(
    @ProtoNumber(1) val ret: Int = 0,
) {
    val isSuccess: Boolean get() = ret == 0

    companion object {
        fun decode(bytes: ByteArray): OpLogRespProto = WeProto.decode(bytes)
    }
}

/** `o30` – the list of operations to apply. */
@Serializable
data class OpLogListProto(
    @ProtoNumber(1) val count: Int,
    @ProtoNumber(2) val operations: List<OperationProto>,
)

/** `n30` – a single operation: a command id + its serialized payload. */
@Serializable
data class OperationProto(
    @ProtoNumber(1) val cmdId: Int,
    @ProtoNumber(2) val opBuf: OpBufProto,
)

/** `xm5` – length-prefixed buffer holding a command payload's protobuf bytes. */
@Serializable
data class OpBufProto(
    @ProtoNumber(1) val length: Int,
    @ProtoNumber(2) val buf: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpBufProto) return false
        return length == other.length && buf.contentEquals(other.buf)
    }

    override fun hashCode(): Int = 31 * length + buf.contentHashCode()
}

/** `ym5` – a username wrapper reused across many command payloads. */
@Serializable
data class UserNameProto(
    @ProtoNumber(1) val userName: String = "",
)

/** `w90` – payload for the delete-contact command (cmd 4). */
@Serializable
data class DelContactProto(
    @ProtoNumber(1) val user: UserNameProto,
    @ProtoNumber(2) val opType: Int = 0,
)

/** `z90` – payload for the block-contact command (cmd 8). */
@Serializable
data class BlockContactProto(
    @ProtoNumber(1) val user: UserNameProto,
    @ProtoNumber(2) val opType: Int = 0,
    @ProtoNumber(3) val lastMsgTime: Long = 0,
)

/** `ii4` – payload for the set-nickname command (cmd 64). */
@Serializable
data class SetNicknameProto(
    @ProtoNumber(1) val opType: Int = 0,
    @ProtoNumber(2) val nickname: String = "",
)

/**
 * `ri4` – payload for the modify-profile command (cmd 1).
 *
 * Only the fields WeChat itself writes for a profile reset are modeled; all default to their
 * "cleared" value so encoding an all-defaults instance reproduces the native clear-profile packet.
 */
@Serializable
data class ModProfileProto(
    @ProtoNumber(1) val flag: Int = 128,
    @ProtoNumber(2) val province: UserNameProto = UserNameProto(),
    @ProtoNumber(3) val city: UserNameProto = UserNameProto(),
    @ProtoNumber(4) val field4: Int = 0,
    @ProtoNumber(5) val signature: UserNameProto = UserNameProto(),
    @ProtoNumber(6) val field6: UserNameProto = UserNameProto(),
    @ProtoNumber(7) val field7: Int = 0,
    @ProtoNumber(8) val field8: Int = 0,
    @ProtoNumber(9) val field9: String = "",
    @ProtoNumber(10) val field10: Int = 0,
    @ProtoNumber(11) val field11: String = "",
    @ProtoNumber(12) val field12: String = "",
    @ProtoNumber(13) val field13: String = "",
    @ProtoNumber(14) val sex: Int = 1,
    @ProtoNumber(16) val field16: Int = 0,
    @ProtoNumber(17) val field17: Int = 0,
    @ProtoNumber(19) val field19: Int = 0,
    @ProtoNumber(20) val field20: Int = 0,
    @ProtoNumber(21) val field21: Int = 0,
    @ProtoNumber(22) val field22: Int = 0,
    @ProtoNumber(23) val field23: Int = 0,
    @ProtoNumber(24) val field24: String = "",
    @ProtoNumber(25) val field25: Int = 0,
    @ProtoNumber(27) val field27: String = "",
    @ProtoNumber(28) val field28: String = "",
    @ProtoNumber(29) val field29: Int = 0,
    @ProtoNumber(30) val field30: Int = 0,
    @ProtoNumber(31) val field31: Int = 0,
    @ProtoNumber(33) val field33: Int = 0,
    @ProtoNumber(34) val field34: Int = 0,
    @ProtoNumber(36) val field36: Int = 0,
    @ProtoNumber(38) val field38: String = "",
)

/**
 * Clear-profile (cmd 91) is an oddball: unlike other oplog commands it embeds its payload proto
 * ([ModProfileProto]) **directly** as the operation's field 2 rather than through the length-prefixed
 * [OpBufProto] wrapper, so it needs its own request/operation types.
 */
@Serializable
data class ClearProfileReqProto(
    @ProtoNumber(1) val opLogList: ClearProfileListProto,
)

@Serializable
data class ClearProfileListProto(
    @ProtoNumber(1) val count: Int = 1,
    @ProtoNumber(2) val operations: List<ClearProfileOpProto> = emptyList(),
)

@Serializable
data class ClearProfileOpProto(
    @ProtoNumber(1) val cmdId: Int = 91,
    @ProtoNumber(2) val profile: ModProfileProto = ModProfileProto(),
)

/**
 * Helpers for assembling `/cgi-bin/micromsg-bin/oplog` (cgi 681) request bodies from typed
 * command payloads. WeChat writes zero-valued scalar fields explicitly, so [protoBuf] mirrors that
 * to stay byte-compatible with the native client.
 */
object OpLog {

    /** oplog command ids (decompiled `xx0.*` → `super(<id>)`). */
    const val CMD_DELETE_CONTACT = 4
    const val CMD_BLOCK_CONTACT = 8
    const val CMD_SET_NICKNAME = 64

    /**
     * Wrap a typed command payload as an [OperationProto], serializing it to the length-prefixed
     * [OpBufProto] buffer WeChat expects.
     */
    inline fun <reified T> operation(cmdId: Int, payload: T): OperationProto {
        val bytes = WeProto.encode(payload)
        return OperationProto(cmdId, OpBufProto(length = bytes.size, buf = bytes))
    }

    /** Build the full oplog request bytes for a set of [operations]. */
    fun encodeRequest(operations: List<OperationProto>): ByteArray =
        WeProto.encode(OpLogReqProto(OpLogListProto(count = operations.size, operations = operations)))

    /** Convenience: build oplog request bytes for a single typed command payload. */
    inline fun <reified T> encodeSingle(cmdId: Int, payload: T): ByteArray =
        encodeRequest(listOf(operation(cmdId, payload)))
}
