package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.DexSqlMetadataProvider
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.resolveFromDexSql
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.DexMatcherSession
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.HostInfo

data class DexHostMetadata(
    val versionCode: Long,
    val versionName: String,
    val isGooglePlay: Boolean,
) {
    companion object {
        fun currentAndroidHost() = DexHostMetadata(
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
                matcherSession?.let { return it }
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

    val dexSql: DexSqlBridge?
        get() = current.get()?.dexSql

    /** Session-scoped class/method/field metadata (the DexKit *Data replacement). */
    val metadata: DexMetadata
        get() = current.get()?.metadata
            ?: error("DexSQL metadata context is not active")

    /** Session-scoped query matcher usable from custom resolveDex bodies. */
    val matcher: DexMatcherSession
        get() = current.get()?.matcher ?: error("Dex resolution context is not active")

    fun findClasses(
        spec: ClassSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexClassInfo> = matcher.findClasses(spec, packages, excludePackages)

    fun findMethods(
        spec: MethodSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexMethodInfo> = matcher.findMethods(spec, packages, excludePackages)

    fun findFields(
        spec: FieldSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexFieldInfo> = matcher.findFields(spec, packages, excludePackages)

    fun ensureResolved(delegate: BaseDexDelegate) {
        val session = current.get() ?: error("Dex resolution context is not active")
        if (session.coordinator.isOwnedByCurrentThread(delegate.owner)) {
            resolveWithinOwner(delegate, session)
            return
        }
        resolve(delegate.owner as IResolveDex, session)
    }

    /**
     * Resolves a delegate whose owner is the feature currently being resolved. SQL providers may
     * refer to a sibling delegate whose provider has not run yet; resolve it on demand. This also
     * permits SQL providers to read each other's metadata as long as there is no cycle.
     */
    private fun resolveWithinOwner(delegate: BaseDexDelegate, session: Session) {
        if (delegate.isSettled) return
        check(session.stack.lastOrNull() === delegate.owner) {
            "Dex delegate " + delegate.key + " belongs to " + delegate.owner.technicalPath +
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
            val spec = delegate.dexSqlSpec
                ?: error(
                    "Dex delegate " + delegate.owner.technicalPath + "#" + delegate.key +
                        " has no SQL plan (DexKit inline matchers were removed)",
                )
            val dexSql = session.dexSql
                ?: error("DexSQL resolution context is not active for " + delegate.owner.technicalPath)
            delegate.resolveFromDexSql(dexSql)
            check(delegate.isSettled) {
                "Dex delegate " + delegate.owner.technicalPath + "#" + delegate.key +
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
        if (previous != null && previous.host == host && previous.dexSql === dexSql &&
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

    fun resolve(item: IResolveDex) {
        resolve(item, current.get() ?: error("Dex resolution context is not active"))
    }

    private fun resolve(item: IResolveDex, session: Session) {
        val feature = item as BaseFeature
        session.coordinator.resolve(
            feature,
            canReuse = { item.dexDelegates.all(BaseDexDelegate::isSettled) },
        ) {
            session.stack += feature
            try {
                item.dexDelegates.forEach(BaseDexDelegate::resetForResolution)
                // SQL builders are lazy: they execute here, inside the resolution context, so
                // .data references inside a builder resolve their dependencies on demand.
                val sqlDelegates = item.dexDelegates.filter { it.dexSqlSpec != null }
                if (sqlDelegates.isNotEmpty()) {
                    val dexSql = session.dexSql
                        ?: error("DexSQL resolution context is not active for " + feature.technicalPath)
                    sqlDelegates.forEach { it.resolveFromDexSql(dexSql) }
                }
                item.resolveDex()
                item.dexDelegates.forEach(BaseDexDelegate::markIncomplete)
                check(item.dexDelegates.all(BaseDexDelegate::isSettled)) {
                    "Incomplete or failed Dex resolution: " + feature.technicalPath
                }
            } catch (error: Throwable) {
                val causeKey = item.dexDelegates.firstOrNull {
                    it.state is DexResolutionState.UnexpectedFailure
                }?.key ?: feature.javaClass.name + "#resolveDex"
                item.dexDelegates.forEach { it.markBlocked(causeKey) }
                throw error
            } finally {
                session.stack.removeAt(session.stack.lastIndex)
            }
        }
    }

    fun newCoordinator(
        items: Collection<IResolveDex>,
        checkActive: () -> Unit = {},
    ) = ResolutionCoordinator(items.map { it as BaseFeature }, { it.technicalPath }, checkActive)
}

fun IResolveDex.resolveAllDex(
    host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
    coordinator: ResolutionCoordinator<BaseFeature> = DexResolutionContext.newCoordinator(listOf(this)),
    dexSql: DexSqlBridge? = null,
) = DexResolutionContext.withResolutionContext(host, coordinator, dexSql) {
    DexResolutionContext.resolve(this)
}
