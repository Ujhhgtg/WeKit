package dev.ujhhgtg.wekit.loader.environment

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The proven aa() envelope; compatibility is gated by the embedded codec profile fingerprint at installation. */
class NormsgReportCodec(tables: ByteArray) {
    private val cipher = NormsgCipher(tables)

    fun decode(report: ByteArray): ByteArray {
        require(report.size <= MAX_REPORT_BYTES)
        require(ReportWire.bytes(report, 1).contentEquals("00000002\u0000".toByteArray()))
        require(ReportWire.number(report, 2) == 2L)
        val encrypted = ReportWire.bytes(report, 3)
        require(encrypted.isNotEmpty())
        val padded = cipher.transform(encrypted, decrypt = true)
        val padding = padded.last().toInt() and 255
        require(padding in 1..16 && padded.takeLast(padding).all { it.toInt() and 255 == padding })
        val inflater = Inflater()
        val plain = try {
            inflater.setInput(padded, 0, padded.size - padding)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                require(count != 0 || inflater.finished()) { "incomplete zlib stream" }
                require(output.size() + count <= MAX_REPORT_BYTES) { "report too large" }
                output.write(buffer, 0, count)
            }
            require(inflater.remaining == 0) { "trailing zlib data" }
            output.toByteArray()
        } finally {
            inflater.end()
        }
        validate(plain)
        return plain
    }

    fun encode(plain: ByteArray, original: ByteArray): ByteArray {
        require(plain.size <= MAX_REPORT_BYTES)
        validate(plain)
        val deflater = Deflater(6)
        val compressed = try {
            deflater.setInput(plain)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
        val padding = 16 - compressed.size % 16
        val padded = compressed.copyOf(compressed.size + padding)
        padded.fill(padding.toByte(), compressed.size)
        return ReportWire.replace(original, 3, ReportWire.encode(3, cipher.transform(padded, decrypt = false)))
    }

    fun rewrite(report: ByteArray, policy: EnvironmentReportPolicy): ByteArray {
        val clear = decode(report)
        val fields = ReportWire.bytes(clear, 3)
        val changed = policy.rewriteFields(fields)
        if (changed.contentEquals(fields)) return report
        val updated = ReportWire.replace(clear, 3, ReportWire.encode(3, changed))
        val checked = ReportWire.replace(updated, 1, ReportWire.encode(1, checksum(changed)))
        return encode(checked, report)
    }

    private fun validate(clear: ByteArray) {
        val data = ReportWire.bytes(clear, 3)
        require(ReportWire.number(clear, 1) == checksum(data)) { "report CRC32 mismatch" }
        ReportWire.fields(data)
    }

    private fun checksum(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    companion object {
        private const val MAX_REPORT_BYTES = 4 * 1024 * 1024
    }
}
