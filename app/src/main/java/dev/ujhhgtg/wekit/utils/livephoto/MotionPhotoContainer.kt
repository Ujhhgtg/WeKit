package dev.ujhhgtg.wekit.utils.livephoto

import dev.ujhhgtg.wekit.utils.fs.moveReplacing
import dev.ujhhgtg.wekit.utils.livephoto.MotionPhotoNative.flag
import dev.ujhhgtg.wekit.utils.livephoto.MotionPhotoNative.long
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Standard JPEG Motion Photos. Vendor HDR/multi-image and extended XMP are unsupported. */
object MotionPhotoContainer {
    data class PhotoInfo(
        val videoOffset: Long,
        val videoLength: Long,
        val coverTimestampMs: Long,
        val imageEnd: Long,
        val coverTimestampUs: Long = if (coverTimestampMs == -1L) -1L else Math.multiplyExact(coverTimestampMs, 1000),
    )

    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CAMERA_OPLUS = "http://ns.oplus.com/photos/1.0/camera/"
    private const val CAMERA_VIVO = "http://ns.vivo.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XMP = "adobe:ns:meta/"
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val MPF_HEADER = byteArrayOf(0x4d, 0x50, 0x46, 0)
    private val WECHAT_CACHED_JPEG_MD5_HEADER = byteArrayOf(-1, -28, -112, 3, 0, 0, 0, 0)
    private val MOTION_PROPERTIES = setOf(
        "MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs",
        "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs",
    )

    /** A header candidate only; [inspect] must still validate the complete image and video. */
    fun hasMotionPhotoMetadata(channel: FileChannel): Boolean =
        MotionPhotoNative.hasMetadata(channel, channel.size())

    /** Returns null for a static JPEG/non-JPEG; malformed motion files throw [IOException]. */
    fun inspect(channel: FileChannel): PhotoInfo? =
        MotionPhotoNative.photoInfo(MotionPhotoNative.decode(MotionPhotoNative.inspectPhoto(channel, channel.size())))

    /** Publishes only a complete validated MP4; a failed extraction preserves an existing output. */
    fun extractVideo(channel: FileChannel, info: PhotoInfo, output: File) {
        if (info.videoOffset < info.imageEnd || info.videoLength <= 0 ||
            add(info.videoOffset, info.videoLength) != channel.size()
        ) fail("Invalid extracted video bounds")
        analyzeVideo(channel, info.videoOffset, info.videoLength).metadata?.let { metadata ->
            if (metadata.frames.any { it.timestampUs != info.coverTimestampUs }) fail("Extracted metadata timestamp disagrees with photo")
        }
        publish(output, ".mp4") { temporary ->
            temporary.outputStream().channel.use { target ->
                copy(channel, info.videoOffset, info.videoLength, target)
                target.force(true)
            }
            temporary.inputStream().channel.use {
                analyzeVideo(it, 0, it.size())
            }
        }
    }

    /** Writes modern XMP and a single-image MPF index, retaining original pixels and EXIF. */
    @JvmOverloads
    fun write(cover: File, video: File, output: File, coverTimestampMs: Long, vivoMetadata: Boolean = false): PhotoInfo =
        writeContainer(cover, video, output, coverTimestampMs, vivoMetadata, conversion = false)

    /** Write converted primary pixels and a caller-validated video directly into one container. */
    fun writeConverted(
        cover: File, video: File, output: File, coverTimestampMs: Long, videoLength: Long,
        vivoMetadata: Boolean, appendVideo: (FileChannel, FileChannel, Long) -> Unit,
    ): PhotoInfo = writeContainer(cover, video, output, coverTimestampMs, vivoMetadata,
        conversion = true, transformedVideoLength = videoLength, appendVideo = appendVideo)

