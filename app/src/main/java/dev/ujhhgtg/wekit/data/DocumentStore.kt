package dev.ujhhgtg.wekit.data

import dev.ujhhgtg.wekit.data.entity.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Synchronous compatibility facade for the document table.
 *
 * The existing settings repositories are synchronous and will be migrated incrementally.  Keep
 * that API shape here while doing every Room call on the IO dispatcher; callers must still avoid
 * invoking this facade from latency-sensitive hook callbacks.
 */
object DocumentStore {
    fun read(namespace: String, key: String, legacyFile: File? = null): String? {
        get(namespace, key)?.let { return it.content }
        val legacy = legacyFile?.takeIf(File::isFile)?.readText() ?: return null
        put(namespace, key, legacy)
        return legacy
    }

    fun write(namespace: String, key: String, content: String, exportable: Boolean = true) {
        put(namespace, key, content, exportable = exportable)
    }

    fun get(namespace: String, key: String): DocumentEntity? = blocking {
        WeKitDatabase.instance.documentDao().get(namespace, key)
    }

    fun put(
        namespace: String,
        key: String,
        content: String,
        formatVersion: Int = 1,
        updatedAt: Long = System.currentTimeMillis(),
        exportable: Boolean = true,
    ) = blocking {
        WeKitDatabase.instance.documentDao().upsert(
            DocumentEntity(namespace, key, content, formatVersion, updatedAt, exportable),
        )
    }

    fun delete(namespace: String, key: String) = blocking {
        WeKitDatabase.instance.documentDao().delete(namespace, key)
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
