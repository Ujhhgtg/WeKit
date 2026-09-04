package dev.ujhhgtg.wekit.utils.math

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DecimalExpressionTest {

    @Test
    fun `evaluates arithmetic with precedence and variables`() {
        assertExpression("value * 2 + 50", "125", "300")
        assertExpression("(value + 5) * 2", "10", "30")
        assertExpression("value / 4", "10", "2.5")
    }

    @Test
    fun `supports power aliases and right associativity`() {
        assertExpression("2 ^ 3", "0", "8")
        assertExpression("2 ** 3", "0", "8")
        assertExpression("2 ^ 3 ^ 2", "0", "512")
        assertExpression("-2 ^ 2", "0", "-4")
        assertExpression("2 ^ -2", "0", "0.25")
    }

    @Test
    fun `supports floor division and matching modulo`() {
        assertExpression("7 // 3", "0", "2")
        assertExpression("-7 // 3", "0", "-3")
        assertExpression("7 // -3", "0", "-3")
        assertExpression("-7 % 3", "0", "2")
        assertExpression("7 % -3", "0", "-2")
    }

    @Test
    fun `supports square root and both round forms`() {
        assertExpression("sqrt(81)", "0", "9")
        assertExpression("round(sqrt(2), 10)", "0", "1.4142135624")
        assertExpression("round(2.5)", "0", "3")
        assertExpression("round(2.345, 2)", "0", "2.35")
        assertExpression("round(149, -2)", "0", "100")
    }

    @Test
    fun `rejects syntax outside the expression language`() {
        assertThrows(DecimalExpressionException::class.java) {
            DecimalExpression.parse("unknown + 1", setOf("value"))
        }
        assertThrows(DecimalExpressionException::class.java) {
            DecimalExpression.parse("Math.sqrt(value)", setOf("value"))
        }
        assertThrows(DecimalExpressionException::class.java) {
            DecimalExpression.parse("round(value, 2, 3)", setOf("value"))
        }
    }

    @Test
    fun `rejects statically invalid arithmetic while parsing`() {
        assertThrows(DecimalExpressionException::class.java) {
            expression("2 / 0")
        }
        assertThrows(DecimalExpressionException::class.java) {
            expression("value / 0")
        }
        assertThrows(DecimalExpressionException::class.java) {
            expression("sqrt(-1)")
        }
        assertThrows(DecimalExpressionException::class.java) {
            expression("value ^ 0.5")
        }
        assertThrows(DecimalExpressionException::class.java) {
            expression("round(value, 1.5)")
        }
        assertThrows(DecimalExpressionException::class.java) {
            expression("round(value, 2147483648)")
        }
    }

    @Test
    fun `reports value dependent runtime arithmetic errors`() {
        assertThrows(DecimalExpressionException::class.java) {
            expression("10 / (value - value)").evaluate(mapOf("value" to BigDecimal.TEN))
        }
        assertThrows(DecimalExpressionException::class.java) {
            expression("sqrt(value)").evaluate(mapOf("value" to BigDecimal.ONE.negate()))
        }
        assertThrows(DecimalExpressionException::class.java) {
            expression("2 ^ value").evaluate(mapOf("value" to BigDecimal("0.5")))
        }
    }

    private fun assertExpression(source: String, value: String, expected: String) {
        assertEquals(
            0,
            expression(source).evaluate(mapOf("value" to value.toBigDecimal())).compareTo(expected.toBigDecimal()),
        )
    }

    private fun expression(source: String) = DecimalExpression.parse(source, setOf("value"))
}
