package dev.ujhhgtg.wekit.loader.environment

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EmbeddedOriginApkTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun extractsFirstOriginAndReusesIdenticalContentsAcrossHostChanges() {
        val original = zip("classes.dex" to ByteArray(150_000) { (it % 251).toByte() })
        val host = File(directory, "host.apk").apply {
            writeBytes(zip(
                "assets/origin.apk" to zip("classes.dex" to "third".toByteArray()),
                "assets/npatch/origin.apk" to zip("classes.dex" to "second".toByteArray()),
                "assets/lspatch/origin.apk" to original,
            ))
        }
        val cache = File(directory, "cache")
        val first = EmbeddedOriginApk.findAndExtract(host, cache)!!
        assertEquals("assets/lspatch/origin.apk", first.assetPath)
        assertArrayEquals(original, first.file.readBytes())
        ZipFile(first.file).use { nested ->
            assertEquals(150_000, nested.getInputStream(nested.getEntry("classes.dex")).use { it.readBytes().size })
        }
        assertTrue(first.file.setLastModified(42_000))
        host.writeBytes(zip("assets/npatch/origin.apk" to original))
        val reused = EmbeddedOriginApk.findAndExtract(host, cache)!!
        assertEquals("assets/npatch/origin.apk", reused.assetPath)
        assertEquals(first.file, reused.file)
        assertEquals(42_000L, reused.file.lastModified())
        assertEquals(listOf(first.file.name), cache.list()!!.toList())
    }

    @Test
    fun missingOriginDoesNotCreateCache() {
        val host = File(directory, "host.apk").apply {
            writeBytes(zip("classes.dex" to "host".toByteArray()))
        }
        val cache = File(directory, "absent/cache")
        assertNull(EmbeddedOriginApk.findAndExtract(host, cache))
        assertFalse(cache.exists())
    }

    @Test
    fun repairsTruncatedCacheAndSeparatesChangedOriginContents() {
        val host = File(directory, "host.apk")
        val cache = File(directory, "cache")
        val original = zip("classes.dex" to "original dex".toByteArray())
        host.writeBytes(zip("assets/origin.apk" to original))
        val first = EmbeddedOriginApk.findAndExtract(host, cache)!!
        first.file.writeBytes(original.copyOf(17))
        assertArrayEquals(original, EmbeddedOriginApk.findAndExtract(host, cache)!!.file.readBytes())
        val updated = zip("classes.dex" to "new original dex".toByteArray())
        host.writeBytes(zip("assets/origin.apk" to updated))
        val second = EmbeddedOriginApk.findAndExtract(host, cache)!!
        assertNotEquals(first.file, second.file)
        assertArrayEquals(updated, second.file.readBytes())
        assertTrue(cache.list()!!.all { it.endsWith(".apk") })
    }

    @Test
    fun invalidFirstOriginThrowsWithoutPublishingOrFallingBack() {
        val host = File(directory, "host.apk").apply {
            writeBytes(zip(
                "assets/lspatch/origin.apk" to "invalid original APK".toByteArray(),
                "assets/npatch/origin.apk" to zip("classes.dex" to "valid".toByteArray()),
            ))
        }
        val cache = File(directory, "cache")
        assertThrows(IOException::class.java) { EmbeddedOriginApk.findAndExtract(host, cache) }
        assertTrue(cache.list()!!.isEmpty())
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        buffer.toByteArray()
    }
}
