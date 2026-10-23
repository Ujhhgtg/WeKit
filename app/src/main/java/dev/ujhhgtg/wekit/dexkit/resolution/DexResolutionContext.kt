package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.resolveFromDexSql
import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.DexSqlMetadataProvider
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import org.luckypray.dexkit.DexKitBridge

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
    private data class Session(
        val dexKit: DexKitBridge,
        val host: DexHostMetadata,
        val coordinator: ResolutionCoordinator<BaseFeature>,
        val dexSql: DexSqlBridge?,
        val metadata: DexMetadata? = dexSql?.let { DexMetadata(DexSqlMetadataProvider(it)) },
        val stack: MutableList<BaseFeature> = mutableListOf(),
        /** Delegates currently being resolved on demand (cycle detection). */
        val resolving: MutableList<BaseDexDelegate> = mutableListOf(),
    )

    private val current = ThreadLocal<Session?>()

    val dexKit: DexKitBridge
        get() = current.get()?.dexKit ?: error("Dex resolution context is not active")

    val host: DexHostMetadata
        get() = current.get()?.host ?: error("Dex resolution context is not active")

    val dexSql: DexSqlBridge?
        get() = current.get()?.dexSql

    /** Session-scoped class/method/field metadata (the DexKit *Data replacement). */
    val metadata: DexMetadata
        get() = current.get()?.metadata
            ?: error("DexSQL metadata context is not active")

    fun ensureResolved(delegate: BaseDexDelegate) {
        val session = current.get() ?: error("Dex resolution context is not active")
        if (session.coordinator.isOwnedByCurrentThread(delegate.owner)) {
            resolveWithinOwner(delegate, session)
            return
        }
        resolve(delegate.owner as IResolveDex, session)
    }

    /**
     * Resolves a delegate whose owner feature is the one currently being resolved. Inline
     * matchers are resolved by the owner's inline phase, but a later SQL delegate may refer to a
     * delegate that has not been touched yet; in that case resolve it on demand. This also
     * permits deferred SQL providers to read each other's metadata as long as there is no cycle.
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
            if (spec != null) {
                val dexSql = session.dexSql
                    ?: error("DexSQL resolution context is not active for " + delegate.owner.technicalPath)
                delegate.resolveFromDexSql(dexSql)
            } else {
                delegate.findInline(session.dexKit)
            }
            check(delegate.isSettled) {
                "Dex delegate " + delegate.owner.technicalPath + "#" + delegate.key +
                    " did not settle during on-demand resolution"
            }
        } finally {
            session.resolving.removeAt(session.resolving.lastIndex)
        }
    }

    fun <T> withResolutionContext(
        dexKit: DexKitBridge,
        host: DexHostMetadata,
        coordinator: ResolutionCoordinator<BaseFeature>? = null,
        dexSql: DexSqlBridge? = null,
        block: () -> T,
    ): T {
        val previous = current.get()
        if (previous?.dexKit === dexKit && previous.host == host && previous.dexSql === dexSql &&
            (coordinator == null || previous.coordinator === coordinator)
        ) return block()
        current.set(Session(dexKit, host, coordinator ?: newCoordinator(emptyList()), dexSql))
        try {
            return block()
        } finally {
            current.set(previous)
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
                // .data references inside a builder resolve their dependencies on demand. SQL
                // delegates still run before the inline phase because inline matchers restored
                // from a SQL migration may read their .data; on-demand resolution also covers
                // inline dependencies referenced from a SQL builder.
                val sqlDelegates = item.dexDelegates.filter { it.dexSqlSpec != null }
                if (sqlDelegates.isNotEmpty()) {
                    val dexSql = session.dexSql
                        ?: error("DexSQL resolution context is not active for " + feature.technicalPath)
                    sqlDelegates.forEach { it.resolveFromDexSql(dexSql) }
                }
                feature.resolveInlineDex(session.dexKit)
                item.resolveDex(session.dexKit)
                item.dexDelegates.forEach(BaseDexDelegate::markIncomplete)
                check(item.dexDelegates.all(BaseDexDelegate::isSettled)) {
                    "Incomplete or failed Dex resolution: ${feature.technicalPath}"
                }
            } catch (error: Throwable) {
                val causeKey = item.dexDelegates.firstOrNull {
                    it.state is DexResolutionState.UnexpectedFailure
                }?.key ?: "${feature.javaClass.name}#resolveDex"
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
    dexKit: DexKitBridge,
    host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
    coordinator: ResolutionCoordinator<BaseFeature> = DexResolutionContext.newCoordinator(listOf(this)),
    dexSql: DexSqlBridge? = null,
) = DexResolutionContext.withResolutionContext(dexKit, host, coordinator, dexSql) {
    DexResolutionContext.resolve(this)
}