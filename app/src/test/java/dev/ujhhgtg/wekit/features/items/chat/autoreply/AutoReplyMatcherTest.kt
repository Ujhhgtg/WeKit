package dev.ujhhgtg.wekit.features.items.chat.autoreply

import dev.ujhhgtg.wekit.features.automation.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyStep
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRuleValues
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoReplyMatcherTest {
    private val spec = AutoReplyAutomation

    private fun rules(vararg values: Pair<AutomationRuleKey<*>, Any>) =
        AutomationRules.Defaults.overlay(
            AutomationRuleValues(
                mapOf(
                    spec.reply.id to
                        AutomationReplyRule(true, listOf(AutomationReplyStep(content = "hi"))),
                ) + values.associate { it.first.id to it.second },
            ),
        )

    private fun text(content: String, group: Boolean = false, atMe: Boolean = false) =
        AutoReplyMessage(
            if (group) "g@chatroom" else "friend",
            "friend",
            content,
            isText = true,
            isGroup = group,
            atMe = atMe,
        )

    private val pat =
        AutoReplyMessage(
            "friend",
            "friend",
            "",
            isText = false,
            isGroup = false,
            isPat = true,
            patMe = true,
        )

    @Test
    fun `a disabled keyword matches any message except excluded text`() {
        val r = rules(spec.excludeKeyword to AutomationKeywordRule(true, strings = listOf("stop")))
        assertTrue(AutoReplyMatcher.matches(r, text("hello")))
        assertFalse(AutoReplyMatcher.matches(r, text("please stop")))
        assertTrue(AutoReplyMatcher.matches(r, pat))
    }

    @Test
    fun `keywords only match text and respect the mode`() {
        val exact =
            rules(
                spec.keyword to
                    AutomationKeywordRule(true, AutomationKeywordMode.EXACT, listOf("ping")),
            )
        assertTrue(AutoReplyMatcher.matches(exact, text("ping")))
        assertFalse(AutoReplyMatcher.matches(exact, text("ping!")))
        assertFalse(AutoReplyMatcher.matches(exact, pat))
    }

    @Test
    fun `mention triggers require groups and the mention`() {
        val r = rules(spec.atTrigger to AutoReplyAtTrigger.AT_ME)
        assertFalse(AutoReplyMatcher.matches(r, text("hi")))
        assertFalse(AutoReplyMatcher.matches(r, text("hi", group = true)))
        assertTrue(AutoReplyMatcher.matches(r, text("hi", group = true, atMe = true)))
    }

    @Test
    fun `pat trigger only fires on pats of me, bypassing keywords`() {
        val r =
            rules(
                spec.patTrigger to AutomationToggleRule(true),
                spec.keyword to AutomationKeywordRule(true, strings = listOf("never")),
            )
        assertTrue(AutoReplyMatcher.matches(r, pat))
        assertFalse(AutoReplyMatcher.matches(r, pat.copy(patMe = false)))
        assertFalse(AutoReplyMatcher.matches(r, text("never")))
    }

    @Test
    fun `disabled rules and rules without steps never match`() {
        assertFalse(
            AutoReplyMatcher.matches(
                rules(spec.enabled to AutomationToggleRule(false)),
                text("hi"),
            ),
        )
        assertFalse(
            AutoReplyMatcher.matches(rules(spec.reply to AutomationReplyRule(true)), text("hi")),
        )
    }
}
