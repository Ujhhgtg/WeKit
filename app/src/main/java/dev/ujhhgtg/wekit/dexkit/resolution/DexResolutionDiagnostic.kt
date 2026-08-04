package dev.ujhhgtg.wekit.dexkit.resolution

enum class DexResolutionStatus {
    PENDING,
    SUCCESS,
    EXPECTED_FAILURE,
    UNEXPECTED_FAILURE,
    BLOCKED,
    INCOMPLETE,
}

data class DexResolutionDiagnostic(
    val status: DexResolutionStatus,
    val descriptor: String? = null,
    val message: String? = null,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
    val blockedBy: String? = null,
)
