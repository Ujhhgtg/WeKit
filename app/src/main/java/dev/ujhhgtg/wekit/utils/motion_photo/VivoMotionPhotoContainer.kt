package dev.ujhhgtg.wekit.utils.motion_photo

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Writes caller-owned staging files. Publishing/rollback and media scanning belong to the caller.
 */
object VivoMotionPhotoContainer {
    data class ExportInfo(
        val associationId: String?,
        val coverTimestampMs: Long,
        val videoLength: Long,
        val photoInfo: MotionPhotoContainer.PhotoInfo? = null,
    )

    private data class Extension(val start: Long, val id: String, val json: JsonObject)

    private data class OwnedBox(val box: MotionPhotoContainer.Box, val extension: Extension?)

    private data class VideoRewritePlan(
        val sourceLength: Long,
        val owned: List<OwnedBox>,
        val scoring: MotionPhotoVideoMetadata.Info?,
        val finalBoxSize: Pair<Long, Int>?,
        val append: ByteArray,
    ) {
        val outputLength: Long
            get() = Math.addExact(sourceLength, append.size.toLong())
    }

    private const val LIVE = "com.android.camera.livephoto"
    private const val TIME = "com.vivo.gallery.livePhoto.newCoverTime"
    private const val FRAME = "com.android.camera.imageTime"
    private const val SOURCE = "com.vivo.gallery.livephoto.source"
    private const val VERSION = "version"
    private const val V2_ID = "motionphoto00010000000000000"
    private const val JSON_LIMIT = 10240
    private val PREFIX = "vivo".toByteArray(Charsets.US_ASCII)
    private val JSON_END = "cameralbum!".toByteArray(Charsets.US_ASCII)
    private val USER_TYPE = "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII)
    private val FOOTER_END =
        byteArrayOf(
            0x1b,
            0x2a,
            0x39,
            0x48,
            0x57,
            0x66,
            0x75,
            0x84.toByte(),
            0x93.toByte(),
            0xa2.toByte(),
            0xb3.toByte(),
        )

    fun writeV1(
        cover: File,
        video: File,
        photoOutput: File,
        videoOutput: File,
        coverTimeMs: Long,
    ): ExportInfo {
        checkOutputs(cover, video, photoOutput, videoOutput)
        checkTimestamp(coverTimeMs)
        val id = UUID.randomUUID().toString().replace("-", "").take(28)
        var primary: File? = null
        try {
            cover.inputStream().channel.use { image ->
                val imageEnd = MotionPhotoContainer.conversionImageEnd(image)
                val old = readExtension(image, image.size())
                // FIXME: Vivo's XDR/auxiliary prefix is preserved byte-for-byte only for
                //        a known private envelope with no embedded Motion Photo. Its relative
                //        offsets and original schema stay intact. Otherwise convert the primary
                // JPEG.
                val opaque =
                    old != null &&
                        old.start >= imageEnd &&
                        validSchema(old.json) &&
                        try {
                            MotionPhotoContainer.inspect(image) == null
                        } catch (_: IOException) {
                            false // The primary JPEG is valid; normalize unusable optional metadata
                            // instead.
                        }
                val preserved = if (opaque) old.json else null
                var photoJson = canonical(preserved, id, coverTimeMs, photo = true)
                val retainPrefix = opaque && encodedSize(photoJson) < JSON_LIMIT
                if (!retainPrefix) {
                    photoJson = canonical(null, id, coverTimeMs, photo = true)
                    val normalized = temporary(photoOutput, ".jpg")
                    primary = normalized
                    MotionPhotoContainer.normalizeCover(cover, normalized)
                }
                photoOutput.outputStream().channel.use { output ->
                    if (retainPrefix) {
                        copy(image, 0, old.start, output)
                    } else {
                        primary!!.inputStream().channel.use {
                            copy(it, 0, it.size(), output)
                        }
                    }
                    put(output, extensionBytes(photoJson, id))
                    output.force(true)
                }
            }
            prepareVideo(video, videoOutput, id, coverTimeMs, preserveOpaque = true)
            return inspectPair(photoOutput, videoOutput)
        } finally {
            primary?.delete()
        }
    }

    fun writeV2(cover: File, video: File, photoOutput: File, coverTimeMs: Long): ExportInfo =
        writeSingle(cover, video, photoOutput, coverTimeMs, vivo = true)

    fun writeStandard(cover: File, video: File, photoOutput: File, coverTimeMs: Long): ExportInfo =
        writeSingle(cover, video, photoOutput, coverTimeMs, vivo = false)

