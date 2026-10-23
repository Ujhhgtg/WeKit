@file:Suppress("unused")

package dev.ujhhgtg.wekit.dexkit.dsl

import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.DexSqlMetadataProvider
import dev.ujhhgtg.wekit.dexkit.matcher.BridgeMatcherRelations
import dev.ujhhgtg.wekit.dexkit.matcher.DexDescriptorQuery
import dev.ujhhgtg.wekit.dexkit.matcher.QueryMatcherScope
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.BaseFeature
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Runs a [QueryMatcherScope] block during Dex resolution and applies the delegate contract:
 * a null result is an expected failure only with allowFailure, otherwise it is an error; more than
 * one match is always an error (raised by the scope's unique* helpers).
 *
 * The block is lazy: it executes inside the active resolution context, so it may read other
 * delegates' .data (their resolution is triggered on demand) exactly like a DexKit matcher block.
 */
internal class QueryMatcherSpec(
    private val allowFailure: Boolean,
    private val block: QueryMatcherScope.() -> String?,
) {
    fun resolve(scope: QueryMatcherScope): String? {
        val descriptor = block.invoke(scope)
        return when {
            descriptor != null -> descriptor
            allowFailure -> null
            else -> error("Query matcher: no result found (allowFailure = false)")
        }
    }

    fun asSqlSpec(): DexSqlSpec = DexSqlSpec.queryMatcher(allowFailure) { bridge ->
        resolve(resolutionQueryMatcherScope(bridge))
    }

    private fun resolutionQueryMatcherScope(bridge: DexSqlBridge): QueryMatcherScope {
        val metadata = DexResolutionContext.metadata
        val callerSource = DexSqlMetadataProvider(bridge)
        return QueryMatcherScope(
            metadata = metadata,
            query = DexDescriptorQuery { sql -> bridge.query(sql).rows.mapNotNull { row -> row.firstOrNull() } },
            relations = BridgeMatcherRelations(
                callersOf = callerSource::callers,
                query = bridge::query,
                metadata = metadata,
            ),
        )
    }
}

/**
 * Query-matcher-backed method delegate (named *Query so it never collides with the inline DexKit
 * `dexMethod` factories). The block runs lazily during resolution, may read other delegates' .data,
 * and returns the resolved method descriptor or null (expected failure only when [allowFailure]).
 * Use [QueryMatcherScope.uniqueMethod] to enforce the single-result contract.
 */
fun dexMethodQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexMethodDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec())
            .also { item.registerDexDelegate(it) }
    }

/**
 * Query-matcher-backed constructor delegate; the block runs lazily during resolution and returns the
 * constructor method descriptor or null (expected failure only when [allowFailure]).
 */
fun dexConstructorQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexConstructorDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec())
            .also { item.registerDexDelegate(it) }
    }

/**
 * Query-matcher-backed class delegate; the block runs lazily during resolution and returns the class
 * descriptor (or dotted class name) or null (expected failure only when [allowFailure]). Use
 * [QueryMatcherScope.uniqueClass].
 */
fun dexClassQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexClassDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec())
            .also { item.registerDexDelegate(it) }
    }

/**
 * Query-matcher-backed field delegate; the block runs lazily during resolution and returns the field
 * descriptor or null (expected failure only when [allowFailure]). Use
 * [QueryMatcherScope.uniqueField].
 */
fun dexFieldQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexFieldDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec())
            .also { item.registerDexDelegate(it) }
    }
