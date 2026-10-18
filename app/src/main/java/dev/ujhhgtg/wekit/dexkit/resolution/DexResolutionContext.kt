package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.resolveFromDexSql
import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
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
        val stack: MutableList<BaseFeature> = mutableListOf(),
    )

    private val current = ThreadLocal<Session?>()

    val dexKit: DexKitBridge
        get() = current.get()?.dexKit ?: error("Dex resolution context is not active")

    val host: DexHostMetadata
        get() = current.get()?.host ?: error("Dex resolution context is not active")

    val dexSql: DexSqlBridge?
        get() = current.get()?.dexSql

    fun ensureResolved(delegate: BaseDexDelegate) {
        val owner = delegate.owner as IResolveDex
        val session = current.get() ?: error("Dex resolution context is not active")
        if (session.coordinator.isOwnedByCurrentThread(delegate.owner)) {
            check(delegate.state != DexResolutionState.Pending) {
                "Unresolved recursive Dex dependency: " +
                    (session.stack.map { it.technicalPath } + "${delegate.owner.technicalPath}#${delegate.key}")
                        .joinToString(" -> ")
            }
            return
        }
        resolve(owner, session)
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
                feature.resolveInlineDex(session.dexKit)
                val sqlDelegates = item.dexDelegates.filter { it.dexSqlSpec != null }
                if (sqlDelegates.isNotEmpty()) {
                    val dexSql = session.dexSql
                        ?: error("DexSQL resolution context is not active for ${feature.technicalPath}")
                    sqlDelegates.forEach { it.resolveFromDexSql(dexSql) }
                }
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
