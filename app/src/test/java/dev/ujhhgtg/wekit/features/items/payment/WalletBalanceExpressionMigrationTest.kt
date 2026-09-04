package dev.ujhhgtg.wekit.features.items.payment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WalletBalanceExpressionMigrationTest {

    @Test
    fun `migrates fixed amount to a constant expression`() {
        assertEquals("100", migrateWalletBalanceExpression("100.00", "fixed"))
    }

    @Test
    fun `migrates increase and decrease modes with value as the original amount`() {
        assertEquals("value + 20", migrateWalletBalanceExpression("20.00", "increase"))
        assertEquals("value - 20", migrateWalletBalanceExpression("-20", "decrease"))
    }

    @Test
    fun `preserves legacy amount parsing and normalization`() {
        assertEquals("value + 1234.57", migrateWalletBalanceExpression("￥1,234.567", "increase"))
        assertEquals("0", migrateWalletBalanceExpression("not an amount", "fixed"))
    }
}
