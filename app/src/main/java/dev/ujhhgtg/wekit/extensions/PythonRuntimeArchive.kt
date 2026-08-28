package dev.ujhhgtg.wekit.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

@Serializable
data class PythonRuntimeMetadata(
    val packId: String,
    val chaquopy: String,
    val agp: String,
    val gradle: String,
    val jdk: String,
    val python: String,
    val ndk: String,
    val abi: String,
    val patchRevision: String,
    val syncHookBudgetMs: Long,
    val taskDrainTimeoutMs: Long,
    val maxManifestBytes: Long,
    val maxPluginFileBytes: Long,
    val nativeLibraries: List<String>,
)

internal data class PythonRuntimeArchiveContents(
    val metadata: PythonRuntimeMetadata,
    val dexEntries: List<String>,
    val nativeEntries: List<String>,
)

internal object PythonRuntimeArchive {
    private const val METADATA_ENTRY = "assets/runtime-manifest.json"
    private const val BUILD_JSON_ENTRY = "assets/chaquopy/build.json"
    private const val SDK_ARCHIVE_ENTRY = "assets/chaquopy/app.imy"
    private const val NATIVE_PREFIX = "lib/arm64-v8a/"
    private const val ENTRYPOINT_DESCRIPTOR =
        "Ldev/ujhhgtg/wekit/python/runtime/RuntimeEntrypoint;"
    private val json = Json { ignoreUnknownKeys = true }
    private val dexName = Regex("classes(?:([2-9][0-9]*))?\\.dex")

