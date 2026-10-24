package dev.ujhhgtg.wekit.data

import dev.ujhhgtg.wekit.data.entity.AssetBindingEntity
import dev.ujhhgtg.wekit.data.entity.AssetChunkEntity
import dev.ujhhgtg.wekit.data.entity.AssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Runtime access to assets imported into the Room catalog. */
object AssetStore {
    /** Chunk size matching [LegacyDocumentMigration]; keeps large media out of single Room objects. */
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
    }.getOrNull()

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
    ): Boolean = runCatching {
        runBlocking(Dispatchers.IO) {
            val dao = WeKitDatabase.instance.assetDao()
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val assetId = "sha256-$sha256"
            val previousBinding = dao.getBinding(owner, slot)
            val expectedChunks = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            val existing = dao.get(assetId)
            if (existing == null || existing.sizeBytes != bytes.size.toLong() ||
                dao.countChunks(assetId) != expectedChunks
            ) {
                // The chunk count check also repairs an asset row left behind by an interrupted
                // earlier import whose chunks were only partially written.
                dao.deleteChunks(assetId)
                dao.upsert(
                    AssetEntity(
                        assetId = assetId,
                        mimeType = mimeType,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = sha256,
                        createdAt = System.currentTimeMillis(),
                        metadataJson = metadataJson,
                    ),
                )
                var ordinal = 0
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + CHUNK_SIZE, bytes.size)
                    dao.insertChunk(AssetChunkEntity(assetId, ordinal++, bytes.copyOfRange(offset, end)))
                    offset = end
                }
            }
            dao.bind(AssetBindingEntity(owner, slot, assetId))
            if (previousBinding != null && previousBinding.assetId != assetId &&
                dao.countBindings(previousBinding.assetId) == 0
            ) {
                dao.deleteChunks(previousBinding.assetId)
                dao.delete(previousBinding.assetId)
            }
        }
        true
    }.getOrDefault(false)

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
    }.getOrDefault(false)

    /** Slots currently bound to [owner], for example the usernames carrying a custom avatar. */
    fun listBindingSlots(owner: String): List<String> = runCatching {
        runBlocking(Dispatchers.IO) {
            WeKitDatabase.instance.assetDao().getBindings(owner).map { it.slot }
        }
    }.getOrDefault(emptyList())

    private suspend fun bindingAssetSize(dao: dev.ujhhgtg.wekit.data.dao.AssetDao, assetId: String): Int =
        dao.get(assetId)?.sizeBytes?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
}
