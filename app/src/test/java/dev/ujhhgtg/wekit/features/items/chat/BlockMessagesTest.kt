package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationMessageKind
import dev.ujhhgtg.wekit.features.automation.AutomationMessageTypeRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleValues
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockMessagesTest {
    private val spec = BlockMessagesAutomation
    private val keyword = AutomationKeywordRule(true, strings = listOf("ad"))

    @Test
    fun `app messages are classified by their appmsg type`() {
        fun app(type: Int) =
            "<msg><appmsg appid=\"\"><title>t</title><type>$type</type></appmsg></msg>"
        assertEquals(AutomationMessageKind.QUOTE, AutomationMessageKind.classify(49, app(57)))
        assertEquals(
            AutomationMessageKind.RED_PACKET,
            AutomationMessageKind.classify(49, app(2001)),
        )
        assertEquals(AutomationMessageKind.LINK, AutomationMessageKind.classify(49, app(6)))
        assertEquals(AutomationMessageKind.UNKNOWN, AutomationMessageKind.classify(49, app(999)))
        assertEquals(
            AutomationMessageKind.PAT,
            AutomationMessageKind.classify(10002, "<sysmsg type=\"pat\">"),
        )
        assertEquals(
            AutomationMessageKind.SYSTEM,
            AutomationMessageKind.classify(10002, "<sysmsg type=\"revokemsg\">"),
        )
        assertEquals(
            "reply",
            AutomationMessageKind.textOf(
                AutomationMessageKind.QUOTE,
                "<appmsg><title><![CDATA[reply]]></title></appmsg>",
            ),
        )
    }

    @Test
    fun `all kinds blocks non-text and only matching text`() {
        val all = AutomationMessageTypeRule(all = true)
        assertTrue(spec.ruleMatches(all, keyword, AutomationMessageKind.IMAGE, ""))
        assertTrue(spec.ruleMatches(all, keyword, AutomationMessageKind.TEXT, "buy ad now"))
        assertFalse(spec.ruleMatches(all, keyword, AutomationMessageKind.TEXT, "hello"))
        assertTrue(
            spec.ruleMatches(all, AutomationKeywordRule(), AutomationMessageKind.TEXT, "hello"),
        )
    }

    @Test
    fun `explicit kinds treat quotes as text`() {
        val text = AutomationMessageTypeRule(all = false, kinds = setOf(AutomationMessageKind.TEXT))
        assertTrue(
            spec.ruleMatches(text, AutomationKeywordRule(), AutomationMessageKind.QUOTE, "x"),
        )
        assertFalse(
            spec.ruleMatches(text, AutomationKeywordRule(), AutomationMessageKind.IMAGE, ""),
        )
        assertFalse(spec.ruleMatches(text, keyword, AutomationMessageKind.TEXT, "hello"))
    }

    @Test
    fun `any blocking variant blocks, and block off never does`() {
        fun rules(block: Boolean, kinds: Set<AutomationMessageKind>) =
            AutomationRules.Defaults.overlay(
                AutomationRuleValues()
                    .with(spec.block, AutomationToggleRule(block))
                    .with(spec.types, AutomationMessageTypeRule(false, kinds)),
            )
        val image = rules(true, setOf(AutomationMessageKind.IMAGE))
        val voice = rules(true, setOf(AutomationMessageKind.VOICE))
        assertTrue(
            spec.shouldBlock(listOf(image, voice), emptyList(), AutomationMessageKind.VOICE, ""),
        )
        assertFalse(
            spec.shouldBlock(listOf(image, voice), emptyList(), AutomationMessageKind.VIDEO, ""),
        )
        assertFalse(
            spec.shouldBlock(
                listOf(rules(false, setOf(AutomationMessageKind.VOICE))),
                emptyList(),
                AutomationMessageKind.VOICE,
                "",
            ),
        )
    }

    @Test
    fun `quick block all wins, then exclusions beat blocking`() {
        val blocking =
            AutomationRules.Defaults.overlay(
                AutomationRuleValues().with(spec.block, AutomationToggleRule(true)),
            )
        val exclusion = AutomationRuleValues().with(spec.block, AutomationToggleRule(false))
        assertFalse(
            spec.shouldBlock(
                listOf(blocking),
                listOf(AutomationRuleValues(), exclusion),
                AutomationMessageKind.IMAGE,
                "",
            ),
        )
        val quick =
            blocking.overlay(AutomationRuleValues().with(spec.blockAll, AutomationToggleRule(true)))
        assertTrue(
            spec.shouldBlock(listOf(quick), listOf(exclusion), AutomationMessageKind.TEXT, "hello"),
        )
    }

    @Test
    fun `switched-off chats are left alone unless quick-blocked`() {
        val blocking =
            AutomationRules.Defaults.overlay(
                AutomationRuleValues().with(spec.block, AutomationToggleRule(true)),
            )
        val off = AutomationRuleValues().with(spec.active, AutomationToggleRule(false))
        assertFalse(
            spec.shouldBlock(listOf(blocking), listOf(off), AutomationMessageKind.IMAGE, ""),
        )
        assertTrue(
            spec.shouldBlock(
                listOf(blocking),
                listOf(off, AutomationRuleValues()),
                AutomationMessageKind.IMAGE,
                "",
            ),
        )
        assertTrue(
            spec.shouldBlock(
                listOf(blocking),
                listOf(off.with(spec.blockAll, AutomationToggleRule(true))),
                AutomationMessageKind.TEXT,
                "x",
            ),
        )
    }
}
