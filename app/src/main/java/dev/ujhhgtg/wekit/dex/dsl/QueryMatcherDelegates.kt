@file:Suppress("unused")

package dev.ujhhgtg.wekit.dex.dsl

import dev.ujhhgtg.wekit.dex.DexSqlBridge
import dev.ujhhgtg.wekit.dex.DexSqlMetadataProvider
import dev.ujhhgtg.wekit.dex.matcher.BridgeMatcherRelations
import dev.ujhhgtg.wekit.dex.matcher.ClassSpec
import dev.ujhhgtg.wekit.dex.matcher.FieldSpec
import dev.ujhhgtg.wekit.dex.matcher.MatcherSessionCache
import dev.ujhhgtg.wekit.dex.matcher.MethodSpec
import dev.ujhhgtg.wekit.dex.matcher.QueryMatcherScope
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.BaseFeature
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Runs a [QueryMatcherScope] block during Dex resolution and applies the delegate contract: a null
 * result is an expected failure only with allowFailure, otherwise it is an error; more than one
 * match is always an error (raised by the scope's unique* helpers).
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

    fun asSqlSpec(): DexSqlSpec = asTwoStageSqlSpec()

    /**
     * Unified spec authoring fallback: SQL when expressible (handled by the caller), matcher here.
     */
    fun asTwoStageSqlSpec(): DexSqlSpec =
        DexSqlSpec.queryMatcher(allowFailure) { bridge ->
            resolve(resolutionQueryMatcherScope(bridge))
        }

    internal fun resolutionQueryMatcherScope(bridge: DexSqlBridge): QueryMatcherScope {
        val metadata = DexResolutionContext.metadata
        val callerSource = DexSqlMetadataProvider(bridge)
        return QueryMatcherScope(
            metadata = metadata,
            query = { sql ->
                bridge.query(sql).rows.mapNotNull { row -> row.firstOrNull() }
            },
            relations =
                BridgeMatcherRelations(
                    callersOf = callerSource::callers,
                    query = bridge::query,
                    metadata = metadata,
                ),
            // Cheap prefilters read the raw JSON document; rejected candidates therefore do not
            // enter the session metadata cache during candidate scans.
            rawMetadata = callerSource::query,
            // Cross-delegate memoization: identical candidate SQL/raw documents/class-string
            // evaluations are shared by every converted delegate of this resolution session.
            sessionCache = MatcherSessionCache.forSession(bridge),
        )
    }
}

internal fun methodSpecMatcherDexSqlSpec(spec: MethodSpec, allowFailure: Boolean): DexSqlSpec =
    QueryMatcherSpec(allowFailure) { uniqueMethod(spec) }.asTwoStageSqlSpec()

internal fun constructorSpecMatcherDexSqlSpec(spec: MethodSpec, allowFailure: Boolean): DexSqlSpec =
    QueryMatcherSpec(allowFailure) {
            uniqueMethod(
                spec.copy(
                    name = dev.ujhhgtg.wekit.dex.matcher.NameSpec("<init>"),
                ),
            )
        }
        .asTwoStageSqlSpec()

internal fun classSpecMatcherDexSqlSpec(spec: ClassSpec, allowFailure: Boolean): DexSqlSpec =
    QueryMatcherSpec(allowFailure) { uniqueClass(spec) }.asTwoStageSqlSpec()

internal fun fieldSpecMatcherDexSqlSpec(spec: FieldSpec, allowFailure: Boolean): DexSqlSpec =
    QueryMatcherSpec(allowFailure) { uniqueField(spec) }.asTwoStageSqlSpec()

/**
 * Query-matcher-backed method delegate (named *Query so it never collides with the inline DexKit
 * `dexMethod` factories). The block runs lazily during resolution, may read other delegates' .data,
 * and returns the resolved method descriptor or null (expected failure only when [allowFailure]).
 * Use [QueryMatcherScope.uniqueMethod] with a declarative spec builder:
 * ```
 * private val methodFoo by dexMethodQuery {
 *     uniqueMethod(
 *         methodSpec {
 *             name("foo")
 *             declaredClass { className("com.tencent.mm.Host") }
 *             paramCount(2, 2)
 *             returnType { className("void") }
 *         },
 *     )
 * }
 * ```
 */
@Deprecated("Use dexMethod/dexClass/dexField/dexConstructor(methodSpec/classSpec/fieldSpec { })")
internal fun dexMethodQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexMethodDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec()).also {
            item.registerDexDelegate(it)
        }
    }

/**
 * Query-matcher-backed constructor delegate; the block runs lazily during resolution and returns
 * the constructor method descriptor or null (expected failure only when [allowFailure]).
 */
@Deprecated("Use dexMethod/dexClass/dexField/dexConstructor(methodSpec/classSpec/fieldSpec { })")
internal fun dexConstructorQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexConstructorDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec())
            .also {
                item.registerDexDelegate(it)
            }
    }

/**
 * Query-matcher-backed class delegate; the block runs lazily during resolution and returns the
 * class descriptor (or dotted class name) or null (expected failure only when [allowFailure]). Use
 * [QueryMatcherScope.uniqueClass] with `classSpec { }`:
 * ```
 * private val classFoo by dexClassQuery {
 *     uniqueClass(classSpec { className("com.tencent.mm.Host") })
 * }
 * ```
 */
@Deprecated("Use dexMethod/dexClass/dexField/dexConstructor(methodSpec/classSpec/fieldSpec { })")
internal fun dexClassQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexClassDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec()).also {
            item.registerDexDelegate(it)
        }
    }

/**
 * Query-matcher-backed field delegate; the block runs lazily during resolution and returns the
 * field descriptor or null (expected failure only when [allowFailure]). Use
 * [QueryMatcherScope.uniqueField] with `fieldSpec { }`:
 * ```
 * private val fieldFoo by dexFieldQuery {
 *     uniqueField(fieldSpec { name("foo"); declaredClass { className("com.tencent.mm.Host") } })
 * }
 * ```
 */
@Deprecated("Use dexMethod/dexClass/dexField/dexConstructor(methodSpec/classSpec/fieldSpec { })")
internal fun dexFieldQuery(
    allowFailure: Boolean = false,
    block: QueryMatcherScope.() -> String?,
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexFieldDelegate(key, sqlSpec = QueryMatcherSpec(allowFailure, block).asSqlSpec()).also {
            item.registerDexDelegate(it)
        }
    }
