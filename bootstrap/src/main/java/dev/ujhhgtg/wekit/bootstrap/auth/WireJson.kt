package dev.ujhhgtg.wekit.bootstrap.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object WireJson {
    val format = Json { ignoreUnknownKeys = false }
    inline fun <reified T> body(value: T): JsonObject = format.encodeToJsonElement(value).jsonObject
    fun bytes(value: JsonObject): ByteArray = format.encodeToString(value).toByteArray(Charsets.UTF_8)
}

fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.let {
    require(it.isString) { "$name must be a string" }; it.content
}
fun JsonObject.nullableString(name: String): String? = if (get(name) == null || get(name) === JsonNull) null else string(name)
fun JsonObject.integer(name: String): Long = getValue(name).jsonPrimitive.let {
    require(!it.isString && it.content.matches(Regex("-?(0|[1-9][0-9]*)"))) { "$name must be an integer" }
    it.longOrNull ?: error("$name exceeds integer range")
}
fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.let {
    require(!it.isString) { "$name must be a boolean" }; it.booleanOrNull ?: error("$name must be a boolean")
}
fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

@Serializable
data class RegistrationContextRequest(val mode: String, val tokenSha256: String, val registrationNonce: String, val existingInstallationId: String? = null)
@Serializable
data class ChallengeRequest(val method: String, val path: String, val processNonce: String,
    val registrationId: String? = null, val installationId: String? = null, val grantId: String? = null)
@Serializable
data class DeviceMetadata(val manufacturer: String, val model: String, val androidVersion: String, val hostPackage: String)
@Serializable
data class RegistrationRequest(val requestId: String, val registrationId: String, val registrationNonce: String,
    val deviceInfo: DeviceMetadata, val spki: String? = null, val certificateChain: List<String>? = null,
    val hardwareKey: Boolean = false)
@Serializable
data class BindingRequest(val requestId: String, val registrationId: String, val registrationNonce: String, val bindingSessionId: String)
@Serializable
data class InitializationRequest(val requestId: String, val installationId: String, val processNonce: String,
    val processName: String, val hostPackage: String, val hostVersionCode: String, val hostVersionName: String,
    val sdkInt: Int, val processAbi: String, val bootstrapVersion: String, val selfWxId: String?, val selfAlias: String?,
    val identityReadState: String, val requestedPayloadId: String?, val cachedApkSha256: String? = null)
@Serializable
data class RenewGrantRequest(val requestId: String, val installationId: String, val processNonce: String,
    val initializationId: String, val grantId: String, val payloadId: String, val apkSha256: String)
@Serializable
data class InitializationReport(val requestId: String, val installationId: String, val processNonce: String,
    val initializationId: String, val stage: String, val result: String, val selfWxId: String?, val selfAlias: String?, val identityReadState: String)
@Serializable
data class InstallationAction(val requestId: String, val installationId: String, val processNonce: String)

/**
 * Single-use challenge pre-issued by the server inside a signed response, so the next
 * signed request does not need a separate POST /v1/challenges round trip.
 * `processNonce` is set for challenges bound to the current process (report after
 * initialization) and null for "floating" challenges reusable by the next process.
 */
@Serializable
data class ChallengeTicket(
    val challengeId: String,
    val serverNonce: String,
    val expiresAt: Long,
    val method: String,
    val path: String,
    val processNonce: String? = null,
)

/** Challenge tickets carried by a successful signed response; absent on old servers. */
fun JsonObject.challengeTickets(): List<ChallengeTicket> {
    val node = get("challenges") ?: return emptyList()
    if (node !is kotlinx.serialization.json.JsonArray) return emptyList()
    return node.map { WireJson.format.decodeFromJsonElement(ChallengeTicket.serializer(), it) }
}

@Serializable
data class ReleaseEnvelope(val keyId: String, val manifest: String, val signature: String)
@Serializable
data class ReleaseEncryption(val algorithm: String, val chunkSize: Int, val noncePrefix: String, val chunkCount: Int)
@Serializable
data class ReleaseDocument(val payloadId: String, val businessVersion: String, val sourceCommit: String, val entryClass: String,
    val apkSha256: String, val apkSize: Long, val cipherSha256: String, val cipherSize: Long,
    val encryption: ReleaseEncryption, val createdAt: String)
