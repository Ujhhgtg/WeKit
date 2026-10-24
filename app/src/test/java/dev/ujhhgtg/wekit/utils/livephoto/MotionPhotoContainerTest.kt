package dev.ujhhgtg.wekit.utils.livephoto

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MotionPhotoContainerTest {
    init {
        MotionPhotoNativeTestRuntime.load()
    }

    @TempDir lateinit var directory: File

    private val jpeg by lazy { resource("cover.jpg") }
    private val mp4 by lazy { resource("video.mp4") }
    private val exif by lazy {
        // EXIF orientation 6 (90 degrees), retained byte-for-byte without recompressing the cover.
        "Exif\u0000\u0000".toByteArray() +
            ByteBuffer.allocate(26)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    putShort(0x4949)
                    putShort(42)
                    putInt(8)
                    putShort(1)
                    putShort(0x0112)
                    putShort(3)
                    putInt(1)
                    putShort(6)
                    putShort(0)
                    putInt(0)
                }
                .array()
    }

    @Test
    fun `native parsing borrows the descriptor and preserves channel position on success and failure`() {
        val image = File(directory, "borrowed.jpg").apply { writeBytes(jpeg) }
        image.inputStream().channel.use { channel ->
            channel.position(7)
            assertNull(MotionPhotoContainer.inspect(channel))
            assertEquals(7, channel.position())
            assertTrue(channel.isOpen)
            val first = ByteBuffer.allocate(2)
            assertEquals(2, channel.read(first, 0))
            assertArrayEquals(byteArrayOf(-1, -40), first.array())
        }
        image.writeBytes(byteArrayOf(-1, -40, -1, -31, 0, 64))
        image.inputStream().channel.use { channel ->
            channel.position(3)
            assertThrows(IOException::class.java) { MotionPhotoContainer.inspect(channel) }
            assertEquals(3, channel.position())
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun `strict EXIF staging rejects expanding XMP instead of dropping unrelated metadata`() {
        val packet =
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"><rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:title='${"\"".repeat(12_000)}'/></rdf:RDF></x:xmpmeta>"""
        val cover = file("expanding-xmp.jpg", withXmp(jpeg, packet))
        val video = file("video.mp4", mp4)
        val output = file("protected-cover.jpg", "existing output".toByteArray())
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.normalizeCoverForExport(cover, video, output)
        }
        assertArrayEquals("existing output".toByteArray(), output.readBytes())
        MotionPhotoContainer.normalizeCover(cover, output)
        assertArrayEquals(
            jpeg,
            output.readBytes(),
            "Explicit conversion can discard the unusable packet",
        )
    }

    @Test
    fun `explicit cover conversion keeps pixels EXIF and ordinary XMP while removing unsupported auxiliary declarations and tail`() {
        val metadata =
            ordinaryXmp()
                .replace(
                    "</rdf:Description>",
                    "<hdr:Version xmlns:hdr=\"http://ns.adobe.com/hdr-gain-map/1.0/\">1.0</hdr:Version>" +
                        "<v:VMotionPhotoVersion xmlns:v=\"http://ns.vivo.com/photos/1.0/camera/\">1</v:VMotionPhotoVersion>" +
                        "</rdf:Description>",
                )
        val bytes = withXmp(insert(jpeg, 0xe1, exif), metadata) + "private XDR data".toByteArray()
        val source = file("auxiliary-source.jpg", bytes)
        val output = File(directory, "primary-only.jpg")
        MotionPhotoContainer.normalizeCover(source, output)
        assertNull(inspect(output))
        assertArrayEquals(jpeg, stripMetadataAndVideo(output.readBytes()))
        assertArrayEquals(
            exif,
            appSegments(output.readBytes(), 0xe1).single { it.startsWith("Exif".toByteArray()) },
        )
        assertTrue(xmpText(output).contains("Original title"))
        assertFalse(xmpText(output).contains("VMotionPhotoVersion"))
        assertFalse(xmpText(output).contains("hdr:Version"))
        assertArrayEquals(bytes, source.readBytes())
        val motion = File(directory, "converted-motion.jpg")
        MotionPhotoContainer.write(
            output,
            file("aux-video.mp4", mp4),
            motion,
            375,
            vivoMetadata = true,
        )
        assertEquals(375, inspect(motion)!!.coverTimestampMs)
        assertTrue(xmpText(motion).contains("VCamera:VMotionPhotoVersion=\"1\""))
        assertTrue(xmpText(motion).contains("VCamera:VMotionPhotoSource=\"4\""))
        assertTrue(xmpText(motion).contains("VCamera:VMediaKitVersion=\"1.0.0.4\""))
    }

    @Test
    fun `conversion can discard unusable XMP but cannot accept a truncated JPEG or replace its source`() {
        val source =
            file("malformed-xmp.jpg", insert(jpeg, 0xe1, XMP_HEADER + "<broken>".toByteArray()))
        assertThrows(IOException::class.java) { inspect(source) }
        val output = File(directory, "without-unusable-xmp.jpg")
        MotionPhotoContainer.normalizeCover(source, output)
        assertArrayEquals(jpeg, output.readBytes())
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.normalizeCover(source, source)
        }
        val existing = file("existing-normalized.jpg", "keep this output".toByteArray())
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.normalizeCover(
                file("truncated.jpg", jpeg.copyOf(jpeg.size - 2)),
                existing,
            )
        }
        assertArrayEquals("keep this output".toByteArray(), existing.readBytes())
    }

    @Test
    fun `real JPEG and audio-video round trip preserves pixels EXIF and non-motion XMP`() {
        val source = file("source.jpg", insert(jpeg, 0xe1, exif))
        val titledSource = file("titled.jpg", withXmp(source.readBytes(), ordinaryXmp()))
        val video = file("video.mp4", mp4)
        val output = File(directory, "result.jpg")
        MotionPhotoContainer.write(titledSource, video, output, 375)

        output.inputStream().channel.use { channel ->
            channel.position(7)
            val info = MotionPhotoContainer.inspect(channel)!!
            assertEquals(7, channel.position())
            assertEquals(375, info.coverTimestampMs)
            assertEquals(mp4.size.toLong(), info.videoLength)
            assertEquals(output.length(), info.videoOffset + info.videoLength)
            assertEquals(info.imageEnd, info.videoOffset)
            val extracted = File(directory, "extracted.mp4")
            MotionPhotoContainer.extractVideo(channel, info, extracted)
            assertArrayEquals(
                mp4,
                extracted.readBytes(),
                "Audio and all video boxes must survive unchanged",
            )
            assertEquals(7, channel.position())
        }
        assertArrayEquals(
            exif,
            appSegments(output.readBytes(), 0xe1).single {
                it.startsWith("Exif\u0000\u0000".toByteArray())
            },
        )
        assertTrue(xmpText(output).contains("Original title"))
        assertArrayEquals(jpeg, stripMetadataAndVideo(output.readBytes()))
        assertArrayEquals(withXmp(source.readBytes(), ordinaryXmp()), titledSource.readBytes())
        assertSingleImageMpf(output)
    }

    @Test
    fun `independent modern fixture without MPF supports padding namespace aliases and microseconds`() {
        val padding = ByteArray(37)
        val photo =
            file(
                "modern.jpg",
                withXmp(jpeg, modern(mp4.size, "1234567", padding.size)) + padding + mp4,
            )
        val info = inspect(photo)!!
        assertEquals(1234, info.coverTimestampMs)
        assertEquals(photo.length() - mp4.size, info.videoOffset)
        assertEquals(info.imageEnd + padding.size, info.videoOffset)
    }

    @Test
    fun `legacy offset is read independently and converted into modern format on export`() {
        val old = file("old.jpg", withXmp(jpeg, legacy(mp4.size, "875000")) + mp4)
        assertEquals(875, inspect(old)!!.coverTimestampMs)
        val output = File(directory, "modernized.jpg")
        MotionPhotoContainer.write(old, file("video.mp4", mp4), output, 875)
        assertEquals(875, inspect(output)!!.coverTimestampMs)
        assertFalse(xmpText(output).contains("MicroVideo"))
        assertTrue(xmpText(output).contains("MotionPhotoVersion"))
        assertSingleImageMpf(output)
    }

    @Test
    fun `missing and unspecified timestamps remain distinguishable from frame zero`() {
        for (timestamp in listOf(null, "-1", "0")) {
            val photo =
                file("timestamp-${timestamp}.jpg", withXmp(jpeg, modern(mp4.size, timestamp)) + mp4)
            assertEquals(if (timestamp == "0") 0 else -1, inspect(photo)!!.coverTimestampMs)
        }
        val output = File(directory, "unset.jpg")
        MotionPhotoContainer.write(file("cover.jpg", jpeg), file("video.mp4", mp4), output, -1)
        assertEquals(-1, inspect(output)!!.coverTimestampMs)
    }

    @Test
    fun `static JPEG and explicit modern zero do not become motion photos from stray magic or legacy metadata`() {
        assertNull(
            inspect(file("ordinary.jpg", insert(jpeg, 0xfe, "ftyp isom moov vide".toByteArray()))),
        )
        val conflicting =
            legacy(mp4.size, "0")
                .replace("c:MicroVideo=\"1\"", "c:MotionPhoto=\"0\" c:MicroVideo=\"1\"")
        val disabled = file("disabled.jpg", withXmp(jpeg, conflicting) + mp4)
        assertNull(inspect(disabled))
        assertFalse(
            disabled.inputStream().channel.use(MotionPhotoContainer::hasMotionPhotoMetadata),
        )
        assertNull(inspect(file("movie-only.mp4", mp4)))
    }

    @Test
    fun `metadata precheck stops at the SOS header but full inspection still rejects missing entropy and EOI`() {
        fun headersOnly(image: ByteArray): ByteArray {
            val scan = indexOf(image, byteArrayOf(-1, -38))
            val length =
                ((image[scan + 2].toInt() and 0xff) shl 8) or (image[scan + 3].toInt() and 0xff)
            return image.copyOfRange(0, scan + 2 + length)
        }
        val ordinary = file("ordinary-headers-only.jpg", headersOnly(jpeg))
        assertFalse(
            ordinary.inputStream().channel.use(MotionPhotoContainer::hasMotionPhotoMetadata),
        )
        assertThrows(IOException::class.java) { inspect(ordinary) }
        val modernCandidate =
            file("modern-headers-only.jpg", headersOnly(withXmp(jpeg, modern(mp4.size, "0"))))
        assertTrue(
            modernCandidate.inputStream().channel.use(MotionPhotoContainer::hasMotionPhotoMetadata),
        )
        assertThrows(IOException::class.java) { inspect(modernCandidate) }
        val legacyCandidate =
            file("legacy-headers-only.jpg", headersOnly(withXmp(jpeg, legacy(mp4.size, "0"))))
        assertTrue(
            legacyCandidate.inputStream().channel.use(MotionPhotoContainer::hasMotionPhotoMetadata),
        )
        assertThrows(IOException::class.java) { inspect(legacyCandidate) }
    }

    @Test
    fun `precheck uses standard XMP placement while full inspection retains late APP1 handling`() {
        val packet = XMP_HEADER + modern(mp4.size, "0").toByteArray()
        val app1 =
            byteArrayOf(-1, -31) +
                ByteBuffer.allocate(2).putShort((packet.size + 2).toShort()).array() +
                packet
        val late =
            file(
                "xmp-after-scan.jpg",
                jpeg.copyOfRange(0, jpeg.size - 2) + app1 + byteArrayOf(-1, -39) + mp4,
            )
        assertFalse(late.inputStream().channel.use(MotionPhotoContainer::hasMotionPhotoMetadata))
        assertEquals(mp4.size.toLong(), inspect(late)!!.videoLength)
    }

    @Test
    fun `segment-tail NUL after a complete UTF8 XMP packet is compatible for static and motion photos`() {
        fun packet(xml: String) =
            "<?xpacket begin=\"\ufeff\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>$xml\n  <?xpacket end=\"w\"?>"
                .toByteArray()
        val static =
            file(
                "static-nul.jpg",
                insert(jpeg, 0xe1, XMP_HEADER + packet(ordinaryXmp()) + byteArrayOf(0)),
            )
        assertNull(
            inspect(static),
            "Terminal NUL must not turn an ordinary photo into a parser error",
        )
        val motion =
            file(
                "motion-nul.jpg",
                insert(
                    jpeg,
                    0xe1,
                    XMP_HEADER + packet(modern(mp4.size, "1234567")) + ByteArray(8),
                ) + mp4,
            )
        assertEquals(1234567, inspect(motion)!!.coverTimestampUs)
        val output = File(directory, "rewritten-nul.jpg")
        MotionPhotoContainer.write(static, file("video.mp4", mp4), output, 375)
        assertEquals(375, inspect(output)!!.coverTimestampMs)
        assertTrue(xmpText(output).contains("Original title"))
        assertFalse(
            xmpText(output).contains('\u0000'),
            "Exports should contain standard XML, without NUL padding",
        )
    }

    @Test
    fun `tail NUL compatibility does not accept interior NUL UTF16 UTF32 or entity declarations`() {
        val interior =
            ordinaryXmp().replace("Original title", "Original\u0000title").toByteArray() +
                ByteArray(3)
        val utf16 = ordinaryXmp().toByteArray(Charsets.UTF_16)
        val utf32 = ordinaryXmp().toByteArray(java.nio.charset.Charset.forName("UTF-32LE"))
        val dtd =
            ("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + ordinaryXmp())
                .toByteArray() + byteArrayOf(0)
        listOf(interior, utf16, utf32, dtd).forEachIndexed { index, packet ->
            assertThrows(IOException::class.java) {
                inspect(
                    file("invalid-encoding-$index.jpg", insert(jpeg, 0xe1, XMP_HEADER + packet)),
                )
            }
        }
    }

    @Test
    fun `stale XMP overlapping offsets truncated JPEG and stripped video never report live`() {
        val valid = withXmp(jpeg, modern(mp4.size, "500000")) + mp4
        val cases =
            listOf(
                withXmp(jpeg, modern(mp4.size, "500000")),
                withXmp(jpeg, modern(mp4.size + jpeg.size, "500000")) + mp4,
                valid.copyOf(valid.size - 1),
                jpeg.copyOf(jpeg.size - 2),
                withXmp(jpeg, legacy(Int.MAX_VALUE, "0")) + mp4,
            )
        cases.forEachIndexed { index, bytes ->
            assertThrows(IOException::class.java) { inspect(file("invalid-$index.jpg", bytes)) }
        }
    }

    @Test
    fun `MP4 requires complete box boundaries a movie and a supported video track`() {
        val audioOnly = mp4.copyOf()
        val videoHandler = indexOf(audioOnly, "vide".toByteArray())
        assertTrue(videoHandler > 0)
        "soun".toByteArray().copyInto(audioOnly, videoHandler)
        val brokenBox = mp4.copyOf().apply { ByteBuffer.wrap(this).putInt(Int.MAX_VALUE) }
        val fake =
            ByteBuffer.allocate(24)
                .putInt(24)
                .put("ftyp".toByteArray())
                .put("isom".toByteArray())
                .putInt(0)
                .put("isom".toByteArray())
                .put("mp42".toByteArray())
                .array()
        listOf(audioOnly, brokenBox, fake).forEachIndexed { index, bytes ->
            val photo =
                file("invalid-mp4-$index.jpg", withXmp(jpeg, modern(bytes.size, "0")) + bytes)
            assertThrows(IOException::class.java) { inspect(photo) }
        }
    }

    @Test
    fun `repeated export strips previous video and rewrites exactly one directory and MPF`() {
        val video = file("video.mp4", mp4)
        val first = File(directory, "first.jpg")
        MotionPhotoContainer.write(file("cover.jpg", jpeg), video, first, 125)
        val firstBytes = first.readBytes()
        val second = File(directory, "second.jpg")
        MotionPhotoContainer.write(first, video, second, 750)
        assertEquals(750, inspect(second)!!.coverTimestampMs)
        assertEquals(first.length(), second.length())
        assertArrayEquals(firstBytes, first.readBytes())
        assertEquals(1, appSegments(second.readBytes(), 0xe1).count { it.startsWith(XMP_HEADER) })
        assertEquals(
            1,
            appSegments(second.readBytes(), 0xe2).count {
                it.startsWith("MPF\u0000".toByteArray())
            },
        )
        assertArrayEquals(jpeg, stripMetadataAndVideo(second.readBytes()))
    }

    @Test
    fun `failed write or extraction preserves existing output and cleans temporary files`() {
        val output = file("existing.jpg", "existing valid output".toByteArray())
        val initial = output.readBytes()
        val badVideo = file("bad.mp4", mp4.copyOf(mp4.size - 5))
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.write(file("cover.jpg", jpeg), badVideo, output, 0)
        }
        assertArrayEquals(initial, output.readBytes())
        val photo = file("photo.jpg", withXmp(jpeg, modern(mp4.size, "0")) + mp4)
        photo.inputStream().channel.use { channel ->
            val info = MotionPhotoContainer.inspect(channel)!!
            assertThrows(IOException::class.java) {
                MotionPhotoContainer.extractVideo(
                    channel,
                    info.copy(videoLength = info.videoLength + 1),
                    output,
                )
            }
        }
        assertArrayEquals(initial, output.readBytes())
        assertFalse(directory.listFiles()!!.any { it.name.startsWith("wekit-motion-") })
    }

    @Test
    fun `stale motion metadata on stripped cover can be exported without recreating old video`() {
        val cover = file("stripped.jpg", withXmp(jpeg, modern(mp4.size, "500000")))
        val output = File(directory, "restored.jpg")
        MotionPhotoContainer.write(cover, file("video.mp4", mp4), output, 250)
        assertEquals(250, inspect(output)!!.coverTimestampMs)
        assertArrayEquals(jpeg, stripMetadataAndVideo(output.readBytes()))
    }

    @Test
    fun `export recognizes a proven duplicate video tail when WeChat removed XMP but album recognition stays strict`() {
        val padding = ByteArray(140_000) { (it % 251).toByte() }
        val videoBytes =
            mp4 +
                ByteBuffer.allocate(8).putInt(padding.size + 8).put("free".toByteArray()).array() +
                padding
        val cachedBytes = jpeg + videoBytes
        val cached = file("cached-no-xmp.jpg", cachedBytes)
        val video = file("same-video.mp4", videoBytes)
        assertNull(inspect(cached), "An unmarked cached cover must not change album recognition")
        val output = File(directory, "normalized.jpg")
        MotionPhotoContainer.write(cached, video, output, 375)
        output.inputStream().channel.use { channel ->
            val info = MotionPhotoContainer.inspect(channel)!!
            assertEquals(videoBytes.size.toLong(), info.videoLength)
            val extracted = File(directory, "normalized.mp4")
            MotionPhotoContainer.extractVideo(channel, info, extracted)
            assertArrayEquals(
                videoBytes,
                extracted.readBytes(),
                "Only one matching video is appended",
            )
        }
        assertArrayEquals(jpeg, stripMetadataAndVideo(output.readBytes()))
        assertArrayEquals(cachedBytes, cached.readBytes())

        val mismatchedVideo =
            file(
                "different-video.mp4",
                videoBytes.copyOf().apply {
                    this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
                },
            )
        val protectedOutput = file("protected-output.jpg", "existing output".toByteArray())
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.write(cached, mismatchedVideo, protectedOutput, 375)
        }
        assertArrayEquals("existing output".toByteArray(), protectedOutput.readBytes())
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.write(
                file("extra-byte.jpg", cachedBytes + byteArrayOf(0)),
                video,
                protectedOutput,
                375,
            )
        }
        assertFalse(directory.listFiles()!!.any { it.name.startsWith("wekit-motion-") })
    }

    @Test
    fun `export removes only an exact cached JPEG checksum envelope with a verified complete-prefix digest`() {
        val prefix =
            insert(insert(jpeg, 0xfe, ByteArray(60_000) { 83 }), 0xfe, ByteArray(20_000) { 71 })
        val header = byteArrayOf(-1, -28, -112, 3, 0, 0, 0, 0)
        val digest = java.security.MessageDigest.getInstance("MD5").digest(prefix)
        val cachedBytes = prefix + header + digest
        val cached = file("cached-checksum.jpg", cachedBytes)
        assertNull(inspect(cached), "Checksum-tailed JPEG remains an ordinary photo")
        val video = file("video.mp4", mp4)
        val output = File(directory, "checksum-normalized.jpg")
        MotionPhotoContainer.write(cached, video, output, 375)
        assertEquals(375, inspect(output)!!.coverTimestampMs)
        assertArrayEquals(prefix, stripMetadataAndVideo(output.readBytes()))
        assertArrayEquals(cachedBytes, cached.readBytes())

        val invalidCases =
            listOf(
                prefix + header.copyOf().apply { this[1] = 0 } + digest,
                prefix +
                    header +
                    digest.copyOf().apply {
                        this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
                    },
                cachedBytes + byteArrayOf(0),
            )
        val protectedOutput = file("checksum-protected.jpg", "existing output".toByteArray())
        invalidCases.forEachIndexed { index, bytes ->
            assertThrows(IOException::class.java) {
                MotionPhotoContainer.write(
                    file("bad-checksum-$index.jpg", bytes),
                    video,
                    protectedOutput,
                    375,
                )
            }
        }
        assertArrayEquals("existing output".toByteArray(), protectedOutput.readBytes())
        assertFalse(directory.listFiles()!!.any { it.name.startsWith("wekit-motion-") })
    }

    @Test
    fun `extended XMP gain maps auxiliary images duplicate properties and external entities are rejected`() {
        val hdr =
            modern(mp4.size, "0")
                .replace(
                    "c:MotionPhoto=",
                    "xmlns:h=\"http://ns.adobe.com/hdr-gain-map/1.0/\" h:Version=\"1.0\" c:MotionPhoto=",
                )
        val duplicate =
            modern(mp4.size, "0")
                .replace("</rdf:Description>", "<c:MotionPhoto>1</c:MotionPhoto></rdf:Description>")
        val entity =
            "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + modern(mp4.size, "0")
        val extended =
            insert(
                withXmp(jpeg, modern(mp4.size, "0")),
                0xe1,
                "http://ns.adobe.com/xmp/extension/\u0000".toByteArray() + ByteArray(40),
            ) + mp4
        listOf(
                withXmp(jpeg, hdr) + mp4,
                withXmp(jpeg, duplicate) + mp4,
                withXmp(jpeg, entity) + mp4,
                extended,
            )
            .forEachIndexed { index, bytes ->
                assertThrows(IOException::class.java) {
                    inspect(file("unsupported-$index.jpg", bytes))
                }
            }
        val generated = File(directory, "generated.jpg")
        MotionPhotoContainer.write(file("cover.jpg", jpeg), file("video.mp4", mp4), generated, 0)
        val multi = generated.readBytes()
        val mpf = indexOf(multi, "MPF\u0000".toByteArray())
        ByteBuffer.wrap(multi).order(ByteOrder.BIG_ENDIAN).putInt(mpf + 4 + 8 + 2 + 12 + 8, 2)
        assertThrows(IOException::class.java) { inspect(file("multi-image.jpg", multi)) }
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.write(
                file("hdr-cover.jpg", withXmp(jpeg, hdr)),
                file("video-again.mp4", mp4),
                File(directory, "hdr-output.jpg"),
                0,
            )
        }
    }

    @Test
    fun `export cannot overwrite either source or silently discard an unknown image tail`() {
        val cover = file("cover.jpg", jpeg)
        val video = file("video.mp4", mp4)
        assertThrows(IOException::class.java) { MotionPhotoContainer.write(cover, video, cover, 0) }
        assertThrows(IOException::class.java) { MotionPhotoContainer.write(cover, video, video, 0) }
        val unknown = file("unknown.jpg", jpeg + "unknown vendor payload".toByteArray())
        assertThrows(IOException::class.java) {
            MotionPhotoContainer.write(unknown, video, File(directory, "output.jpg"), 0)
        }
        assertArrayEquals(jpeg, cover.readBytes())
        assertArrayEquals(mp4, video.readBytes())
    }

    private fun assertSingleImageMpf(file: File) {
        val mpf =
            appSegments(file.readBytes(), 0xe2).single { it.startsWith("MPF\u0000".toByteArray()) }
        val buffer = ByteBuffer.wrap(mpf).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x4d4d, buffer.getShort(4).toInt())
        assertEquals(42, buffer.getShort(6).toInt())
        val ifd = 4 + buffer.getInt(8)
        assertEquals(3, buffer.getShort(ifd).toInt())
        val fields =
            (0 until buffer.getShort(ifd)).associate { i ->
                val offset = ifd + 2 + i * 12
                buffer.getShort(offset).toInt() and 0xffff to offset
            }
        assertEquals(
            "0100",
            mpf.copyOfRange(fields.getValue(0xb000) + 8, fields.getValue(0xb000) + 12)
                .toString(Charsets.US_ASCII),
        )
        assertEquals(1, buffer.getInt(fields.getValue(0xb001) + 8))
        val entry = 4 + buffer.getInt(fields.getValue(0xb002) + 8)
        assertEquals(0x00030000, buffer.getInt(entry))
        assertEquals(inspect(file)!!.imageEnd, buffer.getInt(entry + 4).toLong())
        assertEquals(0, buffer.getInt(entry + 8))
        assertEquals(mpf.size, entry + 16)
    }

    private fun inspect(file: File) = file.inputStream().channel.use(MotionPhotoContainer::inspect)

    private fun file(name: String, bytes: ByteArray) =
        File(directory, name).apply { writeBytes(bytes) }

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/livephoto/$name")!!.use { it.readBytes() }

    private fun ordinaryXmp() =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"><rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">Original title</rdf:li></rdf:Alt></dc:title></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private fun modern(length: Int, timestamp: String?, padding: Int = 0): String =
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF">
        <rdf:Description rdf:about="" xmlns:c="$CAMERA" xmlns:d="$CONTAINER" xmlns:i="$ITEM"
            c:MotionPhoto="1" c:MotionPhotoVersion="1" ${timestamp?.let { "c:MotionPhotoPresentationTimestampUs=\"$it\"" }.orEmpty()}>
            <d:Directory><rdf:Seq>
                <rdf:li rdf:parseType="Resource"><d:Item i:Mime="image/jpeg" i:Semantic="Primary" i:Length="0" i:Padding="$padding"/></rdf:li>
                <rdf:li rdf:parseType="Resource"><d:Item i:Mime="video/mp4" i:Semantic="MotionPhoto" i:Length="$length"/></rdf:li>
            </rdf:Seq></d:Directory>
        </rdf:Description></rdf:RDF></x:xmpmeta>
    """
            .trimIndent()

    private fun legacy(length: Int, timestamp: String) =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"><rdf:Description rdf:about="" xmlns:c="$CAMERA" c:MicroVideo="1" c:MicroVideoVersion="1" c:MicroVideoOffset="$length" c:MicroVideoPresentationTimestampUs="$timestamp"/></rdf:RDF></x:xmpmeta>"""

    private fun withXmp(image: ByteArray, xml: String) =
        insert(image, 0xe1, XMP_HEADER + xml.toByteArray())

    private fun insert(image: ByteArray, marker: Int, payload: ByteArray): ByteArray =
        image.copyOfRange(0, 2) +
            ByteBuffer.allocate(4)
                .put(0xff.toByte())
                .put(marker.toByte())
                .putShort((payload.size + 2).toShort())
                .array() +
            payload +
            image.copyOfRange(2, image.size)

    private fun appSegments(image: ByteArray, marker: Int): List<ByteArray> = buildList {
        var offset = 2
        while (image[offset + 1].toInt() and 0xff != 0xda) {
            val current = image[offset + 1].toInt() and 0xff
            val length =
                image[offset + 2].toInt() and 0xff shl 8 or (image[offset + 3].toInt() and 0xff)
            if (current == marker) add(image.copyOfRange(offset + 4, offset + 2 + length))
            offset += length + 2
        }
    }

    private fun xmpText(file: File) =
        appSegments(file.readBytes(), 0xe1)
            .single { it.startsWith(XMP_HEADER) }
            .copyOfRange(
                XMP_HEADER.size,
                appSegments(file.readBytes(), 0xe1).single { it.startsWith(XMP_HEADER) }.size,
            )
            .toString(Charsets.UTF_8)

    private fun stripMetadataAndVideo(image: ByteArray): ByteArray {
        val result = java.io.ByteArrayOutputStream().apply { write(image, 0, 2) }
        var offset = 2
        while (image[offset + 1].toInt() and 0xff != 0xda) {
            val marker = image[offset + 1].toInt() and 0xff
            val length =
                image[offset + 2].toInt() and 0xff shl 8 or (image[offset + 3].toInt() and 0xff)
            if (marker != 0xe1 && marker != 0xe2) result.write(image, offset, length + 2)
            offset += length + 2
        }
        val originalScanStart = indexOf(jpeg, byteArrayOf(0xff.toByte(), 0xda.toByte()))
        result.write(image, offset, jpeg.size - originalScanStart)
        return result.toByteArray()
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray): Int =
        (0..bytes.size - needle.size).firstOrNull { offset ->
            needle.indices.all { bytes[offset + it] == needle[it] }
        } ?: -1

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    companion object {
        private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
        private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
        private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
        private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    }
}
