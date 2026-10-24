package dev.ujhhgtg.wekit.dexkit.meta

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Metadata entity kind; [key] matches the DexSQL native metadata JSON bucket. */
enum class DexMetadataKind(val key: String) {
    Class("class"),
    Method("method"),
    Field("field"),
}

/**
 * Fetches one raw DexSQL metadata JSON object for an entity descriptor.
 *
 * The production implementation will call the DexSQL native meta_* entry points; tests use a fake
 * provider. The engine contract is that a missing descriptor yields null (not an error).
 */
fun interface DexMetadataProvider {
    fun query(kind: DexMetadataKind, descriptor: String): JsonObject?
}

/**
 * Session-scoped metadata access with a thread-safe descriptor cache.
 *
 * The cache stores one parsed model instance per descriptor, so repeated .data accesses never
 * re-parse JSON or re-query the provider. Missing descriptors are cached as null. Lazy children
 * (method/field declaredClass, and the inline class children) reuse this same cache.
 */
class DexMetadata(private val provider: DexMetadataProvider) {
    private val classes = ConcurrentHashMap<String, Lazy<DexClassInfo?>>()
    private val methods = ConcurrentHashMap<String, Lazy<DexMethodInfo?>>()
    private val fields = ConcurrentHashMap<String, Lazy<DexFieldInfo?>>()

    /** Class metadata for a DEX class descriptor ("Lcom/foo/Bar;"), or null when not found. */
    fun classInfo(descriptor: String): DexClassInfo? =
        cached(classes, DexMetadataKind.Class, descriptor) { DexClassInfo(it, this) }

    /** Class metadata for a dotted Java name ("com.foo.Bar") or a DEX descriptor. */
    fun classInfoByName(javaName: String): DexClassInfo? {
        // Primitives and arrays have no class document; return null instead of sending a malformed
        // descriptor to the engine.
        val descriptor = objectClassDescriptorOrNull(javaName) ?: return null
        return classInfo(descriptor)
    }

    /** Method metadata for a full method descriptor, or null when not found. */
    fun methodInfo(descriptor: String): DexMethodInfo? =
        cached(methods, DexMetadataKind.Method, descriptor) { DexMethodInfo(it, this) }

    /** Field metadata for a full field descriptor, or null when not found. */
    fun fieldInfo(descriptor: String): DexFieldInfo? =
        cached(fields, DexMetadataKind.Field, descriptor) { DexFieldInfo(it, this) }

    private fun <T : Any> cached(
        cache: ConcurrentHashMap<String, Lazy<T?>>,
        kind: DexMetadataKind,
        descriptor: String,
        factory: (JsonObject) -> T,
    ): T? = cache.computeIfAbsent(descriptor) {
        lazy { provider.query(kind, descriptor)?.let(factory) }
    }.value
}
