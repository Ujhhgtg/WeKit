package dev.ujhhgtg.wekit.data

import dev.ujhhgtg.wekit.data.dao.AssetDao
import dev.ujhhgtg.wekit.data.entity.AssetBindingEntity
import dev.ujhhgtg.wekit.data.entity.AssetChunkEntity
import dev.ujhhgtg.wekit.data.entity.AssetEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Runtime access to assets imported into the Room catalog. */
object AssetStore {
    /** Chunk size that keeps large media out of single Room objects. */
    private const val CHUNK_SIZE = 256 * 1024

    fun readBinding(owner: String, slot: String): ByteArray? = runCatching {
        runBlocking(Dispatchers.IO) {
            val dao = WeKitDatabase.instance.assetDao()
            val binding = dao.getBinding(owner, slot) ?: return@runBlocking null
            val output = ByteArrayOutputStream(bindingAssetSize(dao, binding.assetId))
            dao.openChunkCursor(binding.assetId).use { cursor ->
                while (cursor.moveToNext()) output.write(cursor.getBlob(1))
            }
            output.toByteArray()
        }
    }
        .getOrNull()

    /**
     * Streams the asset bound to [owner]/[slot] into [target], replacing it only once the copy is
     * complete. Returns false when the slot is unbound or on failure.
     */
    fun copyBinding(owner: String, slot: String, target: File): Boolean = runCatching {
        runBlocking(Dispatchers.IO) {
            val dao = WeKitDatabase.instance.assetDao()
            val binding = dao.getBinding(owner, slot) ?: return@runBlocking false
            val partial = File(target.path + ".partial")
            partial.outputStream().buffered().use { output ->
                dao.openChunkCursor(binding.assetId).use { cursor ->
                    while (cursor.moveToNext()) output.write(cursor.getBlob(1))
                }
            }
            check(partial.renameTo(target)) { "cannot move $partial to $target" }
            true
        }
    }
        .getOrDefault(false)

    /** Id of the asset bound to [owner]/[slot], or null when the slot is unbound. */
    fun bindingAssetId(owner: String, slot: String): String? = runCatching {
        runBlocking(Dispatchers.IO) {
            WeKitDatabase.instance.assetDao().getBinding(owner, slot)?.assetId
        }
    }
        .getOrNull()

    /**
     * Stores [bytes] as a content-addressed asset and binds it to [owner]/[slot]. Re-importing
     * identical bytes reuses the existing chunks, a replaced binding's orphaned asset is deleted,
     * and re-running for an already-bound slot is idempotent. Returns false on failure.
     */
    fun importBinding(
        owner: String,
        slot: String,
        bytes: ByteArray,
        mimeType: String,
        metadataJson: String? = null,
    ): Boolean =
        import(
            owner,
            slot,
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes),
            mimeType,
            metadataJson,
        ) {
            bytes.inputStream()
        }

    /** [importBinding] that streams [source], for media too large to hold in memory. */
    fun importBinding(
        owner: String,
        slot: String,
        source: File,
        mimeType: String,
        metadataJson: String? = null,
    ): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        import(owner, slot, source.length(), digest.digest(), mimeType, metadataJson) {
            source.inputStream()
        }
    }
        .getOrDefault(false)

    private fun import(
        owner: String,
        slot: String,
        size: Long,
        digest: ByteArray,
        mimeType: String,
        metadataJson: String?,
        open: () -> InputStream,
    ): Boolean = runCatching {
        runBlocking(Dispatchers.IO) {
            val dao = WeKitDatabase.instance.assetDao()
            val sha256 = digest.joinToString("") { "%02x".format(it) }
            val assetId = "sha256-$sha256"
            val previousBinding = dao.getBinding(owner, slot)
            val expectedChunks = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
            val existing = dao.get(assetId)
            if (
                existing == null ||
                    existing.sizeBytes != size ||
                    dao.countChunks(assetId) != expectedChunks
            ) {
                // The chunk count check also repairs an asset row left behind by an interrupted
                // earlier import whose chunks were only partially written.
                dao.deleteChunks(assetId)
                dao.upsert(
                    AssetEntity(
                        assetId = assetId,
                        mimeType = mimeType,
                        sizeBytes = size,
                        sha256 = sha256,
                        createdAt = System.currentTimeMillis(),
                        metadataJson = metadataJson,
                    ),
                )
                open().use { input ->
                    var ordinal = 0
                    while (true) {
                        val chunk = input.readChunk()
                        if (chunk.isEmpty()) break
                        dao.insertChunk(AssetChunkEntity(assetId, ordinal++, chunk))
                    }
                }
            }
            dao.bind(AssetBindingEntity(owner, slot, assetId))
            if (
                previousBinding != null &&
                    previousBinding.assetId != assetId &&
                    dao.countBindings(previousBinding.assetId) == 0
            ) {
                dao.deleteChunks(previousBinding.assetId)
                dao.delete(previousBinding.assetId)
            }
        }
        true
    }
        .getOrDefault(false)

    /** Reads up to one full chunk; shorter only at the end of the stream, empty at its end. */
    private fun InputStream.readChunk(): ByteArray {
        val chunk = ByteArray(CHUNK_SIZE)
        var filled = 0
        while (filled < CHUNK_SIZE) {
            val read = read(chunk, filled, CHUNK_SIZE - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == CHUNK_SIZE) chunk else chunk.copyOf(filled)
    }

    /** Unbinds [owner]/[slot] and deletes the asset when it was the last binding. */
    fun removeBinding(owner: String, slot: String): Boolean = runCatching {
        runBlocking(Dispatchers.IO) {
            val dao = WeKitDatabase.instance.assetDao()
            val binding = dao.getBinding(owner, slot) ?: return@runBlocking false
            dao.unbind(owner, slot)
            if (dao.countBindings(binding.assetId) == 0) {
                dao.deleteChunks(binding.assetId)
                dao.delete(binding.assetId)
            }
            true
        }
    }
        .getOrDefault(false)

    /** Slots currently bound to [owner], for example the usernames carrying a custom avatar. */
    fun listBindingSlots(owner: String): List<String> = runCatching {
        runBlocking(Dispatchers.IO) {
            WeKitDatabase.instance.assetDao().getBindings(owner).map { it.slot }
        }
    }
        .getOrDefault(emptyList())

    private suspend fun bindingAssetSize(dao: AssetDao, assetId: String): Int =
        dao.get(assetId)?.sizeBytes?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
}
