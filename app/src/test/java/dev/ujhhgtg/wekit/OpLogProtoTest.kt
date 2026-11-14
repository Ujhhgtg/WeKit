package dev.ujhhgtg.wekit

import dev.ujhhgtg.wekit.features.api.net.models.protobuf.DelContactProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.DisturbSettingProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.ModProfileProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.OpLog
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.OpLogReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SKBuiltinBufferProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SetNicknameProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.UserNameProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.WeProto
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpLogProtoTest {
    @Test
    fun clearProfileRequestMatchesLegacyJsonPayload() {
        val request = OpLog.encodeSingle(OpLog.CMD_MOD_PROFILE, ModProfileProto())
        val decoded = WeProto.decode<OpLogReqProto>(request)

        assertEquals(
            1,
            decoded.opLogList.operations.single().cmdId,
        )
        assertEquals(
            91,
            decoded.opLogList.operations.single().opBuf.length,
        )
        assertArrayEquals(legacyClearProfileRequest, request)
    }

    @Test
    fun setNicknameUsesNativeOpType() {
        val bytes = WeProto.encodeWithDefaults(SetNicknameProto(nickname = "WeKit"))
        assertArrayEquals(hex("0801120557654b6974"), bytes)
    }

    @Test
    fun deleteContactIncludesRetainChatHistory() {
        val bytes = WeProto.encodeWithDefaults(DelContactProto(UserNameProto("wxid_target")))
        assertArrayEquals(hex("0a0d0a0b777869645f74617267657410001800"), bytes)
    }

    @Test
    fun modProfileImgBufIsBinary() {
        val encoded =
            WeProto.encodeWithDefaults(ModProfileProto(imgBuf = byteArrayOf(0, 0xFF.toByte())))
        val decoded = WeProto.decode<ModProfileProto>(encoded)
        assertArrayEquals(byteArrayOf(0, 0xFF.toByte()), decoded.imgBuf)
    }

    @Test
    fun emptyBufferKeepsItsRequiredZeroLengthField() {
        assertArrayEquals(hex("0800"), WeProto.encode(SKBuiltinBufferProto(length = 0)))
    }

    @Test
    fun optionalNestedProfileFieldsAreOmittedWithDefaultEncoding() {
        val encoded =
            WeProto.encodeWithDefaults(
                ModProfileProto(disturbSetting = DisturbSettingProto()),
            )
        val decoded = WeProto.decode<ModProfileProto>(encoded)

        assertEquals(DisturbSettingProto(), decoded.disturbSetting)
    }

    private val legacyClearProfileRequest =
        hex(
            "0a67080112630801125f085b125b08800112020a001a020a0020002a020a0032020a00" +
                "380040004a0050005a0062006a007001800100880100980100a00100a80100b00100" +
                "b80100c20100c80100da0100e20100e80100f00100f80100880200900200a00200b20200",
        )

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
