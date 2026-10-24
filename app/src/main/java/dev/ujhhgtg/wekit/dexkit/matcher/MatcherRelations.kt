package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.DexSqlException
import dev.ujhhgtg.wekit.dexkit.DexSqlQueryResult
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

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

    /** Methods accessing a field with the given SQL using_type ('get' for read, 'put' for write). */
    fun fieldAccessMethods(fieldDescriptor: String, usingType: String): List<DexMethodInfo>
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
        callerCache.computeIfAbsent(methodDescriptor) {
            lazy { parseCallers(callersOf(methodDescriptor)) }
        }.value

    override fun fieldAccessMethods(fieldDescriptor: String, usingType: String): List<DexMethodInfo> =
        fieldAccessCache.computeIfAbsent(fieldDescriptor + "|" + usingType) {
            lazy { fetchFieldAccessMethods(fieldDescriptor, usingType) }
        }.value

    private fun parseCallers(callers: JsonElement?): List<DexMethodInfo> {
        val array = callers as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val caller = element as? JsonObject ?: return@mapNotNull null
            if (caller.isEmpty()) null else DexMethodInfo(caller, metadata)
        }
    }

    private fun fetchFieldAccessMethods(fieldDescriptor: String, usingType: String): List<DexMethodInfo> {
        // The candidate relation is the canonical field-access shape (single-equality ON, scoping
        // predicates in WHERE). It must select the ACCESSING METHOD descriptor, not the field
        // descriptor: metadata.methodInfo(fieldDescriptor) is null and would silently yield no
        // read/write methods (fieldGlobalUicChildren regression).
        if (!isRealFieldDescriptor(fieldDescriptor)) return emptyList()
        val sql = "SELECT DISTINCT fm0.descriptor AS descriptor FROM fields f " +
            "JOIN method_field_accesses fa0 ON fa0.field = f.descriptor " +
            "JOIN methods fm0 ON fm0.descriptor = fa0.method " +
            "WHERE f.descriptor = " + sqlQuote(fieldDescriptor) +
            " AND fa0.file_id = f.file_id" +
            " AND fa0.field_idx = f.field_idx" +
            " AND fm0.file_id = fa0.file_id" +
            " AND fa0.using_type = " + sqlQuote(usingType) +
            " ORDER BY fm0.descriptor;"
        val rows = try {
            query(sql).rows
        } catch (e: DexSqlException) {
            return emptyList()
        }
        return rows.mapNotNull { row ->
            val descriptor = row.firstOrNull() ?: return@mapNotNull null
            if (!isRealMethodDescriptor(descriptor)) return@mapNotNull null
            try {
                metadata.methodInfo(descriptor)
            } catch (e: DexSqlException) {
                null
            }
        }
    }

    private fun sqlQuote(value: String): String = "'" + value.replace("'", "''") + "'"
}
