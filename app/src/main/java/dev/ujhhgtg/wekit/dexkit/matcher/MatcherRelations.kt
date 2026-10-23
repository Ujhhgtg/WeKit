package dev.ujhhgtg.wekit.dexkit.matcher

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
        val sql = "SELECT DISTINCT f.descriptor AS descriptor FROM fields f " +
            "JOIN method_field_accesses fa0 ON fa0.field = f.descriptor " +
            "JOIN methods fm0 ON fm0.descriptor = fa0.method " +
            "WHERE f.descriptor = " + sqlQuote(fieldDescriptor) +
            " AND fa0.using_type = " + sqlQuote(usingType) +
            " ORDER BY f.descriptor;"
        return query(sql).rows.mapNotNull { row ->
            row.firstOrNull()?.let(metadata::methodInfo)
        }
    }

    private fun sqlQuote(value: String): String = "'" + value.replace("'", "''") + "'"
}
