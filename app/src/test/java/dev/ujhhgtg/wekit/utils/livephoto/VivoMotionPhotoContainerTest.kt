package dev.ujhhgtg.wekit.utils.livephoto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class VivoMotionPhotoContainerTest {
    init { MotionPhotoNativeTestRuntime.load() }

    @TempDir lateinit var directory: File
    private val jpeg by lazy { resource("cover.jpg") }
    private val mp4 by lazy { resource("video.mp4") }
    private val oldId = "0123456789abcdef0123456789ab"

    @Test
    fun `V1 produces a fresh linked pair with millisecond time and unchanged encoded video and audio`() {
        val cover = file("source.jpg", jpeg)
        val video = file("source.mp4", mp4)
        val photoOut = File(directory, "pair.jpg")
        val videoOut = File(directory, "pair.mp4")
        val info = VivoMotionPhotoContainer.writeV1(cover, video, photoOut, videoOut, 375)
        assertEquals(28, info.associationId!!.length)
        assertEquals(info, VivoMotionPhotoContainer.inspectPair(photoOut, videoOut))
        val photo = extension(photoOut.readBytes())
        val movie = extension(videoOut.readBytes())
        assertEquals(info.associationId, photo.second)
        assertEquals(photo.second, movie.second)
        assertEquals(2016, photo.third.getValue("version").jsonPrimitive.long)
        assertEquals(4, photo.third.getValue("com.vivo.gallery.livephoto.source").jsonPrimitive.long)
        assertEquals(375, movie.third.getValue("com.vivo.gallery.livePhoto.newCoverTime").jsonPrimitive.long)
        assertFalse(movie.third.containsKey("com.android.camera.imageTime"))
        assertFalse(movie.third.containsKey("com.vivo.gallery.livephoto.source"))
        assertArrayEquals(mp4, videoOut.readBytes().copyOfRange(0, mp4.size))
        assertArrayEquals(jpeg, cover.readBytes())
        assertArrayEquals(mp4, video.readBytes())
    }

    @Test
    fun `V1 retains exact known auxiliary prefix and opaque schemas while updating association and stale frame time`() {
        val aux = "streamdata".toByteArray() + jpeg + "streamcount".toByteArray() + byteArrayOf(0, 1)
        val prefix = jpeg + aux
        val imageFields = JsonObject(mapOf(
            "version" to JsonPrimitive(2202), "com.android.camera.livephoto" to JsonPrimitive(oldId),
            "com.vivo.xdr.maxGain" to JsonPrimitive(0.84142387), "com.android.camera.hdr" to JsonPrimitive(true),
        ))
        val videoFields = JsonObject(mapOf(
            "version" to JsonPrimitive(2107), "com.android.camera.livephoto" to JsonPrimitive(oldId),
            "com.android.camera.imageTime" to JsonPrimitive(41), "opaqueVideo" to JsonPrimitive("keep"),
        ))
        val cover = file("vivo-source.jpg", prefix + envelope(imageFields, oldId))
        val sourceVideoBytes = mp4 + uuid(videoFields, oldId)
        val video = file("vivo-source.mp4", sourceVideoBytes)
        val photoOut = File(directory, "first.jpg")
        val videoOut = File(directory, "first.mp4")
        val first = VivoMotionPhotoContainer.writeV1(cover, video, photoOut, videoOut, 375)
        assertNotEquals(oldId, first.associationId)
        val photo = extension(photoOut.readBytes())
        assertArrayEquals(prefix, photoOut.readBytes().copyOfRange(0, photo.first))
        assertEquals(imageFields.getValue("com.vivo.xdr.maxGain"), photo.third.getValue("com.vivo.xdr.maxGain"))
        assertEquals(2202, photo.third.getValue("version").jsonPrimitive.long)
        val movie = extension(videoOut.readBytes()).third
        assertEquals(2107, movie.getValue("version").jsonPrimitive.long)
        assertEquals(JsonPrimitive("keep"), movie.getValue("opaqueVideo"))
        assertFalse(movie.containsKey("com.android.camera.imageTime"))
        assertArrayEquals(mp4, videoOut.readBytes().copyOfRange(0, mp4.size))
        assertEquals(listOf("free", "uuid"), atoms(videoOut.readBytes()).drop(atoms(mp4).size).map { it.first })
        assertTrue(atoms(videoOut.readBytes()).drop(atoms(mp4).size).first().second.copyOfRange(8, uuid(videoFields, oldId).size).all { it == 0.toByte() })

        val repeatedPhoto = File(directory, "second.jpg")
        val repeatedVideo = File(directory, "second.mp4")
        val second = VivoMotionPhotoContainer.writeV1(photoOut, videoOut, repeatedPhoto, repeatedVideo, 875)
        assertNotEquals(first.associationId, second.associationId)
        assertEquals(875, second.coverTimestampMs)
        assertArrayEquals(prefix, repeatedPhoto.readBytes().copyOfRange(0, extension(repeatedPhoto.readBytes()).first))
        assertEquals(1, atoms(repeatedVideo.readBytes()).count { it.first == "uuid" })
        assertArrayEquals(sourceVideoBytes, video.readBytes())
        assertArrayEquals(prefix + envelope(imageFields, oldId), cover.readBytes())
    }

    @Test
    fun `V2 includes SDK VCamera profile and one framed video envelope without adding an image footer`() {
        val out = File(directory, "v2.jpg")
        val info = VivoMotionPhotoContainer.writeV2(file("cover.jpg", jpeg), file("video.mp4", mp4), out, 375)
        assertEquals("motionphoto00010000000000000", info.associationId)
        assertEquals(info, VivoMotionPhotoContainer.inspectSingle(out))
        val extracted = extract(out, "v2.mp4")
        assertEquals(info.videoLength, extracted.size.toLong())
        assertArrayEquals(mp4, extracted.copyOfRange(0, mp4.size))
        assertEquals(info.associationId, extension(extracted).second)
        assertArrayEquals(extracted.copyOfRange(extracted.size - 47, extracted.size), out.readBytes().takeLast(47).toByteArray())
        val xmp = xmp(out.readBytes())
        assertTrue(xmp.contains("http://ns.vivo.com/photos/1.0/camera/"))
        assertTrue(xmp.contains("VMotionPhotoVersion=\"1\""))
        assertTrue(xmp.contains("VMotionPhotoSource=\"4\""))
        assertTrue(xmp.contains("VMediaKitVersion=\"1.0.0.4\""))
    }

    @Test
    fun `standard conversion drops unsupported image auxiliary declarations and masks non-final Vivo UUID without moving samples`() {
        val imageFields = JsonObject(mapOf("version" to JsonPrimitive(2202), "com.android.camera.livephoto" to JsonPrimitive(oldId), "com.vivo.xdr.maxGain" to JsonPrimitive(1.25)))
        val movieFields = JsonObject(mapOf("version" to JsonPrimitive(2107), "com.android.camera.livephoto" to JsonPrimitive(oldId)))
        val extra = atom("free", "unrelated trailing box".toByteArray())
        val originalVideo = mp4 + uuid(movieFields, oldId) + extra
        val cover = file("xdr.jpg", jpeg + "uninterpreted auxiliary image".toByteArray() + envelope(imageFields, oldId))
        val video = file("tagged.mp4", originalVideo)
        val out = File(directory, "standard.jpg")
        val info = VivoMotionPhotoContainer.writeStandard(cover, video, out, 875)
        assertEquals(null, info.associationId)
        val extracted = extract(out, "standard.mp4")
        assertEquals(originalVideo.size, extracted.size)
        assertArrayEquals(mp4, extracted.copyOfRange(0, mp4.size))
        assertArrayEquals(extra, extracted.takeLast(extra.size).toByteArray())
        assertFalse(atoms(extracted).any { it.first == "uuid" })
        assertFalse(xmp(out.readBytes()).contains("VCamera"))
        assertFalse(out.readBytes().toString(Charsets.ISO_8859_1).contains("uninterpreted auxiliary image"))
        assertArrayEquals(originalVideo, video.readBytes())
    }

    @Test
    fun `unreadable private envelopes are discarded during conversion while invalid actual media still fails`() {
        val fields = JsonObject(mapOf("version" to JsonPrimitive(2202), "com.android.camera.livephoto" to JsonPrimitive(oldId)))
        val private = envelope(fields, oldId).apply { this[lastIndex] = 0 }
        val cover = file("bad-private.jpg", jpeg + "unsupported extra".toByteArray() + private)
        val video = file("video.mp4", mp4)
        val photoOut = File(directory, "recovered.jpg")
        val videoOut = File(directory, "recovered.mp4")
        assertEquals(375, VivoMotionPhotoContainer.writeV1(cover, video, photoOut, videoOut, 375).coverTimestampMs)
        val image = extension(photoOut.readBytes())
        assertEquals(2016, image.third.getValue("version").jsonPrimitive.long)
        assertFalse(photoOut.readBytes().toString(Charsets.ISO_8859_1).contains("unsupported extra"))
        assertThrows(IOException::class.java) {
            VivoMotionPhotoContainer.writeV2(file("truncated.jpg", jpeg.copyOf(jpeg.size - 2)), video, File(directory, "bad.jpg"), 375)
        }
        assertThrows(IOException::class.java) {
            VivoMotionPhotoContainer.writeV1(cover, file("bad.mp4", mp4.copyOf(mp4.size - 1)), File(directory, "bad-pair.jpg"), File(directory, "bad-pair.mp4"), 375)
        }
    }

    @Test
    fun `V1 rejects a truncated primary JPEG even when its private envelope is valid`() {
        val fields = JsonObject(mapOf("version" to JsonPrimitive(2202), "com.android.camera.livephoto" to JsonPrimitive(oldId)))
        val malformed = jpeg.copyOf(jpeg.size - 2) + envelope(fields, oldId)
        val cover = file("truncated-vivo.jpg", malformed)
        assertThrows(IOException::class.java) {
            VivoMotionPhotoContainer.writeV1(cover, file("valid-video.mp4", mp4), File(directory, "invalid-pair.jpg"), File(directory, "invalid-pair.mp4"), 375)
        }
        assertArrayEquals(malformed, cover.readBytes())
    }

    @Test
    fun `appending metadata closes a final zero-sized box without changing movie sample offsets`() {
        val zero = ByteBuffer.allocate(8).putInt(0).put("free".toByteArray()).array() + ByteArray(37)
        val source = mp4 + zero
        val outVideo = File(directory, "zero-out.mp4")
        VivoMotionPhotoContainer.writeV1(file("cover.jpg", jpeg), file("zero.mp4", source), File(directory, "zero-out.jpg"), outVideo, 375)
        val output = outVideo.readBytes()
        assertArrayEquals(mp4, output.copyOfRange(0, mp4.size))
        assertEquals(zero.size, ByteBuffer.wrap(output, mp4.size, 4).int)
        assertEquals("uuid", atoms(output).last().first)
    }

    @Test
    fun `all export formats synchronize timed scoring metadata while preserving encoded video and audio samples`() {
        val scoredBytes = MotionPhotoVideoMetadataTest().scoredVideoFixture(125000)
        val scored = file("scored.mp4", scoredBytes)
        val sourceFrames = scored.inputStream().channel.use { MotionPhotoVideoMetadata.inspect(it, 0, it.size())!! }
        val expected = scoredBytes.copyOf()
        sourceFrames.frames.forEach { ByteBuffer.wrap(expected).putLong(it.offset.toInt(), 875000) }
        val cover = file("cover.jpg", jpeg)
        val v1Photo = File(directory, "scored-v1.jpg")
        val v1Video = File(directory, "scored-v1.mp4")
        VivoMotionPhotoContainer.writeV1(cover, scored, v1Photo, v1Video, 875)
        assertArrayEquals(expected, v1Video.readBytes().copyOfRange(0, expected.size))
        val v2 = File(directory, "scored-v2.jpg")
        VivoMotionPhotoContainer.writeV2(cover, scored, v2, 875)
        assertArrayEquals(expected, extract(v2, "scored-v2.mp4").copyOfRange(0, expected.size))
        val standard = File(directory, "scored-standard.jpg")
        VivoMotionPhotoContainer.writeStandard(cover, scored, standard, 875)
        assertArrayEquals(expected, extract(standard, "scored-standard.mp4"))
        assertArrayEquals(scoredBytes, scored.readBytes())
    }

    private fun file(name: String, bytes: ByteArray) = File(directory, name).apply { writeBytes(bytes) }
    private fun resource(name: String) = javaClass.getResourceAsStream("/livephoto/$name")!!.use { it.readBytes() }
    private fun atom(type: String, bytes: ByteArray) = ByteBuffer.allocate(8).putInt(bytes.size + 8).put(type.toByteArray()).array() + bytes
    private fun uuid(json: JsonObject, id: String) = atom("uuid", "vivoMediaExtInfo".toByteArray() + envelope(json, id))
    private fun envelope(json: JsonObject, id: String): ByteArray {
        val data = json.toString().toByteArray()
        val footer = ByteBuffer.allocate(47).putInt(47).put(id.toByteArray()).putInt(-1)
            .put(byteArrayOf(0x1b, 0x2a, 0x39, 0x48, 0x57, 0x66, 0x75, 0x84.toByte(), 0x93.toByte(), 0xa2.toByte(), 0xb3.toByte())).array()
        return "vivo".toByteArray() + data + ByteBuffer.allocate(4).putInt(data.size).array() + "cameralbum!".toByteArray() + footer
    }
    private fun extension(bytes: ByteArray): Triple<Int, String, JsonObject> {
        assertEquals(47, ByteBuffer.wrap(bytes, bytes.size - 47, 4).int)
        val id = bytes.copyOfRange(bytes.size - 43, bytes.size - 15).toString(Charsets.US_ASCII)
        val size = ByteBuffer.wrap(bytes, bytes.size - 62, 4).int
        val start = bytes.size - 66 - size
        assertEquals("vivo", bytes.copyOfRange(start, start + 4).toString(Charsets.US_ASCII))
        return Triple(start, id, Json.parseToJsonElement(bytes.copyOfRange(start + 4, start + 4 + size).toString(Charsets.UTF_8)) as JsonObject)
    }
    private fun atoms(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        var offset = 0
        while (offset < bytes.size) {
            val size = ByteBuffer.wrap(bytes, offset, 4).int
            add(bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII) to bytes.copyOfRange(offset, offset + size))
            offset += size
        }
    }
    private fun extract(photo: File, name: String): ByteArray {
        val target = File(directory, name)
        photo.inputStream().channel.use { MotionPhotoContainer.extractVideo(it, MotionPhotoContainer.inspect(it)!!, target) }
        return target.readBytes()
    }
    private fun xmp(bytes: ByteArray): String {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
        var offset = 2
        while ((bytes[offset + 1].toInt() and 255) != 0xda) {
            val length = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            val payload = bytes.copyOfRange(offset + 4, offset + length + 2)
            if ((bytes[offset + 1].toInt() and 255) == 0xe1 && payload.size >= header.size && payload.copyOfRange(0, header.size).contentEquals(header)) {
                return payload.copyOfRange(header.size, payload.size).toString(Charsets.UTF_8)
            }
            offset += length + 2
        }
        error("No standard XMP packet")
    }
}
