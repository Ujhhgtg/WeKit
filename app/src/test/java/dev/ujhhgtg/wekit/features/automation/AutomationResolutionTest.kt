package dev.ujhhgtg.wekit.features.automation

import dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutomationResolutionTest {
    private object Spec : AutomationSpec("TEST") {
        val on = AutomationRuleKey("on", AutomationCodecs.toggle, AutomationToggleRule())
        val limit = AutomationRuleKey("limit", AutomationCodecs.limit, AutomationLimitRule())
        override val keys = listOf(on, limit)
        override val scopeTypes = AutomationScopeType.entries.toSet()
        override val titleRes = 0
        override val editors = emptyList<AutomationRuleEditor<*>>()
        override val sections = emptyList<AutomationSection>()
    }

    private fun stored(
        scope: AutomationScope,
        vararg values: Pair<AutomationRuleKey<*>, Any>,
        presets: List<String> = emptyList(),
    ) =
        scope to
            AutomationStoredScope(
                scope,
                AutomationRuleValues(values.associate { it.first.id to it.second }),
                presets,
            )

    private fun limit(value: Long) = AutomationLimitRule(true, value)

    @Test
    fun `resolution applies global, category, contact presets, contact and member in order`() {
        val snapshot =
            AutomationSnapshot(
                Spec,
                mapOf(
                    stored(
                        AutomationScope.Global,
                        Spec.on to AutomationToggleRule(true),
                        Spec.limit to limit(1),
                    ),
                    stored(
                        AutomationScope.category(AutomationTalkerCategory.GROUP),
                        Spec.limit to limit(2),
                    ),
                    stored(
                        AutomationScope.preset("a"),
                        Spec.limit to limit(3),
                        Spec.on to AutomationToggleRule(false),
                    ),
                    stored(AutomationScope.preset("b"), Spec.limit to limit(4)),
                    stored(AutomationScope.contact("g@chatroom"), presets = listOf("a", "b")),
                    stored(
                        AutomationScope.member("g@chatroom", "m"),
                        Spec.on to AutomationToggleRule(true),
                    ),
                ),
            )
        assertEquals(limit(1), snapshot.resolve("friend")[Spec.limit])
        assertEquals(limit(2), snapshot.resolve("other@chatroom")[Spec.limit])

        val group = snapshot.resolve("g@chatroom", "x")
        assertEquals(limit(4), group[Spec.limit], "later presets win")
        assertFalse(group[Spec.on].enabled)
        assertTrue(
            snapshot.resolve("g@chatroom", "m")[Spec.on].enabled,
            "member overrides beat group presets",
        )

        assertEquals(
            limit(2),
            snapshot.parentRules(AutomationScope.contact("g@chatroom"))[Spec.limit],
        )
        assertEquals(
            limit(4),
            snapshot.parentRules(AutomationScope.member("g@chatroom", "m"))[Spec.limit],
        )
    }

    @Test
    fun `inherited sources name the most specific ancestor or preset of each rule`() {
        val group = AutomationScope.contact("g@chatroom")
        val member = AutomationScope.member("g@chatroom", "m")
        val snapshot =
            AutomationSnapshot(
                Spec,
                mapOf(
                    stored(
                        AutomationScope.Global,
                        Spec.on to AutomationToggleRule(true),
                        Spec.limit to limit(1),
                    ),
                    stored(
                        AutomationScope.category(AutomationTalkerCategory.GROUP),
                        Spec.limit to limit(2),
                    ),
                    stored(AutomationScope.preset("a"), Spec.on to AutomationToggleRule(false)),
                    stored(group, presets = listOf("a")),
                    stored(AutomationScope.preset("draft"), Spec.limit to limit(5)),
                ),
            )
        val sources = snapshot.inheritedSources(member, emptyList())
        assertEquals(
            AutomationScope.preset("a"),
            sources[Spec.on.id],
            "a group's preset beats the global value",
        )
        assertEquals(
            AutomationScope.category(AutomationTalkerCategory.GROUP),
            sources[Spec.limit.id],
        )

        val withDraftPreset = snapshot.inheritedSources(member, listOf("draft"))
        assertEquals(
            AutomationScope.preset("draft"),
            withDraftPreset[Spec.limit.id],
            "the scope's own presets come last",
        )

        assertTrue(
            snapshot.inheritedSources(AutomationScope.Global, emptyList()).isEmpty(),
            "GLOBAL inherits only defaults",
        )
    }

    @Test
    fun `variants resolve each preset of the most specific multi-preset scope separately`() {
        val snapshot =
            AutomationSnapshot(
                Spec,
                mapOf(
                    stored(AutomationScope.preset("a"), Spec.limit to limit(3)),
                    stored(AutomationScope.preset("b"), Spec.on to AutomationToggleRule(true)),
                    stored(AutomationScope.contact("friend"), presets = listOf("a", "b")),
                ),
            )
        val variants = snapshot.resolveVariants("friend")
        assertEquals(2, variants.size)
        assertEquals(limit(3), variants[0][Spec.limit])
        assertFalse(variants[0][Spec.on].enabled)
        assertTrue(variants[1][Spec.on].enabled)
        assertEquals(Spec.limit.default, variants[1][Spec.limit])
        assertEquals(1, snapshot.resolveVariants("stranger").size)
    }

    @Test
    fun `target rule matches chats, members and exclusions`() {
        val specific =
            AutomationTargetRule(
                mode = AutomationTargetRule.Mode.SPECIFIC,
                include = setOf("friend", "g@chatroom"),
                includeMembers = setOf("h@chatroom/m"),
                excludeMembers = setOf("g@chatroom/bad"),
            )
        assertTrue(specific.matches("friend", "friend"))
        assertTrue(specific.matches("g@chatroom", "anyone"))
        assertFalse(specific.matches("g@chatroom", "bad"))
        assertTrue(specific.matches("h@chatroom", "m"))
        assertFalse(specific.matches("h@chatroom", "n"))
        assertFalse(specific.matches("stranger", "stranger"))

        val categories =
            AutomationTargetRule(
                mode = AutomationTargetRule.Mode.CATEGORIES,
                categories = setOf(AutomationTalkerCategory.GROUP),
                exclude = setOf("muted@chatroom"),
            )
        assertTrue(categories.matches("g@chatroom", "m"))
        assertFalse(categories.matches("muted@chatroom", "m"))
        assertFalse(categories.matches("gh_official", "gh_official"))
    }

    @Test
    fun `switched-off chats and presets are skipped at runtime`() {
        val switchable =
            object : AutomationSpec("SWITCH") {
                override val keys = listOf(Spec.on, Spec.limit)
                override val scopeSwitch = Spec.on
                override val scopeTypes = AutomationScopeType.entries.toSet()
                override val titleRes = 0
                override val editors = emptyList<AutomationRuleEditor<*>>()
                override val sections = emptyList<AutomationSection>()
            }
        val off = AutomationToggleRule(false)
        val snapshot =
            AutomationSnapshot(
                switchable,
                mapOf(
                    stored(AutomationScope.Global, Spec.limit to limit(1)),
                    stored(AutomationScope.preset("a"), Spec.limit to limit(2)),
                    stored(AutomationScope.preset("b"), Spec.limit to limit(3), Spec.on to off),
                    stored(AutomationScope.contact("friend"), presets = listOf("a", "b")),
                    stored(
                        AutomationScope.contact("paused"),
                        Spec.limit to limit(4),
                        Spec.on to off,
                    ),
                ),
            )
        assertEquals(listOf(limit(2)), snapshot.resolveVariants("friend").map { it[Spec.limit] })
        assertEquals(limit(1), snapshot.resolve("paused")[Spec.limit])
        assertFalse(snapshot.isSwitchedOn(AutomationScope.contact("paused")))
    }

    @Test
    fun `time range handles overnight windows and equal bounds`() {
        val overnight = AutomationTimeRangeRule(true, 22 * 3600, 6 * 3600 + 30)
        assertTrue(overnight.containsSecond(23 * 3600))
        assertTrue(overnight.containsSecond(6 * 3600 + 29))
        assertFalse(overnight.containsSecond(6 * 3600 + 30))
        assertFalse(overnight.containsSecond(12 * 3600))
        assertTrue(AutomationTimeRangeRule(true, 600, 600).containsSecond(0))
    }
}
