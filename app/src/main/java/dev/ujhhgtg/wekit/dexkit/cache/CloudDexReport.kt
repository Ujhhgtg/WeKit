package dev.ujhhgtg.wekit.dexkit.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class CloudDexHost(
    val versionName: String,
    val versionCode: Long,
    val isGooglePlay: Boolean,
)

data class CurrentDexItem(
    val technicalId: String,
    val methodHash: String,
    val delegateKeys: Set<String>,
)

data class CloudDexCacheEntry(
    val technicalId: String,
    val methodHash: String,
    val descriptors: Map<String, String>,
)

data class CloudDexSelection(
    val entries: List<CloudDexCacheEntry>,
    val rejectedCount: Int,
)

object CloudDexReport {
    private val json = Json { ignoreUnknownKeys = true }

    fun assetName(host: CloudDexHost): String =
        "wechat-${host.versionName}-${host.versionCode}-${if (host.isGooglePlay) "google-play" else "domestic"}.json"

    fun select(
        jsonText: String,
        host: CloudDexHost,
        items: List<CurrentDexItem>,
    ): CloudDexSelection {
        val report = json.decodeFromString<Report>(jsonText)
        require(report.schemaVersion == SCHEMA_VERSION) { "unsupported cloud report schema: ${report.schemaVersion}" }
        require(report.outcome == APK_PASS) { "cloud report did not pass: ${report.outcome}" }
        require(report.versionName == host.versionName) { "cloud report version name does not match host" }
        require(report.versionCode == host.versionCode) { "cloud report version code does not match host" }
        require(report.isGooglePlay == host.isGooglePlay) { "cloud report channel does not match host" }

        val featuresById = report.features.groupBy(Feature::technicalId)
        val entries = items.mapNotNull { item ->
            val features = featuresById[item.technicalId]
            if (features?.size != 1) return@mapNotNull null

            val feature = features.single()
            if (feature.outcome !in FEATURE_PASS_OUTCOMES || feature.methodHash != item.methodHash) {
                return@mapNotNull null
            }

            val delegatesByKey = feature.delegates.groupBy(Delegate::key)
            if (delegatesByKey.values.any { it.size != 1 }) return@mapNotNull null

            val descriptors = LinkedHashMap<String, String>(item.delegateKeys.size)
            for (key in item.delegateKeys) {
                val delegate = delegatesByKey[key]?.singleOrNull() ?: return@mapNotNull null
                val descriptor = delegate.descriptor
                if (!delegate.hasValidOutcome() || descriptor.isNullOrEmpty()) {
                    return@mapNotNull null
                }
                descriptors[key] = descriptor
            }

            CloudDexCacheEntry(
                technicalId = item.technicalId,
                methodHash = item.methodHash,
                descriptors = descriptors,
            )
        }

        return CloudDexSelection(entries, items.size - entries.size)
    }

    private const val SCHEMA_VERSION = 2
    private const val APK_PASS = "PASS"
    private val FEATURE_PASS_OUTCOMES = setOf("PASS", "PASS_WITH_EXPECTED_FAILURES")

    private fun Delegate.hasValidOutcome(): Boolean = when (status) {
        "SUCCESS" -> !isPlaceholder
        "EXPECTED_FAILURE" -> isPlaceholder
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
    val technicalId: String,
    val methodHash: String,
    val outcome: String,
    val delegates: List<Delegate>,
)

@Serializable
private data class Delegate(
    val key: String,
    val status: String,
    val descriptor: String? = null,
    val isPlaceholder: Boolean = false,
)
