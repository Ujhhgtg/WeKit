package dev.ujhhgtg.wekit.bootstrap.payload

import android.os.ParcelFileDescriptor
import android.system.Os
import dalvik.system.InMemoryDexClassLoader
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Enumeration
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Keep this object alive for the successful payload's entire process lifetime. */
class PreparedPayload(
    val apk: ParcelFileDescriptor,
    val dexBuffers: Array<ByteBuffer>,
    val nativeDirectory: File,
    val classLoader: InMemoryDexClassLoader,
    private val archive: ZipFile,
) : Closeable {
    /** Only for a failed startup; never close the input of an active payload. */
    override fun close() {
        try {
            archive.close()
        } finally {
            apk.close()
        }
    }
}

object PayloadLoader {
    private const val MAX_ZIP_ENTRIES = 100_000
    private const val MAX_EXPANDED_SIZE = 256L * 1024 * 1024
    private const val MAX_DEX_SIZE = 128L * 1024 * 1024
    private const val MAX_NATIVE_SIZE = 64L * 1024 * 1024
    private const val ABI = "arm64-v8a"

    /** The caller has verified the envelope; this method never resolves or reopens the cache name. */
    fun prepare(
        cache: ParcelFileDescriptor,
        ciphertextOffset: Long,
        manifest: PayloadDescriptor,
        contentKey: ByteArray,
        nativeRoot: File,
        parent: ClassLoader,
    ): PreparedPayload {
        require(contentKey.size == 32)
        require(ciphertextOffset in 12..(12L + 64 * 1024))
        require(Os.fstat(cache.fileDescriptor).st_size == ciphertextOffset + manifest.cipherSize) {
            "cache length does not match the signed release"
        }
        val writableApk = ParcelFileDescriptor.adoptFd(MemoryApkNative.create())
        val apk = writableApk.use {
            decrypt(cache, ciphertextOffset, manifest, contentKey, it)
            MemoryApkNative.seal(it.fd)
            // F_SEAL_WRITE rejects AssetManager's shared mapping through the original
            // O_RDWR open file description. Reopen read-only; dup() would retain O_RDWR.
            ParcelFileDescriptor.open(File("/proc/self/fd/" + it.fd), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        try {
            val archive = ZipFile("/proc/self/fd/${apk.fd}")
            try {
                val entries = validateArchive(archive)
                val dex = readDex(archive, entries)
                val directory = File(nativeRoot, "${manifest.apkSha256}/$ABI")
                extractNative(archive, entries, directory)
                // R8 deliberately minifies the payload and the bootstrap independently. Both
                // APKs therefore contain short names such as `ix`, `lx`, and `et2`. A normal
                // parent-first InMemoryDexClassLoader would resolve those payload references to
                // an unrelated bootstrap class and ART rejects the merged type graph with a
                // VerifyError. The filtered parent exposes only platform and explicitly shared
                // runtime classes; every other class must come from the payload DEX itself.
                val resourceParent = ApkResourceClassLoader(
                    PayloadParentClassLoader(parent), archive, manifest.apkSha256,
                )
                val loader = InMemoryDexClassLoader(dex, directory.absolutePath, resourceParent)
                return PreparedPayload(apk, dex, directory, loader, archive)
            } catch (error: Throwable) {
                archive.close()
                throw error
            }
        } catch (error: Throwable) {
            apk.close()
            throw error
        }
    }

    private fun decrypt(
        cache: ParcelFileDescriptor,
        offset: Long,
        manifest: PayloadDescriptor,
        key: ByteArray,
        apk: ParcelFileDescriptor,
    ) {
        val input = object : InputStream() {
            private var readOffset = offset
            private val end = offset + manifest.cipherSize
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                if (length == 0) return 0
                if (readOffset == end) return -1
                val count = Os.pread(cache.fileDescriptor, bytes, start,
                    minOf(length.toLong(), end - readOffset).toInt(), readOffset)
                if (count == 0) return -1
                readOffset += count
                return count
            }
        }
        var apkOffset = 0L
        PayloadCipher.decrypt(input, manifest, key) { plaintext ->
            var written = 0
            while (written < plaintext.size) {
                val count = Os.pwrite(apk.fileDescriptor, plaintext, written, plaintext.size - written, apkOffset + written)
                check(count > 0) { "short write to memory APK" }
                written += count
            }
            apkOffset += plaintext.size
        }
        check(Os.fstat(apk.fileDescriptor).st_size == manifest.apkSize)
    }

    private fun validateArchive(archive: ZipFile): List<ZipEntry> {
        val names = HashSet<String>()
        val entries = ArrayList<ZipEntry>()
        var expanded = 0L
        val enumeration = archive.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            val name = entry.name
            require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name)
            val components = name.removeSuffix("/").split('/')
            require(components.none { it.isEmpty() || it == "." || it == ".." }) { "unsafe ZIP entry: $name" }
            require(names.add(name)) { "duplicate ZIP entry: $name" }
            require(entries.size < MAX_ZIP_ENTRIES) { "too many APK ZIP entries" }
            require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED)
            require(entry.size in 0..PayloadDescriptor.MAX_APK_SIZE && entry.compressedSize >= 0 && entry.crc >= 0)
            expanded += entry.size
            require(expanded <= MAX_EXPANDED_SIZE) { "APK expanded size exceeds the limit" }
            entries += entry
        }
        for (name in listOf("AndroidManifest.xml", "resources.arsc")) {
            require(entries.any { it.name == name && !it.isDirectory && it.size > 0 }) { "APK is missing $name" }
        }
        return entries
    }

    private fun readDex(archive: ZipFile, entries: List<ZipEntry>): Array<ByteBuffer> {
        val pattern = Regex("classes([0-9]*)\\.dex")
        val dexEntries = entries.mapNotNull { entry ->
            val match = pattern.matchEntire(entry.name) ?: return@mapNotNull null
            val suffix = match.groupValues[1]
            val index = if (suffix.isEmpty()) 1 else {
                val parsed = suffix.toIntOrNull() ?: error("invalid DEX entry: ${entry.name}")
                require(parsed >= 2 && suffix == parsed.toString()) { "invalid DEX entry: ${entry.name}" }
                parsed
            }
            require(!entry.isDirectory && entry.size >= 112) { "invalid DEX entry: ${entry.name}" }
            index to entry
        }.sortedBy { it.first }
        require(dexEntries.isNotEmpty() && dexEntries.map { it.first } == (1..dexEntries.size).toList()) {
            "APK DEX entries are missing or not contiguous"
        }
        require(dexEntries.sumOf { it.second.size } <= MAX_DEX_SIZE) { "APK DEX size exceeds the limit" }
        return dexEntries.map { (_, entry) ->
            val bytes = archive.getInputStream(entry).use { input -> readEntry(input, entry) }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes).flip()
            bytes.fill(0)
            buffer.asReadOnlyBuffer()
        }.toTypedArray()
    }

    private fun readEntry(input: InputStream, entry: ZipEntry): ByteArray {
        val bytes = ByteArray(entry.size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read == -1) throw IOException("short APK entry: ${entry.name}")
            offset += read
        }
        check(input.read() == -1) { "oversized APK entry: ${entry.name}" }
        check(CRC32().apply { update(bytes) }.value == entry.crc) { "APK entry CRC mismatch: ${entry.name}" }
        return bytes
    }

    private fun extractNative(archive: ZipFile, entries: List<ZipEntry>, directory: File) {
        require(directory.isDirectory || directory.mkdirs()) { "cannot create native directory: $directory" }
        val prefix = "lib/$ABI/"
        val libraries = entries.filter { !it.isDirectory && it.name.startsWith(prefix) }
        require(libraries.isNotEmpty()) { "payload has no $ABI native libraries" }
        require(libraries.sumOf { it.size } <= MAX_NATIVE_SIZE) { "APK native size exceeds the limit" }
        for (entry in libraries) {
            val name = entry.name.removePrefix(prefix)
            require('/' !in name && name.startsWith("lib") && name.endsWith(".so")) { "unexpected native entry: ${entry.name}" }
            val target = File(directory, name)
            if (isValidNative(target, entry)) continue
            val temporary = File.createTempFile(".$name-", ".tmp", directory)
            try {
                val crc = CRC32()
                var size = 0L
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val bytes = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(bytes)
                            if (count == -1) break
                            size += count
                            check(size <= entry.size) { "oversized native entry: ${entry.name}" }
                            crc.update(bytes, 0, count)
                            output.write(bytes, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                check(size == entry.size && crc.value == entry.crc) { "native entry CRC/length mismatch: ${entry.name}" }
                check(temporary.setReadable(true, true) && temporary.setExecutable(true, true) && temporary.setWritable(false, false)) {
                    "cannot protect native library: $temporary"
                }
                // A concurrently published identical library is already complete; leave it in place.
                if (isValidNative(target, entry)) continue
                check(temporary.renameTo(target)) { "cannot publish native library: $target" }
            } finally {
                temporary.delete()
            }
        }
    }

    private fun isValidNative(file: File, entry: ZipEntry): Boolean {
        if (!file.isFile || file.length() != entry.size) return false
        val crc = CRC32()
        file.inputStream().use { input ->
            val bytes = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count == -1) break
                crc.update(bytes, 0, count)
            }
        }
        return crc.value == entry.crc
    }

}