    private fun writeContainer(
        cover: File, video: File, output: File, coverTimestampMs: Long, vivoMetadata: Boolean,
        conversion: Boolean, transformedVideoLength: Long? = null,
        appendVideo: ((FileChannel, FileChannel, Long) -> Unit)? = null,
    ): PhotoInfo {
        if (coverTimestampMs < -1) fail("Invalid cover timestamp")
        if (output.canonicalFile == cover.canonicalFile || output.canonicalFile == video.canonicalFile) {
            fail("Output must not overwrite a source image or video")
        }
        return cover.inputStream().channel.use { imageChannel ->
            video.inputStream().channel.use { videoChannel ->
                val jpeg = parseJpeg(imageChannel, discardUnusableMetadata = conversion)
                if (!conversion) ensureSupported(jpeg)
                val videoMetadata = if (appendVideo == null) {
                    analyzeVideo(videoChannel, 0, videoChannel.size()).metadata
                } else null // The conversion's rewrite plan already validated this source.
                if (!conversion) validateCoverTail(imageChannel, jpeg, videoChannel)
                val videoLength = transformedVideoLength ?: videoChannel.size()
                val document = jpeg.xmp ?: newXmp()
                if (conversion) {
                    // FIXME: Explicit Vivo/standard conversion discards unsupported private
                    //        auxiliary declarations, as selected by the user; normal export stays strict.
                    removeConversionMetadata(document)
                } else removeMotionMetadata(document)
                addMotionMetadata(document, videoLength, coverTimestampMs, vivoMetadata)
                val xmp = XMP_HEADER + serialize(document)
                if (xmp.size > 65533) fail("XMP exceeds the JPEG APP1 segment limit")
                val removed = jpeg.segments.filter { it.isXmp || it.isMpf || conversion && it.isExtendedXmp }
                val imageLength = jpeg.imageEnd - removed.sumOf { it.end - it.start } + xmp.size + 4 + 74
                // FIXME: OPlus 1.0.8 requires MPF even for a single JPEG. Standard Motion
                //        Photo does not; remove this APP2 index when that reader is no longer supported.
                val mpf = singleImageMpf(imageLength)
                publish(output, ".jpg") { temporary ->
                    temporary.outputStream().channel.use { target ->
                        copy(imageChannel, 0, 2, target)
                        writeSegment(target, 0xe1, xmp)
                        writeSegment(target, 0xe2, mpf)
                        var sourceOffset = 2L
                        for ((start, end) in removed) {
                            copy(imageChannel, sourceOffset, start - sourceOffset, target)
                            sourceOffset = end
                        }
                        copy(imageChannel, sourceOffset, jpeg.imageEnd - sourceOffset, target)
                        if (appendVideo != null) appendVideo(videoChannel, target, imageLength) else {
                            copy(videoChannel, 0, videoLength, target)
                            videoMetadata?.let {
                                val timestampUs = if (coverTimestampMs == -1L) -1L else Math.multiplyExact(coverTimestampMs, 1000)
                                MotionPhotoVideoMetadata.synchronize(target, imageLength, it, timestampUs)
                            }
                        }
                        target.force(true)
                    }
                    temporary.inputStream().channel.use {
                        val result = inspect(it) ?: fail("Written file is not a Motion Photo")
                        if (result.videoLength != videoLength || result.coverTimestampMs != coverTimestampMs ||
                            result.imageEnd != imageLength
                        ) fail("Written Motion Photo failed verification")
                        result
                    }
                }
            }
        }
    }

    private fun validateCoverTail(image: FileChannel, jpeg: Jpeg, video: FileChannel) {
        if (jpeg.imageEnd < image.size() && inspect(image) == null &&
            !matchesSuppliedVideo(image, jpeg.imageEnd, video) && !matchesCachedCoverChecksum(image, jpeg.imageEnd)
        ) fail("Cover has unsupported trailing data")
    }

    /** Explicit format conversion: retain the primary JPEG and metadata that can stand alone. */
    fun normalizeCover(cover: File, output: File) = normalizeCover(cover, output, null)

    /** Strip a verified cached cover before EXIF edits, without staging its duplicate video. */
    fun normalizeCoverForExport(cover: File, video: File, output: File) = normalizeCover(cover, output, video)