    private fun writeSingle(
        cover: File,
        video: File,
        photoOutput: File,
        time: Long,
        vivo: Boolean,
    ): ExportInfo {
        checkOutputs(cover, video, photoOutput)
        checkTimestamp(time)
        val plan =
            video.inputStream().channel.use { source ->
                planVideo(source, if (vivo) V2_ID else null, time, preserveOpaque = false)
            }
        // FIXME: Vivo V2 uses the SDK's fixed association/profile, inside the MP4
        //        UUID plus VCamera XMP. Pure standard output deliberately omits that profile.
        val info =
            MotionPhotoContainer.writeConverted(
                cover,
                video,
                photoOutput,
                time,
                plan.outputLength,
                vivoMetadata = vivo,
            ) { source, target, videoStart ->
                writeVideo(source, target, videoStart, plan, time)
            }
        return inspectSingle(photoOutput, info)
    }

    /**
     * Validates explicitly supplied exported files, never searches MediaStore or guesses a pair.
     */
    fun inspectPair(photo: File, video: File): ExportInfo {
        val image =
            photo.inputStream().channel.use {
                if (MotionPhotoContainer.inspect(it) != null) {
                    fail("Vivo V1 image unexpectedly contains a Motion Photo")
                }
                readExtension(it, it.size()) ?: fail("Missing Vivo image envelope")
            }
        return video.inputStream().channel.use {
            val analysis = MotionPhotoContainer.analyzeVideo(it, 0, it.size())
            val owned =
                ownedBoxes(it, analysis.boxes).singleOrNull()
                    ?: fail("Vivo V1 needs exactly one video envelope")
            val movie = owned.extension ?: fail("Invalid Vivo video envelope")
            if (movie.id != image.id) fail("Vivo association identifiers disagree")
            val time = time(movie.json)
            analysis.metadata?.let { metadata ->
                if (metadata.frames.any { frame -> frame.timestampUs != timestampUs(time) }) {
                    fail("Video scoring metadata disagrees with Vivo timestamp")
                }
            }
            ExportInfo(image.id, time, it.size())
        }
    }

    fun inspectSingle(photo: File): ExportInfo =
        photo.inputStream().channel.use { channel ->
            val info =
                MotionPhotoContainer.inspect(channel)
                    ?: fail("Missing standard Motion Photo container")
            inspectSingle(channel, info)
        }

    /** Reuses the same completed-file validation returned by the single-file writer. */
    fun inspectSingle(photo: File, info: MotionPhotoContainer.PhotoInfo): ExportInfo =
        photo.inputStream().channel.use { inspectSingle(it, info) }

    private fun inspectSingle(
        channel: FileChannel,
        info: MotionPhotoContainer.PhotoInfo,
    ): ExportInfo {
        val owned =
            ownedBoxes(
                channel,
                MotionPhotoContainer.boxes(
                    channel,
                    info.videoOffset,
                    info.videoOffset + info.videoLength,
                ),
            )
        return if (owned.isEmpty()) {
            ExportInfo(null, info.coverTimestampMs, info.videoLength, info)
        } else {
            val movie = owned.singleOrNull()?.extension ?: fail("Invalid Vivo V2 envelope")
            if (movie.id != V2_ID || time(movie.json) != info.coverTimestampMs) {
                fail("Vivo V2 metadata disagrees with XMP")
            }
            ExportInfo(movie.id, info.coverTimestampMs, info.videoLength, info)
        }
    }

    private fun prepareVideo(
        source: File,
        output: File,
        id: String?,
        timestamp: Long,
        preserveOpaque: Boolean,
    ) {
        source.inputStream().channel.use { input ->
            val plan = planVideo(input, id, timestamp, preserveOpaque)
            output.outputStream().channel.use { target ->
                writeVideo(input, target, 0, plan, timestamp)
                target.force(true)
            }
        }
    }

