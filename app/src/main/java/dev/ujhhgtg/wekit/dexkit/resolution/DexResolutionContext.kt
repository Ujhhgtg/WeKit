package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
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
        val resolved: MutableSet<BaseFeature> = mutableSetOf(),
        val resolving: MutableSet<BaseFeature> = mutableSetOf(),
    )

    private val current = ThreadLocal<Session?>()

    val dexKit: DexKitBridge
        get() = current.get()?.dexKit ?: error("Dex resolution context is not active")

    val host: DexHostMetadata
        get() = current.get()?.host ?: error("Dex resolution context is not active")

    internal fun ensureResolved(delegate: dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate) {
        if (delegate.getDescriptorString() != null) return
        val owner = delegate.owner as IResolveDex
        val session = current.get() ?: error("Dex resolution context is not active")
        if (delegate.owner in session.resolving) return
        resolve(owner, session)
    }

    internal fun <T> withResolutionContext(
        dexKit: DexKitBridge,
        host: DexHostMetadata,
        block: () -> T,
    ): T {
        val previous = current.get()
        if (previous?.dexKit === dexKit && previous.host == host) return block()
        current.set(Session(dexKit, host))
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    internal fun resolve(item: IResolveDex) {
        resolve(item, current.get() ?: error("Dex resolution context is not active"))
    }

    private fun resolve(item: IResolveDex, session: Session) {
        val feature = item as BaseFeature
        if (feature in session.resolved) return
        check(session.resolving.add(feature)) { "Circular Dex resolver dependency: ${item.javaClass.name}" }
        try {
            feature.resolveInlineDex(session.dexKit)
            item.resolveDex(session.dexKit)
            session.resolved += feature
        } finally {
            session.resolving -= feature
        }
    }
}

fun IResolveDex.resolveAllDex(
    dexKit: DexKitBridge,
    host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
) = DexResolutionContext.withResolutionContext(dexKit, host) {
    DexResolutionContext.resolve(this)
}
