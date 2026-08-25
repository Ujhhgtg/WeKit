package dev.ujhhgtg.wekit.dexkit.cache

import kotlin.io.path.writeText
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CloudDexCacheWriterTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun writesCompleteCacheFilesAndRemovesTransactionArtifacts() {
        writeCloudCacheFiles(
            cacheDir = tempDir,
            entries = listOf(
                CloudDexCacheEntry("First/Feature", "first-hash", mapOf("First:key" to "first-value")),
                CloudDexCacheEntry("SecondFeature", "second-hash", mapOf("Second:key" to "second-value")),
            ),
            timestamp = 1234L,
        )

        assertCache(
            path = tempDir.resolve("First_Feature.json"),
            methodHash = "first-hash",
            timestamp = "1234",
            key = "First:key",
            value = "first-value",
        )
        assertCache(
            path = tempDir.resolve("SecondFeature.json"),
            methodHash = "second-hash",
            timestamp = "1234",
            key = "Second:key",
            value = "second-value",
        )
        assertTrue(tempDir.listDirectoryEntries().none { it.fileName.toString().endsWith(".tmp") })
        assertTrue(tempDir.listDirectoryEntries().none { it.fileName.toString().endsWith(".bak") })
    }

    @Test
    fun replacesAnExistingCacheWithOneCompleteJsonObject() {
        val destination = tempDir.resolve("FirstFeature.json")
        destination.writeText("old-cache")

        writeCloudCacheFiles(
            cacheDir = tempDir,
            entries = listOf(
                CloudDexCacheEntry("FirstFeature", "new-hash", mapOf("First:key" to "new-value")),
            ),
            timestamp = 5678L,
        )

        assertCache(destination, "new-hash", "5678", "First:key", "new-value")
        assertEquals(listOf("FirstFeature.json"), tempDir.listDirectoryEntries().map { it.fileName.toString() })
    }

    private fun assertCache(
        path: Path,
        methodHash: String,
        timestamp: String,
        key: String,
        value: String,
    ) {
        val json = Json.parseToJsonElement(path.readText()).jsonObject
        assertEquals(methodHash, json.getValue("methodHash").jsonPrimitive.content)
        assertEquals(timestamp, json.getValue("timestamp").jsonPrimitive.content)
        assertEquals(value, json.getValue(key).jsonPrimitive.content)
    }
}
