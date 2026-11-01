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
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Keep this object alive for the successful payload's entire process lifetime. */
class PreparedPayload(
    val apk: ParcelFileDescriptor,
    val dexBuffers: Array<ByteBuffer>,
    val nativeDirectory: File,
    val classLoader: InMemoryDexClassLoader,
    val apkSha256: String,
    private val archive: ZipFile,
) : Closeable {
    /**
     * Removes the plaintext libraries once the payload has loaded them. A deleted file stays mapped, but
     * the class loader's `findLibrary` stops resolving it, so any later `System.loadLibrary` from payload
     * code would throw. Each file is therefore replaced by an empty placeholder: ART returns early for a
     * library it already loaded under that path, so the placeholder is never handed to the linker.
     * This relies on ART behavior and needs a check on a device.
     */
    fun scrubNativeLibraries() {
        nativeDirectory.listFiles()?.forEach { file ->
            check(file.delete() && file.createNewFile()) { "cannot scrub native library: $file" }
        }
    }

    /** Only for a failed startup; never close the input of an active payload. */
    override fun close() {
        try {
            archive.close()
        } finally {
            try {
                apk.close()
            } finally {
                nativeDirectory.deleteRecursively()
            }
        }
    }
}

object PayloadLoader {
    private const val MAX_ZIP_ENTRIES = 100_000
    private const val MAX_EXPANDED_SIZE = 256L * 1024 * 1024
    private const val MAX_DEX_SIZE = 128L * 1024 * 1024
    private const val MAX_NATIVE_SIZE = 64L * 1024 * 1024
    private const val ABI = "arm64-v8a"
    private const val NATIVE_PREFIX = "proc-"
    private const val EMBEDDED_PAYLOAD_ASSET = "assets/wekit/payload.apk"

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
        val writableApk = ParcelFileDescriptor.adoptFd(LoaderNatives.create())
        val apk = writableApk.use {
            LoaderNatives.decryptPayload(
                cache.fd, ciphertextOffset, it.fd, manifest.apkSize,
                manifest.chunkCount, manifest.chunkSize, manifest.noncePrefix,
                manifest.payloadId, manifest.apkSha256, manifest.cipherSha256, contentKey,
            )
            LoaderNatives.seal(it.fd)
            // F_SEAL_WRITE rejects AssetManager's shared mapping through the original
            // O_RDWR open file description. Reopen read-only; dup() would retain O_RDWR.
            ParcelFileDescriptor.open(File("/proc/self/fd/" + it.fd), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        try {
            return prepareArchive(apk, manifest.apkSha256, nativeRoot, parent)
        } catch (error: Throwable) {
            apk.close()
            throw error
        }
    }

    /** Streams the release payload embedded in this bootstrap APK into a sealed, read-only file. */
    fun prepareEmbedded(
        bootstrapApk: File,
        nativeRoot: File,
        parent: ClassLoader,
    ): PreparedPayload {
        val (apk, apkSha256) = ZipFile(bootstrapApk).use { archive ->
            val matches = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name == EMBEDDED_PAYLOAD_ASSET }
                .toList()
            require(matches.size == 1) {
                "bootstrap must contain exactly one $EMBEDDED_PAYLOAD_ASSET"
            }
            val entry = matches.single()
            require(entry.size in 1..PayloadDescriptor.MAX_APK_SIZE) { "embedded payload size is invalid" }
            val writableApk = ParcelFileDescriptor.adoptFd(LoaderNatives.create())
            writableApk.use { writable ->
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                ParcelFileDescriptor.AutoCloseOutputStream(
                    ParcelFileDescriptor.dup(writable.fileDescriptor)
                ).use { output ->
                    archive.getInputStream(entry).use { input ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(bytes)
                            if (count == -1) break
                            copied += count
                            check(copied <= entry.size) { "embedded payload exceeds its ZIP entry size" }
                            digest.update(bytes, 0, count)
                            output.write(bytes, 0, count)
                        }
                    }
                    output.flush()
                }
                check(copied == entry.size) { "embedded payload is truncated" }
                LoaderNatives.seal(writable.fd)
                val readOnly = ParcelFileDescriptor.open(
                    File("/proc/self/fd/${writable.fd}"),
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                readOnly to digest.digest().toHex()
            }
        }
        return try {
            prepareArchive(apk, apkSha256, nativeRoot, parent)
        } catch (error: Throwable) {
            apk.close()
            throw error
        }
    }

