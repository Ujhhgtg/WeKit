package dev.ujhhgtg.wekit.agent.environment

import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

object ArchiveExtractor {
    data class Limits(
        val maxEntries: Int = 500_000,
        val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
        val maxTotalBytes: Long = 12L * 1024 * 1024 * 1024,
    )

    fun extractTarGz(input: InputStream, destination: Path, limits: Limits = Limits(), checkActive: () -> Unit = {}) {
        Files.createDirectories(destination)
        GZIPInputStream(input, BUFFER_SIZE).use { extractTar(it, destination, limits, checkActive) }
    }

    internal fun extractTar(input: InputStream, destination: Path, limits: Limits = Limits(), checkActive: () -> Unit = {}) {
        val root = destination.toAbsolutePath().normalize()
        var entries = 0
        var totalBytes = 0L
        var pax = emptyMap<String, String>()
        var longName: String? = null
        var longLink: String? = null
        val pendingHardLinks = mutableListOf<Pair<Path, String>>()
        val header = ByteArray(TAR_BLOCK)
        while (true) {
            checkActive()
            readFullyOrEof(input, header) ?: break
            if (header.all { it == 0.toByte() }) break
            verifyChecksum(header)
            entries++
            require(entries <= limits.maxEntries) { "archive has too many entries" }
            val rawName = string(header, 0, 100)
            val prefix = string(header, 345, 155)
            val name = pax["path"] ?: longName ?: listOf(prefix, rawName).filter(String::isNotEmpty).joinToString("/")
            val linkName = pax["linkpath"] ?: longLink ?: string(header, 157, 100)
            val size = number(header, 124, 12)
            require(size in 0..limits.maxEntryBytes) { "archive entry is too large: $name" }
            totalBytes += size
            require(totalBytes <= limits.maxTotalBytes) { "archive exceeds extracted size limit" }
            val type = header[156].toInt().toChar()
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                require(size <= MAX_METADATA_BYTES) { "archive metadata entry is too large" }
                val metadata = readBytes(input, size).decodeToString().trimEnd('\u0000', '\n')
                skipPadding(input, size, checkActive)
                when (type) {
                    'x', 'g' -> pax = parsePax(metadata)
                    'L' -> longName = metadata
                    'K' -> longLink = metadata
                }
                continue
            }
            val target = safePath(root, name)
            ensureSafeParent(root, target.parent)
            when (type) {
                '\u0000', '0', '7' -> {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        copyExact(input, output::write, size, checkActive)
                    }
                    setMode(target, number(header, 100, 8), directory = false)
                }
                '5' -> {
                    require(size == 0L) { "directory entry has data: $name" }
                    Files.createDirectories(target)
                    setMode(target, number(header, 100, 8), directory = true)
                }
                '2' -> {
                    require(size == 0L) { "symlink entry has data: $name" }
                    validateLink(root, target.parent, linkName)
                    Files.createDirectories(target.parent)
                    Files.createSymbolicLink(target, Path.of(linkName))
                }
                '1' -> {
                    require(size == 0L) { "hardlink entry has data: $name" }
                    validateLink(root, target.parent, linkName)
                    pendingHardLinks += target to linkName
                }
                else -> error("unsupported special archive entry type '$type': $name")
            }
            if (type != '\u0000' && type != '0' && type != '7') skipExact(input, size, checkActive)
            skipPadding(input, size, checkActive)
            pax = emptyMap()
            longName = null
            longLink = null
        }
        for ((target, link) in pendingHardLinks) {
            val source = resolveGuestLink(root, target.parent, link)
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "hardlink target is not a regular file: $link" }
            Files.createLink(target, source)
        }
    }

    private fun safePath(root: Path, name: String): Path {
        require(name.isNotEmpty() && !name.startsWith('/')) { "absolute or empty archive path: $name" }
        val relative = Path.of(name).normalize()
        require(!relative.startsWith("..")) { "archive path escapes destination: $name" }
        return root.resolve(relative).normalize().also { require(it.startsWith(root)) }
    }

    private fun ensureSafeParent(root: Path, parent: Path?) {
        var current = root
        val relative = root.relativize(parent ?: root)
        for (part in relative) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "archive entry traverses symlink: $current" }
        }
    }

    private fun validateLink(root: Path, parent: Path, link: String) {
        require(link.isNotEmpty()) { "empty archive link target" }
        resolveGuestLink(root, parent, link)
    }

    private fun resolveGuestLink(root: Path, parent: Path, link: String): Path {
        val value = Path.of(link)
        val resolved = if (value.isAbsolute) root.resolve(link.removePrefix("/")) else parent.resolve(value)
        return resolved.normalize().also { require(it.startsWith(root)) { "archive link escapes destination: $link" } }
    }

    private fun setMode(path: Path, mode: Long, directory: Boolean) {
        val permissions = mutableSetOf<PosixFilePermission>()
        val flags = arrayOf(
            0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE,
            0x40 to PosixFilePermission.OWNER_EXECUTE, 0x20 to PosixFilePermission.GROUP_READ,
            0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
            0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE,
            0x1 to PosixFilePermission.OTHERS_EXECUTE,
        )
        flags.filter { mode.toInt() and it.first != 0 }.mapTo(permissions) { it.second }
        if (permissions.isEmpty() && directory) permissions += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(path, permissions)
    }

    private fun verifyChecksum(header: ByteArray) {
        val expected = number(header, 148, 8)
        val actual = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 0xff }.toLong()
        require(actual == expected) { "invalid tar header checksum" }
    }

    private fun number(bytes: ByteArray, offset: Int, length: Int): Long {
        if (bytes[offset].toInt() and 0x80 != 0) {
            var result = (bytes[offset].toInt() and 0x7f).toLong()
            for (i in offset + 1 until offset + length) result = (result shl 8) or (bytes[i].toInt() and 0xff).toLong()
            return result
        }
        return string(bytes, offset, length).trim().ifEmpty { "0" }.toLong(8)
    }

    private fun string(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: offset + length
        return bytes.copyOfRange(offset, end).decodeToString()
    }

    private fun parsePax(value: String): Map<String, String> = buildMap {
        var position = 0
        while (position < value.length) {
            val space = value.indexOf(' ', position)
            require(space > position) { "invalid pax record" }
            val length = value.substring(position, space).toInt()
            val record = value.substring(space + 1, position + length).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) put(record.substring(0, equals), record.substring(equals + 1))
            position += length
        }
    }

    private fun readFullyOrEof(input: InputStream, bytes: ByteArray): Unit? {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return if (offset == 0) null else throw EOFException("truncated tar header")
            offset += count
        }
        return Unit
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        require(size <= Int.MAX_VALUE)
        return ByteArray(size.toInt()).also { readFullyOrEof(input, it) ?: throw EOFException("truncated archive entry") }
    }

    private fun copyExact(input: InputStream, write: (ByteArray, Int, Int) -> Unit, size: Long, checkActive: () -> Unit = {}) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            checkActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("truncated archive entry")
            write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipExact(input: InputStream, size: Long, checkActive: () -> Unit = {}) =
        copyExact(input, { _, _, _ -> }, size, checkActive)
    private fun skipPadding(input: InputStream, size: Long, checkActive: () -> Unit = {}) =
        skipExact(input, (TAR_BLOCK.toLong() - size % TAR_BLOCK) % TAR_BLOCK, checkActive)

    private const val TAR_BLOCK = 512
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_METADATA_BYTES = 1024 * 1024
}
