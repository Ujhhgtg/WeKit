package dev.ujhhgtg.wekit.agent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Converts kotlinx-serialization [JsonElement]s into the plain Kotlin values expected by the MCP
 * client SDK's `callTool(name, arguments: Map<String, Any?>)` overload.
 */
object McpJsonBridge {
    fun toPlain(e: JsonElement): Any? =
        when (e) {
            is JsonNull -> {
                null
            }

            is JsonPrimitive -> {
                when {
                    e.isString -> e.content
                    e.booleanOrNull != null -> e.booleanOrNull
                    e.longOrNull != null -> e.longOrNull
                    e.doubleOrNull != null -> e.doubleOrNull
                    else -> e.content
                }
            }

            is JsonArray -> {
                e.map { toPlain(it) }
            }

            is JsonObject -> {
                e.mapValues { toPlain(it.value) }
            }
        }
}
