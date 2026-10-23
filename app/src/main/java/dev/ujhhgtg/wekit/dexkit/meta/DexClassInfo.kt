package dev.ujhhgtg.wekit.dexkit.meta

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * DexKit ClassData replacement.
 *
 * The top-level class JSON already contains its members, so [methods]/[fields]/[interfaces] are
 * parsed from that JSON without extra provider queries. [superClass] entries are also inline
 * descriptor/name references; use [name]/[descriptor] there. Deeper member lookups go through the
 * session [DexMetadata] cache on demand.
 */
class DexClassInfo internal constructor(
    private val json: JsonObject,
    private val metadata: DexMetadata,
) {
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

    val superClass: DexClassInfo? by lazy {
        json.objectOrNull("super_class")?.let { DexClassInfo(it, metadata) }
    }

    val interfaces: List<DexClassInfo> by lazy {
        json.arrayOrEmpty("interfaces").map { DexClassInfo(it.jsonObject, metadata) }
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
