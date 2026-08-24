package dev.ujhhgtg.wekit.extensions.monet

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class MonetRoleCatalog(
    val schemaVersion: Int,
    val roles: List<MonetRoleDefinition>,
    val overlays: List<MonetOverlayDefinition>,
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported Monet role catalog schemaVersion: $schemaVersion"
        }
        val roleIds = roles.map(MonetRoleDefinition::id)
        require(roleIds.size == roleIds.toSet().size) { "Monet role catalog contains duplicate role IDs" }
        val knownRoleIds = roleIds.toSet()
        roles.forEach { role ->
            require(role.id.isNotBlank()) { "Monet role ID must not be blank" }
            require(role.type.isNotBlank()) { "Monet role ${role.id} has a blank type" }
            require(role.requiredIncomingRoleIds.all(knownRoleIds::contains)) {
                "Monet role ${role.id} references an unknown incoming role"
            }
            require(role.dexAnchors.flatMap(MonetDexAnchor::neighboringRoleIds).all(knownRoleIds::contains)) {
                "Monet role ${role.id} has a Dex anchor referencing an unknown neighboring role"
            }
        }
        overlays.forEach { overlay ->
            require(overlay.templateResources.keys.all(knownRoleIds::contains)) {
                "Monet overlay ${overlay.id} binds an unknown role"
            }
        }
    }

    fun hasRole(roleId: String): Boolean = roles.any { it.id == roleId }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private val json = Json { ignoreUnknownKeys = false }

        fun load(payloadDir: File): MonetRoleCatalog = json.decodeFromString<MonetRoleCatalog>(
            payloadDir.resolve("monet_roles.json").readText(),
        )
    }
}

@Serializable
internal data class MonetProfileCatalog(
    val schemaVersion: Int,
    val digestAlgorithm: String,
    val verifiedProfiles: List<MonetVerifiedProfile>,
    val structuralOnlyProfiles: List<MonetStructuralProfile>,
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported Monet profile catalog schemaVersion: $schemaVersion"
        }
        require(digestAlgorithm == SUPPORTED_DIGEST_ALGORITHM) {
            "Unsupported Monet resource graph digest algorithm: $digestAlgorithm"
        }
        require(verifiedProfiles.map(MonetVerifiedProfile::resourceDigest).toSet().size == verifiedProfiles.size) {
            "Monet profile catalog contains duplicate verified resource digests"
        }
        val domesticVersions = structuralOnlyProfiles
            .filter { it.channel == "domestic" }
            .map(MonetStructuralProfile::versionName)
        require(domesticVersions.size == DOMESTIC_VERSIONS.size && domesticVersions.toSet() == DOMESTIC_VERSIONS) {
            "Monet profile catalog must contain exactly the five audited domestic versions"
        }
        require(structuralOnlyProfiles.all { !it.selectable }) {
            "Structural-only Monet profiles must not be selectable"
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val SUPPORTED_DIGEST_ALGORITHM = "monet-resource-graph-v1"
        val DOMESTIC_VERSIONS = setOf("8.0.65", "8.0.67", "8.0.69", "8.0.74", "8.0.76")
        private val json = Json { ignoreUnknownKeys = false }

        fun load(payloadDir: File): MonetProfileCatalog = json.decodeFromString<MonetProfileCatalog>(
            payloadDir.resolve("monet_profiles.json").readText(),
        )
    }
}

@Serializable
internal data class MonetVerifiedProfile(
    val resourceDigest: String,
    val versionName: String,
    val versionCode: Int,
    val channel: String,
    val sourceApksSha256: String,
    @Serializable(with = MonetResourceKeyMapSerializer::class)
    val roles: Map<String, MonetResourceKey>,
) {
    init {
        require(resourceDigest.matches(Regex("[0-9a-f]{64}"))) { "Invalid verified profile digest" }
        require(sourceApksSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid verified profile source hash" }
        require(roles.values.toSet().size == roles.size) {
            "Verified Monet profile assigns one resource key to multiple roles"
        }
    }

    fun toResolutionProfile(): MonetProfile = MonetProfile(resourceDigest, versionName, channel, roles)
}

@Serializable
internal data class MonetStructuralProfile(
    val versionName: String,
    val versionCode: Int? = null,
    val channel: String,
    val selectable: Boolean,
    val sourceKind: String? = null,
    val reason: String,
    @Serializable(with = MonetResourceKeyMapSerializer::class)
    val roles: Map<String, MonetResourceKey> = emptyMap(),
    val sourceEvidence: MonetSourceEvidence? = null,
)

@Serializable
internal data class MonetSourceEvidence(
    val resourceFileCount: Int,
    val resourceSnapshotSha256: String,
)

@Serializable
internal data class MonetRoleDefinition(
    val id: String,
    val type: String,
    val core: Boolean,
    val minSdk: Int = 31,
    val maxSdk: Int? = null,
    val defaultValue: String? = null,
    val nightValue: String? = null,
    val defaultValueStructure: String? = null,
    val nightValueStructure: String? = null,
    val xmlShapeSha256: String? = null,
    val requiredIncomingRoleIds: List<String> = emptyList(),
    val dexAnchors: List<MonetDexAnchor> = emptyList(),
)

@Serializable
internal data class MonetDexAnchor(
    val descriptor: String? = null,
    val stableStrings: List<String> = emptyList(),
    val invokedMethodShapes: List<String> = emptyList(),
    val neighboringRoleIds: List<String> = emptyList(),
    val fieldAccesses: List<MonetDexFieldAccessAnchor> = emptyList(),
)

@Serializable
internal data class MonetDexFieldAccessAnchor(
    val descriptor: String,
    val access: String,
)

@Serializable
internal data class MonetProfile(
    val resourceDigest: String,
    val versionName: String,
    val channel: String,
    @Serializable(with = MonetResourceKeyMapSerializer::class)
    val roles: Map<String, MonetResourceKey>,
)

@Serializable
internal data class MonetOverlayDefinition(
    val id: String,
    val packageName: String,
    val fileName: String,
    val templateFile: String,
    val selectionCondition: MonetOverlaySelectionCondition = MonetOverlaySelectionCondition(),
    @Serializable(with = MonetResourceKeyMapSerializer::class)
    val templateResources: Map<String, MonetResourceKey>,
)

@Serializable
internal data class MonetOverlaySelectionCondition(
    val bubbleStyle: String? = null,
    val multiSceneCornersEnabled: Boolean? = null,
    val tabStyle: String? = null,
)

@Serializable
private data class SerializableMonetResourceKey(
    val type: String,
    val name: String,
)

internal object MonetResourceKeySerializer : KSerializer<MonetResourceKey> {
    override val descriptor: SerialDescriptor = SerializableMonetResourceKey.serializer().descriptor

    override fun serialize(encoder: Encoder, value: MonetResourceKey) {
        encoder.encodeSerializableValue(
            SerializableMonetResourceKey.serializer(),
            SerializableMonetResourceKey(value.type, value.name),
        )
    }

    override fun deserialize(decoder: Decoder): MonetResourceKey {
        val key = decoder.decodeSerializableValue(SerializableMonetResourceKey.serializer())
        return MonetResourceKey(key.type, key.name)
    }
}

internal object MonetResourceKeyMapSerializer : KSerializer<Map<String, MonetResourceKey>> by MapSerializer(
    String.serializer(),
    MonetResourceKeySerializer,
)
