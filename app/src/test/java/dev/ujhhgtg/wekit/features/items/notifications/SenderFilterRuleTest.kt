package dev.ujhhgtg.wekit.features.items.notifications

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SenderFilterRuleTest {
    @Test
    fun `tokens match ids and names both ways, or a name prefix of the text`() {
        val only = SenderFilterRule(only = listOf("Alice", "wxid_boss"))
        assertTrue(only.allows("wxid_1", "alice wong", "hi"))
        assertTrue(only.allows("wxid_boss", "", "hi"))
        assertTrue(only.allows("wxid_2", "Al", "hi"), "a short name contained in the token matches")
        assertTrue(only.allows("wxid_3", "", "alice: forwarded"))
        assertFalse(only.allows("wxid_4", "Bob", "hi"))
    }

    @Test
    fun `block tokens win over an empty only list`() {
        val block = SenderFilterRule(block = listOf("spam"))
        assertFalse(block.allows("wxid_1", "spambot", "hi"))
        assertTrue(block.allows("wxid_2", "friend", "hi"))
    }
}
