package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerKind
import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.dexkit.resolution.effectiveFingerprint
import java.util.TreeMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class CloudDexHost(
    val versionName: String,
    val versionCode: Long,
    val isGooglePlay: Boolean,
)

internal data class CurrentDexDelegate(
    val id: String,
    val producerId: String,
    val producerFingerprint: String,
)

internal data class CurrentDexOwner(
    val ownerId: String,
    val technicalId: String,
    val delegates: Map<String, CurrentDexDelegate>,
)

internal data class CloudDexCacheEntry(
    val technicalId: String,
    val manifest: DexCacheManifest,
)

internal data class CloudDexSelection(
    val entries: List<CloudDexCacheEntry>,
    val rejectedCount: Int,
)

internal object CloudDexReport {
    private val json = Json { ignoreUnknownKeys = true }

    fun assetName(host: CloudDexHost): String =
        "wechat-${host.versionName}-${host.versionCode}-${if (host.isGooglePlay) "google-play" else "domestic"}.json"

    fun select(
        jsonText: String,
        host: CloudDexHost,
        owners: List<CurrentDexOwner>,
    ): CloudDexSelection {
        val report = json.decodeFromString<Report>(jsonText)
        require(report.schemaVersion == SCHEMA_VERSION) { "unsupported cloud report schema: ${report.schemaVersion}" }
        require(report.outcome == APK_PASS) { "cloud report did not pass: ${report.outcome}" }
        require(report.versionName == host.versionName) { "cloud report version name does not match host" }
        require(report.versionCode == host.versionCode) { "cloud report version code does not match host" }
        require(report.isGooglePlay == host.isGooglePlay) { "cloud report channel does not match host" }

        val outputsByProducer = owners.flatMap { owner -> owner.delegates.values.map { it.producerId to it } }
            .groupBy({ it.first }, { it.second })
        val ownerByProducer = owners.flatMap { owner ->
            owner.delegates.values.map { it.producerId to owner.ownerId }
        }.toMap()
        val featuresByOwner = report.features.groupBy(Feature::className)
        val reportDelegatesById = report.features.flatMap(Feature::delegates).groupBy(Delegate::id)
        val invalidOwners = mutableSetOf<String>()
        val entriesByOwner = mutableMapOf<String, Map<String, Delegate>>()

        owners.forEach ownerLoop@{ owner ->
            val feature = featuresByOwner[owner.ownerId]?.singleOrNull()
            if (feature == null || feature.outcome !in FEATURE_PASS_OUTCOMES) {
                invalidOwners += owner.ownerId
                return@ownerLoop
            }
            val selected = TreeMap<String, Delegate>()
            for (delegateId in owner.delegates.keys.sorted()) {
                val candidates = reportDelegatesById[delegateId]
                val reportDelegate = candidates?.singleOrNull()
                val current = owner.delegates.getValue(delegateId)
                if (reportDelegate == null ||
                    reportDelegate.producerFingerprint != current.producerFingerprint ||
                    !reportDelegate.isValidOutcome() ||
                    reportDelegate.descriptor.isNullOrBlank() ||
                    reportDelegate.dependencies.size != reportDelegate.dependencies.distinct().size
                ) {
                    invalidOwners += owner.ownerId
                    return@ownerLoop
                }
                selected[delegateId] = reportDelegate
            }
            owner.delegates.values.groupBy { it.producerId }.forEach { (producerId, outputs) ->
                val snapshots = outputs.map { output -> selected.getValue(output.id).producerSnapshot() }.distinct()
                if (snapshots.size != 1) {
                    invalidOwners += owner.ownerId
                    return@ownerLoop
                }
            }
            entriesByOwner[owner.ownerId] = selected
        }

        val effectiveByProducer = mutableMapOf<String, String>()
        val visiting = mutableSetOf<String>()
        fun validateProducer(producerId: String): Boolean {
            if (producerId in effectiveByProducer) return true
            if (!visiting.add(producerId)) return false
            val ownerId = ownerByProducer[producerId] ?: return false
            if (ownerId in invalidOwners) return false
            val outputs = outputsByProducer[producerId] ?: return false
            val entry = entriesByOwner[ownerId]?.get(outputs.first().id) ?: return false
            val dependencyFingerprints = TreeMap<String, String>()
            for (dependencyId in entry.dependencies.sorted()) {
                if (!validateProducer(dependencyId)) return false
                dependencyFingerprints[dependencyId] = effectiveByProducer.getValue(dependencyId)
            }
            val expected = effectiveFingerprint(
                DexProducerMetadata(
                    stableId = producerId,
                    ownerClassName = ownerId,
                    propertyName = null,
                    kind = DexProducerKind.CUSTOM,
                    localFingerprint = outputs.first().producerFingerprint,
                    usesOwnerSafetyFingerprint = false,
                ),
                dependencyFingerprints,
            )
            if (entry.effectiveFingerprint != expected) return false
            visiting.remove(producerId)
            effectiveByProducer[producerId] = expected
            return true
        }

        owners.forEach { owner ->
            if (owner.ownerId !in invalidOwners &&
                owner.delegates.values.map { it.producerId }.distinct().any { !validateProducer(it) }
            ) {
                invalidOwners += owner.ownerId
            }
        }
        var changed: Boolean
        do {
            changed = false
            owners.forEach { owner ->
                if (owner.ownerId !in invalidOwners) {
                    val badDependency = entriesByOwner.getValue(owner.ownerId).values
                        .flatMap { it.dependencies }
                        .any { ownerByProducer[it] in invalidOwners }
                    if (badDependency) {
                        invalidOwners += owner.ownerId
                        changed = true
                    }
                }
            }
        } while (changed)

        val entries = owners.filter { it.ownerId !in invalidOwners }.sortedBy { it.ownerId }.map { owner ->
            val delegates = entriesByOwner.getValue(owner.ownerId).mapValuesTo(TreeMap()) { (_, delegate) ->
                DexCacheDelegateEntry(
                    descriptor = delegate.descriptor!!,
                    status = delegate.status,
                    isPlaceholder = delegate.isPlaceholder,
                    producerFingerprint = delegate.producerFingerprint,
                    effectiveFingerprint = delegate.effectiveFingerprint,
                    dependencies = delegate.dependencies.sorted(),
                )
            }
            CloudDexCacheEntry(
                technicalId = owner.technicalId,
                manifest = DexCacheManifest(owner = owner.ownerId, timestamp = 0, delegates = delegates),
            )
        }
        return CloudDexSelection(entries, invalidOwners.size)
    }

    private const val SCHEMA_VERSION = 2
    private const val APK_PASS = "PASS"
    private val FEATURE_PASS_OUTCOMES = setOf("PASS", "PASS_WITH_EXPECTED_FAILURES")

    private fun Delegate.isValidOutcome(): Boolean = when (status) {
        DexResolutionStatus.SUCCESS -> !isPlaceholder
        DexResolutionStatus.EXPECTED_FAILURE -> isPlaceholder
        else -> false
    }
}

@Serializable
private data class Report(
    val schemaVersion: Int,
    val outcome: String,
    val versionCode: Long,
    val versionName: String,
    val isGooglePlay: Boolean,
    val features: List<Feature>,
)

@Serializable
private data class Feature(
    val className: String,
    val outcome: String,
    val delegates: List<Delegate>,
)

@Serializable
private data class Delegate(
    val id: String,
    val status: DexResolutionStatus,
    val descriptor: String? = null,
    val isPlaceholder: Boolean = false,
    val producerFingerprint: String,
    val effectiveFingerprint: String,
    val dependencies: List<String>,
)

private fun Delegate.producerSnapshot() =
    Triple(producerFingerprint, effectiveFingerprint, dependencies.sorted())
