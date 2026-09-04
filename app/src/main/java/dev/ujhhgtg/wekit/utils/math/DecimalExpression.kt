package dev.ujhhgtg.wekit.utils.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * A small, deterministic decimal expression language for user-configurable arithmetic.
 *
 * The language intentionally has no access to BeanShell, Python, reflection, or application
 * objects. Callers explicitly declare the variable names an expression may reference. It supports
 * parentheses, unary signs, `+`, `-`, `*`, `/`, floor division `//`, modulo `%`, integer powers
 * `^`/`**`, `sqrt(x)`, `round(x)`, and `round(x, digits)`. Decimal operations use 34 significant
 * digits and `HALF_UP` rounding.
 */
class DecimalExpression private constructor(
    private val root: Node,
) {

    fun evaluate(variables: Map<String, BigDecimal> = emptyMap()): BigDecimal = try {
        root.evaluate(variables)
    } catch (error: DecimalExpressionException) {
        throw error
    } catch (error: ArithmeticException) {
        throw DecimalExpressionException(error.message ?: "Arithmetic error", cause = error)
    }

    companion object {
        private const val MAX_EXPRESSION_LENGTH = 1024

        fun parse(source: String, allowedVariables: Set<String> = emptySet()): DecimalExpression {
            if (source.isBlank()) throw DecimalExpressionException("Expression is empty", 0)
            if (source.length > MAX_EXPRESSION_LENGTH) {
                throw DecimalExpressionException(
                    "Expression exceeds $MAX_EXPRESSION_LENGTH characters",
                    MAX_EXPRESSION_LENGTH,
                )
            }
            val root = Parser(source, allowedVariables).parse()
            root.validateConstantSubexpressions()
            return DecimalExpression(root)
        }
    }
}

class DecimalExpressionException(
    message: String,
    val position: Int? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(
    if (position == null) message else "$message at position ${position + 1}",
    cause,
)

private val DECIMAL_CONTEXT = MathContext(34, RoundingMode.HALF_UP)
private val TWO = BigDecimal.valueOf(2L)
private val MAX_POWER = BigInteger.valueOf(10_000L)
private const val MAX_ROUND_DIGITS = 1_000

private sealed interface Node {
    fun evaluate(variables: Map<String, BigDecimal>): BigDecimal
}

private data class LiteralNode(val value: BigDecimal) : Node {
    override fun evaluate(variables: Map<String, BigDecimal>) = value
}

private data class VariableNode(val name: String) : Node {
    override fun evaluate(variables: Map<String, BigDecimal>): BigDecimal =
        variables[name] ?: throw DecimalExpressionException("Missing variable '$name'")
}

private data class UnaryNode(val negate: Boolean, val operand: Node) : Node {
    override fun evaluate(variables: Map<String, BigDecimal>): BigDecimal {
        val value = operand.evaluate(variables)
        return if (negate) value.negate(DECIMAL_CONTEXT) else value
    }
}

private enum class BinaryOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    FLOOR_DIVIDE,
    MODULO,
    POWER,
}

private data class BinaryNode(
    val operator: BinaryOperator,
    val left: Node,
    val right: Node,
) : Node {
    override fun evaluate(variables: Map<String, BigDecimal>): BigDecimal {
        val lhs = left.evaluate(variables)
        val rhs = right.evaluate(variables)
        return when (operator) {
            BinaryOperator.ADD -> lhs.add(rhs, DECIMAL_CONTEXT)
            BinaryOperator.SUBTRACT -> lhs.subtract(rhs, DECIMAL_CONTEXT)
            BinaryOperator.MULTIPLY -> lhs.multiply(rhs, DECIMAL_CONTEXT)
            BinaryOperator.DIVIDE -> divide(lhs, rhs)
            BinaryOperator.FLOOR_DIVIDE -> floorDivide(lhs, rhs)
            BinaryOperator.MODULO -> lhs.subtract(floorDivide(lhs, rhs).multiply(rhs))
            BinaryOperator.POWER -> power(lhs, rhs)
        }
    }

    private fun divide(lhs: BigDecimal, rhs: BigDecimal): BigDecimal {
        if (rhs.signum() == 0) throw DecimalExpressionException("Division by zero")
        return lhs.divide(rhs, DECIMAL_CONTEXT)
    }

    private fun floorDivide(lhs: BigDecimal, rhs: BigDecimal): BigDecimal {
        if (rhs.signum() == 0) throw DecimalExpressionException("Division by zero")
        var quotient = lhs.divideToIntegralValue(rhs)
        if (lhs.remainder(rhs).signum() != 0 && lhs.signum() != rhs.signum()) {
            quotient = quotient.subtract(BigDecimal.ONE)
        }
        return quotient.setScale(0)
    }

    private fun power(base: BigDecimal, exponentValue: BigDecimal): BigDecimal {
        val exponentInt = powerExponent(exponentValue)
        if (exponentInt >= 0) return base.pow(exponentInt, DECIMAL_CONTEXT)
        if (base.signum() == 0) throw DecimalExpressionException("Zero cannot have a negative exponent")
        return BigDecimal.ONE.divide(base.pow(-exponentInt, DECIMAL_CONTEXT), DECIMAL_CONTEXT)
    }
}

