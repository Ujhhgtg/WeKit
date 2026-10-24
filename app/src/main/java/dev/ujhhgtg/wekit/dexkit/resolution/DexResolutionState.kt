package dev.ujhhgtg.wekit.dexkit.resolution

import kotlinx.serialization.Serializable

/** Serialized name of a [DexResolutionState] in desktop and cloud reports. */
@Serializable
enum class DexResolutionStatus {
    PENDING,
    SUCCESS,
    EXPECTED_FAILURE,
    UNEXPECTED_FAILURE,
    BLOCKED,
    INCOMPLETE,
}

/**
 * Resolution state of one Dex delegate; the delegate's resolved descriptor lives only in [Success].
 */
sealed interface DexResolutionState {
    val status: DexResolutionStatus

    /** Not resolved yet in this resolution run (or not restored from the cache). */
    data object Pending : DexResolutionState {
        override val status
            get() = DexResolutionStatus.PENDING
    }

    /** Resolved to a host class or member. */
    data class Success(val descriptor: String) : DexResolutionState {
        override val status
            get() = DexResolutionStatus.SUCCESS
    }

    /**
     * Not found, and the resolver allows that (`allowFailure`, or an explicit structure-selected
     * path).
     */
    data object ExpectedFailure : DexResolutionState {
        override val status
            get() = DexResolutionStatus.EXPECTED_FAILURE
    }

    /** The lookup of this delegate threw. */
    data class UnexpectedFailure(
        val message: String?,
        val exceptionType: String,
        val stackTrace: String,
    ) : DexResolutionState {
        override val status
            get() = DexResolutionStatus.UNEXPECTED_FAILURE
    }

    /** Still pending when the resolver threw on [blockedBy]. */
    data class Blocked(val blockedBy: String) : DexResolutionState {
        override val status
            get() = DexResolutionStatus.BLOCKED
    }

    /** Still pending when the resolver returned normally. */
    data object Incomplete : DexResolutionState {
        override val status
            get() = DexResolutionStatus.INCOMPLETE
    }
}