    private fun normalizeCover(cover: File, output: File, verifiedVideo: File?) {
        if (cover.canonicalFile == output.canonicalFile) fail("Normalized cover must not replace its source")
        cover.inputStream().channel.use { source ->
            // FIXME: Vivo conversion may discard unsupported private XDR/auxiliary data,
            //        as explicitly selected by the user. Preserve primary pixels/EXIF and
            //        usable non-motion XMP; never use this lossy metadata path for normal export.
            //        Removing this exception restores strict rejection of unsupported covers.
            val jpeg = parseJpeg(source, discardUnusableMetadata = verifiedVideo == null)
            if (verifiedVideo != null) {
                ensureSupported(jpeg)
                verifiedVideo.inputStream().channel.use {
                    analyzeVideo(it, 0, it.size())
                    validateCoverTail(source, jpeg, it)
                }
            }
            val packet = jpeg.xmp?.let { document ->
                removeMotionMetadata(document)
                if (verifiedVideo == null) removeConversionMetadata(document)
                val bytes = XMP_HEADER + serialize(document)
                if (verifiedVideo != null && bytes.size > 65533) fail("XMP exceeds the JPEG APP1 segment limit")
                bytes.takeIf { it.size <= 65533 }
            }
            val removed = jpeg.segments.filter { it.isXmp || it.isMpf || it.isExtendedXmp }
            publish(output, ".jpg") { temporary ->
                temporary.outputStream().channel.use { target ->
                    copy(source, 0, 2, target)
                    if (packet != null) writeSegment(target, 0xe1, packet)
                    var offset = 2L
                    for ((start, end) in removed) {
                        copy(source, offset, start - offset, target)
                        offset = end
                    }
                    copy(source, offset, jpeg.imageEnd - offset, target)
                    target.force(true)
                }
                temporary.inputStream().channel.use { normalized ->
                    val result = parseJpeg(normalized)
                    ensureSupported(result)
                    if (result.imageEnd != normalized.size()) fail("Normalized cover has trailing data")
                }
            }
        }
    }

    private data class Segment(
        val start: Long,
        val end: Long,
        val isXmp: Boolean,
        val isMpf: Boolean,
        val isExtendedXmp: Boolean = false,
    )
    private data class Jpeg(
        val segments: List<Segment>,
        val imageEnd: Long,
        val xmp: Document?,
        val extendedXmp: Boolean,
        val multiImage: Boolean,
    )

    private fun parseJpeg(channel: FileChannel, discardUnusableMetadata: Boolean = false): Jpeg {
        val data = MotionPhotoNative.decode(
            MotionPhotoNative.parseJpeg(channel, channel.size(), false, discardUnusableMetadata),
        ) as JsonObject
        val segments = (data.getValue("segments") as JsonArray).map {
            val segment = it as JsonObject
            Segment(segment.long("start"), segment.long("end"), segment.flag("is_xmp"),
                segment.flag("is_mpf"), segment.flag("is_extended_xmp"))
        }
        val packet = data.getValue("xmp")
        return Jpeg(segments, data.long("image_end"),
            if (packet == JsonNull) null else
                parseXmp((packet as JsonPrimitive).content.toByteArray(Charsets.UTF_8)),
            data.flag("has_extended_xmp"), data.flag("multi_image"))
    }

    /** Validate the primary JPEG once before choosing Vivo's opaque-prefix preservation. */
    fun conversionImageEnd(channel: FileChannel): Long {
        val data = MotionPhotoNative.decode(MotionPhotoNative.parseJpeg(channel, channel.size(), headers = false, discard = true))
            as JsonObject
        return data.long("image_end")
    }

