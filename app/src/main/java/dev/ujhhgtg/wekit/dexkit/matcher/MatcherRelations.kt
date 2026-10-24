package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.DexSqlException
import dev.ujhhgtg.wekit.dexkit.DexSqlQueryResult
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Relation data needed by matcher evaluation that metadata documents do not inline.
 *
 * Implementations must be lazy: caller lists can be huge (pathological host methods), so they are
 * only fetched when a spec actually uses [MethodSpec.callerMethods]; field access lists are fetched
 * only for [FieldSpec.readMethods]/[FieldSpec.writeMethods]. Results are cached per session.
 */
interface DexMatcherRelations {
    /** Callers of a method descriptor in DexKit order. */
    fun callers(methodDescriptor: String): List<DexMethodInfo>

    /**
     * Methods accessing a field with the given SQL using_type ('get' for read, 'put' for write).
     */
    fun fieldAccessMethods(fieldDescriptor: String, usingType: String): List<DexMethodInfo>

    /**
     * Batched variant: one relation lookup for many candidate fields. The default implementation
     * keeps fake/test relations simple; the production bridge overrides it with one bounded query.
     */
    fun fieldAccessMethods(
        fieldDescriptors: Collection<String>,
        usingType: String,
    ): Map<String, List<DexMethodInfo>> = fieldDescriptors.associateWith {
        fieldAccessMethods(it, usingType)
    }
}

/**
 * Production relations backed by one open DexSQL session.
 *
 * Callers use the opt-in DexSqlMetadataProvider.callers API (huge lists; only fetched lazily when a
 * spec actually uses callerMethods); field accesses use a generic SQL join over
 * method_field_accesses (few delegates, generic execution is acceptable). Both are cached.
 */
class BridgeMatcherRelations(
    private val callersOf: (String) -> JsonElement?,
    private val query: (String) -> DexSqlQueryResult,
    private val metadata: DexMetadata,
) : DexMatcherRelations {
    private val callerCache = ConcurrentHashMap<String, Lazy<List<DexMethodInfo>>>()
    private val fieldAccessCache = ConcurrentHashMap<String, Lazy<List<DexMethodInfo>>>()

    override fun callers(methodDescriptor: String): List<DexMethodInfo> =
        callerCache
            .computeIfAbsent(methodDescriptor) {
                lazy { parseCallers(callersOf(methodDescriptor)) }
            }
            .value

    override fun fieldAccessMethods(
        fieldDescriptor: String,
        usingType: String,
    ): List<DexMethodInfo> =
        fieldAccessCache
            .computeIfAbsent("$fieldDescriptor|$usingType") {
                lazy { fetchFieldAccessMethods(fieldDescriptor, usingType) }
            }
            .value

    /**
     * Fetches access methods for many candidate fields. Each candidate uses the engine's fast
     * method-base field-access shape (one field descriptor literal, single-equality joins). The old
     * IN (...) batch shape falls back to the generic streaming executor and costs ~1-2 s per
     * candidate set, so per-field fast lookups are substantially cheaper after candidate
     * intersection (the usual case is 1-3 fields). Results fill the per-field cache, making the
     * evaluator's subsequent single-field calls O(1).
     */
    override fun fieldAccessMethods(
        fieldDescriptors: Collection<String>,
        usingType: String,
    ): Map<String, List<DexMethodInfo>> {
        val valid = fieldDescriptors.filter(::isRealFieldDescriptor).distinct()
        if (valid.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, List<DexMethodInfo>>(valid.size)
        for (descriptor in valid) {
            val key = "$descriptor|$usingType"
            val cached = fieldAccessCache[key]?.value
            if (cached != null) {
                result[descriptor] = cached
                continue
            }
            val methods = fetchFieldAccessMethods(descriptor, usingType)
            result[descriptor] = methods
            fieldAccessCache.putIfAbsent(key, lazyOf(methods))
        }
        return result
    }

    private fun parseCallers(callers: JsonElement?): List<DexMethodInfo> {
        val array = callers as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val caller = element as? JsonObject ?: return@mapNotNull null
            if (caller.isEmpty()) null else DexMethodInfo(caller, metadata)
        }
    }

    private fun fetchFieldAccessMethods(
        fieldDescriptor: String,
        usingType: String,
    ): List<DexMethodInfo> {
        // The candidate relation is the canonical field-access shape (single-equality ON, scoping
        // predicates in WHERE). It must select the ACCESSING METHOD descriptor, not the field
        // descriptor: metadata.methodInfo(fieldDescriptor) is null and would silently yield no
        // read/write methods (fieldGlobalUicChildren regression).
        if (!isRealFieldDescriptor(fieldDescriptor)) return emptyList()
        val sql =
            "SELECT DISTINCT m.descriptor AS method FROM methods m " +
                "JOIN method_field_accesses fa0 ON fa0.method = m.descriptor " +
                "WHERE fa0.field = " +
                sqlQuote(fieldDescriptor) +
                " AND fa0.file_id = m.file_id" +
                " AND fa0.method_idx = m.method_idx" +
                " AND fa0.using_type = " +
                sqlQuote(usingType) +
                " ORDER BY m.descriptor;"
        val rows =
            try {
                query(sql).rows
            } catch (_: DexSqlException) {
                return emptyList()
            }
        return rows.mapNotNull { row ->
            val descriptor = row.firstOrNull() ?: return@mapNotNull null
            if (!isRealMethodDescriptor(descriptor)) return@mapNotNull null
            try {
                metadata.methodInfo(descriptor)
            } catch (_: DexSqlException) {
                null
            }
        }
    }

    private fun sqlQuote(value: String): String = "'" + value.replace("'", "''") + "'"
}
