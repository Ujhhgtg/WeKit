package dev.ujhhgtg.wekit.utils

import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import java.nio.file.Path

/**
 * Identifies panel media from bytes, never from the name supplied by a document provider.
 *
 * The detector intentionally only reports formats that the panel can preserve or pass to the
 * existing WeKit conversion/send paths.  A caller can use [extension] when it needs a stable
 * filename after importing a file whose extension is missing or incorrect.
 */
object MediaFileTypeDetector {
    enum class ImageFormat(val extension: String) {
        GIF("gif"),
        PNG("png"),
        JPEG("jpg"),
        WEBP("webp"),
        WXGF("wxgf"),
    }

    enum class AudioFormat(val extension: String) {
        MP3("mp3"),
        M4A("m4a"),
        AAC("aac"),
        WAV("wav"),
        SILK("silk"),
        AMR("amr"),
        FLAC("flac"),
    }

    fun detectImage(path: Path): ImageFormat? = readHeader(path)?.let(::detectImage)

    fun detectImage(bytes: ByteArray): ImageFormat? = when {
        bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> ImageFormat.GIF
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> ImageFormat.PNG
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> ImageFormat.JPEG
        bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 12) == "WEBP" -> ImageFormat.WEBP
        bytes.ascii(0, 4).equals("wxgf", ignoreCase = true) -> ImageFormat.WXGF
        else -> null
    }

    fun detectAudio(path: Path): AudioFormat? {
        if (!path.isRegularFile()) return null
        val header = readHeader(path) ?: return null
        detectAudio(header)?.let { return it }

        // ID3v2 is a tag container and the first audio frame may be beyond the small header.
        // Read just after the tag instead of treating the filename as a format hint.
        if (header.startsWithAscii("ID3") && header.size >= 10) {
            val tagSize = synchsafeInt(header, 6)
            val frameOffset = 10L + tagSize + if (header[5].toInt() and 0x10 != 0) 10 else 0
            readHeader(path, frameOffset)?.let { payload ->
                detectMpegOrAac(payload)?.let { return it }
            }
        }
        return null
    }

    fun detectAudio(bytes: ByteArray): AudioFormat? {
        detectAudioHeader(bytes)?.let { return it }
        if (bytes.startsWithAscii("ID3") && bytes.size >= 10) {
            val tagSize = synchsafeInt(bytes, 6)
            val frameOffset = 10 + tagSize + if (bytes[5].toInt() and 0x10 != 0) 10 else 0
            if (frameOffset < bytes.size) {
                detectMpegOrAac(bytes.copyOfRange(frameOffset, bytes.size))?.let { return it }
            }
        }
        return null
    }

    private fun detectAudioHeader(bytes: ByteArray): AudioFormat? = when {
        bytes.startsWithAscii("#!SILK_V3") ||
                bytes.size >= 10 && bytes[0] == 0x02.toByte() && bytes.copyOfRange(1, 10)
            .contentEquals("#!SILK_V3".toByteArray()) -> AudioFormat.SILK
        bytes.startsWithAscii("#!AMR-WB") || bytes.startsWithAscii("#!AMR\n") -> AudioFormat.AMR
        bytes.startsWithAscii("RIFF") && bytes.ascii(8, 12) == "WAVE" -> AudioFormat.WAV
        bytes.startsWithAscii("RF64") && bytes.ascii(8, 12) == "WAVE" -> AudioFormat.WAV
        bytes.startsWithAscii("fLaC") -> AudioFormat.FLAC
        isMp4Container(bytes) -> AudioFormat.M4A
        else -> detectMpegOrAac(bytes)
    }

    private fun detectMpegOrAac(bytes: ByteArray): AudioFormat? {
        if (bytes.size < 2 || bytes[0] != 0xFF.toByte()) return null
        val second = bytes[1].toInt() and 0xFF
        // ADTS AAC: 12-bit sync, layer must be zero.
        if (second and 0xF6 == 0xF0) return AudioFormat.AAC
        // MPEG audio: sync, valid version/layer, bitrate and sample-rate fields.
        if (second and 0xE0 == 0xE0 && second and 0x06 != 0 && bytes.size >= 3) {
            val third = bytes[2].toInt() and 0xFF
            if (third and 0xF0 != 0 && third and 0xF0 != 0xF0 && third and 0x0C != 0x0C) {
                return AudioFormat.MP3
            }
        }
        return null
    }

    private fun isMp4Container(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || bytes.ascii(4, 8) != "ftyp") return false
        val brand = bytes.ascii(8, 12).lowercase()
        return brand in setOf(
            "m4a ", "m4b ", "mp4 ", "mp41", "mp42", "isom", "iso2",
            "iso5", "iso6", "dash", "3gp4", "3gp5", "3gp6", "3g2a", "qt  ",
        )
    }

    private fun readHeader(path: Path, offset: Long = 0L): ByteArray? = runCatching {
        path.inputStream().use { input ->
            var remainingOffset = offset
            while (remainingOffset > 0) {
                val skipped = input.skip(remainingOffset)
                if (skipped > 0) {
                    remainingOffset -= skipped
                } else {
                    if (input.read() < 0) return@use null
                    remainingOffset--
                }
            }
            val buffer = ByteArray(HEADER_BYTES)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read <= 0) break
                count += read
            }
            if (count <= 0) null else buffer.copyOf(count)
        }
    }.getOrNull()

    private fun synchsafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return bytes[offset].toInt() and 0x7F shl 21 or
                (bytes[offset + 1].toInt() and 0x7F shl 14) or
                (bytes[offset + 2].toInt() and 0x7F shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun ByteArray.ascii(start: Int, end: Int): String =
        if (start < 0 || end > size || start > end) "" else copyOfRange(start, end).toString(Charsets.US_ASCII)

    private fun ByteArray.startsWithAscii(value: String): Boolean =
        size >= value.length && copyOfRange(0, value.length).contentEquals(value.toByteArray())

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private const val HEADER_BYTES = 64
}
