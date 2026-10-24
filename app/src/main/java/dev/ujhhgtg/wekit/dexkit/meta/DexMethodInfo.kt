package dev.ujhhgtg.wekit.dexkit.meta

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * DexKit MethodData replacement (method and constructor metadata).
 *
 * Invokes/usingFields/usingStrings live in the top-level method JSON. Class JSON member refs are
 * shallow (they carry only scalar fields), so relation lists lazily resolve the full top-level
 * method document through the session [DexMetadata] cache when absent inline. [declaredClass]
 * resolves through the same cache on demand; it is null for external classes.
 */
class DexMethodInfo
internal constructor(private val json: JsonObject, private val metadata: DexMetadata) {
    /** Raw JSON backing this instance (shallow class member ref or full top-level method doc). */
    internal val rawJson: JsonObject
        get() = json

    /** Full method descriptor ("Lcom/foo/Bar;->name(I)V"). */
    val descriptor: String = json.requiredString("descriptor")

    /** Method name ("name"); constructors use "<init>". */
    val name: String = json.requiredString("name")

    /** Dotted Java name of the declaring class. */
    val declaredClassName: String = json.requiredString("declared_class_name")

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
     * DexKit MethodData.returnType semantics: resolved class metadata for object return types, null
     * for primitives, void and array types. Only object types trigger a metadata lookup, and the
     * result is resolved lazily through the session cache (null for external classes).
     */
    val returnType: DexClassInfo? by lazy {
        if (isPrimitiveOrArrayReturnType) null else metadata.classInfoByName(returnTypeName)
    }

    private val isPrimitiveOrArrayReturnType: Boolean =
        returnTypeName in PRIMITIVE_JAVA_TYPE_NAMES || returnTypeName.endsWith("]")

    /**
     * JSON that carries the relation lists. Top-level method documents include them inline; shallow
     * class member refs resolve the full top-level document through the session cache. The
     * self-check prevents a recursive lookup when a method document has no relation keys.
     */
    private val relationsJson: JsonObject by lazy {
        if (json.hasMethodRelations()) {
            json
        } else {
            val full = metadata.methodInfo(descriptor)
            if (full != null && full !== this && full.json.hasMethodRelations()) full.json else json
        }
    }

    private fun JsonObject.hasMethodRelations(): Boolean =
        containsKey("invokes") || containsKey("using_fields") || containsKey("using_strings")

    /** Methods called by this method, in code order. */
    val invokes: List<DexMethodInfo> by lazy {
        relationsJson.arrayOrEmpty("invokes").map { DexMethodInfo(it.jsonObject, metadata) }
    }

    /** Fields used by this method, in code order, with read/write direction. */
    val usingFields: List<DexFieldAccess> by lazy {
        relationsJson.arrayOrEmpty("using_fields").map {
            DexFieldAccess.parse(it.jsonObject, metadata)
        }
    }

    /** String constants referenced by this method, in code order. */
    val usingStrings: List<String> by lazy {
        relationsJson.arrayOrEmpty("using_strings").strings()
    }

    /** Fields read by this method (DexKit MethodData.readFields() equivalent). */
    val readFields: List<DexFieldInfo>
        get() =
            usingFields.filter { it.usingType.isRead }.map { it.field }.distinctBy { it.descriptor }

    /** Fields written by this method. */
    val writeFields: List<DexFieldInfo>
        get() =
            usingFields
                .filter { it.usingType.isWrite }
                .map { it.field }
                .distinctBy { it.descriptor }

    override fun toString(): String = descriptor
}
