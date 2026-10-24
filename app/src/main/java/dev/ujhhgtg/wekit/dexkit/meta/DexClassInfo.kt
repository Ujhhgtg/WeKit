package dev.ujhhgtg.wekit.dexkit.meta

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * DexKit ClassData replacement.
 *
 * The top-level class JSON already contains its members, so [methods]/[fields]/[interfaces] are
 * parsed from that JSON without extra provider queries. [superClass] entries are also inline
 * descriptor/name references; use [name]/[descriptor] there. Deeper member lookups go through the
 * session [DexMetadata] cache on demand.
 */
class DexClassInfo
internal constructor(private val json: JsonObject, private val metadata: DexMetadata) {
    /** Raw JSON backing this instance (class document including additive annotations). */
    internal val rawJson: JsonObject
        get() = json

    /** Shallow descriptor/name reference for classes without dataset metadata (e.g. framework). */
    internal constructor(
        descriptor: String,
        name: String,
        metadata: DexMetadata,
    ) : this(
        buildJsonObject {
            put("descriptor", descriptor)
            put("name", name)
        },
        metadata,
    )

    /** Class descriptor ("Lcom/foo/Bar;"). */
    val descriptor: String = json.requiredString("descriptor")

    /** Dotted Java class name ("com.foo.Bar"). */
    val name: String = json.requiredString("name")

    /** Simple class name ("Bar"). */
    val simpleName: String = json.stringOrNull("simple_name") ?: name.substringAfterLast('.')

    /** Raw DexKit sourceFile string, or null when absent. */
    val sourceFile: String? = json.stringOrNull("source_file")

    /** Java modifier bits derived by the engine from the DEX access flags. */
    val modifiers: Int = json.intOr("modifiers", 0)

    /** Raw DEX access flags. */
    val accessFlags: Int = json.intOr("access_flags", 0)

    /** Whether this class metadata describes an array type. */
    val isArray: Boolean = json.booleanOr("is_array", false)

    /**
     * Superclass metadata. Defined superclasses resolve full metadata through the session provider;
     * external classes fall back to a shallow descriptor/name reference.
     */
    val superClass: DexClassInfo? by lazy {
        json.objectOrNull("super_class")?.let { resolveReferencedClass(it) }
    }

    /**
     * Interface metadata. Defined interfaces resolve full metadata through the session provider;
     * external ones fall back to a shallow descriptor/name reference.
     */
    val interfaces: List<DexClassInfo> by lazy {
        json.arrayOrEmpty("interfaces").map { resolveReferencedClass(it.jsonObject) }
    }

    private fun resolveReferencedClass(reference: JsonObject): DexClassInfo {
        val descriptor = reference.requiredString("descriptor")
        return metadata.classInfo(descriptor)
            ?: DexClassInfo(
                descriptor,
                reference.stringOrNull("name") ?: descriptorToJavaName(descriptor),
                metadata,
            )
    }

    val methods: List<DexMethodInfo> by lazy {
        json.arrayOrEmpty("methods").map { DexMethodInfo(it.jsonObject, metadata) }
    }

    val fields: List<DexFieldInfo> by lazy {
        json.arrayOrEmpty("fields").map { DexFieldInfo(it.jsonObject, metadata) }
    }

    val interfaceCount: Int
        get() = interfaces.size

    val methodCount: Int
        get() = methods.size

    val fieldCount: Int
        get() = fields.size

    override fun toString(): String = name
}
