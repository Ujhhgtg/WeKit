package dev.ujhhgtg.wekit.agent.environment

import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.copyTo
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.outputStream
import kotlin.io.path.setPosixFilePermissions
import dev.ujhhgtg.wekit.utils.fs.asPath
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
        destination.createDirectories()
        GZIPInputStream(input, BUFFER_SIZE).use { extractTar(it, destination, limits, checkActive) }
    }

    internal fun extractTar(input: InputStream, destination: Path, limits: Limits = Limits(), checkActive: () -> Unit = {}) {
        val root = destination.toAbsolutePath().normalize()
        var entries = 0
        var totalBytes = 0L
        var globalPax = emptyMap<String, String>()
        var pax = emptyMap<String, String>()
        var longName: String? = null
        var longLink: String? = null
        val pendingHardLinks = mutableListOf<Pair<Path, String>>()
        val directoryModes = mutableMapOf<Path, Long>()
        val regularFiles = mutableSetOf<Path>()
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
            val attributes = globalPax + pax
            val name = attributes["path"] ?: longName ?: listOf(prefix, rawName).filter(String::isNotEmpty).joinToString("/")
            val linkName = attributes["linkpath"] ?: longLink ?: string(header, 157, 100)
            val size = number(header, 124, 12)
            require(size in 0..limits.maxEntryBytes) { "archive entry is too large: $name" }
            totalBytes += size
            require(totalBytes <= limits.maxTotalBytes) { "archive exceeds extracted size limit" }
            val type = header[156].toInt().toChar()
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                require(size <= MAX_METADATA_BYTES) { "archive metadata entry is too large" }
                val metadata = readBytes(input, size)
                skipPadding(input, size, checkActive)
                when (type) {
                    'x' -> pax = parsePax(metadata)
                    'g' -> globalPax = globalPax + parsePax(metadata)
                    'L' -> longName = decodeUtf8(metadata).trimEnd('\u0000', '\n')
                    'K' -> longLink = decodeUtf8(metadata).trimEnd('\u0000', '\n')
                }
                continue
            }
            val target = safePath(root, name)
            ensureSafeParent(root, target.parent)
            when (type) {
                '\u0000', '0', '7' -> {
                    target.parent.createDirectories()
                    target.outputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        copyExact(input, output::write, size, checkActive)
                    }
                    setMode(target, number(header, 100, 8), directory = false)
                    regularFiles.add(target)
                }
                '5' -> {
                    require(size == 0L) { "directory entry has data: $name" }
                    target.createDirectories()
                    directoryModes[target] = number(header, 100, 8)
                }
                '2' -> {
                    require(size == 0L) { "symlink entry has data: $name" }
                    validateLink(root, target.parent, linkName)
                    target.parent.createDirectories()
                    target.createSymbolicLinkPointingTo(linkName.asPath)
                }
                '1' -> {
                    require(size == 0L) { "hardlink entry has data: $name" }
                    safePath(root, linkName)
                    target.parent.createDirectories()
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
            val source = safePath(root, link)
            require(source in regularFiles) { "hardlink target does not name an archive regular file: $link" }
            require(source.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "hardlink target is not a regular file: $link" }
            source.copyTo(target)
            target.setPosixFilePermissions(source.getPosixFilePermissions(LinkOption.NOFOLLOW_LINKS))
        }
        directoryModes.entries.sortedByDescending { it.key.nameCount }.forEach { (path, mode) ->
            setMode(path, mode, directory = true)
        }
    }

    private fun safePath(root: Path, name: String): Path {
        require(name.isNotEmpty() && !name.startsWith('/')) { "absolute or empty archive path: $name" }
        val relative = name.asPath.normalize()
        require(!relative.startsWith("..")) { "archive path escapes destination: $name" }
        return root.resolve(relative).normalize().also { require(it.startsWith(root)) }
    }

    private fun ensureSafeParent(root: Path, parent: Path?) {
        var current = root
        val relative = root.relativize(parent ?: root)
        for (part in relative) {
            current = current.resolve(part)
            require(!current.isSymbolicLink()) { "archive entry traverses symlink: $current" }
        }
    }

    private fun validateLink(root: Path, parent: Path, link: String) {
        require(link.isNotEmpty()) { "empty archive link target" }
        resolveGuestLink(root, parent, link)
    }

    private fun resolveGuestLink(root: Path, parent: Path, link: String): Path {
        val value = link.asPath
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
        path.setPosixFilePermissions(permissions)
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

    private fun parsePax(value: ByteArray): Map<String, String> = buildMap {
        var position = 0
        while (position < value.size) {
            var space = position
            while (space < value.size && value[space] != ' '.code.toByte()) space++
            require(space > position) { "invalid pax record" }
            require(space < value.size) { "invalid pax record length" }
            val lengthText = value.copyOfRange(position, space).decodeToString()
            require(lengthText.all(Char::isDigit)) { "invalid pax record length" }
            val length = lengthText.toIntOrNull() ?: throw IllegalArgumentException("invalid pax record length")
            require(length > space - position + 2 && length <= value.size - position) { "invalid pax record length" }
            val end = position + length
            require(value[end - 1] == '\n'.code.toByte()) { "pax record is not newline terminated" }
            val body = value.copyOfRange(space + 1, end - 1)
            val equals = body.indexOf('='.code.toByte())
            require(equals > 0) { "invalid pax record" }
            val key = decodeUtf8(body.copyOfRange(0, equals))
            if (key == "path" || key == "linkpath") {
                put(key, decodeUtf8(body.copyOfRange(equals + 1, body.size)))
            }
            position = end
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

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
