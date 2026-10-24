package dev.ujhhgtg.wekit.loader.environment

import java.io.InputStream
import java.security.MessageDigest

/**
 * Content fingerprint of the proven IV/table profile, independent of ELF offsets and unrelated
 * code. Presence identifies a candidate codec; each returned report still has to pass decode/CRC
 * checks.
 */
object NormsgCompatibility {
    private const val PROFILE_SIZE = 262372
    private const val PROFILE_SHA256 =
        "5678db0ba10bf755663b70360c5532833f413daa23689404ac09041a6b2911b8"
    private val marker = hex("627d1c7e3dcd679259cf2d984858f9fa18e0604a8001e5eb96a80491cd786205")
    private val expectedHash = hex(PROFILE_SHA256)

    /** Bounded memory, including when the ELF comes directly from a compressed APK entry. */
    fun matchesLibrary(input: InputStream): Boolean {
        val buffer = ByteArray(PROFILE_SIZE + 65536)
        val digest = MessageDigest.getInstance("SHA-256")
        var used = 0
        while (true) {
            val count = input.read(buffer, used, buffer.size - used)
            if (count < 0) return false
            check(count > 0) { "library stream made no progress" }
            used += count
            var position = 0
            while (position <= used - PROFILE_SIZE) {
                if (
                    buffer[position] == marker[0] &&
                        marker.indices.all { buffer[position + it] == marker[it] }
                ) {
                    digest.update(buffer, position, PROFILE_SIZE)
                    if (digest.digest().contentEquals(expectedHash)) return true
                }
                position++
            }
            // Keep every possible cross-read candidate, not just the marker's trailing bytes.
            buffer.copyInto(buffer, 0, position, used)
            used -= position
        }
    }

    private fun hex(value: String) =
        ByteArray(value.length / 2) {
            value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
}
