package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CloudDexCacheWriterTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun writesStableV2ManifestsAndRemovesTransactionArtifacts() {
        val manifest = manifest("owner.First", listOf("z", "a"))
        writeCloudCacheFiles(tempDir, listOf(CloudDexCacheEntry("First/Feature", manifest)), 1234L)

        val text = tempDir.resolve("First_Feature.json").readText()
        val decoded = Json.decodeFromString<DexCacheManifest>(text)
        assertEquals(2, decoded.schema)
        assertEquals(1234L, decoded.timestamp)
        assertEquals(listOf("a", "z"), decoded.delegates.getValue("owner.First#target").dependencies)
        assertTrue(tempDir.listDirectoryEntries().none { it.fileName.toString().endsWith(".tmp") })
        assertTrue(tempDir.listDirectoryEntries().none { it.fileName.toString().endsWith(".bak") })
    }

    @Test
    fun failureMidCommitRestoresEveryPreviousDestination() {
        val first = tempDir.resolve("First.json")
        val second = tempDir.resolve("Second.json")
        first.writeText("old-first")
        second.writeText("old-second")
        var moves = 0

        assertThrows(IOException::class.java) {
            writeDexCacheManifests(
                tempDir,
                listOf(manifest("owner.First"), manifest("owner.Second")),
                mapOf("owner.First" to "First", "owner.Second" to "Second"),
            ) { source, destination ->
                moves++
                if (moves == 4) throw IOException("injected commit failure")
                Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        assertEquals("old-first", first.readText())
        assertEquals("old-second", second.readText())
        assertEquals(listOf("First.json", "Second.json"), tempDir.listDirectoryEntries().map { it.fileName.toString() }.sorted())
    }

    private fun manifest(owner: String, dependencies: List<String> = emptyList()) = DexCacheManifest(
        owner = owner,
        timestamp = 0,
        delegates = mapOf(
            "$owner#target" to DexCacheDelegateEntry(
                descriptor = "Ltarget;->call()V",
                status = DexResolutionStatus.SUCCESS,
                isPlaceholder = false,
                producerFingerprint = "local",
                effectiveFingerprint = "effective",
                dependencies = dependencies,
            ),
        ),
    )
}
