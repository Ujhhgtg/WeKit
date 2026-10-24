package dev.ujhhgtg.wekit.utils.livephoto

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MotionPhotoVideoMetadataTest {
    init {
        MotionPhotoNativeTestRuntime.load()
    }

    @TempDir lateinit var directory: File
    private val video by lazy { resource("video.mp4") }
    private val cover by lazy { resource("cover.jpg") }

    @Test
    fun `classic stco co64 fixed variable and compact sizes preserve all media while synchronizing metadata`() {
        for ((wide, compact, separateChunks) in
            listOf(
                Triple(false, 0, false),
                Triple(true, 0, true),
                Triple(false, 8, false),
                Triple(true, 16, true),
            )) {
            val bytes =
                classic(
                    listOf(descriptor(1234567, 8, 4), descriptor(1234567, 1)),
                    wide,
                    compact,
                    separateChunks,
                )
            val source = file("source-$wide-$compact-$separateChunks.mp4", bytes)
            val before = inspect(source)!!
            assertEquals(listOf(1234567L, 1234567L), before.frames.map { it.timestampUs })
            val photo = File(directory, "output-$wide-$compact-$separateChunks.jpg")
            MotionPhotoContainer.write(file("cover.jpg", cover), source, photo, 875)
            photo.inputStream().channel.use { channel ->
                val info = MotionPhotoContainer.inspect(channel)!!
                assertEquals(875000, info.coverTimestampUs)
                val extracted = File(directory, "extracted.mp4")
                MotionPhotoContainer.extractVideo(channel, info, extracted)
                assertEquals(bytes.size.toLong(), extracted.length())
                assertEquals(
                    listOf(875000L, 875000L),
                    inspect(extracted)!!.frames.map { it.timestampUs },
                )
                val expected = bytes.copyOf()
                before.frames.forEach {
                    ByteBuffer.wrap(expected).putLong(it.offset.toInt(), 875000)
                }
                assertArrayEquals(
                    expected,
                    extracted.readBytes(),
                    "Only fixed-width primary timestamps may change",
                )
                assertArrayEquals(bytes, source.readBytes(), "Original MP4 must remain untouched")
            }
        }
        val fixed = classic(listOf(descriptor(1234567), descriptor(1234567)), fixedSize = true)
        assertEquals(2, inspect(file("fixed-sample-size.mp4", fixed))!!.frames.size)
    }

    @Test
    fun `raw XMP microseconds must exactly match timed metadata and missing timestamp requires minus one`() {
        val timed = classic(listOf(descriptor(1234567)))
        val good = file("exact.jpg", xmpPhoto(timed, "1234567"))
        val info = good.inputStream().channel.use(MotionPhotoContainer::inspect)!!
        assertEquals(1234, info.coverTimestampMs)
        assertEquals(1234567, info.coverTimestampUs)
        assertThrows(IOException::class.java) {
            file("mismatch.jpg", xmpPhoto(timed, "1234000"))
                .inputStream()
                .channel
                .use(MotionPhotoContainer::inspect)
        }
        assertThrows(IOException::class.java) {
            file("missing.jpg", xmpPhoto(timed, null))
                .inputStream()
                .channel
                .use(MotionPhotoContainer::inspect)
        }
        val unspecified = file("unspecified.jpg", xmpPhoto(classic(listOf(descriptor(-1))), null))
        assertEquals(
            -1,
            unspecified.inputStream().channel.use(MotionPhotoContainer::inspect)!!.coverTimestampUs,
        )
    }

    @Test
    fun `all fragment base forms including inherited last-run bases and extended headers locate exact samples`() {
        for (mode in Fragment.entries) {
            val bytes = fragmented(mode, descriptor(250000))
            val source = file("fragment-$mode.mp4", bytes)
            val info = inspect(source)!!
            assertEquals(if (mode == Fragment.CHAINED_RUNS) 2 else 1, info.frames.size)
            assertTrue(info.frames.all { it.timestampUs == 250000L })
            val output = File(directory, "fragment-$mode.jpg")
            MotionPhotoContainer.write(file("cover.jpg", cover), source, output, 600)
            output.inputStream().channel.use { channel ->
                val written = MotionPhotoContainer.inspect(channel)!!
                val extracted = File(directory, "fragment-$mode-extracted.mp4")
                MotionPhotoContainer.extractVideo(channel, written, extracted)
                val expected = bytes.copyOf()
                info.frames.forEach { ByteBuffer.wrap(expected).putLong(it.offset.toInt(), 600000) }
                assertArrayEquals(expected, extracted.readBytes())
            }
        }
    }

    @Test
    fun `trun sizes override explicit zero tfhd but zero tfhd never falls back to trex`() {
        val payload = descriptor(0)
        assertEquals(
            0,
            inspect(
                    file(
                        "per-sample-size.mp4",
                        fragmented(
                            Fragment.DEFAULT_MOOF,
                            payload,
                            explicitZero = true,
                            perSampleSize = true,
                        ),
                    ),
                )!!
                .frames
                .single()
                .timestampUs,
        )
        assertThrows(IOException::class.java) {
            inspect(
                file(
                    "zero-default.mp4",
                    fragmented(Fragment.DEFAULT_MOOF, payload, explicitZero = true),
                ),
            )
        }
    }

    @Test
    fun `bad descriptor tags lengths scores and out-of-mdat sample locations are rejected`() {
        val badTag = descriptor(0).apply { this[0] = 0xc1.toByte() }
        val badLength = descriptor(0).apply { this[1] = 127 }
        val badScore = descriptor(0).apply { ByteBuffer.wrap(this).putFloat(10, Float.NaN) }
        listOf(badTag, badLength, badScore).forEachIndexed { index, payload ->
            assertThrows(IOException::class.java) {
                inspect(file("bad-$index.mp4", classic(listOf(payload))))
            }
        }
        val outside = classic(listOf(descriptor(0)))
        // The last stco is the deliberately added metadata track, not video/audio.
        val offset = occurrences(outside, "stco".toByteArray()).last() + 12
        ByteBuffer.wrap(outside).putInt(offset, 0)
        assertThrows(IOException::class.java) { inspect(file("bad-offset.mp4", outside)) }
        val external = classic(listOf(descriptor(0)))
        val url = occurrences(external, "url ".toByteArray()).last()
        ByteBuffer.wrap(external).putInt(url + 4, 0)
        assertThrows(IOException::class.java) { inspect(file("external-reference.mp4", external)) }
        val badMapping = classic(listOf(descriptor(0)))
        val stsc = occurrences(badMapping, "stsc".toByteArray()).last()
        ByteBuffer.wrap(badMapping).putInt(stsc + 20, 2)
        assertThrows(IOException::class.java) { inspect(file("bad-mapping.mp4", badMapping)) }
    }

    @Test
    fun `OPlus secondary padding zero is accepted but nonzero is rejected and export omits padding`() {
        val plain = xmpPhoto(video, "0")
        val zero =
            file(
                "zero-padding.jpg",
                replaceAscii(
                    plain,
                    "i:Semantic=\"MotionPhoto\"",
                    "i:Semantic=\"MotionPhoto\" i:Padding=\"0\"",
                ),
            )
        assertEquals(
            video.size.toLong(),
            zero.inputStream().channel.use(MotionPhotoContainer::inspect)!!.videoLength,
        )
        val nonzero =
            file(
                "nonzero-padding.jpg",
                replaceAscii(
                    plain,
                    "i:Semantic=\"MotionPhoto\"",
                    "i:Semantic=\"MotionPhoto\" i:Padding=\"1\"",
                ),
            )
        assertThrows(IOException::class.java) {
            nonzero.inputStream().channel.use(MotionPhotoContainer::inspect)
        }
        val output = File(directory, "standard.jpg")
        MotionPhotoContainer.write(zero, file("video.mp4", video), output, 0)
        assertTrue(!output.readBytes().toString(Charsets.ISO_8859_1).contains("Padding"))
    }

    private fun inspect(file: File) =
        file.inputStream().channel.use { MotionPhotoVideoMetadata.inspect(it, 0, it.size()) }

    private fun file(name: String, bytes: ByteArray) =
        File(directory, name).apply { writeBytes(bytes) }

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/livephoto/$name")!!.use { it.readBytes() }

    private data class Atom(val type: String, val start: Int, val bytes: ByteArray) {
        val body
            get() = bytes.copyOfRange(8, bytes.size)
    }

    private fun atoms(bytes: ByteArray): List<Atom> = buildList {
        var offset = 0
        while (offset < bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            add(
                Atom(
                    bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII),
                    offset,
                    bytes.copyOfRange(offset, offset + length),
                ),
            )
            offset += length
        }
    }

    private fun box(type: String, body: ByteArray, extended: Boolean = false): ByteArray =
        if (extended) {
            int(1) + type.toByteArray() + long(body.size + 16L) + body
        } else {
            int(body.size + 8) + type.toByteArray() + body
        }

    private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    private fun long(value: Long) = ByteBuffer.allocate(8).putLong(value).array()

    private fun shorts(value: Int) = ByteBuffer.allocate(2).putShort(value.toShort()).array()

    private fun concat(parts: List<ByteArray>) =
        parts.fold(ByteArray(0)) { result, bytes -> result + bytes }

    private val originalMovie
        get() = atoms(video).single { it.type == "moov" }

    private fun neutralVideo() =
        video.copyOf().apply { "free".toByteArray().copyInto(this, originalMovie.start + 4) }

    private fun movie(track: ByteArray, extra: ByteArray = ByteArray(0)) =
        box("moov", originalMovie.body + track + extra)

    private fun track(table: ByteArray): ByteArray {
        val source = atoms(originalMovie.body).filter { it.type == "trak" }.last()
        val tkhd =
            atoms(source.body)
                .single { it.type == "tkhd" }
                .bytes
                .copyOf()
                .apply { ByteBuffer.wrap(this).putInt(20, 3) }
        val mdia = atoms(source.body).single { it.type == "mdia" }
        val mdhd = atoms(mdia.body).single { it.type == "mdhd" }.bytes
        val hdlr =
            box(
                "hdlr",
                int(0) +
                    int(0) +
                    "meta".toByteArray() +
                    ByteArray(12) +
                    "Motion Photo\u0000".toByteArray(),
            )
        val dref = box("dref", int(0) + int(1) + box("url ", int(1)))
        val minf = box("minf", box("nmhd", int(0)) + box("dinf", dref) + box("stbl", table))
        return box("trak", tkhd + box("mdia", mdhd + hdlr + minf))
    }

    private fun table(
        samples: List<ByteArray>,
        offsets: List<Long>,
        wide: Boolean,
        compact: Int,
        separate: Boolean,
        fixedSize: Boolean = false,
    ): ByteArray {
        val mett =
            box(
                "mett",
                ByteArray(6) +
                    shorts(1) +
                    byteArrayOf(0) +
                    "application/motionphoto-image-meta\u0000".toByteArray(),
            )
        val stsd = box("stsd", int(0) + int(1) + mett)
        val stts =
            box(
                "stts",
                int(0) +
                    int(if (samples.isEmpty()) 0 else 1) +
                    if (samples.isEmpty()) {
                        ByteArray(0)
                    } else {
                        int(samples.size) + int(44100 / samples.size)
                    },
            )
        val sizes =
            if (compact == 0) {
                box(
                    "stsz",
                    int(0) +
                        int(if (fixedSize) samples.first().size else 0) +
                        int(samples.size) +
                        if (fixedSize) ByteArray(0) else concat(samples.map { int(it.size) }),
                )
            } else {
                box(
                    "stz2",
                    int(0) +
                        ByteArray(3) +
                        byteArrayOf(compact.toByte()) +
                        int(samples.size) +
                        concat(
                            samples.map {
                                if (compact == 8) byteArrayOf(it.size.toByte()) else shorts(it.size)
                            },
                        ),
                )
            }
        val chunks =
            box(
                if (wide) "co64" else "stco",
                int(0) +
                    int(offsets.size) +
                    concat(offsets.map { if (wide) long(it) else int(it.toInt()) }),
            )
        val mapping =
            box(
                "stsc",
                int(0) +
                    int(if (samples.isEmpty()) 0 else 1) +
                    if (samples.isEmpty()) {
                        ByteArray(0)
                    } else {
                        int(1) + int(if (separate) 1 else samples.size) + int(1)
                    },
            )
        return stsd + stts + sizes + chunks + mapping
    }

    fun scoredVideoFixture(timestampUs: Long): ByteArray = classic(listOf(descriptor(timestampUs)))

    private fun classic(
        samples: List<ByteArray>,
        wide: Boolean = false,
        compact: Int = 0,
        separate: Boolean = false,
        fixedSize: Boolean = false,
    ): ByteArray {
        val base = neutralVideo()
        var offset = base.size + 8L
        val allOffsets = samples.map { sample -> offset.also { offset += sample.size } }
        val offsets = if (separate) allOffsets else allOffsets.take(1)
        return base +
            box("mdat", concat(samples)) +
            movie(track(table(samples, offsets, wide, compact, separate, fixedSize)))
    }

    private enum class Fragment {
        DEFAULT_MOOF,
        EXPLICIT,
        SIGNED,
        INHERITED,
        CHAINED_RUNS,
        LAST_RUN_END,
        EXTENDED,
    }

    private fun fragmented(
        mode: Fragment,
        payload: ByteArray,
        explicitZero: Boolean = false,
        perSampleSize: Boolean = false,
    ): ByteArray {
        val metadataTrack = track(table(emptyList(), emptyList(), false, 0, false))

        fun trex(id: Int, size: Int) =
            box("trex", int(0) + int(id) + int(1) + int(44100) + int(size) + int(0))
        val prefix =
            neutralVideo() + movie(metadataTrack, box("mvex", trex(3, payload.size) + trex(1, 2)))
        val pad = byteArrayOf(0x33, 0x44)
        val data =
            when (mode) {
                Fragment.INHERITED -> pad + payload
                Fragment.LAST_RUN_END -> pad + payload + pad
                Fragment.CHAINED_RUNS -> payload + payload
                else -> payload
            }

        fun build(size: Int): ByteArray {
            val dataOffset = size + 8
            val dataStart = prefix.size + dataOffset
            val baseFlags =
                when (mode) {
                    Fragment.EXPLICIT,
                    Fragment.SIGNED -> 1 or 0x20000

                    Fragment.INHERITED,
                    Fragment.LAST_RUN_END -> 0

                    else -> 0x20000
                }
            val explicitBase =
                when (mode) {
                    Fragment.EXPLICIT -> long(dataStart.toLong())
                    Fragment.SIGNED -> long(dataStart + payload.size.toLong())
                    else -> ByteArray(0)
                }
            val tfhd =
                box(
                    "tfhd",
                    int(baseFlags or if (explicitZero) 0x10 else 0) +
                        int(3) +
                        explicitBase +
                        if (explicitZero) int(0) else ByteArray(0),
                )
            val hasOffset =
                mode !in listOf(Fragment.EXPLICIT, Fragment.INHERITED, Fragment.LAST_RUN_END)
            val run =
                box(
                    "trun",
                    int((if (hasOffset) 1 else 0) or if (perSampleSize) 0x200 else 0) +
                        int(1) +
                        (if (hasOffset) {
                            int(if (mode == Fragment.SIGNED) -payload.size else dataOffset)
                        } else {
                            ByteArray(0)
                        }) +
                        if (perSampleSize) int(payload.size) else ByteArray(0),
                )
            val preceding =
                if (mode in listOf(Fragment.INHERITED, Fragment.LAST_RUN_END)) {
                    val first =
                        box(
                            "trun",
                            int(1) +
                                int(1) +
                                int(
                                    if (mode == Fragment.LAST_RUN_END) {
                                        dataOffset + 2 + payload.size
                                    } else {
                                        dataOffset
                                    },
                                ),
                        )
                    val last =
                        if (mode == Fragment.LAST_RUN_END) {
                            box("trun", int(1) + int(1) + int(dataOffset))
                        } else {
                            ByteArray(0)
                        }
                    box("traf", box("tfhd", int(0) + int(1)) + first + last)
                } else {
                    ByteArray(0)
                }
            val chained =
                if (mode == Fragment.CHAINED_RUNS) box("trun", int(0) + int(1)) else ByteArray(0)
            return box(
                "moof",
                box("mfhd", int(0) + int(1)) + preceding + box("traf", tfhd + run + chained),
                mode == Fragment.EXTENDED,
            )
        }
        val moof = build(build(0).size)
        return prefix + moof + box("mdat", data)
    }

    private fun descriptor(
        timestamp: Long,
        highResolutionFrames: Int = 0,
        width: Int = 1,
    ): ByteArray {
        fun d(tag: Int, body: ByteArray): ByteArray {
            val groups =
                MutableList(width) { index ->
                    ((body.size ushr ((width - index - 1) * 7)) and 0x7f) or
                        if (index < width - 1) 0x80 else 0
                }
            return byteArrayOf(tag.toByte()) + groups.map { it.toByte() }.toByteArray() + body
        }

        fun frame(time: Long) =
            d(
                0xc3,
                ByteBuffer.allocate(12).putFloat(0.5f).putLong(time).array(),
            )
        val track =
            d(
                0xc2,
                int(highResolutionFrames) +
                    concat(List(highResolutionFrames) { frame(it * 1000L) }),
            )
        val score = d(0xc1, int(1) + frame(timestamp) + track)
        val flags = d(0xc4, d(0xc5, byteArrayOf(0x80.toByte())) + d(0xc5, byteArrayOf(0)))
        return d(0xc0, score + flags)
    }

    private fun xmpPhoto(bytes: ByteArray, timestamp: String?): ByteArray {
        val xml =
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:c="http://ns.google.com/photos/1.0/camera/" xmlns:d="http://ns.google.com/photos/1.0/container/" xmlns:i="http://ns.google.com/photos/1.0/container/item/" c:MotionPhoto="1" c:MotionPhotoVersion="1" ${timestamp?.let {
                "c:MotionPhotoPresentationTimestampUs=\"$it\""
            }.orEmpty()}><d:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><d:Item i:Mime="image/jpeg" i:Semantic="Primary"/></rdf:li><rdf:li rdf:parseType="Resource"><d:Item i:Mime="video/mp4" i:Semantic="MotionPhoto" i:Length="${bytes.size}"/></rdf:li></rdf:Seq></d:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val metadata = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xml.toByteArray()
        return cover.copyOfRange(0, 2) +
            byteArrayOf(-1, -31) +
            shorts(metadata.size + 2) +
            metadata +
            cover.copyOfRange(2, cover.size) +
            bytes
    }

    private fun occurrences(bytes: ByteArray, needle: ByteArray) =
        (0..bytes.size - needle.size).filter { offset ->
            needle.indices.all { bytes[offset + it] == needle[it] }
        }

    private fun replaceAscii(photo: ByteArray, old: String, replacement: String): ByteArray {
        val source = photo.toString(Charsets.ISO_8859_1)
        val changed = source.replace(old, replacement).toByteArray(Charsets.ISO_8859_1)
        val previousLength = ((photo[4].toInt() and 0xff) shl 8) or (photo[5].toInt() and 0xff)
        ByteBuffer.wrap(changed)
            .putShort(4, (previousLength + replacement.length - old.length).toShort())
        return changed
    }
}
