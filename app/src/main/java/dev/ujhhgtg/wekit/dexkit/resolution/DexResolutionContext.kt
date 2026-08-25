package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
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
    private val current = ThreadLocal<DexResolutionSession?>()

    val dexKit: DexKitBridge
        get() = current.get()?.coordinator?.dexKit ?: error("Dex resolution context is not active")

    val host: DexHostMetadata
        get() = current.get()?.coordinator?.host ?: error("Dex resolution context is not active")

    fun requireData(delegate: BaseDexDelegate): String {
        val session = current.get() ?: error("Dex resolution context is not active")
        return session.coordinator.requireData(session.producerId, delegate)
    }

    internal fun <T> withResolutionSession(
        session: DexResolutionSession,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(session)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }
}

fun resolveAllDex(
    owners: Collection<IResolveDex>,
    dexKit: DexKitBridge,
    host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
): DexResolutionBatchResult {
    val registry = DexResolutionRegistry.create(owners.toList())
    val coordinator = DexResolutionCoordinator(registry, dexKit, host)
    return coordinator.resolveOwners(owners)
}

internal fun IResolveDex.resolveAllDex(
    dexKit: DexKitBridge,
    host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
) {
    val result = resolveAllDex(listOf(this), dexKit, host)
    result.resultsByProducer.forEach { (producerId, nodeResult) ->
        when (nodeResult) {
            is DexNodeResult.Failed -> throw nodeResult.error
            is DexNodeResult.Resolved -> require(
                nodeResult.diagnostic.status in setOf(
                    DexResolutionStatus.SUCCESS,
                    DexResolutionStatus.EXPECTED_FAILURE,
                )
            ) {
                "Dex resolution producer $producerId completed with ${nodeResult.diagnostic.status}"
            }
        }
    }
}

data class DexResolutionSession(
    val coordinator: DexResolutionCoordinator,
    val producerId: String,
)
