package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.utils.fs.asPath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        assertEquals("/bin/tool".asPath, Files.readSymbolicLink(root.resolve("tool-link")))
    }

    @Test
    fun `extracts children before applying a read only directory mode`(@TempDir root: Path) {
        val archive = tar(
            entry("certificates/", type = '5', mode = 365),
            entry("certificates/root.pem", "certificate".toByteArray()),
        )

        ArchiveExtractor.extractTar(ByteArrayInputStream(archive), root)

        assertEquals("certificate", Files.readString(root.resolve("certificates/root.pem")))
        assertEquals(
            PosixFilePermissions.fromString("r-xr-xr-x"),
            Files.getPosixFilePermissions(root.resolve("certificates")),
        )
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

    @Test
    fun `pax lengths use raw utf8 bytes and preserve the final record`(@TempDir root: Path) {
        val path = "目录/文件.txt"
        val metadata = paxRecord("comment", "多字节") + paxRecord("path", path)
        val archive = tar(
            entry("pax", metadata, type = 'x'),
            entry("ignored", "content".toByteArray()),
        )

        ArchiveExtractor.extractTar(ByteArrayInputStream(archive), root)

        assertEquals("content", Files.readString(root.resolve(path)))
    }

    @Test
    fun `pax path and linkpath override tar header fields`(@TempDir root: Path) {
        val metadata = paxRecord("path", "links/tool") + paxRecord("linkpath", "/bin/目标")
        val archive = tar(
            entry("bin/目标", "ok".toByteArray()),
            entry("pax", metadata, type = 'x'),
            entry("ignored", type = '2', link = "ignored-target"),
        )

        ArchiveExtractor.extractTar(ByteArrayInputStream(archive), root)

        assertEquals("/bin/目标".asPath, Files.readSymbolicLink(root.resolve("links/tool")))
    }

    @Test
    fun `ignores binary values of unhandled pax attributes`(@TempDir root: Path) {
        val metadata = paxRecord("SCHILY.xattr.security.capability", byteArrayOf(0x80.toByte()))
        val archive = tar(
            entry("pax", metadata, type = 'x'),
            entry("bin/tool", "ok".toByteArray()),
        )

        ArchiveExtractor.extractTar(ByteArrayInputStream(archive), root)

        assertEquals("ok", Files.readString(root.resolve("bin/tool")))
    }

    @Test
    fun `rejects binary values of path pax attributes`(@TempDir root: Path) {
        val metadata = paxRecord("path", byteArrayOf(0x80.toByte()))

        assertThrows(MalformedInputException::class.java) {
            ArchiveExtractor.extractTar(
                ByteArrayInputStream(tar(entry("pax", metadata, type = 'x'))),
                root,
            )
        }
    }

    @Test
    fun `rejects malformed pax records safely`(@TempDir root: Path) {
        val malformed = listOf(
            "20 path=short\n".toByteArray(),
            "12 path=x".toByteArray(),
            "9 missing\n".toByteArray(),
        )
        malformed.forEachIndexed { index, metadata ->
            assertThrows(IllegalArgumentException::class.java) {
                ArchiveExtractor.extractTar(
                    ByteArrayInputStream(tar(entry("pax", metadata, type = 'x'))),
                    root.resolve("malformed-$index"),
                )
            }
        }
    }

    @Test
    fun `hardlinks are archive root relative and may target a later regular file`(@TempDir root: Path) {
        val archive = tar(
            entry("usr/bin/tool-link", type = '1', link = "opt/tool"),
            entry("opt/tool", "ok".toByteArray(), mode = 493),
        )

        ArchiveExtractor.extractTar(ByteArrayInputStream(archive), root)

        assertEquals("ok", Files.readString(root.resolve("usr/bin/tool-link")))
        assertNotEquals(Files.getAttribute(root.resolve("opt/tool"), "unix:ino"), Files.getAttribute(root.resolve("usr/bin/tool-link"), "unix:ino"))
        assertEquals(
            PosixFilePermissions.fromString("rwxr-xr-x"),
            Files.getPosixFilePermissions(root.resolve("usr/bin/tool-link")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveExtractor.extractTar(
                ByteArrayInputStream(tar(entry("bad", type = '1', link = "../outside"))),
                root.resolve("bad-hardlink"),
            )
        }
    }

    @Test
    fun `instance installer removes staging after extraction failure`(@TempDir root: Path) = runBlocking {
        val archive = root.resolve("broken.tar.gz").toFile().apply { writeText("not gzip") }
        val proot = executable(root, "proot")
        val loader = executable(root, "loader")
        val bridge = executable(root, "bridge")
        val instances = root.resolve("instances").toFile()

        assertThrows(Exception::class.java) {
            runBlocking {
                ArchLinuxInstanceInstaller.install(
                    "failed", "version", archive, proot, loader, bridge, instances, 1024 * 1024,
                )
            }
        }

        assertFalse(File(instances, "failed").exists())
        assertEquals(emptyList<String>(), instances.listFiles().orEmpty().map(File::getName))
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

    private fun paxRecord(key: String, value: String): ByteArray = paxRecord(key, value.toByteArray())

    private fun paxRecord(key: String, value: ByteArray): ByteArray {
        val body = "$key=".toByteArray() + value + byteArrayOf('\n'.code.toByte())
        var length = body.size + 2
        while (true) {
            val adjusted = body.size + length.toString().length + 1
            if (adjusted == length) return "$length ".toByteArray() + body
            length = adjusted
        }
    }

    private fun executable(root: Path, name: String): File = root.resolve(name).toFile().apply {
        writeText("test")
        setExecutable(true)
    }

    private fun put(target: ByteArray, offset: Int, length: Int, value: String) {
        value.toByteArray().also { System.arraycopy(it, 0, target, offset, minOf(it.size, length)) }
    }

    private fun octal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val encoded = value.toString(8).padStart(length - 2, '0') + "\u0000 "
        put(target, offset, length, encoded)
    }
}
