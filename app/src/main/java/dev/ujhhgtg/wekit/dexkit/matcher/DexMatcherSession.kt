package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.DexSqlMetadataProvider
import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo

/**
 * Standalone query-matcher session for scripts, Monet evidence collection and other runtime hosts
 * that run WITHOUT a DexResolutionContext.
 *
 * The caller owns the [DexSqlBridge] (usually a lease from `dev.ujhhgtg.wekit.dexkit.withDexSql` /
 * local `DexSqlRuntime`); this session only creates the matching stack over it:
 * [DexMetadata] + [DexSqlMetadataProvider] + a [QueryMatcherScope] with the bridge-backed
 * relations. [close] therefore marks the session unusable but does NOT close the caller's bridge;
 * close the bridge/runtime lease yourself.
 *
 * All find* calls are serialized on this session and return the regular metadata models, so callers
 * can keep using [DexMethodInfo.usingFields], [DexMethodInfo.invokes], etc. Callers and
 * field-access relations (not inlined in metadata documents) are exposed through [callers] and
 * [fieldAccessMethods].
 *
 * This class deliberately has no DexResolutionContext or DexKit dependency.
 */
class DexMatcherSession(
    private val bridge: DexSqlBridge,
    val metadata: DexMetadata = DexMetadata(DexSqlMetadataProvider(bridge)),
) : AutoCloseable {
    private val metadataProvider = DexSqlMetadataProvider(bridge)
    private val relations: DexMatcherRelations =
        BridgeMatcherRelations(
            callersOf = metadataProvider::callers,
            query = bridge::query,
            metadata = metadata,
        )
    private val scope =
        QueryMatcherScope(
            metadata = metadata,
            query =
                DexDescriptorQuery { sql ->
                    bridge.query(sql).rows.mapNotNull { row -> row.firstOrNull() }
                },
            relations = relations,
            rawMetadata = metadataProvider::query,
            sessionCache = MatcherSessionCache.forSession(bridge),
        )
    private val lock = Any()

    @Volatile private var closed = false

    fun findClasses(
        spec: ClassSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexClassInfo> =
        synchronized(lock) {
            checkOpen()
            scope.findClasses(spec, packages, excludePackages)
        }

    /** DexKit-style matcher block; package scope is set with searchPackages/excludePackages. */
    fun findClasses(block: ClassMatcher.() -> Unit): List<DexClassInfo> {
        val matcher = ClassMatcher().apply(block)
        return findClasses(matcher.build(), emptyList(), matcher.excludePackages.toList())
    }

    fun findMethods(
        spec: MethodSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexMethodInfo> =
        synchronized(lock) {
            checkOpen()
            scope.findMethods(spec, packages, excludePackages)
        }

    /** DexKit-style matcher block; package scope is set with searchPackages/excludePackages. */
    fun findMethods(block: MethodMatcher.() -> Unit): List<DexMethodInfo> {
        val matcher = MethodMatcher().apply(block)
        return findMethods(matcher.build(), emptyList(), matcher.excludePackages.toList())
    }

    fun findFields(
        spec: FieldSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexFieldInfo> =
        synchronized(lock) {
            checkOpen()
            scope.findFields(spec, packages, excludePackages)
        }

    /** DexKit-style matcher block; package scope is set with searchPackages/excludePackages. */
    fun findFields(block: FieldMatcher.() -> Unit): List<DexFieldInfo> {
        val matcher = FieldMatcher().apply(block)
        return findFields(matcher.build(), emptyList(), matcher.excludePackages.toList())
    }

    /** Callers of a method descriptor (DexKit order); fetched lazily from the bridge. */
    fun callers(methodDescriptor: String): List<DexMethodInfo> =
        synchronized(lock) {
            checkOpen()
            relations.callers(methodDescriptor)
        }

    /** Methods reading ("get") or writing ("put") a field descriptor; fetched lazily. */
    fun fieldAccessMethods(fieldDescriptor: String, usingType: String): List<DexMethodInfo> =
        synchronized(lock) {
            checkOpen()
            relations.fieldAccessMethods(fieldDescriptor, usingType)
        }

    val isOpen: Boolean
        get() = !closed

    /**
     * Marks the session unusable. The [DexSqlBridge] is caller-owned and is NOT closed here; close
     * the bridge/runtime lease that created it.
     */
    override fun close() {
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "DexMatcherSession is closed" }
    }
}
