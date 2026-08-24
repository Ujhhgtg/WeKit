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
        require(schemaVersion > 0) { "Monet role catalog schemaVersion must be positive" }
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
        private val json = Json { ignoreUnknownKeys = false }

        fun load(payloadDir: File): MonetRoleCatalog = json.decodeFromString<MonetRoleCatalog>(
            payloadDir.resolve("monet_roles.json").readText(),
        )
    }
}

@Serializable
internal data class MonetRoleDefinition(
    val id: String,
    val type: String,
    val core: Boolean,
    val minSdk: Int = 31,
    val maxSdk: Int? = null,
    val defaultValue: String? = null,
    val nightValue: String? = null,
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
