package dev.ujhhgtg.wekit.data

import android.content.Context
import android.net.Uri
import android.os.Build
import dev.ujhhgtg.wekit.data.entity.AssetBindingEntity
import dev.ujhhgtg.wekit.data.entity.AssetChunkEntity
import dev.ujhhgtg.wekit.data.entity.AssetEntity
import dev.ujhhgtg.wekit.data.entity.ExtensionInstallEntity
import dev.ujhhgtg.wekit.data.entity.ManagedDataEntryEntity
import dev.ujhhgtg.wekit.data.entity.ScriptCatalogEntity
import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.extensions.PackFs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Imports user-owned media and file indexes into the unified Room catalog. Feature JSON now
 * goes directly through [JsonDataMigration]; the existing completion key is retained for assets.
 * Source files remain until explicit old-data cleanup.
 */
object LegacyDocumentMigration {
    private const val TAG = "LegacyDocumentMigration"
    private const val MIGRATION_NAMESPACE = "migration"
    /**
     * The suffix version bumps whenever the migration logic changes, so an installation that
     * already ran an older version re-runs the new one exactly once. v4 imports the legacy JSON
     * files directly into their runtime document keys; v3 kept file-backed media in the file
     * tree and only imported the catalogued user asset directory and custom avatars.
     */
    private const val MIGRATION_KEY = "legacy-document-v4"
    private const val CHUNK_SIZE = 256 * 1024

    /**
     * Every legacy JSON file this migration consumes or leaves behind, including the custom
     * avatar map (avatar reconciliation input, not a document store). After the migration has
     * completed these are pure rollback copies and the cleanup action may delete them.
     */
    private val legacyFiles: List<String> =
        JsonDataMigration.legacyFiles + "custom_avatars_map.json"

    /** Hands every existing legacy JSON file to [delete] under the caller's lock. */
    fun cleanupFiles(delete: (File) -> Unit) {
        val root = KnownPaths.moduleRoot.toFile()
        legacyFiles.forEach { name ->
            val file = File(root, name)
            if (file.isFile) delete(file)
        }
    }

    private data class AssetSource(
        val owner: String,
        val slot: String,
        val mimeType: String,
        val metadataJson: String?,
        val sizeHint: Long? = null,
        val open: () -> InputStream,
    )

    private data class AssetDigest(val sha256: String, val sizeBytes: Long)

    fun isCompleted(): Boolean =
        DocumentStore.get(MIGRATION_NAMESPACE, MIGRATION_KEY)?.content?.contains("\"status\":\"completed\"") == true

    fun run(context: Context) {
        if (isCompleted()) return

        val startedAt = System.currentTimeMillis()
        putState("running", startedAt, null)
        try {
            val root = KnownPaths.moduleRoot.toFile()
            val assetCount = AtomicInteger()
            // Themes, sticker packs, voice packs and other media are file-backed runtime data.
            // Keep their original directory layout intact: importing them into Room would both
            // duplicate potentially gigabytes of data and break the existing import/read paths.
            // Only the explicitly catalogued user asset directory and custom avatar URIs belong
            // in the asset catalog.
            migrateDirectoryAssets(root, "assets/user", "legacy-assets-user", assetCount)
            migrateCustomAvatars(context, File(root, "custom_avatars_map.json"), assetCount)
            val indexedFiles = scanScriptCatalog(root, startedAt) +
                    scanManagedData(root, startedAt) +
                    scanExtensionCatalog(root)

            putState(
                "completed",
                startedAt,
                mapOf(
                    "assets" to assetCount.get(),
                    "indexedFiles" to indexedFiles,
                ),
            )
        } catch (error: Throwable) {
            runCatching {
                putState("failed", startedAt, mapOf("error" to (error.message ?: error.javaClass.name)))
            }.onFailure { metadataError ->
                WeLogger.e(TAG, "failed to persist legacy migration error state", metadataError)
            }
            WeLogger.e(TAG, "legacy document and asset migration failed; source files were retained", error)
        }
    }