    private fun ensureSupported(jpeg: Jpeg) {
        if (jpeg.extendedXmp || jpeg.multiImage) fail("Extended XMP and multi-image JPEG are unsupported")
        jpeg.xmp?.let { document ->
            val all = document.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val element = all.item(i) as Element
                val namespace = element.namespaceURI.orEmpty()
                if (namespace.contains("hdr-gain-map") || namespace.contains("hdrgm")) {
                    fail("Ultra HDR JPEG is unsupported")
                }
                for (j in 0 until element.attributes.length) {
                    val attributeNamespace = element.attributes.item(j).namespaceURI.orEmpty()
                    if (attributeNamespace.contains("hdr-gain-map") || attributeNamespace.contains("hdrgm")) {
                        fail("Ultra HDR JPEG is unsupported")
                    }
                }
            }
            val items = document.getElementsByTagNameNS(CONTAINER, "Item")
            if (items.length > 2) fail("Auxiliary JPEG items are unsupported")
            for (i in 0 until items.length) {
                if (field(items.item(i) as Element, "Semantic") == "GainMap") fail("Gain map JPEG is unsupported")
            }
        }
    }

    private fun parseXmp(bytes: ByteArray): Document {
        // FIXME: An observed producer appends NUL padding after a complete UTF-8 packet;
        //        this is a compatibility exception, not standard XMP whitespace padding.
        //        Trim only segment-tail zeros. Interior zeros and UTF-16/32 remain rejected.
        //        Restore strict XMP parsing by rejecting all NUL bytes instead of trimming this suffix.
        var packetEnd = bytes.size
        while (packetEnd > 0 && bytes[packetEnd - 1] == 0.toByte()) packetEnd--
        // Reject DTDs before parsing: Android's XML factories differ in supported feature names.
        if ((0 until packetEnd).any { bytes[it] == 0.toByte() }) fail("XMP must use UTF-8 encoding")
        val text = String(bytes, 0, packetEnd, Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            fail("XMP external/entity declarations are forbidden")
        }
        try {
            val builder = xmlFactory().newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IOException("External XMP entity is forbidden") }
            return builder.parse(InputSource(ByteArrayInputStream(bytes, 0, packetEnd)))
        } catch (exception: Exception) {
            throw IOException("Malformed XMP", exception)
        }
    }

    private fun xmlFactory() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }

    private fun field(element: Element, name: String): String? {
        val values = mutableListOf<String>()
        if (element.hasAttributeNS(ITEM, name)) values += element.getAttributeNS(ITEM, name).trim()
        childElements(element).filter { it.namespaceURI == ITEM && it.localName == name }
            .forEach { values += it.textContent.trim() }
        if (values.size > 1) fail("Duplicate directory field: $name")
        return values.singleOrNull()
    }

    private fun childElements(element: Element): List<Element> = buildList {
        for (i in 0 until element.childNodes.length) {
            val node = element.childNodes.item(i)
            if (node is Element) add(node)
        }
    }

    private fun newXmp(): Document = xmlFactory().newDocumentBuilder().newDocument().apply {
        val root = createElementNS(XMP, "x:xmpmeta")
        appendChild(root)
        root.appendChild(createElementNS(RDF, "rdf:RDF"))
    }

    private fun removeMotionMetadata(document: Document) {
        val all = document.getElementsByTagName("*")
        val remove = mutableListOf<Element>()
        for (i in 0 until all.length) {
            val element = all.item(i) as Element
            if (element.namespaceURI == CAMERA && element.localName in MOTION_PROPERTIES ||
                element.namespaceURI == CONTAINER && element.localName == "Directory"
            ) remove += element
            for (name in MOTION_PROPERTIES) element.removeAttributeNS(CAMERA, name)
            // FIXME: OPlus motion XMP can reference the previous video/crop; drop only its
            //        camera namespace on export. Remove this branch for standard-only XMP handling.
            for (j in element.attributes.length - 1 downTo 0) {
                val attribute = element.attributes.item(j)
                if (attribute.namespaceURI == CAMERA_OPLUS) {
                    element.removeAttributeNode(attribute as org.w3c.dom.Attr)
                }
            }
            if (element.namespaceURI == CAMERA_OPLUS) remove += element
        }
        remove.distinct().forEach { it.parentNode?.removeChild(it) }
        // Do not accumulate an empty old motion description on every subsequent export.
        val descriptions = document.getElementsByTagNameNS(RDF, "Description")
        for (i in descriptions.length - 1 downTo 0) {
            val description = descriptions.item(i) as Element
            val hasProperty = (0 until description.attributes.length).any { index ->
                val attribute = description.attributes.item(index)
                attribute.namespaceURI != "http://www.w3.org/2000/xmlns/" &&
                    !(attribute.namespaceURI == RDF && attribute.localName == "about" && attribute.nodeValue.isEmpty())
            }
            if (!hasProperty && childElements(description).isEmpty() && description.textContent.isBlank()) {
                description.parentNode.removeChild(description)
            }
        }
    }

    private fun removeConversionMetadata(document: Document) {
        // FIXME: Rebuilding Vivo exports must not retain private declarations for old
        //        video associations or discarded XDR/gain maps. This conversion-only
        //        cleanup follows the user's choice to discard unsupported auxiliary data.
        val remove = mutableListOf<Element>()
        val all = document.getElementsByTagName("*")
        fun discard(namespace: String?, name: String?) = namespace == CAMERA_VIVO ||
            namespace.orEmpty().contains("hdr-gain-map") || namespace.orEmpty().contains("hdrgm") ||
            namespace == "http://ns.adobe.com/xmp/note/" && name == "HasExtendedXMP"
        for (i in 0 until all.length) {
            val element = all.item(i) as Element
            if (discard(element.namespaceURI, element.localName)) remove += element
            for (j in element.attributes.length - 1 downTo 0) {
                val attribute = element.attributes.item(j)
                if (discard(attribute.namespaceURI, attribute.localName)) {
                    element.removeAttributeNode(attribute as org.w3c.dom.Attr)
                }
            }
        }
        remove.distinct().forEach { it.parentNode?.removeChild(it) }
        removeMotionMetadata(document)
    }

    private fun addMotionMetadata(document: Document, length: Long, timestampMs: Long, vivoMetadata: Boolean = false) {
        val rdf = document.getElementsByTagNameNS(RDF, "RDF")
        if (rdf.length != 1) fail("XMP must have exactly one RDF root")
        val description = document.createElementNS(RDF, "rdf:Description")
        description.setAttributeNS(RDF, "rdf:about", "")
        description.setAttributeNS(CAMERA, "Camera:MotionPhoto", "1")
        description.setAttributeNS(CAMERA, "Camera:MotionPhotoVersion", "1")
        val us = if (timestampMs == -1L) -1 else try {
            Math.multiplyExact(timestampMs, 1000)
        } catch (exception: ArithmeticException) {
            throw IOException("Cover timestamp overflows microseconds", exception)
        }
        description.setAttributeNS(CAMERA, "Camera:MotionPhotoPresentationTimestampUs", us.toString())
        if (vivoMetadata) {
            // FIXME: Vivo V2 compatibility uses the provided SDK's XMP profile identifiers;
            //        no vendor SDK is loaded. These are format-compatibility declarations,
            //        not device/camera identity. Disable this branch for pure standard XMP.
            description.setAttributeNS(CAMERA_VIVO, "VCamera:VMotionPhotoVersion", "1")
            description.setAttributeNS(CAMERA_VIVO, "VCamera:VMotionPhotoSource", "4")
            description.setAttributeNS(CAMERA_VIVO, "VCamera:VMediaKitVersion", "1.0.0.4")
        }
        val directory = document.createElementNS(CONTAINER, "Container:Directory")
        val sequence = document.createElementNS(RDF, "rdf:Seq")
        directory.appendChild(sequence)
        for ((mime, semantic, itemLength) in listOf(Triple("image/jpeg", "Primary", 0L), Triple("video/mp4", "MotionPhoto", length))) {
            val entry = document.createElementNS(RDF, "rdf:li")
            entry.setAttributeNS(RDF, "rdf:parseType", "Resource")
            val item = document.createElementNS(CONTAINER, "Container:Item")
            item.setAttributeNS(ITEM, "Item:Mime", mime)
            item.setAttributeNS(ITEM, "Item:Semantic", semantic)
            item.setAttributeNS(ITEM, "Item:Length", itemLength.toString())
            entry.appendChild(item)
            sequence.appendChild(entry)
        }
        description.appendChild(directory)
        rdf.item(0).appendChild(description)
    }

    private fun serialize(document: Document): ByteArray {
        val stream = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(document), StreamResult(stream))
        return stream.toByteArray()
    }

    /** MP Index IFD with one baseline image; MPEntry offsets are relative to the TIFF header. */
    private fun singleImageMpf(imageLength: Long): ByteArray {
        if (imageLength > 0xffffffffL) fail("JPEG is too large for MPF")
        return ByteBuffer.allocate(70).order(ByteOrder.BIG_ENDIAN).apply {
            put(MPF_HEADER)
            putShort(0x4d4d.toShort())
            putShort(42)
            putInt(8)
            putShort(3)
            putShort(0xb000.toShort()); putShort(7); putInt(4); put("0100".toByteArray(Charsets.US_ASCII))
            putShort(0xb001.toShort()); putShort(4); putInt(1); putInt(1)
            putShort(0xb002.toShort()); putShort(7); putInt(16); putInt(50)
            putInt(0)
            putInt(0x00030000)
            putInt(imageLength.toInt())
            putInt(0)
            putShort(0)
            putShort(0)
        }.array()
    }

    data class Box(val type: String, val payload: Long, val end: Long, val start: Long = payload - 8)
    data class VideoAnalysis(val boxes: List<Box>, val metadata: MotionPhotoVideoMetadata.Info?)

    fun analyzeVideo(channel: FileChannel, start: Long, length: Long): VideoAnalysis {
        val value = MotionPhotoNative.decode(MotionPhotoNative.analyzeVideo(channel, channel.size(), start, length))
            as JsonObject
        return VideoAnalysis(decodeBoxes(value.getValue("boxes")), MotionPhotoNative.metadata(value.getValue("metadata")))
    }

    fun boxes(channel: FileChannel, start: Long, end: Long): List<Box> =
        decodeBoxes(MotionPhotoNative.decode(MotionPhotoNative.readBoxes(channel, channel.size(), start, end)))

    private fun decodeBoxes(value: JsonElement): List<Box> =
        (value as JsonArray).map {
            val box = it as JsonObject
            Box((box.getValue("kind") as JsonPrimitive).content,
                box.long("payload"), box.long("end"), box.long("start"))
        }

    private fun <T> publish(output: File, suffix: String, write: (File) -> T): T {
        val parent = output.absoluteFile.parentFile ?: fail("Output has no parent directory")
        // Scoped storage resolves media type by extension. Keep a JPEG/MP4 suffix so
        // the media directory accepts staging files before validation and publication.
        val temporary = File.createTempFile("wekit-motion-", suffix, parent)
        try {
            val result = write(temporary)
            temporary.toPath().moveReplacing(output.toPath())
            return result
        } finally {
            temporary.delete()
        }
    }

    private fun matchesSuppliedVideo(cover: FileChannel, imageEnd: Long, video: FileChannel): Boolean {
        // FIXME: WeChat can remove XMP while retaining the same appended video in its cached
        //        cover. Only export may strip that duplicate: prove its entire bounded suffix
        //        equals the separately validated video. Album recognition remains XMP-based.
        //        Remove this exception to reject unmarked cached covers again.
        val length = video.size()
        if (cover.size() - imageEnd != length) return false
        var offset = 0L
        while (offset < length) {
            val count = minOf(64 * 1024L, length - offset).toInt()
            if (!read(cover, imageEnd + offset, count).contentEquals(read(video, offset, count))) return false
            offset += count
        }
        return true
    }

    private fun matchesCachedCoverChecksum(cover: FileChannel, imageEnd: Long): Boolean {
        // FIXME: An observed WeChat cached cover appends this 8-byte marker and the
        //        JPEG prefix's MD5. Verify the complete 24-byte envelope; only export removes it.
        //        Remove this exception to reject checksum-tailed cached covers again.
        if (cover.size() - imageEnd != 24L) return false
        val footer = read(cover, imageEnd, 24)
        if (!footer.startsWith(WECHAT_CACHED_JPEG_MD5_HEADER)) return false
        val digest = MessageDigest.getInstance("MD5")
        var offset = 0L
        while (offset < imageEnd) {
            val count = minOf(64 * 1024L, imageEnd - offset).toInt()
            digest.update(read(cover, offset, count))
            offset += count
        }
        return digest.digest().contentEquals(footer.copyOfRange(8, 24))
    }

    private fun copy(source: FileChannel, start: Long, count: Long, target: FileChannel) {
        if (start < 0 || count < 0 || add(start, count) > source.size()) fail("Invalid copy bounds")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var copied = 0L
        while (copied < count) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), count - copied).toInt())
            val read = source.read(buffer, start + copied)
            if (read <= 0) fail("Source truncated while copying")
            buffer.flip()
            while (buffer.hasRemaining()) target.write(buffer)
            copied += read
        }
    }

    private fun writeSegment(target: FileChannel, marker: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(4).put(0xff.toByte()).put(marker.toByte()).putShort((data.size + 2).toShort())
        header.flip()
        while (header.hasRemaining()) target.write(header)
        val payload = ByteBuffer.wrap(data)
        while (payload.hasRemaining()) target.write(payload)
    }

    private fun read(channel: FileChannel, offset: Long, size: Int): ByteArray {
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) <= 0) fail("Truncated file")
        }
        return buffer.array()
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun add(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (exception: ArithmeticException) {
        throw IOException("File offset overflow", exception)
    }
    private fun fail(message: String): Nothing = throw IOException(message)
}
