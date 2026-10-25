package dev.ujhhgtg.wekit.dex.matcher

import dev.ujhhgtg.wekit.dex.meta.DexMetadata
import dev.ujhhgtg.wekit.dex.meta.DexMetadataKind
import dev.ujhhgtg.wekit.dex.meta.DexMetadataProvider
import dev.ujhhgtg.wekit.dex.meta.DexMethodInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared fake metadata graph for the query-backed matcher tests. */
internal object MatcherFixtures {
    const val ALPHA = "Lcom/test/Alpha;"
    const val ALPHA_RUN = "Lcom/test/Alpha;->run()V"
    const val ALPHA_HELPER = "Lcom/test/Alpha;->helper(I)V"
    const val ALPHA_INIT = "Lcom/test/Alpha;-><init>()V"
    const val CALL = "Lcom/test/Call;"
    const val CALL_HELPER = "Lcom/test/Call;->helper()V"
    const val CALL_MAKE = "Lcom/test/Call;->make()Lcom/test/Base;"
    const val BASE = "Lcom/test/Base;"
    const val IFACE = "Lcom/test/I1;"
    const val GAMMA = "Lcom/test/Gamma;"
    const val GAMMA_RUN = "Lcom/test/Gamma;->run()V"
    const val ALPHA_COUNT_FIELD = "Lcom/test/Alpha;->count:I"
    const val ALPHA_NAME_FIELD = "Lcom/test/Alpha;->label:Ljava/lang/String;"

    private val entries = linkedMapOf<String, JsonObject>()

    private fun putEntry(kind: DexMetadataKind, descriptor: String, json: JsonObject) {
        entries[kind.key + "|" + descriptor] = json
    }

    private fun ref(descriptor: String, name: String): JsonObject = buildJsonObject {
        put("descriptor", descriptor)
        put("name", name)
    }

    private fun methodRef(
        descriptor: String,
        name: String,
        declaredClassName: String,
        methodSign: String,
        returnTypeName: String,
        paramTypeNames: List<String>,
        modifiers: Int = 1,
        accessFlags: Int = modifiers,
        isConstructor: Boolean = false,
        isStaticInitializer: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("descriptor", descriptor)
        put("name", name)
        put("declared_class_name", declaredClassName)
        put("method_sign", methodSign)
        put("modifiers", modifiers)
        put("access_flags", accessFlags)
        put("return_type_name", returnTypeName)
        put(
            "param_type_names",
            JsonArray(paramTypeNames.map { kotlinx.serialization.json.JsonPrimitive(it) }),
        )
        put("param_count", paramTypeNames.size)
        put("is_constructor", isConstructor)
        put("is_static_initializer", isStaticInitializer)
        put("is_method", !isConstructor && !isStaticInitializer)
    }

    private fun methodDoc(
        ref: JsonObject,
        invokes: List<JsonObject> = emptyList(),
        usingFields: List<JsonObject> = emptyList(),
        usingStrings: List<String> = emptyList(),
        usingNumbers: List<Pair<String, String>> = emptyList(),
    ): JsonObject = buildJsonObject {
        ref.forEach { (key, value) -> put(key, value) }
        put("invokes", JsonArray(invokes))
        put("using_fields", JsonArray(usingFields))
        put(
            "using_numbers",
            JsonArray(
                usingNumbers.map { (kind, value) ->
                    buildJsonObject {
                        put("kind", kind)
                        put("value", value)
                    }
                },
            ),
        )
        put(
            "using_strings",
            JsonArray(usingStrings.map { kotlinx.serialization.json.JsonPrimitive(it) }),
        )
    }

    private fun fieldRef(
        descriptor: String,
        name: String,
        declaredClassName: String,
        typeName: String,
        modifiers: Int = 1,
        typeDescriptor: String? = null,
    ): JsonObject = buildJsonObject {
        put("descriptor", descriptor)
        put("name", name)
        put("declared_class_name", declaredClassName)
        put("type_name", typeName)
        if (typeDescriptor != null) put("type_descriptor", typeDescriptor)
        put("modifiers", modifiers)
        put("access_flags", modifiers)
    }

    private fun usingField(field: JsonObject, usingType: String): JsonObject = buildJsonObject {
        put("field", field)
        put("using_type", usingType)
    }

    private fun classDoc(
        descriptor: String,
        name: String,
        modifiers: Int = 1,
        superClass: JsonObject? = null,
        interfaces: List<JsonObject> = emptyList(),
        methods: List<JsonObject> = emptyList(),
        fields: List<JsonObject> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("descriptor", descriptor)
        put("name", name)
        put("simple_name", name.substringAfterLast('.'))
        put("source_file", JsonNull)
        put("modifiers", modifiers)
        put("access_flags", modifiers)
        put("is_array", false)
        if (superClass != null) put("super_class", superClass)
        put("interfaces", JsonArray(interfaces))
        put("methods", JsonArray(methods))
        put("fields", JsonArray(fields))
    }

