package dev.ujhhgtg.wekit.dexkit.meta

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * DexKit MethodData replacement (method and constructor metadata).
 *
 * Invokes/usingFields/usingStrings are part of the top-level method JSON and are parsed lazily
 * without extra provider queries. [declaredClass] resolves through the session [DexMetadata] cache
 * on demand; it is null for external method references whose class metadata is not available.
 */
class DexMethodInfo internal constructor(
    private val json: JsonObject,
    private val metadata: DexMetadata,
) {
    /** Full method descriptor ("Lcom/foo/Bar;->name(I)V"). */
    val descriptor: String = json.requiredString("descriptor")

    /** Method name ("name"); constructors use "<init>". */
    val name: String = json.requiredString("name")

    /** DexKit alias for [name]. */
    val methodName: String
        get() = name

    /** Dotted Java name of the declaring class. */
    val declaredClassName: String = json.requiredString("declared_class_name")

    /** DexKit alias for [declaredClassName]. */
    val className: String
        get() = declaredClassName

    /** Prototype sign "(params)return", without the method name. */
    val methodSign: String = json.stringOrNull("method_sign") ?: methodDescriptorSign(descriptor)

    /** Java modifier bits derived by the engine from the DEX access flags. */
    val modifiers: Int = json.intOr("modifiers", 0)

    /** Raw DEX access flags. */
    val accessFlags: Int = json.intOr("access_flags", 0)

    /** Java return type name ("void", "int[]", "java.lang.String"). */
    val returnTypeName: String = json.requiredString("return_type_name")

    /** Java parameter type names in order. */
    val paramTypeNames: List<String> = json.arrayOrEmpty("param_type_names").strings()

    val paramCount: Int = json.intOr("param_count", paramTypeNames.size)

    val isConstructor: Boolean = json.booleanOr("is_constructor", false)

    val isStaticInitializer: Boolean = json.booleanOr("is_static_initializer", false)

    val isMethod: Boolean = json.booleanOr("is_method", !isConstructor && !isStaticInitializer)

    /** Declaring class metadata, resolved from the session cache on demand (may be null). */
    val declaredClass: DexClassInfo? by lazy {
        metadata.classInfoByName(declaredClassName)
    }

    /**
     * DexKit MethodData.returnType semantics: resolved class metadata for object return types,
     * null for primitives, void and array types. Only object types trigger a metadata lookup, and
     * the result is resolved lazily through the session cache (null for external classes).
     */
    val returnType: DexClassInfo? by lazy {
        if (isPrimitiveOrArrayReturnType) null else metadata.classInfoByName(returnTypeName)
    }

    private val isPrimitiveOrArrayReturnType: Boolean =
        returnTypeName in PRIMITIVE_JAVA_TYPE_NAMES || returnTypeName.endsWith("]")

    /** Methods called by this method, in code order. */
    val invokes: List<DexMethodInfo> by lazy {
        json.arrayOrEmpty("invokes").map { DexMethodInfo(it.jsonObject, metadata) }
    }

    /** Fields used by this method, in code order, with read/write direction. */
    val usingFields: List<DexFieldAccess> by lazy {
        json.arrayOrEmpty("using_fields").map { DexFieldAccess.parse(it.jsonObject, metadata) }
    }

    /** String constants referenced by this method, in code order. */
    val usingStrings: List<String> by lazy {
        json.arrayOrEmpty("using_strings").strings()
    }

    /** Fields read by this method (DexKit MethodData.readFields() equivalent). */
    fun readFields(): List<DexFieldInfo> =
        usingFields.filter { it.usingType.isRead }.map { it.field }

    /** Fields written by this method. */
    fun writeFields(): List<DexFieldInfo> =
        usingFields.filter { it.usingType.isWrite }.map { it.field }

    override fun toString(): String = descriptor
}
