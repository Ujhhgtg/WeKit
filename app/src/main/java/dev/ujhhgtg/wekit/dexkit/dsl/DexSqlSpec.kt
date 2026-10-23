package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.DexSqlBridge

/**
 * SQL query and expected-failure policy attached to a Dex delegate.
 *
 * The SQL must return the descriptor in its first column and is written to produce
 * exactly one row (or none). DexSQL only executes queries; deciding that an empty
 * result is an expected failure and that a multi-row result is an ambiguity bug is
 * WeKit resolver policy, implemented here rather than in the engine.
 *
 * The query is kept as source text so the existing room cache hash can include it.
 */
data class DexSqlSpec(
    val sql: String,
    val allowFailure: Boolean = false,
) {
    init {
        require(sql.isNotBlank()) { "DexSQL query must not be blank" }
    }

    /** Returns the descriptor, or null when the empty result is an allowed failure. */
    fun resolve(bridge: DexSqlBridge): String? {
        val result = bridge.query(sql)
        return when (result.rows.size) {
            0 -> {
                if (!allowFailure) error("DexSQL: no row found for query: $sql")
                null
            }

            1 -> result.rows.single().firstOrNull()
                ?: error("DexSQL: query returned no columns: $sql")

            else -> error("DexSQL: query returned ${result.rows.size} rows; expected one: $sql")
        }
    }
}

/** Resolve one SQL-backed delegate into the same descriptor state as DexKit. */
fun BaseDexDelegate.resolveFromDexSql(bridge: DexSqlBridge) {
    val spec = dexSqlSpec ?: return
    try {
        val descriptor = spec.resolve(bridge)
        if (descriptor == null) {
            markExpectedFailure()
            return
        }
        require(descriptor.isNotBlank()) { "DexSQL delegate $key returned a blank descriptor" }
        when (this) {
            is DexClassDelegate -> setDescriptor(descriptor.toJavaClassName())
            is DexFieldDelegate -> setDescriptor(descriptor)
            is DexMethodDelegate -> setDescriptor(DexMethodDescriptor(descriptor))
            is DexConstructorDelegate -> setDescriptor(DexMethodDescriptor(descriptor))
        }
    } catch (error: Throwable) {
        recordUnexpectedFailure(error)
        throw error
    }
}

private fun String.toJavaClassName(): String =
    if (startsWith("L") && endsWith(";")) {
        substring(1, length - 1).replace('/', '.')
    } else {
        this
    }
