package dev.ujhhgtg.wekit.dexkit.matcher

import kotlinx.serialization.json.JsonObject
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded caches shared by every query-matcher delegate of one DexSQL session.
 *
 * Per-delegate [QueryMatcherScope] instances issue many identical candidate SQL queries and raw
 * metadata reads (the same package/class scans, method lookups and class-string evaluations are
 * repeated across converted delegates of a feature). The native bridge and metadata documents are
 * immutable for the lifetime of a session, so results can be memoized across delegates.
 *
 * Everything is bounded: candidate lists larger than [MAX_CACHED_DESCRIPTORS] are never cached,
 * and each map stops growing at [MAX_ENTRIES]. Values are plain immutable snapshots; the cache is
 * keyed weakly by the DexSQL session object so sessions do not leak.
 */
class MatcherSessionCache internal constructor() {
    private val candidateRowsBySql = ConcurrentHashMap<String, List<String>>()
    private val rawDocuments = ConcurrentHashMap<String, JsonObject>()
    private val classStrings = ConcurrentHashMap<String, Boolean>()

    /** Memoized candidate SQL rows (only small enough result sets). */
    fun candidateRows(sql: String, load: () -> List<String>): List<String> {
        candidateRowsBySql[sql]?.let { return it }
        val rows = load()
        if (rows.size <= MAX_CACHED_DESCRIPTORS && candidateRowsBySql.size < MAX_ENTRIES) {
            candidateRowsBySql.putIfAbsent(sql, rows)
        }
        return rows
    }

    /** Memoized raw metadata document for a kind/descriptor pair. */
    fun rawDocument(key: String, load: () -> JsonObject?): JsonObject? {
        rawDocuments[key]?.let { return it }
        val document = load() ?: return null
        if (rawDocuments.size < MAX_ENTRIES) rawDocuments.putIfAbsent(key, document)
        return document
    }

    /** Memoized class-level usingStrings evaluation (class descriptor + literal). */
    fun classString(key: String, load: () -> Boolean): Boolean {
        classStrings[key]?.let { return it }
        val result = load()
        if (classStrings.size < MAX_ENTRIES) classStrings.putIfAbsent(key, result)
        return result
    }

    companion object {
        private const val MAX_ENTRIES = 2048
        private const val MAX_CACHED_DESCRIPTORS = 4096

        private val bySession = WeakHashMap<Any, MatcherSessionCache>()

        /** One shared cache per DexSQL session object (weak keys, so sessions do not leak). */
        fun forSession(session: Any): MatcherSessionCache = synchronized(bySession) {
            bySession.getOrPut(session) { MatcherSessionCache() }
        }
    }
}
