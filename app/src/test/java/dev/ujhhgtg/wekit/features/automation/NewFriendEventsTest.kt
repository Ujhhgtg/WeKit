package dev.ujhhgtg.wekit.features.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NewFriendEventsTest {
    @Test
    fun `attribute payload yields the contact wxid and its verify username`() {
        val raw =
            """<msg fromusername="wxid_abc" encryptusername="v3_0123@stranger" fromnickname="Alice &amp; Bob" """ +
                """content="hi" ticket="v4_ticket" scene="30" />"""
        assertEquals(
            NewFriendRequest("wxid_abc", "v3_0123@stranger", "v4_ticket", 30, "Alice & Bob"),
            NewFriendEvents.parse(raw),
        )
    }

    @Test
    fun `tag and query payloads fall back to the verify username`() {
        val raw =
            "<msg><encryptusername><![CDATA[v3_9@stranger]]></encryptusername>" +
                "<url>weixin://verify?antispamticket=v4_x%2By&amp;scene=17</url></msg>"
        assertEquals(
            NewFriendRequest("v3_9@stranger", "v3_9@stranger", "v4_x+y", 17),
            NewFriendEvents.parse(raw),
        )
    }

    @Test
    fun `group, official and ticketless payloads are ignored`() {
        assertNull(NewFriendEvents.parse("""<msg fromusername="123@chatroom" ticket="t" />"""))
        assertNull(NewFriendEvents.parse("""<msg fromusername="gh_abc" ticket="t" />"""))
        assertNull(NewFriendEvents.parse("""<msg fromusername="wxid_abc" />"""))
    }
}
