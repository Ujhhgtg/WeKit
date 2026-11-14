package dev.ujhhgtg.wekit.dex.resolution

import dev.ujhhgtg.dexsql.DexSqlBridge
import dev.ujhhgtg.dexsql.DexSqlMetadataProvider
import dev.ujhhgtg.dexsql.matcher.ClassMatcher
import dev.ujhhgtg.dexsql.matcher.ClassSpec
import dev.ujhhgtg.dexsql.matcher.DexMatcherSession
import dev.ujhhgtg.dexsql.matcher.FieldMatcher
import dev.ujhhgtg.dexsql.matcher.FieldSpec
import dev.ujhhgtg.dexsql.matcher.MethodMatcher
import dev.ujhhgtg.dexsql.matcher.MethodSpec
import dev.ujhhgtg.dexsql.meta.DexClassInfo
import dev.ujhhgtg.dexsql.meta.DexFieldInfo
import dev.ujhhgtg.dexsql.meta.DexMetadata
import dev.ujhhgtg.dexsql.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dex.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dex.dsl.resolveComputedDescriptor
import dev.ujhhgtg.wekit.dex.dsl.resolveFromDexSql
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.HostInfo

data class DexHostMetadata(
    val versionCode: Long,
    val versionName: String,
    val isGooglePlay: Boolean,
) {
    companion object {
        fun currentAndroidHost() =
            DexHostMetadata(
                versionCode = HostInfo.versionCode,
                versionName = HostInfo.versionName,
                isGooglePlay = HostInfo.isHostGooglePlay,
            )
    }
}

object DexResolutionContext {
    private class Session(
        val host: DexHostMetadata,
        val coordinator: ResolutionCoordinator<BaseFeature>,
        val dexSql: DexSqlBridge?,
        val metadata: DexMetadata? = dexSql?.let { DexMetadata(DexSqlMetadataProvider(it)) },
    ) : AutoCloseable {
        val stack: MutableList<BaseFeature> = mutableListOf()

        /** Delegates currently being resolved on demand (cycle detection). */
        val resolving: MutableList<BaseDexDelegate> = mutableListOf()

        private var matcherSession: DexMatcherSession? = null

        val matcher: DexMatcherSession
            get() {
                matcherSession?.let {
                    return it
                }
                val sql = dexSql ?: error("DexSQL session is not active")
                val md = metadata ?: DexMetadata(DexSqlMetadataProvider(sql))
                return DexMatcherSession(sql, md).also { matcherSession = it }
            }

        override fun close() {
            matcherSession?.close()
            matcherSession = null
        }
    }

    private val current = ThreadLocal<Session?>()

    val host: DexHostMetadata
        get() = current.get()?.host ?: error("Dex resolution context is not active")

    /** Session-scoped class/method/field metadata (the DexKit *Data replacement). */
    val metadata: DexMetadata
        get() = current.get()?.metadata ?: error("DexSQL metadata context is not active")

    /** Session-scoped query matcher usable from computed delegate providers. */
    val matcher: DexMatcherSession
        get() = current.get()?.matcher ?: error("Dex resolution context is not active")

    fun findClasses(spec: ClassSpec): List<DexClassInfo> = matcher.findClasses(spec)

    /** DexKit-style matcher block; package scope is set with searchPackages/excludePackages. */
    fun findClasses(block: ClassMatcher.() -> Unit): List<DexClassInfo> {
        val matcher = ClassMatcher().apply(block)
        return findClasses(matcher.build())
    }

    fun findMethods(spec: MethodSpec): List<DexMethodInfo> = matcher.findMethods(spec)

    /** DexKit-style matcher block; package scope is set with searchPackages/excludePackages. */
    fun findMethods(block: MethodMatcher.() -> Unit): List<DexMethodInfo> {
        val matcher = MethodMatcher().apply(block)
        return findMethods(matcher.build())
    }

    fun findFields(spec: FieldSpec): List<DexFieldInfo> = matcher.findFields(spec)

    /** DexKit-style matcher block; package scope is set with searchPackages/excludePackages. */
    fun findFields(block: FieldMatcher.() -> Unit): List<DexFieldInfo> {
        val matcher = FieldMatcher().apply(block)
        return findFields(matcher.build())
    }

