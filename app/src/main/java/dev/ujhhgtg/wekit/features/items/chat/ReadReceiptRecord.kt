package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import java.net.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The persistence backend responsible for a tracked read-receipt record. */
enum class ReadReceiptBackend {
    THIRD_PARTY,
    BUILT_IN,
}

data class ReadReceiptRecord(
    val id: String,
    val wxId: String,
    val backend: ReadReceiptBackend,
    val endpoint: String,
    val createdAtMillis: Long,
)

object ReadReceiptRecordCodec {
    private const val SCHEMA_VERSION = 1
    private const val BUILT_IN_ENDPOINT = "builtin://local"
    private const val MAX_ID_LENGTH = 128
    private const val MAX_WX_ID_BYTES = 128
    private const val MAX_ENDPOINT_LENGTH = 2048
    private val lowercaseHexId = Regex("[0-9a-f]+")

    fun encode(record: ReadReceiptRecord): String {
        val normalized = normalize(record)
        return buildJsonObject {
            put("version", SCHEMA_VERSION)
            put("id", normalized.id)
            put("wxId", normalized.wxId)
            put("backend", normalized.backend.name)
            put("endpoint", normalized.endpoint)
            put("createdAtMillis", normalized.createdAtMillis)
        }.toString()
    }

    fun decode(value: String): ReadReceiptRecord? = runCatching {
        val objectValue = DefaultJson.parseToJsonElement(value).jsonObject
        val version = objectValue["version"]?.strictIntOrNull() ?: return null
        if (version != SCHEMA_VERSION) return null

        val id = objectValue["id"]?.stringOrNull() ?: return null
        val wxId = objectValue["wxId"]?.stringOrNull() ?: return null
        val backend = objectValue["backend"]?.stringOrNull()?.let {
            ReadReceiptBackend.entries.firstOrNull { backend -> backend.name == it }
        } ?: return null
        val endpoint = objectValue["endpoint"]?.stringOrNull() ?: return null
        val createdAtMillis = objectValue["createdAtMillis"]?.strictLongOrNull() ?: return null

        normalize(ReadReceiptRecord(id, wxId, backend, endpoint, createdAtMillis))
    }.getOrNull()

    fun prune(
        records: Collection<ReadReceiptRecord>,
        nowMillis: Long,
        retentionMillis: Long,
    ): Set<ReadReceiptRecord> {
        val cutoff = nowMillis - retentionMillis
        val retained = LinkedHashMap<RecordKey, ReadReceiptRecord>()
        for (record in records) {
            val normalized = normalize(record)
            if (normalized.createdAtMillis < cutoff) continue
            val key = RecordKey(
                normalized.id,
                normalized.wxId,
                normalized.backend,
                normalized.endpoint,
            )
            val previous = retained[key]
            if (previous == null || normalized.createdAtMillis > previous.createdAtMillis) {
                retained[key] = normalized
            }
        }
        return retained.values.toSet()
    }

    private fun normalize(record: ReadReceiptRecord): ReadReceiptRecord {
        require(record.id.isNotEmpty() && record.id.length <= MAX_ID_LENGTH)
        require(lowercaseHexId.matches(record.id))
        require(
            record.wxId.isNotBlank() &&
                record.wxId.toByteArray(Charsets.UTF_8).size <= MAX_WX_ID_BYTES,
        )
        require(record.endpoint.isNotBlank() && record.endpoint.length <= MAX_ENDPOINT_LENGTH)
        require(record.createdAtMillis > 0)

        val endpoint = when (record.backend) {
            ReadReceiptBackend.THIRD_PARTY -> {
                val normalized = record.endpoint.trimEnd('/')
                require(normalized.isNotBlank() && normalized.length <= MAX_ENDPOINT_LENGTH)
                require(normalized == normalized.trim())
                require(normalized.none(Char::isWhitespace))
                val uri = URI(normalized)
                require(uri.scheme == "https")
                require(!uri.host.isNullOrEmpty())
                require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                normalized
            }

            ReadReceiptBackend.BUILT_IN -> {
                require(record.endpoint == BUILT_IN_ENDPOINT)
                BUILT_IN_ENDPOINT
            }
        }
        return record.copy(endpoint = endpoint)
    }

    private data class RecordKey(
        val id: String,
        val wxId: String,
        val backend: ReadReceiptBackend,
        val endpoint: String,
    )

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.strictIntOrNull(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonElement.strictLongOrNull(): Long? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