    private fun prepareArchive(
        apk: ParcelFileDescriptor,
        apkSha256: String,
        nativeRoot: File,
        parent: ClassLoader,
    ): PreparedPayload {
        val archive = ZipFile("/proc/self/fd/${apk.fd}")
        try {
            val entries = validateArchive(archive)
            val dex = readDex(archive, entries)
            sweepNativeRoot(nativeRoot)
            // Each process owns its directory: the libraries are scrubbed after loading, so a shared
            // directory would let one process delete a file another is about to load.
            val directory = File(nativeRoot, "$NATIVE_PREFIX${Os.getpid()}-${UUID.randomUUID()}")
            try {
                extractNative(archive, entries, directory)
            } catch (error: Throwable) {
                directory.deleteRecursively()
                throw error
            }
            // R8 deliberately minifies the payload and the bootstrap independently. Both
            // APKs therefore contain short names such as `ix`, `lx`, and `et2`. A normal
            // parent-first InMemoryDexClassLoader would resolve those payload references to
            // an unrelated bootstrap class and ART rejects the merged type graph with a
            // VerifyError. The filtered parent exposes only platform and explicitly shared
            // runtime classes; every other class must come from the payload DEX itself.
            val resourceParent = ApkResourceClassLoader(
                PayloadParentClassLoader(parent), archive, apkSha256,
            )
            val loader = try {
                InMemoryDexClassLoader(dex, directory.absolutePath, resourceParent)
            } catch (error: Throwable) {
                directory.deleteRecursively()
                throw error
            }
            return PreparedPayload(apk, dex, directory, loader, apkSha256, archive)
        } catch (error: Throwable) {
            archive.close()
            throw error
        }
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

    /**
     * Drops directories left by processes that died before scrubbing, plus the shared per-release
     * directories earlier builds kept. A live process's directory is left alone.
     */
    private fun sweepNativeRoot(root: File) {
        for (child in root.listFiles().orEmpty()) {
            val owner = if (child.name.startsWith(NATIVE_PREFIX)) {
                child.name.removePrefix(NATIVE_PREFIX).substringBefore('-').toIntOrNull()
            } else null
            if (owner == null || !File("/proc/$owner").exists()) child.deleteRecursively()
        }
    }

    private fun extractNative(archive: ZipFile, entries: List<ZipEntry>, directory: File) {
        require(directory.mkdirs()) { "cannot create native directory: $directory" }
        val prefix = "lib/$ABI/"
        val libraries = entries.filter { !it.isDirectory && it.name.startsWith(prefix) }
        require(libraries.isNotEmpty()) { "payload has no $ABI native libraries" }
        require(libraries.sumOf { it.size } <= MAX_NATIVE_SIZE) { "APK native size exceeds the limit" }
        for (entry in libraries) {
            val name = entry.name.removePrefix(prefix)
            require('/' !in name && name.startsWith("lib") && name.endsWith(".so")) { "unexpected native entry: ${entry.name}" }
            val target = File(directory, name)
            val crc = CRC32()
            var size = 0L
            archive.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output ->
                    val bytes = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(bytes)
                        if (count == -1) break
                        size += count
                        check(size <= entry.size) { "oversized native entry: ${entry.name}" }
                        crc.update(bytes, 0, count)
                        output.write(bytes, 0, count)
                    }
                }
            }
            check(size == entry.size && crc.value == entry.crc) { "native entry CRC/length mismatch: ${entry.name}" }
            check(target.setReadable(true, true) && target.setExecutable(true, true) && target.setWritable(false, false)) {
                "cannot protect native library: $target"
            }
        }
    }

}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

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
            "dev/ujhhgtg/reflekt/",
        )
    }
}