    fun ensureResolved(delegate: BaseDexDelegate) {
        val session = current.get() ?: error("Dex resolution context is not active")
        if (session.coordinator.isOwnedByCurrentThread(delegate.owner)) {
            resolveWithinOwner(delegate, session)
            return
        }
        resolve(delegate.owner, session)
    }

    /**
     * Resolves a delegate whose owner is the feature currently being resolved. SQL providers may
     * refer to a sibling delegate whose provider has not run yet; resolve it on demand. This also
     * permits SQL providers to read each other's metadata as long as there is no cycle.
     */
    private fun resolveWithinOwner(delegate: BaseDexDelegate, session: Session) {
        if (delegate.isSettled) return
        check(session.stack.lastOrNull() === delegate.owner) {
            "Dex delegate " +
                delegate.key +
                " belongs to " +
                delegate.owner.technicalPath +
                ", which is not the feature currently being resolved"
        }
        check(session.resolving.none { it === delegate }) {
            "Unresolved recursive Dex dependency: " +
                (session.resolving.map { it.owner.technicalPath + "#" + it.key } +
                        (delegate.owner.technicalPath + "#" + delegate.key))
                    .joinToString(" -> ")
        }
        session.resolving += delegate
        try {
            if (delegate.group?.isAvailable() == false) {
                delegate.markExpectedFailure()
                return
            }
            if (delegate.computedDescriptor != null) {
                delegate.resolveComputedDescriptor()
            } else {
                val dexSql =
                    session.dexSql
                        ?: error(
                            "DexSQL resolution context is not active for " +
                                delegate.owner.technicalPath,
                        )
                delegate.resolveFromDexSql(dexSql)
            }
            check(delegate.isSettled) {
                "Dex delegate " +
                    delegate.owner.technicalPath +
                    "#" +
                    delegate.key +
                    " did not settle during on-demand resolution"
            }
        } finally {
            session.resolving.removeAt(session.resolving.lastIndex)
        }
    }

    fun <T> withResolutionContext(
        host: DexHostMetadata,
        coordinator: ResolutionCoordinator<BaseFeature>? = null,
        dexSql: DexSqlBridge? = null,
        block: () -> T,
    ): T {
        val previous = current.get()
        if (
            previous != null &&
                previous.host == host &&
                previous.dexSql === dexSql &&
                (coordinator == null || previous.coordinator === coordinator)
        ) {
            return block()
        }
        val session = Session(host, coordinator ?: newCoordinator(emptyList()), dexSql)
        current.set(session)
        try {
            return block()
        } finally {
            current.set(previous)
            session.close()
        }
    }

    fun resolve(item: BaseFeature) {
        resolve(item, current.get() ?: error("Dex resolution context is not active"))
    }

    private fun resolve(feature: BaseFeature, session: Session) {
        session.coordinator.resolve(
            feature,
            canReuse = { feature.dexDelegates.all(BaseDexDelegate::isSettled) },
        ) {
            session.stack += feature
            try {
                feature.dexDelegates.forEach(BaseDexDelegate::resetForResolution)
                // SQL builders are lazy: they execute here, inside the resolution context, so
                // .data references inside a builder resolve their dependencies on demand.
                feature.dexDelegates.forEach { resolveWithinOwner(it, session) }
                feature.dexDelegates.forEach(BaseDexDelegate::markIncomplete)
                check(feature.dexDelegates.all(BaseDexDelegate::isSettled)) {
                    "Incomplete or failed Dex resolution: " + feature.technicalPath
                }
            } catch (error: Throwable) {
                val causeKey =
                    feature.dexDelegates
                        .firstOrNull {
                            it.state is DexResolutionState.UnexpectedFailure
                        }
                        ?.key ?: feature.javaClass.name + "#resolution"
                feature.dexDelegates.forEach { it.markBlocked(causeKey) }
                throw error
            } finally {
                session.stack.removeAt(session.stack.lastIndex)
            }
        }
    }

    fun newCoordinator(items: Collection<BaseFeature>, checkActive: () -> Unit = {}) =
        ResolutionCoordinator(items, { it.technicalPath }, checkActive)
}
