package dev.ujhhgtg.wekit.features.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureMetadataRegistryTest {
    @Test
    fun registryContainsEveryLegacyFeatureWithItsStableIdentity() {
        val entries = FeatureMetadataRegistry.ALL
        assertTrue(entries.isNotEmpty())
        assertEquals(entries.size, entries.map { it.className }.distinct().size)
        assertEquals(entries.size, entries.map { it.technicalId }.distinct().size)
        assertTrue(entries.any { it.className.endsWith("DisableTypingStatusUploading") })
        assertTrue(entries.any { it.className.endsWith("AntiMomentCommentsDelete") })

        val expectedIds = javaClass.getResourceAsStream("/feature-identity-compatibility.tsv")!!
            .bufferedReader()
            .useLines { lines ->
                lines.associate { line ->
                    val (className, legacyId) = line.split('\t', limit = 2)
                    className to legacyId
                }
            }

        assertEquals(expectedIds.size, entries.size)
        assertEquals(
            expectedIds,
            entries.associate { it.className to it.technicalId },
        )
    }
}
