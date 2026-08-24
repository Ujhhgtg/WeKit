package dev.ujhhgtg.wekit.dexkit.resolution

enum class DexProducerKind { INLINE_CLASS, INLINE_FIELD, INLINE_METHOD, INLINE_CONSTRUCTOR, CUSTOM }

data class DexProducerMetadata(
    val stableId: String,
    val ownerClassName: String,
    val propertyName: String?,
    val kind: DexProducerKind,
    val localFingerprint: String,
    val usesOwnerSafetyFingerprint: Boolean,
)

data class DexOwnerMetadata(
    val ownerClassName: String,
    val ownerSafetyFingerprint: String,
    val producers: Map<String, DexProducerMetadata>,
    val customOutputPropertyNames: Set<String>,
)
