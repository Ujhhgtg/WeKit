package dev.ujhhgtg.wekit.utils.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

val DefaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun JsonElement.getByPath(path: String): JsonElement? {
    return path.split(".").fold(this as JsonElement?) { current, key ->
        current?.jsonObject?.get(key)
    }
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun JsonElement.get(key: String): JsonElement? = jsonObject[key]

inline val JsonElement.asInt: Int
    get() = jsonPrimitive.int
inline val JsonElement.asLong: Long
    get() = jsonPrimitive.long
inline val JsonElement.asString: String
    get() = jsonPrimitive.content

inline val JsonElement.asIntOrNull: Int?
    get() = jsonPrimitive.intOrNull
inline val JsonElement.asLongOrNull: Long?
    get() = jsonPrimitive.longOrNull
inline val JsonElement.asStringOrNull: String?
    get() = jsonPrimitive.contentOrNull
