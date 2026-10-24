package dev.ujhhgtg.wekit.utils.livephoto

import androidx.annotation.Keep
import java.nio.channels.FileChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long

/** Each call borrows the open channel's descriptor; only bounded metadata crosses JNI. */
@Keep
object MotionPhotoNative {
    external fun hasMetadata(channel: FileChannel, size: Long): Boolean

    external fun inspectPhoto(channel: FileChannel, size: Long): ByteArray

    external fun parseJpeg(
        channel: FileChannel,
        size: Long,
        headers: Boolean,
        discard: Boolean,
    ): ByteArray

    external fun inspectMetadata(
        channel: FileChannel,
        size: Long,
        start: Long,
        length: Long,
    ): ByteArray

    external fun analyzeVideo(
        channel: FileChannel,
        size: Long,
        start: Long,
        length: Long,
    ): ByteArray

    external fun readBoxes(channel: FileChannel, size: Long, start: Long, end: Long): ByteArray

    fun decode(bytes: ByteArray): JsonElement =
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))

    fun photoInfo(value: JsonElement): MotionPhotoContainer.PhotoInfo? {
        if (value == JsonNull) return null
        val data = value as JsonObject
        return MotionPhotoContainer.PhotoInfo(
            data.long("video_offset"),
            data.long("video_length"),
            data.long("cover_timestamp_ms"),
            data.long("image_end"),
            data.long("cover_timestamp_us"),
        )
    }

    fun metadata(value: JsonElement): MotionPhotoVideoMetadata.Info? {
        if (value == JsonNull) return null
        val frames = (value as JsonObject)["frames"] as JsonArray
        return MotionPhotoVideoMetadata.Info(
            frames.map {
                val frame = it as JsonObject
                MotionPhotoVideoMetadata.FrameTimestamp(
                    frame.long("timestamp_us"),
                    frame.long("offset"),
                )
            },
        )
    }

    fun JsonObject.long(name: String) = (getValue(name) as JsonPrimitive).long

    fun JsonObject.flag(name: String) = (getValue(name) as JsonPrimitive).boolean
}
