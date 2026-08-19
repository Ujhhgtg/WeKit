package dev.ujhhgtg.wekit.agent.environment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ArchiveExtractorTest {
    @Test
    fun `streams files modes and symlinks`(@TempDir root: Path) {
        val archive = tar(
            entry("bin/", type = '5', mode = 493),
            entry("bin/tool", "hello".toByteArray(), mode = 493),
            entry("tool-link", type = '2', link = "/bin/tool"),
        )
        ArchiveExtractor.extractTar(ByteArrayInputStream(archive), root)
        assertEquals("hello", Files.readString(root.resolve("bin/tool")))
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(root.resolve("bin/tool")))
        assertEquals(Path.of("/bin/tool"), Files.readSymbolicLink(root.resolve("tool-link")))
    }

    @Test
    fun `rejects traversal absolute links special files and size limits`(@TempDir root: Path) {
        listOf(
            entry("../escape", byteArrayOf()),
            entry("escape", type = '2', link = "../../outside"),
            entry("device", type = '3'),
        ).forEach { unsafe ->
            assertThrows(Exception::class.java) {
                ArchiveExtractor.extractTar(ByteArrayInputStream(tar(unsafe)), root.resolve(Files.createTempDirectory(root, "case-").fileName))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveExtractor.extractTar(ByteArrayInputStream(tar(entry("large", "12345".toByteArray()))), root.resolve("limited"), ArchiveExtractor.Limits(maxEntryBytes = 4))
        }
    }

    @Test
    fun `does not follow a symlink planted by an earlier entry`(@TempDir root: Path) {
        val archive = tar(entry("dir", type = '2', link = "/tmp"), entry("dir/file", "bad".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveExtractor.extractTar(ByteArrayInputStream(archive), root)
        }
    }

    private fun entry(name: String, data: ByteArray = byteArrayOf(), type: Char = '0', link: String = "", mode: Int = 420): ByteArray {
        val header = ByteArray(512)
        put(header, 0, 100, name)
        octal(header, 100, 8, mode.toLong())
        octal(header, 108, 8, 0); octal(header, 116, 8, 0)
        octal(header, 124, 12, data.size.toLong()); octal(header, 136, 12, 0)
        for (i in 148..155) header[i] = 32
        header[156] = type.code.toByte()
        put(header, 157, 100, link)
        put(header, 257, 6, "ustar")
        val checksum = header.sumOf { it.toInt() and 0xff }
        octal(header, 148, 8, checksum.toLong())
        return ByteArrayOutputStream().apply {
            write(header); write(data); write(ByteArray((512 - data.size % 512) % 512))
        }.toByteArray()
    }

    private fun tar(vararg entries: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        entries.forEach(::write); write(ByteArray(1024))
    }.toByteArray()

    private fun put(target: ByteArray, offset: Int, length: Int, value: String) {
        value.toByteArray().also { System.arraycopy(it, 0, target, offset, minOf(it.size, length)) }
    }

    private fun octal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val encoded = value.toString(8).padStart(length - 2, '0') + "\u0000 "
        put(target, offset, length, encoded)
    }
}