    private fun planVideo(
        source: FileChannel,
        id: String?,
        timestamp: Long,
        preserveOpaque: Boolean,
    ): VideoRewritePlan {
        val length = source.size()
        val analysis = MotionPhotoContainer.analyzeVideo(source, 0, length)
        val boxes = analysis.boxes
        val owned = ownedBoxes(source, boxes)
        val scoring = analysis.metadata
        var metadata =
            if (id == null) {
                null
            } else {
                canonical(
                    if (preserveOpaque) {
                        owned.lastOrNull()?.extension?.json?.takeIf(::validSchema)
                    } else {
                        null
                    },
                    id,
                    timestamp,
                    photo = false,
                )
            }
        if (metadata != null && encodedSize(metadata) >= JSON_LIMIT) {
            metadata = canonical(null, id!!, timestamp, photo = false)
        }
        val append =
            if (metadata == null) {
                ByteArray(0)
            } else {
                val payload = USER_TYPE + extensionBytes(metadata, id!!)
                ByteBuffer.allocate(8)
                    .putInt(payload.size + 8)
                    .put("uuid".toByteArray(Charsets.US_ASCII))
                    .array() + payload
            }
        val finalBoxSize =
            if (append.isNotEmpty()) {
                // A final size=0 box extends through EOF. Materialize its existing size
                // before appending a new top-level box, retaining all original offsets.
                boxes
                    .last()
                    .takeIf { ByteBuffer.wrap(read(source, it.start, 4)).int == 0 }
                    ?.let {
                        val size = it.end - it.start
                        if (size > 0xffffffffL) {
                            fail("Final MP4 box cannot be represented without moving media")
                        }
                        it.start to size.toInt()
                    }
            } else {
                null
            }
        return VideoRewritePlan(length, owned, scoring, finalBoxSize, append)
    }

    private fun writeVideo(
        source: FileChannel,
        target: FileChannel,
        videoStart: Long,
        plan: VideoRewritePlan,
        timestamp: Long,
    ) {
        target.position(videoStart)
        copy(source, 0, plan.sourceLength, target)
        // FIXME: Replace only structurally located Vivo UUID metadata with equally
        //        sized zero-filled free boxes. This clears stale IDs without moving media
        //        or stco/co64 offsets; no arbitrary search of mdat bytes is performed.
        for ((box) in plan.owned) {
            putAt(
                target,
                Math.addExact(videoStart, box.start + 4),
                "free".toByteArray(Charsets.US_ASCII),
            )
            zero(target, Math.addExact(videoStart, box.payload), box.end - box.payload)
        }
        plan.finalBoxSize?.let { (offset, size) ->
            putAt(
                target,
                Math.addExact(videoStart, offset),
                ByteBuffer.allocate(4).putInt(size).array(),
            )
        }
        target.position(Math.addExact(videoStart, plan.sourceLength))
        put(target, plan.append)
        plan.scoring?.let {
            MotionPhotoVideoMetadata.synchronize(target, videoStart, it, timestampUs(timestamp))
        }
    }

    private fun ownedBoxes(
        channel: FileChannel,
        boxes: List<MotionPhotoContainer.Box>,
    ): List<OwnedBox> =
        boxes
            .filter {
                it.type == "uuid" &&
                    it.end - it.payload >= USER_TYPE.size &&
                    read(channel, it.payload, USER_TYPE.size).contentEquals(USER_TYPE)
            }
            .map { box ->
                val extension =
                    readExtension(channel, box.end)?.takeIf {
                        it.start == box.payload + USER_TYPE.size
                    }
                OwnedBox(box, extension)
            }

    private fun canonical(
        old: JsonObject?,
        id: String,
        timestamp: Long,
        photo: Boolean,
    ): JsonObject {
        val values = old?.toMutableMap() ?: linkedMapOf()
        // FIXME: Preserve an existing Vivo schema only alongside unchanged opaque data;
        //        newly generated profiles use the SDK's 2016 schema, not a fabricated device
        // version.
        val version = old?.get(VERSION) as? JsonPrimitive
        if (version == null || version.isString || version.intOrNull?.let { it > 0 } != true) {
            values[VERSION] = JsonPrimitive(2016)
        }
        values[LIVE] = JsonPrimitive(id)
        values.remove(FRAME)
        values.remove(TIME)
        values.remove(SOURCE)
        if (photo) values[SOURCE] = JsonPrimitive(4) else values[TIME] = JsonPrimitive(timestamp)
        return JsonObject(values)
    }