private data class FunctionNode(val name: String, val arguments: List<Node>) : Node {
    override fun evaluate(variables: Map<String, BigDecimal>): BigDecimal {
        val values = arguments.map { it.evaluate(variables) }
        return when (name) {
            "sqrt" -> squareRoot(values.single())
            "round" -> round(values)
            else -> error("Function was validated while parsing: $name")
        }
    }

    private fun squareRoot(value: BigDecimal): BigDecimal {
        if (value.signum() < 0) throw DecimalExpressionException("Cannot take the square root of a negative number")
        if (value.signum() == 0) return BigDecimal.ZERO

        val magnitude = value.precision() - value.scale()
        var estimate = BigDecimal.ONE.scaleByPowerOfTen(Math.floorDiv(magnitude, 2))
        repeat(DECIMAL_CONTEXT.precision + 10) {
            val next = estimate.add(value.divide(estimate, DECIMAL_CONTEXT), DECIMAL_CONTEXT)
                .divide(TWO, DECIMAL_CONTEXT)
            if (next.compareTo(estimate) == 0) return next
            estimate = next
        }
        return estimate
    }

    private fun round(values: List<BigDecimal>): BigDecimal {
        val digits = if (values.size == 1) 0 else roundDigits(values[1])
        return values[0].setScale(digits, RoundingMode.HALF_UP)
    }
}

private fun powerExponent(value: BigDecimal): Int {
    val exponent = try {
        value.toBigIntegerExact()
    } catch (error: ArithmeticException) {
        throw DecimalExpressionException("Power exponent must be an integer", cause = error)
    }
    if (exponent.abs() > MAX_POWER) {
        throw DecimalExpressionException("Power exponent must be between -10000 and 10000")
    }
    return exponent.toInt()
}

private fun roundDigits(value: BigDecimal): Int {
    val digits = try {
        value.toBigIntegerExact()
    } catch (error: ArithmeticException) {
        throw DecimalExpressionException("round digits must be an integer", cause = error)
    }
    if (digits < BigInteger.valueOf(-MAX_ROUND_DIGITS.toLong()) ||
        digits > BigInteger.valueOf(MAX_ROUND_DIGITS.toLong())
    ) {
        throw DecimalExpressionException("round digits must be between -1000 and 1000")
    }
    return digits.toInt()
}

/**
 * Evaluates variable-free subtrees while parsing, and validates operands whose invalidity is
 * independent of the remaining variables. This keeps expressions such as `2 / 0`,
 * `value / 0`, and `value ^ 0.5` out of persisted configuration while leaving genuinely
 * value-dependent failures to runtime handling.
 */
private fun Node.validateConstantSubexpressions(): BigDecimal? = when (this) {
    is LiteralNode -> value
    is VariableNode -> null
    is UnaryNode -> operand.validateConstantSubexpressions()?.let {
        if (negate) it.negate(DECIMAL_CONTEXT) else it
    }
    is BinaryNode -> {
        val leftValue = left.validateConstantSubexpressions()
        val rightValue = right.validateConstantSubexpressions()
        when (operator) {
            BinaryOperator.DIVIDE, BinaryOperator.FLOOR_DIVIDE, BinaryOperator.MODULO -> {
                if (rightValue?.signum() == 0) throw DecimalExpressionException("Division by zero")
            }
            BinaryOperator.POWER -> if (rightValue != null) powerExponent(rightValue)
            else -> Unit
        }
        if (leftValue != null && rightValue != null) evaluate(emptyMap()) else null
    }
    is FunctionNode -> {
        val values = arguments.map(Node::validateConstantSubexpressions)
        if (name == "round" && values.size == 2) values[1]?.let(::roundDigits)
        if (values.all { it != null }) evaluate(emptyMap()) else null
    }
}