    private fun migrateDirectoryAssets(
        root: File,
        relativeRoot: String,
        owner: String,
        assetCount: AtomicInteger,
    ) {
        val sourceRoot = File(root, relativeRoot)
        if (!sourceRoot.isDirectory) return
        val canonicalRoot = sourceRoot.canonicalPath + File.separator
        sourceRoot.walkTopDown()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                check(file.canonicalPath.startsWith(canonicalRoot)) { "asset escapes managed root: $file" }
                val slot = file.relativeTo(sourceRoot).invariantSeparatorsPath
                importAsset(
                    AssetSource(
                        owner = owner,
                        slot = slot,
                        mimeType = mimeType(file.name),
                        metadataJson = metadata("path" to "$relativeRoot/$slot"),
                        sizeHint = file.length(),
                        open = { file.inputStream() },
                    ),
                )
                assetCount.incrementAndGet()
            }
    }

    private fun migrateCustomAvatars(context: Context, mapFile: File, assetCount: AtomicInteger) {
        if (!mapFile.isFile) return
        val entries = Json.decodeFromString<Map<String, String>>(mapFile.readText(Charsets.UTF_8))
        entries.forEach { (username, rawUri) ->
            runCatching {
                val uri = Uri.parse(rawUri)
                val source = AssetSource(
                    owner = "custom-avatar",
                    slot = username,
                    mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                    metadataJson = metadata("uri" to rawUri),
                    open = {
                        context.contentResolver.openInputStream(uri)
                            ?: error("cannot read custom avatar URI: $rawUri")
                    },
                )
                importAsset(source)
                assetCount.incrementAndGet()
            }.onFailure { WeLogger.w(TAG, "failed to migrate custom avatar for $username", it) }
        }
    }

    private fun scanScriptCatalog(root: File, updatedAt: Long): Int = runBlocking(Dispatchers.IO) {
        val dao = WeKitDatabase.instance.scriptCatalogDao()
        var count = 0
        listOf("scripts_java" to "java", "scripts_python" to "python").forEach { (directory, kind) ->
            val sourceRoot = File(root, directory)
            if (!sourceRoot.isDirectory) return@forEach
            sourceRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { scriptRoot ->
                if (java.nio.file.Files.isSymbolicLink(scriptRoot.toPath())) return@forEach
                val relative = scriptRoot.relativeTo(root).invariantSeparatorsPath
                val manifest = File(scriptRoot, "plugin.json")
                    .takeIf { kind == "python" && it.isFile }
                    ?.readText(Charsets.UTF_8)
                val updated = scriptRoot.lastModified().takeIf { it > 0 } ?: updatedAt
                dao.upsert(
                    ScriptCatalogEntity(
                        scriptId = "$kind:${scriptRoot.name}",
                        kind = kind,
                        relativePath = relative,
                        manifestJson = manifest,
                        contentHash = hashTree(scriptRoot),
                        enabled = !File(scriptRoot, "disabled.flag").exists(),
                        updatedAt = updated,
                    ),
                )
                count++
            }
        }
        count
    }

    private fun scanManagedData(root: File, updatedAt: Long): Int = runBlocking(Dispatchers.IO) {
        val sourceRoot = File(root, "python/data")
        if (!sourceRoot.isDirectory) return@runBlocking 0
        val dao = WeKitDatabase.instance.managedDataDao()
        var count = 0
        sourceRoot.walkTopDown()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                val pluginId = relative.substringBefore('/').ifBlank { "python" }
                dao.upsert(
                    ManagedDataEntryEntity(
                        pluginId = pluginId,
                        relativePath = relative,
                        sizeBytes = file.length(),
                        contentHash = sha256(file),
                        modifiedAt = file.lastModified().takeIf { it > 0 } ?: updatedAt,
                    ),
                )
                count++
            }
        count
    }

    private fun scanExtensionCatalog(root: File): Int = runBlocking(Dispatchers.IO) {
        val dao = WeKitDatabase.instance.extensionInstallDao()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        var count = 0
        ExtensionPacks.packs.forEach { pack ->
            val installRoot = pack.installDir()
            if (!installRoot.isDirectory) return@forEach
            val selectedVersion = pack.installedManifest()?.version
            installRoot.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .forEach { versionDir ->
                    val manifest = runCatching { PackFs.readManifest(versionDir) }.getOrNull() ?: return@forEach
                    dao.upsert(
                        ExtensionInstallEntity(
                            extensionId = pack.id,
                            version = manifest.version,
                            abi = abi,
                            manifestJson = versionDir.resolve("manifest.json").takeIf { it.isFile }?.readText(),
                            contentHash = manifest.sha256,
                            relativePath = versionDir.relativeTo(root).invariantSeparatorsPath,
                            selected = manifest.version == selectedVersion,
                            mounted = manifest.version == selectedVersion && pack.isInUse(),
                            updatedAt = manifest.installedAtEpochMs,
                        ),
                    )
                    count++
                }
        }
        count
    }

    private fun importAsset(source: AssetSource) = runBlocking(Dispatchers.IO) {
        val dao = WeKitDatabase.instance.assetDao()
        val existingBinding = dao.getBinding(source.owner, source.slot)
        val existingAsset = existingBinding?.let { dao.get(it.assetId) }
        val expectedExistingChunks = source.sizeHint?.let { (it + CHUNK_SIZE - 1) / CHUNK_SIZE }?.toInt()
        if (source.sizeHint != null && existingAsset != null &&
            existingAsset.sizeBytes == source.sizeHint && existingAsset.metadataJson == source.metadataJson &&
            expectedExistingChunks == dao.countChunks(existingAsset.assetId)
        ) {
            return@runBlocking
        }

        val digest = digest(source.open)
        val assetId = "legacy-${digest.sha256}"
        val existing = dao.get(assetId)
        val expectedChunks = ((digest.sizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val currentChunks = if (existing == null) -1 else dao.countChunks(assetId)
        if (existing == null || existing.sizeBytes != digest.sizeBytes || existing.sha256 != digest.sha256 || currentChunks != expectedChunks) {
            dao.deleteChunks(assetId)
            dao.upsert(
                AssetEntity(
                    assetId = assetId,
                    mimeType = source.mimeType,
                    sizeBytes = digest.sizeBytes,
                    sha256 = digest.sha256,
                    createdAt = System.currentTimeMillis(),
                    metadataJson = source.metadataJson,
                ),
            )
            source.open().use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                var ordinal = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    dao.insertChunk(AssetChunkEntity(assetId, ordinal++, buffer.copyOf(read)))
                }
            }
        }
        dao.bind(AssetBindingEntity(source.owner, source.slot, assetId))
    }

    private fun digest(open: () -> InputStream): AssetDigest {
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        open().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sha.update(buffer, 0, read)
                size += read
            }
        }
        return AssetDigest(sha.digest().toHex(), size)
    }

    private fun putState(status: String, startedAt: Long, details: Map<String, Any?>?) {
        val content = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("status", status)
            put("startedAt", startedAt)
            put("updatedAt", System.currentTimeMillis())
            details?.forEach { (key, value) ->
                when (value) {
                    null -> put(key, JsonNull)
                    is Number -> put(key, value.toLong())
                    else -> put(key, value.toString())
                }
            }
        })
        DocumentStore.put(MIGRATION_NAMESPACE, MIGRATION_KEY, content, exportable = true)
    }

    private fun metadata(vararg values: Pair<String, String>): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        })

    private fun mimeType(name: String): String =
        URLConnection.guessContentTypeFromName(name)?.lowercase(Locale.ROOT)
            ?: "application/octet-stream"

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (value in this@toHex) {
            val byte = value.toInt() and 0xff
            append(HEX_DIGITS[byte ushr 4])
            append(HEX_DIGITS[byte and 0x0f])
        }
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun hashTree(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                file.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().toHex()
    }
}
