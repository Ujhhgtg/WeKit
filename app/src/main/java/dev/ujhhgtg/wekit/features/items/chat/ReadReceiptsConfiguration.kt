package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ReadReceiptsConfiguration(
    val thirdPartyUrl: String = "",
    val pollIntervalSecs: Int = 5,
)

object ReadReceiptsConfigurationCodec {
    private const val SCHEMA_VERSION = 2

    fun encode(configuration: ReadReceiptsConfiguration): String {
        val value = validate(configuration)
        return buildJsonObject {
            put("version", SCHEMA_VERSION)
            put("thirdPartyUrl", value.thirdPartyUrl)
            put("pollIntervalSecs", value.pollIntervalSecs)
        }.toString()
    }

    fun decode(value: String): ReadReceiptsConfiguration? = runCatching {
        val objectValue = DefaultJson.parseToJsonElement(value).jsonObject
        val version = objectValue["version"]?.strictIntOrNull()
        require(version == 1 || version == SCHEMA_VERSION)
        val url = objectValue["thirdPartyUrl"]?.stringOrNull()
            ?: error("missing third-party URL")
        validate(
            ReadReceiptsConfiguration(
                // Older snapshots could retain an invalid inactive URL; leave it unconfigured.
                thirdPartyUrl = if (version == 1) {
                    normalizeThirdPartyReadReceiptEndpoint(url) ?: ""
                } else {
                    url
                },
                pollIntervalSecs = objectValue["pollIntervalSecs"]?.strictIntOrNull()
                    ?: error("missing poll interval"),
            ),
        )
    }.getOrNull()

    private fun validate(value: ReadReceiptsConfiguration): ReadReceiptsConfiguration {
        require(value.pollIntervalSecs > 0)
        if (value.thirdPartyUrl.isEmpty()) return value
        val endpoint = requireNotNull(normalizeThirdPartyReadReceiptEndpoint(value.thirdPartyUrl)) {
            "invalid third-party server URL"
        }
        return value.copy(thirdPartyUrl = endpoint)
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.strictIntOrNull(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
