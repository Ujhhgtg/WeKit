package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadataKind
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadataProvider
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DexKit opcode + annotation matcher semantics over the additive metadata keys. */
class OpcodeAnnotationTest {
    private val methodDescriptor = "Lcom/test/Sample;->run()V"

    private val methodDoc: JsonObject = buildJsonObject {
        put("descriptor", methodDescriptor)
        put("name", "run")
        put("declared_class_name", "com.test.Sample")
        put("method_sign", "()V")
        put("modifiers", 1)
        put("access_flags", 1)
        put("return_type_name", "void")
        put("param_type_names", JsonArray(emptyList()))
        put("param_count", 0)
        put("is_constructor", false)
        put("is_static_initializer", false)
        put("is_method", true)
        put("opcode_count", 5)
        put(
            "opcodes",
            JsonArray(
                listOf("new-instance", "invoke-direct", "return-void").map { JsonPrimitive(it) },
            ),
        )
        put("annotations", JsonArray(listOf(signatureAnnotation())))
    }

    private val classDoc: JsonObject = buildJsonObject {
        put("descriptor", "Lcom/test/Sample;")
        put("name", "com.test.Sample")
        put("simple_name", "Sample")
        put("modifiers", 1)
        put("access_flags", 1)
        put("is_array", false)
        put("interfaces", JsonArray(emptyList()))
        put("methods", JsonArray(emptyList()))
        put("fields", JsonArray(emptyList()))
        put("annotations", JsonArray(listOf(signatureAnnotation())))
    }

    private val provider = object : DexMetadataProvider {
        override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? = when {
            kind == DexMetadataKind.Method && descriptor == methodDescriptor -> methodDoc
            kind == DexMetadataKind.Class && descriptor == "Lcom/test/Sample;" -> classDoc
            else -> null
        }
    }
    private val metadata = DexMetadata(provider)
    private val evaluator = DexMatcherEvaluator(metadata)

    private fun method() = DexMethodInfo(methodDoc, metadata)
    private fun sampleClass() = DexClassInfo(classDoc, metadata)