    private fun readExtension(channel: FileChannel, end: Long): Extension? {
        if (end < 66) return null
        val footer = read(channel, end - 47, 47)
        if (
            ByteBuffer.wrap(footer).int != 47 ||
                !footer.copyOfRange(32, 36).contentEquals(byteArrayOf(-1, -1, -1, -1)) ||
                !footer.copyOfRange(36, 47).contentEquals(FOOTER_END)
        ) {
            return null
        }
        val idBytes = footer.copyOfRange(4, 32)
        if (idBytes.any { it.toInt() and 0xff !in 33..126 }) return null
        val id = idBytes.toString(Charsets.US_ASCII)
        val trailer = read(channel, end - 62, 15)
        if (!trailer.copyOfRange(4, 15).contentEquals(JSON_END)) return null
        val length = ByteBuffer.wrap(trailer).int.toLong() and 0xffffffffL
        if (length > JSON_LIMIT || length == 0L || length > end - 66) return null
        val start = end - 66 - length
        if (!read(channel, start, 4).contentEquals(PREFIX)) return null
        val bytes = read(channel, start + 4, length.toInt())
        val json =
            try {
                val text =
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                Json.parseToJsonElement(text) as? JsonObject ?: return null
            } catch (_: Exception) {
                return null // Unsupported private metadata is optional; valid image/media stays
                // usable.
            }
        val association = json[LIVE] as? JsonPrimitive
        if (association == null || !association.isString || association.contentOrNull != id) {
            return null
        }
        return Extension(start, id, json)
    }

    private fun extensionBytes(json: JsonObject, id: String): ByteArray {
        // FIXME: Vivo private framing is vivo+UTF8 JSON+BE size+cameralbum! followed by
        //        the exact 47-byte repeated association footer, not a JPEG or MP4 magic-byte
        // heuristic.
        val data = json.toString().toByteArray(Charsets.UTF_8)
        if (data.size + 19 >= JSON_LIMIT) fail("Vivo JSON exceeds SDK envelope size limit")
        val idBytes = id.toByteArray(Charsets.US_ASCII)
        if (idBytes.size != 28) fail("Vivo association identifier must have 28 ASCII bytes")
        val footer =
            ByteBuffer.allocate(47).putInt(47).put(idBytes).putInt(-1).put(FOOTER_END).array()
        return PREFIX + data + ByteBuffer.allocate(4).putInt(data.size).array() + JSON_END + footer
    }

    private fun encodedSize(json: JsonObject) =
        json.toString().toByteArray(Charsets.UTF_8).size + 19

    private fun validSchema(json: JsonObject): Boolean {
        val version = json[VERSION] as? JsonPrimitive
        return version != null && !version.isString && version.intOrNull?.let { it > 0 } == true
    }

    private fun time(json: JsonObject): Long {
        val value = json[TIME] as? JsonPrimitive
        if (value == null || value.isString) fail("Missing numeric Vivo millisecond timestamp")
        return value.longOrNull?.takeIf { it >= 0 } ?: fail("Invalid Vivo millisecond timestamp")
    }

    private fun checkTimestamp(value: Long) {
        if (value < 0) fail("Vivo export needs a resolved nonnegative timestamp")
        timestampUs(value)
    }

    private fun timestampUs(value: Long): Long =
        try {
            Math.multiplyExact(value, 1000)
        } catch (error: ArithmeticException) {
            throw IOException("Timestamp overflow", error)
        }

    private fun checkOutputs(cover: File, video: File, vararg outputs: File) {
        val inputs = setOf(cover.canonicalFile, video.canonicalFile)
        val targets = outputs.map { it.canonicalFile }
        if (targets.any { it in inputs } || targets.toSet().size != targets.size) {
            fail("Staging files must be distinct from inputs and each other")
        }
    }

    private fun temporary(output: File, suffix: String) =
        File.createTempFile("wekit-vivo-", suffix, output.absoluteFile.parentFile)

    private fun copy(source: FileChannel, offset: Long, count: Long, target: FileChannel) {
        val buffer = ByteBuffer.allocate(64 * 1024)
        var position = 0L
        while (position < count) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), count - position).toInt())
            val size = source.read(buffer, offset + position)
            if (size <= 0) fail("Truncated Vivo input")
            buffer.flip()
            while (buffer.hasRemaining()) target.write(buffer)
            position += size
        }
    }

    private fun zero(target: FileChannel, offset: Long, count: Long) {
        val bytes = ByteArray(64 * 1024)
        var position = 0L
        while (position < count) {
            val size = minOf(bytes.size.toLong(), count - position).toInt()
            putAt(target, offset + position, if (size == bytes.size) bytes else bytes.copyOf(size))
            position += size
        }
    }

    private fun read(channel: FileChannel, offset: Long, size: Int): ByteArray {
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) <= 0) {
                fail("Truncated Vivo metadata")
            }
        }
        return buffer.array()
    }

    private fun put(target: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) target.write(buffer)
    }

    private fun putAt(target: FileChannel, offset: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (target.write(buffer, offset + buffer.position()) <= 0) {
                fail("Could not write Vivo metadata")
            }
        }
    }

    private fun fail(message: String): Nothing = throw IOException(message)
}
