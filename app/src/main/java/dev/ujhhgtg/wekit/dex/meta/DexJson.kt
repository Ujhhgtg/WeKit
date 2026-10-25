package dev.ujhhgtg.wekit.dex.meta

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal fun JsonObject.requiredObject(key: String): JsonObject =
    objectOrNull(key) ?: error("DexSQL metadata is missing object '$key'")

internal fun JsonObject.objectOrNull(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.jsonObject

internal fun JsonObject.requiredString(key: String): String =
    stringOrNull(key) ?: error("DexSQL metadata is missing string '$key'")

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intOr(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default

internal fun JsonObject.booleanOr(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObject.arrayOrEmpty(key: String): JsonArray =
    (this[key] as? JsonArray) ?: JsonArray(emptyList())

internal fun JsonArray.strings(): List<String> = map { (it as JsonPrimitive).content }
