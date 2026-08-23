package dev.ujhhgtg.wekit.dextest

import dev.ujhhgtg.wekit.features.core.DexResolutionTestEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DexTestFeatureSelectionTest {
    private val entries = listOf(
        entry("dev.example.chat.AntiReadReceipts"),
        entry("dev.example.chat.AntiSecMsg"),
    )

    @Test
    fun omittedSelectorsKeepAllFeatures() {
        assertEquals(entries, selectDexTestEntries(entries, null))
    }

    @Test
    fun selectsExactShortNamesInRequestedOrder() {
        assertEquals(
            listOf(entries[1], entries[0]),
            selectDexTestEntries(entries, listOf("AntiSecMsg", "AntiReadReceipts")),
        )
    }

    @Test
    fun selectsExactFullyQualifiedName() {
        assertEquals(
            listOf(entries[0]),
            selectDexTestEntries(entries, listOf("dev.example.chat.AntiReadReceipts")),
        )
    }

    @Test
    fun rejectsUnknownFeature() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            selectDexTestEntries(entries, listOf("MissingFeature"))
        }
        assertEquals("unknown Dex resolver feature: MissingFeature", error.message)
    }

    @Test
    fun rejectsAmbiguousShortNameAndAcceptsFqn() {
        val duplicate = entry("dev.example.other.AntiReadReceipts")
        val ambiguousEntries = entries + duplicate

        val error = assertThrows(IllegalArgumentException::class.java) {
            selectDexTestEntries(ambiguousEntries, listOf("AntiReadReceipts"))
        }
        assertEquals(
            "ambiguous Dex resolver feature AntiReadReceipts; use its fully qualified name: " +
                "dev.example.chat.AntiReadReceipts, dev.example.other.AntiReadReceipts",
            error.message,
        )
        assertEquals(
            listOf(duplicate),
            selectDexTestEntries(
                ambiguousEntries,
                listOf("dev.example.other.AntiReadReceipts"),
            ),
        )
    }

    private fun entry(className: String) = DexResolutionTestEntry(className)
}
