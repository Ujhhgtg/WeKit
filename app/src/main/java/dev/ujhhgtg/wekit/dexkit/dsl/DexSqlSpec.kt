package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.sql.DexSqlQuery

/**
 * SQL query and expected-failure policy attached to a Dex delegate.
 *
 * The SQL must return the descriptor in its first column and is written to produce exactly one row
 * (or none). DexSQL only executes queries; deciding that an empty result is an expected failure and
 * that a multi-row result is an ambiguity bug is WeKit resolver policy, implemented here rather than
 * in the engine.
 *
 * A spec is either static (final SQL text known at declaration time) or deferred (a [DexSqlQuery]
 * whose deferred slots are rendered when the query runs). The template text keeps the existing room
 * cache hash stable; rendering happens through [sql].
 */
class DexSqlSpec private constructor(
    private val template: String,
    private val query: DexSqlQuery?,
    private val allowFailure: Boolean,
) {
    init {
        require(template.isNotBlank()) { "DexSQL query must not be blank" }
    }

    /** Whether the query is complete at declaration time, i.e. it has no deferred values. */
    val isStatic: Boolean
        get() = query?.isStatic ?: true

    /** The executable SQL text: the static query, or the rendered deferred query. */
    fun sql(): String = if (isStatic) template else requireNotNull(query).buildSql()

    /** Returns the descriptor, or null when the empty result is an allowed failure. */
    fun resolve(bridge: DexSqlBridge): String? {
        val sql = sql()
        val result = bridge.query(sql)
        return when (result.rows.size) {
            0 -> {
                if (!allowFailure) error("DexSQL: no row found for query: $sql")
                null
            }

            1 -> result.rows.single().firstOrNull()
                ?: error("DexSQL: query returned no columns: $sql")

            else -> error("DexSQL: query returned " + result.rows.size + " rows; expected one: " + sql)
        }
    }

    companion object {
        /** Static raw SQL that is complete at declaration time. */
        fun static(sql: String, allowFailure: Boolean = false): DexSqlSpec =
            DexSqlSpec(sql, null, allowFailure)

        /** Query built by the DexSQL DSL; deferred slots are rendered when the query runs. */
        fun deferred(query: DexSqlQuery, allowFailure: Boolean = false): DexSqlSpec =
            DexSqlSpec(query.toSql(), query, allowFailure)
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
