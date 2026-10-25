package dev.ujhhgtg.wekit.dex.meta

import kotlinx.serialization.json.JsonObject

/**
 * DexKit FieldData replacement.
 *
 * [declaredClass] resolves through the session [DexMetadata] cache on demand; it is null for field
 * references whose class metadata is not available.
 */
class DexFieldInfo
internal constructor(private val json: JsonObject, private val metadata: DexMetadata) {
    /** Full field descriptor ("Lcom/foo/Bar;->field:Ljava/lang/String;"). */
    val descriptor: String = json.requiredString("descriptor")

    /** Field name. */
    val name: String = json.requiredString("name")

    /** DexKit alias for [name]. */
    val fieldName: String
        get() = name

    /** Dotted Java name of the declaring class. */
    val declaredClassName: String = json.requiredString("declared_class_name")

    /** DexKit alias for [declaredClassName]. */
    val className: String
        get() = declaredClassName

    /** Java field type name ("int", "java.lang.String[]"). */
    val typeName: String = json.requiredString("type_name")

    /** DEX field type descriptor ("I", "[Ljava/lang/String;"). */
    val typeDescriptor: String =
        json.stringOrNull("type_descriptor") ?: javaNameToDescriptor(typeName)

    /** DexKit FieldData.typeSign alias for [typeDescriptor]. */
    val typeSign: String
        get() = typeDescriptor

    /** Java modifier bits derived by the engine from the DEX access flags. */
    val modifiers: Int = json.intOr("modifiers", 0)

    /** Raw DEX access flags. */
    val accessFlags: Int = json.intOr("access_flags", 0)

    /** Declaring class metadata, resolved from the session cache on demand (may be null). */
    val declaredClass: DexClassInfo? by lazy {
        metadata.classInfoByName(declaredClassName)
    }

    override fun toString(): String = descriptor
}