/**
 * Parent for the private payload's DEX loader. Android's class loader is parent-first, so the
 * parent must refuse ordinary application/library names instead of exposing bootstrap's
 * independently minified Compose and coroutine classes. Shared runtime packages are pinned to
 * the bootstrap copy; platform classes remain delegated to the Android framework.
 */
private class PayloadParentClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val delegatedPrefixes = listOf(
        "java.", "javax.", "android.", "dalvik.", "sun.", "libcore.",
        "org.xmlpull.", "org.json.", "org.w3c.", "org.xml.sax.",
        "kotlin.",
        "dev.ujhhgtg.wekit.loader.abc.",
        "dev.ujhhgtg.wekit.loader.api.",
        "dev.ujhhgtg.wekit.bootstrap.",
        "dev.ujhhgtg.reflekt.",
        "org.luckypray.dexkit.",
        "com.google.flatbuffers.",
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (delegatedPrefixes.any(name::startsWith)) return super.loadClass(name, resolve)
        throw ClassNotFoundException(name)
    }
}

/** Adds only APK resources to the bootstrap parent; it never defines a class or a DEX path. */
class ApkResourceClassLoader(parent: ClassLoader, private val archive: ZipFile, private val sha256: String) : ClassLoader(parent) {
    override fun getResource(name: String): URL? = if (sharedResource(name)) {
        super.getResource(name)
    } else {
        findResource(name) ?: parent.getResource(name)
    }

    override fun getResources(name: String): Enumeration<URL> {
        val own = listOfNotNull(findResource(name))
        val inherited = parent.getResources(name).toList()
        val resources = if (sharedResource(name)) inherited + own else own + inherited
        return Collections.enumeration(resources.distinctBy(URL::toExternalForm))
    }

    override fun findResource(name: String): URL? {
        val entry = archive.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        return URL(null, "wekit-apk:$sha256!/$name", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
                override fun connect() { connected = true }
                override fun getInputStream(): InputStream = archive.getInputStream(entry)
                override fun getContent(): Any = getInputStream()
                override fun getContentLengthLong(): Long = entry.size
            }
        })
    }

    override fun findResources(name: String): Enumeration<URL> = Collections.enumeration(listOfNotNull(findResource(name)))

    private fun sharedResource(name: String): Boolean = SHARED_RESOURCES.any(name::startsWith)

    companion object {
        private val SHARED_RESOURCES = listOf(
            "java/", "javax/", "android/", "kotlin/",
            "dev/ujhhgtg/wekit/loader/api/", "dev/ujhhgtg/wekit/loader/abc/",
            "dev/ujhhgtg/reflekt/", "org/luckypray/dexkit/", "com/google/flatbuffers/",
        )
    }
}
