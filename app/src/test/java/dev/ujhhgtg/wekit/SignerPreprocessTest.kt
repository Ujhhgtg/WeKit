@file:Suppress("DEPRECATION")

package dev.ujhhgtg.wekit

import dev.ujhhgtg.wekit.features.api.net.models.protobuf.AppMsgItemProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.EmojiItemProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.NewSendMsgItemProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.NewSendMsgReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SKBuiltinBufferProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SendAppMsgReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SendEmojiReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SendPatReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.UserNameProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.WeProto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignerPreprocessTest {
    @Test
    fun testNewSendMsgPreprocess() {
        val item =
            NewSendMsgItemProto(
                toUser = UserNameProto("12345@chatroom"),
                content = "@Test Hello",
            )
        val req =
            NewSendMsgReqProto(
                count = 1,
                items = listOf(item),
            )

        val bytes = WeProto.encode(req)
        assertTrue(bytes.isNotEmpty(), "Encoded bytes should not be empty")

        val decoded = WeProto.decode<NewSendMsgReqProto>(bytes)
        assertNotEquals(0, decoded.createTime, "createTime should be auto-populated")
        assertNotEquals(0, decoded.clientMsgId, "clientMsgId should be auto-populated")
        assertNotEquals(0, decoded.items[0].createTime, "item createTime should be auto-populated")
        assertNotEquals(
            0,
            decoded.items[0].clientMsgId,
            "item clientMsgId should be auto-populated",
        )
    }

    @Test
    fun testAppMsgPreprocess() {
        val req =
            SendAppMsgReqProto(
                msg = AppMsgItemProto(toUserName = "receiver_user", content = "<appmsg></appmsg>"),
            )

        val bytes = WeProto.encode(req)
        assertTrue(bytes.isNotEmpty(), "Encoded bytes should not be empty")

        val decoded = WeProto.decode<SendAppMsgReqProto>(bytes)
        assertNotEquals(0, decoded.reqTime, "reqTime should be auto-populated")
        assertTrue(decoded.signature.isNotEmpty(), "signature should be auto-populated")
        assertNotEquals(0, decoded.msg.createTime, "msg.createTime should be auto-populated")
        assertTrue(decoded.msg.clientMsgId.isNotEmpty(), "msg.clientMsgId should be auto-populated")
    }

    @Test
    fun testEmojiPreprocess() {
        val req =
            SendEmojiReqProto(
                emojiList = listOf(EmojiItemProto(md5 = "dummy_md5")),
            )

        val bytes = WeProto.encode(req)
        assertTrue(bytes.isNotEmpty(), "Encoded bytes should not be empty")

        val decoded = WeProto.decode<SendEmojiReqProto>(bytes)
        assertNotEquals(0, decoded.count, "count should be auto-populated")
        assertTrue(
            decoded.emojiList[0].clientMsgId.isNotEmpty(),
            "clientMsgId should be auto-populated",
        )
        assertEquals(
            SKBuiltinBufferProto(length = 0),
            decoded.emojiList[0].emojiBuffer,
            "emojiBuffer should retain WeChat's required empty cu5 wrapper",
        )
    }

    @Test
    fun testSendPatPreprocess() {
        val req =
            SendPatReqProto(
                fromUser = "wxid_sender",
                chatUserName = "group@chatroom",
                pattedUser = "patted_user",
            )

        val bytes = WeProto.encode(req)
        assertTrue(bytes.isNotEmpty(), "Encoded bytes should not be empty")

        val decoded = WeProto.decode<SendPatReqProto>(bytes)
        assertEquals("wxid_sender", decoded.fromUser, "fromUser must be the sender's wxid")
        assertEquals("group@chatroom", decoded.chatUserName)
        assertTrue(decoded.msgPointer.isNotEmpty(), "msgPointer should be auto-populated")
    }
}
