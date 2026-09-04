package dev.ujhhgtg.wekit.agent.model

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Shared JSON config + helpers for the LLM adapters. */
object LlmJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    /**
     * Shallow-merges [override] onto [base] at the top level: keys present in [override] replace
     * those in [base] (§5.2 — custom JSON has higher precedence than generated fields). Null
     * [override] returns [base] unchanged.
     */
    fun shallowMerge(base: JsonObject, override: JsonObject?): JsonObject {
        if (override.isNullOrEmpty()) return base
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            override.forEach { (k, v) -> put(k, v) }
        }
    }
}

/** Minimal SSE line parser: extracts the payload after a `data:` prefix, or null for other lines. */
object SseParser {
    fun dataOrNull(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.substring(5).trim()
    }
}

/** [JsonPrimitive.content] but null when the JSON value is literally null. */
fun JsonPrimitive.contentOrNullSafe(): String? =
    if (!isString && content == "null") null else content

/**
 * Parses an OpenAI-style `usage` object into [LlmUsage]. Accepts both the OpenAI Chat/Responses
 * field names (`prompt_tokens`/`completion_tokens`/`total_tokens` and the Responses
 * `input_tokens`/`output_tokens`) so one helper serves both adapters. Returns null if [usage] is null.
 */
fun parseUsage(usage: JsonObject?): LlmUsage? {
    if (usage == null) return null
    fun i(vararg keys: String): Int? = keys.firstNotNullOfOrNull { usage[it]?.jsonPrimitive?.intOrNull }
    return LlmUsage(
        promptTokens = i("prompt_tokens", "input_tokens"),
        completionTokens = i("completion_tokens", "output_tokens"),
        totalTokens = i("total_tokens"),
    )
}

/** Drains the rest of a channel as UTF-8 text (used for error bodies). */
suspend fun ByteReadChannel.readRemainingText(): String = buildString {
    while (true) {
        @Suppress("DEPRECATION")
        val line = readUTF8Line() ?: break
        append(line).append('\n')
    }
}
