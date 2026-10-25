package dev.ujhhgtg.wekit.dex.cache

/**
 * Persisted Dex state of one [dev.ujhhgtg.wekit.dex.abc.IResolveDex] item for the current host.
 */
sealed interface DexItemState {
    /** The cache is complete for the current resolver hash. */
    data object Valid : DexItemState

    /** No cache, a stale resolver hash, missing descriptors, or an unreadable cache. */
    data object NeedsResolution : DexItemState

    /** Resolution failed for the current resolver hash; it is retried only after a re-run. */
    data class Failed(val message: String) : DexItemState
}
