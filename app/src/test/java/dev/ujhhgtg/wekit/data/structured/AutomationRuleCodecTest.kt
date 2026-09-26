package dev.ujhhgtg.wekit.data.structured

import dev.ujhhgtg.wekit.data.entity.MomentAutomationContentTypeEntity
import dev.ujhhgtg.wekit.data.entity.MomentAutomationKeywordEntity
import dev.ujhhgtg.wekit.data.entity.RedPacketKeywordEntity
import dev.ujhhgtg.wekit.data.entity.TransferKeywordEntity
import dev.ujhhgtg.wekit.features.items.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.items.AutomationToggleRule
import dev.ujhhgtg.wekit.features.items.moments.MomentActionRule
import dev.ujhhgtg.wekit.features.items.moments.MomentAgeRule
import dev.ujhhgtg.wekit.features.items.moments.MomentAutomationAction
import dev.ujhhgtg.wekit.features.items.moments.MomentAutomationOverrides
import dev.ujhhgtg.wekit.features.items.moments.MomentIntervalRule
import dev.ujhhgtg.wekit.features.items.moments.MomentTypeRule
import dev.ujhhgtg.wekit.features.items.payment.RedPacketSettings
import dev.ujhhgtg.wekit.features.items.payment.TransferSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Pure migration reconstruction; no database, hooks, Android context or UI is instantiated. */
class AutomationRuleCodecTest {
    @Test
    fun `red packet groups preserve inheritance disabled values and ordered duplicate keywords`() {
        val input = RedPacketSettings.RuleOverrides(
            grab = AutomationToggleRule(false),
            timeRange = AutomationTimeRangeRule(false, 123, 456),
            keyword = AutomationKeywordRule(false, AutomationKeywordMode.REGEX, listOf("重复", "", "重复"), "[a-z]+", true),
            skipKeyword = AutomationKeywordRule(true, strings = emptyList()),
            delay = RedPacketSettings.DelayRule(false, "0005", ""),
            autoReply = RedPacketSettings.ReplyRule(false, "  retained  "),
        )
        val row = input.toEntity("GROUP_MEMBER", "group@im.chatroom", "member")
        val keywords = input.keyword!!.strings.mapIndexed { index, text ->
            RedPacketKeywordEntity("GROUP_MEMBER", "group@im.chatroom", "member", "KEYWORD", index, text)
        }.reversed()
        val decoded = row.toOverrides(keywords)
        assertEquals(input, decoded)
        assertNull(decoded.grabSelf)
        assertNull(decoded.receiveMode)
        assertNotEquals(decoded.keyword, decoded.skipKeyword)
    }

    @Test
    fun `transfer retains textual amounts and independent group member inheritance`() {
        val input = TransferSettings.RuleOverrides(
            amountRange = TransferSettings.AmountRangeRule(false, "0001.20", ""),
            memoKeyword = AutomationKeywordRule(true, AutomationKeywordMode.EXACT, listOf("", "memo", "memo")),
            delay = TransferSettings.DelayRule(false, "", "000"),
            notification = AutomationToggleRule(false),
        )
        val row = input.toEntity("GROUP_MEMBER", "group@chatroom", "payer")
        val keywords = input.memoKeyword!!.strings.mapIndexed { index, text ->
            TransferKeywordEntity("GROUP_MEMBER", "group@chatroom", "payer", index, text)
        }
        assertEquals(input, row.toOverrides(keywords))
    }

    @Test
    fun `repost retains hidden action and distinguishes inherited disabled and empty type sets`() {
        val base = MomentAutomationOverrides(
            action = MomentActionRule(false, MomentAutomationAction.UNLIKE),
            interval = MomentIntervalRule(false, "000"),
            keyword = AutomationKeywordRule(false, AutomationKeywordMode.REGEX, listOf("a", "a", ""), "x+"),
            maximumAge = MomentAgeRule(false, ""),
        )
        val keywords = base.keyword!!.strings.mapIndexed { index, text ->
            MomentAutomationKeywordEntity("REPOST", "CONTACT", "owner", index, text)
        }
        listOf(null, MomentTypeRule(false, emptySet()), MomentTypeRule(true, emptySet()), MomentTypeRule(false, setOf(1, 54))).forEach { typeRule ->
            val input = base.copy(contentType = typeRule)
            val types = typeRule?.typeIds.orEmpty().map { MomentAutomationContentTypeEntity("REPOST", "CONTACT", "owner", it) }
            assertEquals(input, input.toEntity("REPOST", "CONTACT", "owner").toOverrides(keywords, types))
        }
    }

    @Test
    fun `partial inherited and unknown enum groups fail reconstruction`() {
        val valid = RedPacketSettings.RuleOverrides(keyword = AutomationKeywordRule()).toEntity("CONTACT", "owner", "")
        assertThrows(IllegalArgumentException::class.java) { valid.copy(keywordEnabled = null).toOverrides(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(keywordMode = null).toOverrides(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(keywordMode = "FUTURE_MODE").toOverrides(emptyList()) }
        val inherited = RedPacketSettings.RuleOverrides().toEntity("CONTACT", "owner", "")
        assertThrows(IllegalArgumentException::class.java) {
            inherited.toOverrides(listOf(RedPacketKeywordEntity("CONTACT", "owner", "", "KEYWORD", 0, "hidden")))
        }
    }

    @Test
    fun `global rules require every group while contact rules can inherit all groups`() {
        val global = RedPacketSettings.RuleSet()
        assertEquals(global, global.asOverrides().toEntity("GLOBAL", "", "").toOverrides(emptyList()).asGlobalRules())
        val inherited = RedPacketSettings.RuleOverrides().toEntity("CONTACT", "owner", "").toOverrides(emptyList())
        assertEquals(RedPacketSettings.RuleOverrides(), inherited)
        assertThrows(IllegalArgumentException::class.java) { inherited.asGlobalRules() }
    }
}