    fun inspect(apk: File, expectedMeta: String?): PythonRuntimeArchiveContents {
        require(apk.isFile) { "Python runtime APK is missing: $apk" }
        requireNoSymlinkEntries(apk)
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = HashSet<String>(entries.size)
            entries.forEach { entry ->
                require(names.add(entry.name)) { "Duplicate runtime APK entry: ${entry.name}" }
                requireSafeName(entry.name)
            }

            require(names.contains(BUILD_JSON_ENTRY)) { "Python runtime APK is missing $BUILD_JSON_ENTRY" }
            require(names.contains(SDK_ARCHIVE_ENTRY)) { "Python runtime APK is missing $SDK_ARCHIVE_ENTRY" }
            val metadataEntry = zip.getEntry(METADATA_ENTRY)
                ?: error("Python runtime APK is missing $METADATA_ENTRY")
            val metadata = zip.getInputStream(metadataEntry).use { input ->
                json.decodeFromString<PythonRuntimeMetadata>(input.readBytes().decodeToString())
            }
            require(metadata.packId == PythonRuntimePack.ID) { "Unexpected runtime pack ID: ${metadata.packId}" }
            require(metadata.abi == PythonRuntimePack.ABI) { "Unsupported Python runtime ABI: ${metadata.abi}" }
            require(metadata.patchRevision.isNotBlank()) { "Python runtime patch revision is blank" }
            require(
                metadata.syncHookBudgetMs > 0 && metadata.taskDrainTimeoutMs > 0 &&
                    metadata.maxManifestBytes > 0 && metadata.maxPluginFileBytes > 0,
            ) { "Python runtime limits must be positive" }

            if (expectedMeta != null) {
                val expected = json.decodeFromString<PythonRuntimeMetadata>(expectedMeta)
                require(metadata == expected) {
                    "Runtime artifact metadata does not match the extension index"
                }
            }

            val dexEntries = entries.mapNotNull { entry ->
                val match = dexName.matchEntire(entry.name)
                if (match == null) {
                    require(!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) {
                        "Invalid runtime DEX name: ${entry.name}"
                    }
                    return@mapNotNull null
                }
                val index = match.groupValues[1].ifBlank { "1" }.toInt()
                index to entry.name
            }.sortedBy { it.first }
            require(dexEntries.isNotEmpty()) { "Python runtime APK contains no DEX" }
            dexEntries.forEachIndexed { index, (number, _) ->
                require(number == index + 1) { "Python runtime APK has a gap before classes${index + 1}.dex" }
            }
            require(dexEntries.any { (_, name) ->
                zip.getInputStream(zip.getEntry(name)).use { input ->
                    String(input.readBytes(), StandardCharsets.ISO_8859_1).contains(ENTRYPOINT_DESCRIPTOR)
                }
            }) { "Python runtime entrypoint is missing from DEX" }

            val allNativeEntries = entries.filter { !it.isDirectory && it.name.startsWith("lib/") }
            require(allNativeEntries.all { it.name.startsWith(NATIVE_PREFIX) }) {
                "Python runtime APK contains an unsupported ABI"
            }
            val declaredLibraries = metadata.nativeLibraries
            require(declaredLibraries.isNotEmpty()) { "Python runtime APK declares no native libraries" }
            declaredLibraries.forEach { name ->
                require(name.matches(Regex("lib[A-Za-z0-9_.+-]+\\.so"))) { "Invalid native library name: $name" }
                require(names.contains("$NATIVE_PREFIX$name")) { "Python runtime APK is missing native library $name" }
            }

            return PythonRuntimeArchiveContents(
                metadata = metadata.copy(nativeLibraries = declaredLibraries),
                dexEntries = dexEntries.map { it.second },
                nativeEntries = declaredLibraries.map { "$NATIVE_PREFIX$it" },
            )
        }
    }

    fun extractNativeLibraries(apk: File, contents: PythonRuntimeArchiveContents, destination: File) {
        destination.mkdirs()
        val canonicalDestination = destination.canonicalFile
        ZipFile(apk).use { zip ->
            contents.nativeEntries.forEach { name ->
                val entry = zip.getEntry(name) ?: error("Missing native library after validation: $name")
                val output = File(destination, name.substringAfterLast('/')).canonicalFile
                require(output.parentFile == canonicalDestination) { "Native library escaped destination: $name" }
                zip.getInputStream(entry).use { input -> output.outputStream().use(input::copyTo) }
                require(output.length() == entry.size) { "Native library extraction was incomplete: $name" }
                require(output.setExecutable(true, true)) { "Cannot mark native library executable: ${output.name}" }
            }
        }
    }

    fun extractSdk(apk: File, destination: File) {
        destination.mkdirs()
        val canonicalDestination = destination.canonicalFile
        ZipFile(apk).use { zip ->
            val archive = zip.getEntry(SDK_ARCHIVE_ENTRY) ?: error("Python runtime SDK archive is missing")
            ZipInputStream(zip.getInputStream(archive)).use { sdk ->
                while (true) {
                    val entry = sdk.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.startsWith("wekit/")) continue
                    requireSafeName(entry.name)
                    val output = File(destination, entry.name).canonicalFile
                    require(output.path.startsWith(canonicalDestination.path + File.separator)) {
                        "Python SDK entry escaped destination: ${entry.name}"
                    }
                    output.parentFile!!.mkdirs()
                    output.outputStream().use(sdk::copyTo)
                }
            }
        }
        require(
            File(destination, "wekit/__init__.py").isFile ||
                File(destination, "wekit/__init__.pyc").isFile,
        ) { "Python runtime SDK is incomplete" }
    }

    fun encodeMetadata(metadata: PythonRuntimeMetadata): String = json.encodeToString(metadata)

    private fun requireSafeName(name: String) {
        require(
            name.isNotBlank() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name &&
                !name.matches(Regex("^[A-Za-z]:/.*")),
        ) { "Unsafe runtime APK entry: $name" }
        require(name.split('/').none { it == "." || it == ".." }) { "Unsafe runtime APK entry: $name" }
    }

    private fun requireNoSymlinkEntries(apk: File) {
        RandomAccessFile(apk, "r").use { file ->
            val tailSize = minOf(file.length(), 65_557L).toInt()
            val tail = ByteArray(tailSize)
            file.seek(file.length() - tailSize)
            file.readFully(tail)
            val eocd = (tailSize - 22 downTo 0).firstOrNull { offset ->
                littleEndianInt(tail, offset) == 0x06054b50L
            } ?: error("Runtime APK has no ZIP end record")
            val entries = littleEndianShort(tail, eocd + 10)
            val centralOffset = littleEndianInt(tail, eocd + 16)
            file.seek(centralOffset)
            repeat(entries) {
                require(readLittleEndianInt(file) == 0x02014b50L) { "Invalid runtime APK central directory" }
                val madeBy = readLittleEndianShort(file)
                file.skipBytes(22)
                val nameLength = readLittleEndianShort(file)
                val extraLength = readLittleEndianShort(file)
                val commentLength = readLittleEndianShort(file)
                file.skipBytes(4)
                val externalAttributes = readLittleEndianInt(file)
                file.skipBytes(4 + nameLength + extraLength + commentLength)
                val unixMode = (externalAttributes ushr 16).toInt()
                require((madeBy ushr 8) != 3 || unixMode and 0xF000 != 0xA000) {
                    "Runtime APK contains a symbolic link"
                }
            }
        }
    }

    private fun readLittleEndianShort(file: RandomAccessFile): Int =
        file.readUnsignedByte() or (file.readUnsignedByte() shl 8)

    private fun readLittleEndianInt(file: RandomAccessFile): Long =
        readLittleEndianShort(file).toLong() or (readLittleEndianShort(file).toLong() shl 16)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Long =
        littleEndianShort(bytes, offset).toLong() or
            (littleEndianShort(bytes, offset + 2).toLong() shl 16)
}