    private fun signatureAnnotation(): JsonObject = buildJsonObject {
        put("type", "Ldalvik/annotation/Signature;")
        put("type_name", "dalvik.annotation.Signature")
        put("visibility", "system")
        put(
            "elements",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("name", "value")
                        put(
                            "value",
                            JsonArray(
                                listOf(
                                    "Lcom/tencent/mm/sdk/event/IListener<",
                                    "Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;",
                                    ">;",
                                ).map { JsonPrimitive(it) },
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private val signatureSpec = AnnotationSpec(
        type = NameSpec("dalvik.annotation.Signature"),
        visibility = AnnotationVisibility.System,
        elements = listOf(
            AnnotationElementSpec(
                NameSpec("value"),
                AnnotationValue.ListValue(
                    listOf(
                        AnnotationValue.Text("Lcom/tencent/mm/sdk/event/IListener<"),
                        AnnotationValue.Text("Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;"),
                        AnnotationValue.Text(">;"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun opcodeContainsAndCountConstraints() {
        assertTrue(evaluator.matchesMethod(method(), MethodSpec(opcodes = OpcodeSpec(listOf("new-instance")))))
        assertTrue(
            evaluator.matchesMethod(
                method(),
                MethodSpec(opcodes = OpcodeSpec(listOf("return-void", "new-instance"))),
            ),
        )
        assertFalse(evaluator.matchesMethod(method(), MethodSpec(opcodes = OpcodeSpec(listOf("missing-op")))))
        assertTrue(evaluator.matchesMethod(method(), MethodSpec(opcodes = OpcodeSpec(count = 0..10))))
        assertFalse(evaluator.matchesMethod(method(), MethodSpec(opcodes = OpcodeSpec(count = 0..2))))
        assertTrue(
            evaluator.matchesMethod(
                method(),
                MethodSpec(
                    opcodes = OpcodeSpec(
                        names = listOf("new-instance", "invoke-direct", "return-void"),
                        matchType = OpcodeMatchType.Equals,
                    ),
                ),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                method(),
                MethodSpec(
                    opcodes = OpcodeSpec(
                        names = listOf("new-instance", "return-void"),
                        matchType = OpcodeMatchType.Equals,
                    ),
                ),
            ),
        )
    }

    @Test
    fun missingOpcodeDataIsNonMatchNotError() {
        val noCodeDoc = buildJsonObject {
            methodDoc.forEach { (key, value) -> put(key, value) }
            put("opcode_count", JsonNull)
            put("opcodes", JsonArray(emptyList()))
        }
        val noCodeMethod = DexMethodInfo(noCodeDoc, metadata)
        assertFalse(
            evaluator.matchesMethod(
                noCodeMethod,
                MethodSpec(opcodes = OpcodeSpec(listOf("new-instance"))),
            ),
        )
        assertFalse(evaluator.matchesMethod(noCodeMethod, MethodSpec(opcodes = OpcodeSpec(count = 0..10))))
    }

    @Test
    fun classAnnotationConstraints() {
        assertTrue(evaluator.matchesClass(sampleClass(), ClassSpec(annotations = listOf(signatureSpec))))
        assertFalse(
            evaluator.matchesClass(
                sampleClass(),
                ClassSpec(annotations = listOf(signatureSpec.copy(type = NameSpec("java.lang.Deprecated")))),
            ),
        )
        assertFalse(
            evaluator.matchesClass(
                sampleClass(),
                ClassSpec(annotations = listOf(signatureSpec.copy(visibility = AnnotationVisibility.Runtime))),
            ),
        )
        assertFalse(
            evaluator.matchesClass(
                sampleClass(),
                ClassSpec(
                    annotations = listOf(
                        signatureSpec.copy(
                            elements = listOf(
                                AnnotationElementSpec(
                                    NameSpec("value"),
                                    AnnotationValue.ListValue(listOf(AnnotationValue.Text("wrong"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val noAnnotations = DexClassInfo(
            buildJsonObject {
                classDoc.forEach { (key, value) ->
                    if (key != "annotations") put(key, value)
                }
                put("annotations", JsonArray(emptyList()))
            },
            metadata,
        )
        assertFalse(evaluator.matchesClass(noAnnotations, ClassSpec(annotations = listOf(signatureSpec))))
    }

    @Test
    fun methodAnnotationAndEncodedValues() {
        assertTrue(evaluator.matchesMethod(method(), MethodSpec(annotations = listOf(signatureSpec))))

        val annotatedDoc = buildJsonObject {
            methodDoc.forEach { (key, value) -> put(key, value) }
            put(
                "annotations",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "Lcom/test/Marker;")
                            put("type_name", "com.test.Marker")
                            put("visibility", "runtime")
                            put(
                                "elements",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("name", "count")
                                            put("value", 42)
                                        },
                                        buildJsonObject {
                                            put("name", "enabled")
                                            put("value", true)
                                        },
                                        buildJsonObject {
                                            put("name", "target")
                                            put("value", "Lcom/x/Y;")
                                        },
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            )
        }
        val annotated = DexMethodInfo(annotatedDoc, metadata)
        val markerSpec = AnnotationSpec(
            type = NameSpec("Lcom/test/Marker;"),
            visibility = AnnotationVisibility.Runtime,
            elements = listOf(
                AnnotationElementSpec(NameSpec("count"), AnnotationValue.NumberValue(42)),
                AnnotationElementSpec(NameSpec("enabled"), AnnotationValue.BooleanValue(true)),
                AnnotationElementSpec(NameSpec("target"), AnnotationValue.DescriptorValue("com.x.Y")),
            ),
        )
        assertTrue(evaluator.matchesMethod(annotated, MethodSpec(annotations = listOf(markerSpec))))
        // Requested elements are a subset requirement: fewer requested elements still match.
        assertTrue(
            evaluator.matchesMethod(
                annotated,
                MethodSpec(annotations = listOf(markerSpec.copy(elements = markerSpec.elements.take(2)))),
            ),
        )
        // A requested element must be present with the exact encoded value.
        assertFalse(
            evaluator.matchesMethod(
                annotated,
                MethodSpec(
                    annotations = listOf(
                        markerSpec.copy(
                            elements = listOf(
                                AnnotationElementSpec(
                                    NameSpec("enabled"),
                                    AnnotationValue.BooleanValue(false),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun candidateGenerationIgnoresOpcodeAndAnnotationPredicates() {
        val query = RecordingDescriptorQuery { emptyList() }
        val scope = QueryMatcherScope(metadata, query)
        scope.findMethods(
            MethodSpec(
                name = NameSpec("run"),
                opcodes = OpcodeSpec(listOf("new-instance")),
                annotations = listOf(signatureSpec),
            ),
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
        assertFalse(sql.contains("opcode", ignoreCase = true), sql)
        assertFalse(sql.contains("annotation", ignoreCase = true), sql)
    }

    @Test
    fun annotationOnlyMethodSpecIsUnsupportedCandidateShape() {
        val query = RecordingDescriptorQuery { emptyList() }
        val scope = QueryMatcherScope(metadata, query)
        assertThrows(UnsupportedOperationException::class.java) {
            scope.findMethods(MethodSpec(annotations = listOf(signatureSpec)))
        }
        assertTrue(query.queries.isEmpty())
    }
}