private class Parser(
    private val source: String,
    private val allowedVariables: Set<String>,
) {
    private var index = 0

    fun parse(): Node {
        val result = parseAdditive()
        skipWhitespace()
        if (index != source.length) fail("Unexpected '${source[index]}'")
        return result
    }

    private fun parseAdditive(): Node {
        var result = parseMultiplicative()
        while (true) {
            result = when {
                consume("+") -> BinaryNode(BinaryOperator.ADD, result, parseMultiplicative())
                consume("-") -> BinaryNode(BinaryOperator.SUBTRACT, result, parseMultiplicative())
                else -> return result
            }
        }
    }

    private fun parseMultiplicative(): Node {
        var result = parseUnary()
        while (true) {
            result = when {
                consume("//") -> BinaryNode(BinaryOperator.FLOOR_DIVIDE, result, parseUnary())
                consume("/") -> BinaryNode(BinaryOperator.DIVIDE, result, parseUnary())
                consume("%") -> BinaryNode(BinaryOperator.MODULO, result, parseUnary())
                startsWith("**") -> return result
                consume("*") -> BinaryNode(BinaryOperator.MULTIPLY, result, parseUnary())
                else -> return result
            }
        }
    }

    private fun parseUnary(): Node = when {
        consume("+") -> UnaryNode(negate = false, parseUnary())
        consume("-") -> UnaryNode(negate = true, parseUnary())
        else -> parsePower()
    }

    private fun parsePower(): Node {
        val base = parsePrimary()
        return when {
            consume("**") || consume("^") -> BinaryNode(BinaryOperator.POWER, base, parseUnary())
            else -> base
        }
    }

    private fun parsePrimary(): Node {
        skipWhitespace()
        if (consume("(")) {
            val result = parseAdditive()
            expect(")")
            return result
        }
        if (index >= source.length) fail("Expected a number, variable, function, or '('")

        val current = source[index]
        if (current.isDigit() || current == '.' && source.getOrNull(index + 1)?.isDigit() == true) {
            return LiteralNode(parseNumber())
        }
        if (current.isLetter() || current == '_') {
            val namePosition = index
            val name = parseIdentifier()
            if (consume("(")) return parseFunction(name, namePosition)
            if (name !in allowedVariables) fail("Unknown variable '$name'", namePosition)
            return VariableNode(name)
        }
        fail("Unexpected '$current'")
    }

    private fun parseFunction(name: String, namePosition: Int): Node {
        val arguments = mutableListOf<Node>()
        if (!consume(")")) {
            do {
                arguments += parseAdditive()
            } while (consume(","))
            expect(")")
        }
        when (name) {
            "sqrt" -> if (arguments.size != 1) fail("sqrt expects 1 argument", namePosition)
            "round" -> if (arguments.size !in 1..2) fail("round expects 1 or 2 arguments", namePosition)
            else -> fail("Unknown function '$name'", namePosition)
        }
        return FunctionNode(name, arguments)
    }

    private fun parseNumber(): BigDecimal {
        val start = index
        while (source.getOrNull(index)?.isDigit() == true) index++
        if (source.getOrNull(index) == '.') {
            index++
            while (source.getOrNull(index)?.isDigit() == true) index++
        }
        if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
            index++
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
            val exponentStart = index
            while (source.getOrNull(index)?.isDigit() == true) index++
            if (index == exponentStart) fail("Expected exponent digits")
        }
        return try {
            source.substring(start, index).toBigDecimal()
        } catch (error: NumberFormatException) {
            throw DecimalExpressionException("Invalid number", start, error)
        }
    }

    private fun parseIdentifier(): String {
        val start = index
        index++
        while (source.getOrNull(index)?.let { it.isLetterOrDigit() || it == '_' } == true) index++
        return source.substring(start, index)
    }

    private fun consume(token: String): Boolean {
        skipWhitespace()
        if (!startsWith(token)) return false
        index += token.length
        return true
    }

    private fun startsWith(token: String): Boolean = source.startsWith(token, index)

    private fun expect(token: String) {
        if (!consume(token)) fail("Expected '$token'")
    }

    private fun skipWhitespace() {
        while (source.getOrNull(index)?.isWhitespace() == true) index++
    }

    private fun fail(message: String, position: Int = index): Nothing =
        throw DecimalExpressionException(message, position)
}
