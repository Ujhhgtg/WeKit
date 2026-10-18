package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.dexkit.ResolveOptions

/**
 * Stable SQL selection policy attached to a Dex delegate.
 *
 * The SQL must return the descriptor in its first column.  The query is kept
 * as source text so the existing Room cache hash can include it; selection
 * semantics are explicit rather than being hidden in a resolver body.
 */
data class DexSqlSpec(
    val sql: String,
    val allowMultiple: Boolean = false,
    val allowFailure: Boolean = false,
    val resultIndex: Int = 0,
) {
    init {
        require(sql.isNotBlank()) { "DexSQL query must not be blank" }
        require(resultIndex >= 0) { "DexSQL resultIndex must be non-negative" }
    }

    fun resolve(bridge: DexSqlBridge): String? = bridge.resolve(
        sql,
        ResolveOptions(
            resultIndex = resultIndex,
            allowMultiple = allowMultiple,
            allowFailure = allowFailure,
        ),
    )
}

/** Resolve one SQL-backed delegate into the same descriptor state as DexKit. */
fun BaseDexDelegate.resolveFromDexSql(bridge: DexSqlBridge) {
    val spec = dexSqlSpec ?: return
    val descriptor = spec.resolve(bridge)
    if (descriptor == null) {
        markExpectedFailure()
        return
    }
    require(descriptor.isNotBlank()) { "DexSQL delegate $key returned a blank descriptor" }
    when (this) {
        is DexClassDelegate -> setDescriptor(descriptor)
        is DexFieldDelegate -> setDescriptor(descriptor)
        is DexMethodDelegate -> setDescriptor(DexMethodDescriptor(descriptor))
        is DexConstructorDelegate -> setDescriptor(DexMethodDescriptor(descriptor))
    }
}
