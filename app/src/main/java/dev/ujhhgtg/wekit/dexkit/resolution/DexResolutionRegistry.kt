package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexClassDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexConstructorDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexFieldDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.features.core.BaseFeature
import java.util.IdentityHashMap
import java.util.TreeMap

class DexDelegateNode internal constructor(
    val delegate: BaseDexDelegate,
) {
    val stableId: String get() = delegate.stableId
}

class DexProducerNode internal constructor(
    val metadata: DexProducerMetadata,
    val owner: BaseFeature,
    val inlineDelegate: BaseDexDelegate?,
    val outputs: List<BaseDexDelegate>,
) {
    val stableId: String get() = metadata.stableId
}

class DexResolutionRegistry private constructor(
    val ownersById: Map<String, BaseFeature>,
    val nodesById: Map<String, DexDelegateNode>,
    val producersById: Map<String, DexProducerNode>,
    private val producerByDelegate: Map<BaseDexDelegate, DexProducerNode>,
    private val customPhaseByOwner: Map<BaseFeature, DexProducerNode>,
) {
    fun node(delegate: BaseDexDelegate): DexDelegateNode =
        nodesById[delegate.stableId]
            ?.takeIf { it.delegate === delegate }
            ?: error("Dex delegate is not registered: ${delegate.stableId}")

    fun producerOf(delegate: BaseDexDelegate): DexProducerNode =
        producerByDelegate[delegate]
            ?: error("Dex delegate producer is not registered: ${delegate.stableId}")

    fun customPhase(owner: IResolveDex): DexProducerNode =
        customPhaseByOwner[owner as? BaseFeature]
            ?: error("Dex owner has no custom resolution phase: ${(owner as? BaseFeature)?.javaClass?.name ?: owner.javaClass.name}")

    companion object {
        fun create(owners: List<IResolveDex>): DexResolutionRegistry =
            create(owners, GeneratedDexResolutionMetadata.OWNERS)

        internal fun create(
            owners: List<IResolveDex>,
            metadataByOwner: Map<String, DexOwnerMetadata>,
        ): DexResolutionRegistry {
            val ownersById = TreeMap<String, BaseFeature>()
            val nodesById = TreeMap<String, DexDelegateNode>()
            val producersById = TreeMap<String, DexProducerNode>()
            val producerByDelegate = IdentityHashMap<BaseDexDelegate, DexProducerNode>()
            val customPhaseByOwner = IdentityHashMap<BaseFeature, DexProducerNode>()
            val seenProducerIds = mutableSetOf<String>()

            owners.forEach { owner ->
                val feature = owner as? BaseFeature
                    ?: throw IllegalArgumentException("Dex resolution owner must extend BaseFeature: ${owner.javaClass.name}")
                val ownerId = feature.javaClass.name
                require(ownersById.put(ownerId, feature) == null) {
                    "Duplicate Dex resolution owner ID: $ownerId"
                }
                val ownerMetadata = requireNotNull(metadataByOwner[ownerId]) {
                    "Missing generated Dex resolution metadata for owner: $ownerId"
                }
                require(ownerMetadata.ownerClassName == ownerId) {
                    "Dex owner metadata ID mismatch: key=$ownerId value=${ownerMetadata.ownerClassName}"
                }
                ownerMetadata.producers.forEach { (metadataKey, producerMetadata) ->
                    require(metadataKey == producerMetadata.stableId) {
                        "Dex producer metadata key mismatch: key=$metadataKey value=${producerMetadata.stableId}"
                    }
                    require(seenProducerIds.add(producerMetadata.stableId)) {
                        "Duplicate Dex producer stable ID: ${producerMetadata.stableId}"
                    }
                }

                val delegatesByProperty = TreeMap<String, BaseDexDelegate>()
                owner.dexDelegates.forEach { delegate ->
                    require(delegate.owner === feature) {
                        "Dex delegate owner mismatch: ${delegate.stableId}"
                    }
                    require(delegatesByProperty.put(delegate.propertyName, delegate) == null) {
                        "Duplicate Dex delegate property: $ownerId#${delegate.propertyName}"
                    }
                    require(nodesById.put(delegate.stableId, DexDelegateNode(delegate)) == null) {
                        "Duplicate Dex delegate stable ID: ${delegate.stableId}"
                    }
                }

                val inlineMetadataByProperty = TreeMap<String, DexProducerMetadata>()
                ownerMetadata.producers.values
                    .filter { it.kind != DexProducerKind.CUSTOM }
                    .forEach { producerMetadata ->
                        val propertyName = requireNotNull(producerMetadata.propertyName) {
                            "Inline producer has no property: ${producerMetadata.stableId}"
                        }
                        require(inlineMetadataByProperty.put(propertyName, producerMetadata) == null) {
                            "Duplicate Dex producer property: $ownerId#$propertyName"
                        }
                    }
                val expectedProperties = inlineMetadataByProperty.keys + ownerMetadata.customOutputPropertyNames
                require(expectedProperties == delegatesByProperty.keys) {
                    val missing = delegatesByProperty.keys - expectedProperties
                    val unexpected = expectedProperties - delegatesByProperty.keys
                    "Dex property metadata mismatch for $ownerId. Missing: ${missing.sorted()}; unexpected: ${unexpected.sorted()}"
                }

                inlineMetadataByProperty.toSortedMap().forEach { (propertyName, producerMetadata) ->
                    val delegate = delegatesByProperty.getValue(propertyName)
                    require(delegate.inlineProducer != null) {
                        "Inline metadata maps a custom Dex delegate: ${delegate.stableId}"
                    }
                    requireProducerMetadata(ownerId, delegate, producerMetadata)
                    require(producerMetadata.kind == expectedInlineKind(delegate)) {
                        "Dex producer kind mismatch for ${delegate.stableId}: ${producerMetadata.kind}"
                    }
                    val producer = DexProducerNode(producerMetadata, feature, delegate, listOf(delegate))
                    require(producersById.put(producer.stableId, producer) == null) {
                        "Duplicate Dex producer stable ID: ${producer.stableId}"
                    }
                    producerByDelegate[delegate] = producer
                }

                val customOutputs = ownerMetadata.customOutputPropertyNames.sorted().map(delegatesByProperty::getValue)
                customOutputs.forEach { delegate ->
                    require(delegate.inlineProducer == null) {
                        "Custom metadata maps an inline Dex delegate: ${delegate.stableId}"
                    }
                }
                val customMetadata = ownerMetadata.producers.values.filter { it.kind == DexProducerKind.CUSTOM }
                require(customMetadata.size == if (customOutputs.isEmpty()) 0 else 1) {
                    "Expected ${if (customOutputs.isEmpty()) 0 else 1} custom producer for $ownerId, found ${customMetadata.size}"
                }
                customMetadata.singleOrNull()?.let { producerMetadata ->
                    require(producerMetadata.stableId == "$ownerId#resolveDex") {
                        "Invalid custom producer ID: ${producerMetadata.stableId}"
                    }
                    require(producerMetadata.ownerClassName == ownerId && producerMetadata.propertyName == null) {
                        "Invalid custom producer metadata: ${producerMetadata.stableId}"
                    }
                    val producer = DexProducerNode(producerMetadata, feature, null, customOutputs)
                    require(producersById.put(producer.stableId, producer) == null) {
                        "Duplicate Dex producer stable ID: ${producer.stableId}"
                    }
                    customPhaseByOwner[feature] = producer
                    customOutputs.forEach { producerByDelegate[it] = producer }
                }
            }

            return DexResolutionRegistry(
                ownersById = ownersById.toMap(),
                nodesById = nodesById.toMap(),
                producersById = producersById.toMap(),
                producerByDelegate = producerByDelegate,
                customPhaseByOwner = customPhaseByOwner,
            )
        }

        private fun requireProducerMetadata(
            ownerId: String,
            delegate: BaseDexDelegate,
            metadata: DexProducerMetadata,
        ) {
            require(metadata.stableId == delegate.stableId) {
                "Dex producer ID mismatch: delegate=${delegate.stableId} metadata=${metadata.stableId}"
            }
            require(metadata.ownerClassName == ownerId && metadata.propertyName == delegate.propertyName) {
                "Dex producer metadata mismatch: ${metadata.stableId}"
            }
        }

        private fun expectedInlineKind(delegate: BaseDexDelegate): DexProducerKind = when (delegate) {
            is DexClassDelegate -> DexProducerKind.INLINE_CLASS
            is DexFieldDelegate -> DexProducerKind.INLINE_FIELD
            is DexMethodDelegate -> DexProducerKind.INLINE_METHOD
            is DexConstructorDelegate -> DexProducerKind.INLINE_CONSTRUCTOR
        }
    }
}
