package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ReadReceiptsConfiguration(
    val mode: ReadReceiptsServerMode = ReadReceiptsServerMode.THIRD_PARTY,
    val thirdPartyUrl: String = "",
    val prefix: String = "#",
    val pollIntervalSecs: Int = 5,
    val automaticPort: Boolean = true,
    val builtInPort: Int = 3000,
    val automaticLifecycle: Boolean = true,
    val tunnelMode: String = "QUICK",
    val hostname: String = "",
    val selectedAccountId: String = "",
    val selectedAccountName: String = "",
    val selectedTunnelId: String = "",
    val selectedTunnelName: String = "",
)

object ReadReceiptsConfigurationCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(configuration: ReadReceiptsConfiguration): String {
        val value = validate(configuration)
        return buildJsonObject {
            put("version", SCHEMA_VERSION)
            put("mode", value.mode.name)
            put("thirdPartyUrl", value.thirdPartyUrl)
            put("prefix", value.prefix)
            put("pollIntervalSecs", value.pollIntervalSecs)
            put("automaticPort", value.automaticPort)
            put("builtInPort", value.builtInPort)
            put("automaticLifecycle", value.automaticLifecycle)
            put("tunnelMode", value.tunnelMode)
            put("hostname", value.hostname)
            put("selectedAccountId", value.selectedAccountId)
            put("selectedAccountName", value.selectedAccountName)
            put("selectedTunnelId", value.selectedTunnelId)
            put("selectedTunnelName", value.selectedTunnelName)
        }.toString()
    }

    fun decode(value: String): ReadReceiptsConfiguration? = runCatching {
        val objectValue = DefaultJson.parseToJsonElement(value).jsonObject
        require(objectValue["version"]?.strictIntOrNull() == SCHEMA_VERSION)
        val modeName = objectValue["mode"]?.stringOrNull() ?: error("missing mode")
        val mode = ReadReceiptsServerMode.entries.firstOrNull { it.name == modeName }
            ?: error("unknown mode")

        validate(
            ReadReceiptsConfiguration(
                mode = mode,
                thirdPartyUrl = objectValue["thirdPartyUrl"]?.stringOrNull()
                    ?: error("missing third-party URL"),
                prefix = objectValue["prefix"]?.stringOrNull() ?: error("missing prefix"),
                pollIntervalSecs = objectValue["pollIntervalSecs"]?.strictIntOrNull()
                    ?: error("missing poll interval"),
                automaticPort = objectValue["automaticPort"]?.strictBooleanOrNull()
                    ?: error("missing automatic-port selection"),
                builtInPort = objectValue["builtInPort"]?.strictIntOrNull()
                    ?: error("missing built-in port"),
                automaticLifecycle = objectValue["automaticLifecycle"]?.strictBooleanOrNull()
                    ?: error("missing automatic lifecycle"),
                tunnelMode = objectValue["tunnelMode"]?.stringOrNull()
                    ?: error("missing tunnel mode"),
                hostname = objectValue["hostname"]?.stringOrNull() ?: error("missing hostname"),
                selectedAccountId = objectValue["selectedAccountId"]?.stringOrNull()
                    ?: error("missing account id"),
                selectedAccountName = objectValue["selectedAccountName"]?.stringOrNull()
                    ?: error("missing account name"),
                selectedTunnelId = objectValue["selectedTunnelId"]?.stringOrNull()
                    ?: error("missing tunnel id"),
                selectedTunnelName = objectValue["selectedTunnelName"]?.stringOrNull()
                    ?: error("missing tunnel name"),
            ),
        )
    }.getOrNull()

    private fun validate(value: ReadReceiptsConfiguration): ReadReceiptsConfiguration {
        require(value.pollIntervalSecs > 0)
        require(value.builtInPort in 1..65535)
        require(value.tunnelMode.isNotBlank())
        return value
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.strictIntOrNull(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonElement.strictBooleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