    init {
        val alphaDeclaredClassName = "com.test.Alpha"
        val runRef =
            methodRef(
                ALPHA_RUN,
                "run",
                alphaDeclaredClassName,
                "()V",
                "void",
                emptyList(),
            )
        val helperRef =
            methodRef(
                ALPHA_HELPER,
                "helper",
                alphaDeclaredClassName,
                "(I)V",
                "void",
                listOf("int"),
            )
        val initRef =
            methodRef(
                ALPHA_INIT,
                "<init>",
                alphaDeclaredClassName,
                "()V",
                "void",
                emptyList(),
                accessFlags = 65537,
                isConstructor = true,
            )
        val callHelperRef =
            methodRef(
                CALL_HELPER,
                "helper",
                "com.test.Call",
                "()V",
                "void",
                emptyList(),
            )
        val countFieldRef =
            fieldRef(
                ALPHA_COUNT_FIELD,
                "count",
                alphaDeclaredClassName,
                "int",
                modifiers = 8,
            )
        val nameFieldRef =
            fieldRef(
                ALPHA_NAME_FIELD,
                "label",
                alphaDeclaredClassName,
                "java.lang.String",
            )

        putEntry(
            DexMetadataKind.Method,
            ALPHA_RUN,
            methodDoc(
                runRef,
                invokes = listOf(callHelperRef),
                usingFields = listOf(usingField(countFieldRef, "read")),
                usingStrings = listOf("alpha marker", "second string"),
                usingNumbers = listOf("long" to "42", "double" to "0.5"),
            ),
        )
        putEntry(
            DexMetadataKind.Method,
            ALPHA_HELPER,
            methodDoc(helperRef, usingStrings = listOf("other")),
        )
        putEntry(DexMetadataKind.Method, ALPHA_INIT, methodDoc(initRef))
        putEntry(
            DexMetadataKind.Method,
            CALL_MAKE,
            methodDoc(
                methodRef(
                    CALL_MAKE,
                    "make",
                    "com.test.Call",
                    "()Lcom/test/Base;",
                    "com.test.Base",
                    emptyList(),
                ),
            ),
        )
        putEntry(
            DexMetadataKind.Method,
            CALL_HELPER,
            methodDoc(
                callHelperRef,
                usingStrings = listOf("call helper"),
                usingNumbers = listOf("long" to "7"),
            ),
        )
        putEntry(
            DexMetadataKind.Method,
            GAMMA_RUN,
            methodDoc(
                methodRef(GAMMA_RUN, "run", "com.test.Gamma", "()V", "void", emptyList()),
            ),
        )

        putEntry(
            DexMetadataKind.Field,
            ALPHA_COUNT_FIELD,
            fieldRef(ALPHA_COUNT_FIELD, "count", alphaDeclaredClassName, "int", 8, "I"),
        )
        putEntry(
            DexMetadataKind.Field,
            ALPHA_NAME_FIELD,
            fieldRef(
                ALPHA_NAME_FIELD,
                "label",
                alphaDeclaredClassName,
                "java.lang.String",
                1,
                "Ljava/lang/String;",
            ),
        )

        putEntry(
            DexMetadataKind.Class,
            ALPHA,
            classDoc(
                ALPHA,
                "com.test.Alpha",
                superClass = ref(BASE, "com.test.Base"),
                interfaces = listOf(ref(IFACE, "com.test.I1")),
                methods = listOf(runRef, helperRef, initRef),
                fields = listOf(countFieldRef, nameFieldRef),
            ),
        )
        putEntry(
            DexMetadataKind.Class,
            BASE,
            classDoc(BASE, "com.test.Base"),
        )
        putEntry(
            DexMetadataKind.Class,
            IFACE,
            classDoc(IFACE, "com.test.I1"),
        )
        putEntry(
            DexMetadataKind.Class,
            GAMMA,
            classDoc(
                GAMMA,
                "com.test.Gamma",
                superClass = ref("Ljava/lang/Object;", "java.lang.Object"),
            ),
        )
    }

    val provider: DexMetadataProvider =
        object : DexMetadataProvider {
            override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? =
                entries[kind.key + "|" + descriptor]
        }

    val metadata: DexMetadata = DexMetadata(provider)

    fun methodDescriptors(): List<String> =
        listOf(ALPHA_RUN, ALPHA_HELPER, ALPHA_INIT, CALL_HELPER, CALL_MAKE, GAMMA_RUN)

    fun classDescriptors(): List<String> = listOf(ALPHA, BASE, IFACE, GAMMA)

    fun fieldDescriptors(): List<String> = listOf(ALPHA_COUNT_FIELD, ALPHA_NAME_FIELD)
}

/** Fake relation provider for evaluator/scope tests (no DexSqlBridge needed). */
internal class FakeMatcherRelations(
    private val metadata: DexMetadata,
    private val callersByMethod: Map<String, List<String>> = emptyMap(),
    private val fieldAccessByKey: Map<String, List<String>> = emptyMap(),
) : DexMatcherRelations {
    override fun callers(methodDescriptor: String): List<DexMethodInfo> =
        callersByMethod[methodDescriptor].orEmpty().mapNotNull { metadata.methodInfo(it) }

    override fun fieldAccessMethods(
        fieldDescriptor: String,
        usingType: String,
    ): List<DexMethodInfo> =
        fieldAccessByKey[fieldDescriptor + "|" + usingType].orEmpty().mapNotNull {
            metadata.methodInfo(it)
        }
}

/** Fake query executor: records every generated SQL and returns preconfigured descriptor rows. */
internal class RecordingDescriptorQuery(private val rowsFor: (String) -> List<String>) :
    DexDescriptorQuery {
    val queries = mutableListOf<String>()

    override fun descriptors(sql: String): List<String> {
        queries += sql
        return rowsFor(sql)
    }
}
