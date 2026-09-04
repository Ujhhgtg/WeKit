package dev.ujhhgtg.wekit.agent.model

import dev.ujhhgtg.wekit.agent.model.GeminiCommon.STRIP_SCHEMA_KEYS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared utilities for the two Gemini API adapters ([GeminiGenerateContentClient] and
 * [GeminiInteractionsClient]).
 */
object GeminiCommon {

    /**
     * Keys that Gemini rejects in JSON Schema parameter objects. The Gemini function-declaration
     * validator is stricter than standard JSON Schema: it rejects `additionalProperties`,
     * `$schema`, and `title` at any nesting level. Stripping them avoids HTTP 400 errors when
     * any existing tool schema carries these innocuous-but-incompatible keys.
     */
    private val STRIP_SCHEMA_KEYS = setOf("additionalProperties", "\$schema", "title")

    /**
     * Recursively walks [schema] and removes keys in [STRIP_SCHEMA_KEYS] at every nesting level.
     * Non-object elements are returned unchanged.
     */
    fun sanitizeSchema(schema: JsonElement): JsonElement = when (schema) {
        is JsonObject -> buildJsonObject {
            schema.forEach { (k, v) ->
                if (k !in STRIP_SCHEMA_KEYS) put(k, sanitizeSchema(v))
            }
        }

        is JsonArray -> JsonArray(schema.map { sanitizeSchema(it) })
        else -> schema
    }

    /**
     * Maps the standard reasoning-effort gear string to a Gemini `thinkingLevel` value
     * (used by both the Interactions API `generation_config.thinking_level` and the
     * `generateContent` API `generationConfig.thinkingConfig.thinkingLevel`).
     *
     * Returns null to omit the field when effort is blank/null/"off"/"none".
     * Numeric values (e.g. "8192") are not meaningful for Gemini's level-based API; they are
     * treated as "high" so custom-JSON overrides can still set `thinkingBudget` directly.
     */
    fun effortToThinkingLevel(effort: String?): String? = when (effort?.lowercase()) {
        null, "", "off", "none" -> null
        "minimal" -> "minimal"
        "low" -> "low"
        "medium" -> "medium"
        "high", "xhigh", "max" -> "high"
        else -> if (effort.toIntOrNull() != null) "high" else null
    }

    /**
     * Builds a `generationConfig` / `generation_config` object for both Gemini API shapes.
     * Returns an empty object when [thinkingLevel] is null and [maxOutputTokens] is null.
     *
     * For generateContent the keys are camelCase (`maxOutputTokens`, `thinkingConfig`).
     * For Interactions the keys are snake_case (`max_output_tokens`, `thinking_level`).
     */
    fun buildGenerationConfig(
        thinkingLevel: String?,
        maxOutputTokens: Int?,
        camelCase: Boolean,
    ): JsonObject = buildJsonObject {
        if (maxOutputTokens != null) {
            put(if (camelCase) "maxOutputTokens" else "max_output_tokens", maxOutputTokens)
        }
        if (thinkingLevel != null) {
            if (camelCase) {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", thinkingLevel)
                    put("includeThoughts", true)
                })
            } else {
                put("thinking_level", thinkingLevel)
                // Request summaries so streaming delivers thought deltas we can map to ReasoningDelta.
                put("thinking_summaries", "auto")
            }
        }
    }

    /**
     * Auth header name shared by both Gemini endpoints.
     * Gemini authenticates via `x-goog-api-key` rather than an `Authorization: Bearer` token.
     */
    const val API_KEY_HEADER = "x-goog-api-key"
}
