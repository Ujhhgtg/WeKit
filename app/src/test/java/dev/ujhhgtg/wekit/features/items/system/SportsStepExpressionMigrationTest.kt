package dev.ujhhgtg.wekit.features.items.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SportsStepExpressionMigrationTest {

    @Test
    fun `migrates fixed and multiplier modes`() {
        assertEquals("12000", migrateSportsStepExpression("FIXED", 12_000L))
        assertEquals("value * 2", migrateSportsStepExpression("MULTIPLIER", 2L))
    }

    @Test
    fun `migrates an unset legacy value to the identity expression`() {
        assertEquals("value", migrateSportsStepExpression("FIXED", -1L))
        assertEquals("value", migrateSportsStepExpression("MULTIPLIER", -1L))
    }
}
