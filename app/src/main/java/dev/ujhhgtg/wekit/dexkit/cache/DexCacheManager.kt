package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.data.WeKitDatabase
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.unreachable

/** Host-versioned Dex descriptors stored inside the shared Room database. */
object DexCacheManager {
    private const val TAG = "DexCacheManager"
    private lateinit var hostVersion: String

    fun init(currentVer: String) {
        hostVersion = currentVer
        WeLogger.i(TAG, "using Room Dex cache version: $currentVer")
    }

    fun isItemCacheValid(item: IResolveDex): Boolean {
        if (item !is BaseFeature) unreachable()
        val record = read(item.technicalId) ?: return false
        if (record.methodHash != methodHash(item)) return false
        val missing = item.dexDelegates.filter { record.descriptors[it.key].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            WeLogger.d(TAG, "cache incomplete for ${item.technicalPath}: ${missing.map { it.key }}")
            return false
        }
        return true
    }

    fun saveItemCache(item: IResolveDex) {
        if (item !is BaseFeature) error("item is not BaseFeature")
        val descriptors = item.collectDescriptors()
        val db = database()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM dex_cache_descriptors WHERE hostVersion = ? AND technicalId = ?", arrayOf<Any?>(hostVersion, item.technicalId))
            db.execSQL("INSERT OR REPLACE INTO dex_cache_entries(hostVersion, technicalId, methodHash, timestamp) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, item.technicalId, methodHash(item), System.currentTimeMillis()))
            descriptors.forEach { (key, value) ->
                db.execSQL("INSERT OR REPLACE INTO dex_cache_descriptors(hostVersion, technicalId, descriptorKey, descriptorValue) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, item.technicalId, key, value))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadItemCache(item: IResolveDex): Map<String, Any>? {
        if (item !is BaseFeature) error("item is not BaseFeature")
        return read(item.technicalId)?.descriptors?.mapValues { it.value as Any }
    }

    fun deleteCache(technicalId: String) {
        val db = database()
        db.beginTransaction()
        try {
            // The descriptor table intentionally has no FK so imported databases can preserve
            // file-backed metadata. Delete both halves together to avoid orphan descriptors.
            db.execSQL("DELETE FROM dex_cache_descriptors WHERE hostVersion = ? AND technicalId = ?", arrayOf<Any?>(hostVersion, technicalId))
            db.execSQL("DELETE FROM dex_cache_entries WHERE hostVersion = ? AND technicalId = ?", arrayOf<Any?>(hostVersion, technicalId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Clears only the current host-version partition; older versions remain available. */
    fun clearAllCache() {
        val db = database()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM dex_cache_descriptors WHERE hostVersion = ?", arrayOf<Any?>(hostVersion))
            db.execSQL("DELETE FROM dex_cache_entries WHERE hostVersion = ?", arrayOf<Any?>(hostVersion))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        WeLogger.i(TAG, "cleared Room Dex cache for $hostVersion")
    }

    fun getOutdatedItems(items: List<IResolveDex>): List<IResolveDex> =
        items.filter { !isItemCacheValid(it) }

    fun importCloudCaches(entries: List<CloudDexCacheEntry>) {
        val db = database()
        db.beginTransaction()
        try {
            entries.forEach { entry ->
                db.execSQL("DELETE FROM dex_cache_descriptors WHERE hostVersion = ? AND technicalId = ?", arrayOf<Any?>(hostVersion, entry.technicalId))
                db.execSQL("INSERT OR REPLACE INTO dex_cache_entries(hostVersion, technicalId, methodHash, timestamp) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, entry.technicalId, entry.methodHash, System.currentTimeMillis()))
                entry.descriptors.forEach { (key, value) ->
                    db.execSQL("INSERT OR REPLACE INTO dex_cache_descriptors(hostVersion, technicalId, descriptorKey, descriptorValue) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, entry.technicalId, key, value))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun methodHash(item: IResolveDex): String {
        val hash = GeneratedMethodHashes.HASHES[(item as BaseFeature).technicalId]
        if (hash.isNullOrBlank()) error("failed to retrieve method hash for item ${item.technicalId}")
        return hash
    }

    private data class CacheRecord(val methodHash: String, val descriptors: Map<String, String>)

    private fun read(technicalId: String): CacheRecord? = runCatching {
        // Read the entry and all descriptors through one SQL statement.  Two independent
        // queries can observe different generations while another process replaces a cache.
        val descriptors = LinkedHashMap<String, String>()
        var methodHash: String? = null
        database().query(
            "SELECT e.methodHash, d.descriptorKey, d.descriptorValue " +
                    "FROM dex_cache_entries e LEFT JOIN dex_cache_descriptors d " +
                    "ON d.hostVersion = e.hostVersion AND d.technicalId = e.technicalId " +
                    "WHERE e.hostVersion = ? AND e.technicalId = ?",
            arrayOf(hostVersion, technicalId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                methodHash = cursor.getString(0)
                if (!cursor.isNull(1)) descriptors[cursor.getString(1)] = cursor.getString(2)
            }
        }
        val resolvedMethodHash = methodHash ?: return@runCatching null
        CacheRecord(resolvedMethodHash, descriptors)
    }.onFailure { WeLogger.e(TAG, "failed to read Room Dex cache for $technicalId", it) }.getOrNull()

    private fun database() = WeKitDatabase.instance.openHelper.writableDatabase
}
