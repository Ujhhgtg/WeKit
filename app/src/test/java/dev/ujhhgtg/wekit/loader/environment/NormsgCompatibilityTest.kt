package dev.ujhhgtg.wekit.loader.environment

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NormsgCompatibilityTest {
    private val profile =
        javaClass.getResourceAsStream("/environment/normsg/codec-profile.bin")!!.use {
            it.readBytes()
        }

    @Test
    fun findsProfileAcrossReadBoundariesAndUnrelatedChanges() {
        for (prefixLength in listOf(0, 31, 65535, 262371, 327907)) {
            val library = ByteArray(prefixLength) { 0x55 } + profile + ByteArray(173) { 0x33 }
            assertTrue(
                NormsgCompatibility.matchesLibrary(library.inputStream()),
                "offset=$prefixLength",
            )
            val fragmented =
                object : ByteArrayInputStream(library) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        super.read(bytes, offset, minOf(length, 997))
                }
            assertTrue(
                NormsgCompatibility.matchesLibrary(fragmented),
                "fragmented offset=$prefixLength",
            )
        }
    }

    @Test
    fun rejectsIncompleteOrChangedTablesAndContinuesPastWrongCandidates() {
        val changed = profile.copyOf().apply { this[12345] = (this[12345].toInt() xor 1).toByte() }
        assertFalse(NormsgCompatibility.matchesLibrary(changed.inputStream()))
        assertFalse(
            NormsgCompatibility.matchesLibrary(profile.copyOf(profile.size - 1).inputStream()),
        )
        assertTrue(
            NormsgCompatibility.matchesLibrary((changed + ByteArray(37) + profile).inputStream()),
        )
        assertFalse(NormsgCompatibility.matchesLibrary(ByteArray(0).inputStream()))
    }
}
