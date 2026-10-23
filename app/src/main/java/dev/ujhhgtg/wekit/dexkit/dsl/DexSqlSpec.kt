package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.DexSqlBridge

/**
 * Resolution plan attached to a Dex delegate.
 *
 * Two kinds exist:
 *
 * - SQL-backed (the default): the SQL text is produced lazily by a provider, which runs during
 *   resolution inside the active Dex resolution context. A DexSQL builder block can therefore read
 *   other delegates' metadata naturally (for example declaredClass = classFoo.data.name), exactly
 *   like a DexKit matcher block; static raw SQL simply returns its string.
 * - Query-matcher-backed ([queryMatcher]): a Kotlin resolver runs a [dev.ujhhgtg.wekit.dexkit.matcher.QueryMatcherScope]
 *   block during resolution and returns the descriptor (or null for a not-found result).
 *
 * For SQL-backed plans the SQL must return the descriptor in its first column and is written to
 * produce exactly one row (or none); deciding that an empty result is an expected failure and that
 * a multi-row result is an ambiguity bug is WeKit resolver policy, implemented here rather than in
 * the engine. Query-matcher ambiguity is raised by the matcher scope's unique* helpers.
 */
class DexSqlSpec private constructor(
    val allowFailure: Boolean,
    private val sqlProvider: (() -> String)?,
    private val matcherProvider: ((DexSqlBridge) -> String?)?,
) {
    /** SQL-backed plan: [sqlProvider] runs lazily once per resolution attempt. */
    constructor(
        sqlProvider: () -> String,
        allowFailure: Boolean = false,
    ) : this(allowFailure, sqlProvider, null)

    /** Builds the executable SQL text; called once per resolution attempt. */
    fun sql(): String = sqlProvider?.invoke()
        ?: error("DexSqlSpec is query-matcher backed and has no SQL text")

    /**
     * Resolves the descriptor: null means the delegate's expected-failure policy decided the
     * member does not exist; non-null is the resolved descriptor (or class name for class plans).
     */
    fun resolve(bridge: DexSqlBridge): String? {
        if (matcherProvider != null) {
            val descriptor = matcherProvider.invoke(bridge)
            return when {
                descriptor != null -> descriptor
                allowFailure -> null
                else -> error("Query matcher: no result found (allowFailure = false)")
            }
        }
        val sql = sql()
        val result = bridge.query(sql)
        return when (result.rows.size) {
            0 -> {
                if (!allowFailure) error("DexSQL: no row found for query: " + sql)
                null
            }

            1 -> result.rows.single().firstOrNull()
                ?: error("DexSQL: query returned no columns: " + sql)

            else -> error("DexSQL: query returned " + result.rows.size + " rows; expected one: " + sql)
        }
    }

    internal companion object {
        /**
         * Query-matcher plan: [provider] runs during resolution inside the active Dex resolution
         * context and returns the descriptor or null (null is interpreted with [allowFailure]).
         */
        fun queryMatcher(
            allowFailure: Boolean,
            provider: (DexSqlBridge) -> String?,
        ): DexSqlSpec = DexSqlSpec(allowFailure, null, provider)
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
        require(descriptor.isNotBlank()) { "DexSQL delegate " + key + " returned a blank descriptor" }
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
