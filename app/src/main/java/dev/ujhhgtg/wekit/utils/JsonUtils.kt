package dev.ujhhgtg.wekit.utils

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
}

fun JsonElement.getByPath(path: String): JsonElement? {
    return path.split(".").fold(this as JsonElement?) { current, key ->
        current?.jsonObject?.get(key)
    }
}

val JsonElement.asInt: Int get() = jsonPrimitive.int
val JsonElement.asLong: Long get() = jsonPrimitive.long
val JsonElement.asString: String get() = jsonPrimitive.content

val JsonElement.asIntOrNull: Int? get() = jsonPrimitive.intOrNull
val JsonElement.asLongOrNull: Long? get() = jsonPrimitive.longOrNull
val JsonElement.asStringOrNull: String? get() = jsonPrimitive.contentOrNull
