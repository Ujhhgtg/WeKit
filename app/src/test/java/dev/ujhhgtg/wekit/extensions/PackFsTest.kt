package dev.ujhhgtg.wekit.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PackFsTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `sha256 matches known digest`() {
        val file = temp.resolve("a.txt")
        file.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PackFs.sha256(file),
        )
    }

    @Test
    fun `verify accepts matching hash case-insensitively and rejects mismatch`() {
        val file = temp.resolve("a.txt")
        file.writeText("abc")
        val expected = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertTrue(PackFs.verify(file, expected))
        assertFalse(PackFs.verify(file, "0".repeat(64)))
    }

    @Test
    fun `atomicReplace publishes tmp and removes old target`() {
        val dir = temp.resolve("v1")
        dir.mkdirs()
        val dst = dir.resolve("classes.dex")
        dst.writeText("old")
        val tmp = dir.resolve("download.tmp")
        tmp.writeText("new")
        PackFs.atomicReplace(tmp, dst)
        assertEquals("new", dst.readText())
        assertFalse(tmp.exists())
    }

    @Test
    fun `manifest roundtrip and missing manifest`() {
        val dir = temp.resolve("script-deps")
        dir.mkdirs()
        assertNull(PackFs.readManifest(dir))
        val manifest = PackManifest("script-deps", "20260818-abc123def456", "a".repeat(64), 123L)
        PackFs.writeManifest(dir, manifest)
        assertEquals(manifest, PackFs.readManifest(dir))
    }
}
