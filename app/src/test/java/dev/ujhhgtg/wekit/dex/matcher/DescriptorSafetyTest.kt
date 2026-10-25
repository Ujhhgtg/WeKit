package dev.ujhhgtg.wekit.dex.matcher

import dev.ujhhgtg.wekit.dex.DexSqlException
import dev.ujhhgtg.wekit.dex.DexSqlQueryResult
import dev.ujhhgtg.wekit.dex.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dex.meta.DexMetadata
import dev.ujhhgtg.wekit.dex.meta.DexMetadataKind
import dev.ujhhgtg.wekit.dex.meta.DexMetadataProvider
import dev.ujhhgtg.wekit.dex.meta.DexMethodInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Array/primitive descriptor safety: the shared helper, candidate SQL emission, evaluator matching
 * and raw prefilters must never convert an array/primitive name into a malformed class descriptor.
 */
class DescriptorSafetyTest {
    private val metadata = MatcherFixtures.metadata
    private val evaluator = DexMatcherEvaluator(metadata)

    // ------------------------------------------------------------------
    // Shared helper
    // ------------------------------------------------------------------

    @Test
    fun typeNameConversionNeverProducesMalformedDescriptors() {
        assertEquals("byte[]", javaTypeNameOf("[B"))
        assertEquals("com.x.Y[]", javaTypeNameOf("[Lcom/x/Y;"))
        assertEquals("int[][]", javaTypeNameOf("[[I"))
        assertEquals("com.x.Y", javaTypeNameOf("Lcom/x/Y;"))
        assertEquals("byte[]", javaTypeNameOf("byte[]"))
        assertEquals("int", javaTypeNameOf("int"))
        assertEquals("com.x.Y[][]", javaTypeNameOf("com.x.Y[][]"))

        assertEquals("[B", typeDescriptorOrNull("[B"))
        assertEquals("[Lcom/x/Y;", typeDescriptorOrNull("[Lcom/x/Y;"))
        assertEquals("[[I", typeDescriptorOrNull("[[I"))
        assertEquals("[B", typeDescriptorOrNull("byte[]"))
        assertEquals("I", typeDescriptorOrNull("int"))
        assertEquals("[[Lcom/x/Y;", typeDescriptorOrNull("com.x.Y[][]"))
        assertEquals("Lcom/x/Y;", typeDescriptorOrNull("com.x.Y"))
        // Malformed array-ish forms are rejected instead of becoming "L...[ ];" descriptors.
        assertNull(typeDescriptorOrNull("Lcom/x/c[];"))
        assertNull(typeDescriptorOrNull("[Lcom/x/c[];"))
        assertNull(typeDescriptorOrNull(""))
    }

    @Test
    fun classDescriptorConversionRejectsPrimitivesAndArrays() {
        assertEquals("Lcom/x/Y;", classDescriptorOrNull("com.x.Y"))
        assertEquals("Lcom/x/Y;", classDescriptorOrNull("Lcom/x/Y;"))
        assertNull(classDescriptorOrNull("int"))
        assertNull(classDescriptorOrNull("byte[]"))
        assertNull(classDescriptorOrNull("[B"))
        assertNull(classDescriptorOrNull("[Lcom/x/Y;"))
        assertNull(classDescriptorOrNull("Lcom/x/c[];"))
        assertEquals("com.x.Y", dottedClassNameOrNull("Lcom/x/Y;"))
        assertEquals("com.x.Y", dottedClassNameOrNull("com.x.Y"))
        assertNull(dottedClassNameOrNull("[Lcom/x/Y;"))
    }

    @Test
    fun methodAndFieldDescriptorValidation() {
        assertTrue(isRealMethodDescriptor("Lcom/x/Y;->run([B)[Lcom/x/Y;"))
        assertFalse(isRealMethodDescriptor("[Lcom/x/Y;->clone()Ljava/lang/Object;"))
        assertFalse(isRealMethodDescriptor("[B"))
        assertFalse(isRealMethodDescriptor("Lcom/x/Y;"))
        assertTrue(isRealFieldDescriptor("Lcom/x/Y;->names:[B"))
        assertFalse(isRealFieldDescriptor("[Lcom/x/Y;->names:[B"))
        assertFalse(isRealFieldDescriptor("[B"))
    }

    // ------------------------------------------------------------------
    // Evaluator: arrays/primitives are compared via name normalization
    // ------------------------------------------------------------------

    @Test
    fun evaluatorFieldTypeArrayAndPrimitiveNeverCrash() {
        val arrayField =
            fieldInfo(
                descriptor = "Lcom/test/Alpha;->names:[B",
                name = "names",
                typeName = "[B",
                declaredClassName = "com.test.Alpha",
            )
        // No match for incompatible primitive/object specs, and no metadata conversion.
        assertFalse(
            evaluator.matchesField(
                arrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("int"))),
            ),
        )
        assertFalse(
            evaluator.matchesField(
                arrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("java.lang.String"))),
            ),
        )
        assertFalse(
            evaluator.matchesField(
                arrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("byte", NameMatch.Contains))),
            ),
        )
        // Explicitly compatible exact specs match (Java name or descriptor form).
        assertTrue(
            evaluator.matchesField(
                arrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("byte[]"))),
            ),
        )
        assertTrue(
            evaluator.matchesField(
                arrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("[B"))),
            ),
        )

        val objectArrayField =
            fieldInfo(
                descriptor = "Lcom/test/Alpha;->items:[Lcom/x/Y;",
                name = "items",
                typeName = "com.x.Y[]",
                declaredClassName = "com.test.Alpha",
            )
        assertFalse(
            evaluator.matchesField(
                objectArrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("com.x", NameMatch.Contains))),
            ),
        )
        assertFalse(
            evaluator.matchesField(
                objectArrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("com.x", NameMatch.StartsWith))),
            ),
        )
        assertFalse(
            evaluator.matchesField(
                objectArrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("Y[]", NameMatch.EndsWith))),
            ),
        )
        assertTrue(
            evaluator.matchesField(
                objectArrayField,
                FieldSpec(type = ClassSpec(className = NameSpec("com.x.Y[]"))),
            ),
        )
    }

    @Test
    fun evaluatorMethodParamAndReturnArrayDescriptors() {
        val method =
            methodInfo(
                descriptor = "Lcom/test/Alpha;->run([B)[Lcom/x/Y;",
                name = "run",
                declaredClassName = "com.test.Alpha",
                returnTypeName = "[Lcom/x/Y;",
                paramTypeNames = listOf("byte[]"),
                paramCount = 1,
            )
        assertTrue(evaluator.matchesMethod(method, MethodSpec(paramTypes = listOf(TypeSpec("[B")))))
        assertTrue(
            evaluator.matchesMethod(method, MethodSpec(paramTypes = listOf(TypeSpec("byte[]")))),
        )
        assertFalse(
            evaluator.matchesMethod(method, MethodSpec(paramTypes = listOf(TypeSpec("int")))),
        )

        // Array return types only match explicitly compatible exact specs; Contains/StartsWith/
        // EndsWith must be a clean no-match (the DexKit returnType class cannot match arrays).
        assertFalse(
            evaluator.matchesMethod(
                method,
                MethodSpec(
                    returnType = ClassSpec(className = NameSpec("com.x", NameMatch.Contains)),
                ),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                method,
                MethodSpec(
                    returnType = ClassSpec(className = NameSpec("com.x", NameMatch.StartsWith)),
                ),
            ),
        )
        assertTrue(
            evaluator.matchesMethod(
                method,
                MethodSpec(returnType = ClassSpec(className = NameSpec("com.x.Y[]"))),
            ),
        )
        assertTrue(
            evaluator.matchesMethod(
                method,
                MethodSpec(returnType = ClassSpec(className = NameSpec("[Lcom/x/Y;"))),
            ),
        )
        val primitiveMethod =
            methodInfo(
                descriptor = "Lcom/test/Alpha;->count()I",
                name = "count",
                declaredClassName = "com.test.Alpha",
                returnTypeName = "int",
                paramTypeNames = emptyList(),
                paramCount = 0,
            )
        assertFalse(
            evaluator.matchesMethod(
                primitiveMethod,
                MethodSpec(returnType = ClassSpec(className = NameSpec("in", NameMatch.Contains))),
            ),
        )
        assertTrue(
            evaluator.matchesMethod(
                primitiveMethod,
                MethodSpec(returnType = ClassSpec(className = NameSpec("int"))),
            ),
        )
        assertTrue(
            evaluator.matchesMethod(
                primitiveMethod,
                MethodSpec(returnType = ClassSpec(className = NameSpec("I"))),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Candidate SQL emission
    // ------------------------------------------------------------------

    @Test
    fun candidateSqlUsesSafeTypeDescriptors() {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        scope.findMethods(
            methodSpec {
                name("helper")
                paramTypes(
                    TypeSpec("[B"),
                    TypeSpec("[Lx/Y;"),
                    TypeSpec("byte[]"),
                    TypeSpec("int"),
                    TypeSpec("com.x.Y[][]"),
                )
                returnType { className("[B") }
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("m.param_descriptors = '[B,[Lx/Y;,[B,I,[[Lcom/x/Y;'"), sql)
        assertTrue(sql.contains("m.return_descriptor = '[B'"), sql)
        assertFalse(sql.contains("L[Lcom"), sql)
        assertFalse(sql.contains("[];"), sql)
    }

    @Test
    fun fieldCandidateSqlUsesSafeTypeDescriptors() {
        val query = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        // Type-only spec: the type predicate is the (last-resort) candidate source, so the safe
        // descriptor form is what actually reaches the SQL.
        scope.findFields(fieldSpec { type { className("com.x.Y[][]") } })
        val sql = query.queries.single()
        assertTrue(sql.contains("f.type_descriptor = '[[Lcom/x/Y;'"), sql)
        assertFalse(sql.contains("[];"), sql)
    }

    @Test
    fun malformedFieldTypeIsUnsupportedWithoutSql() {
        val query = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        assertThrows(UnsupportedOperationException::class.java) {
            scope.findFields(fieldSpec { type { className("Lcom/x/c[];") } })
        }
        assertTrue(query.queries.isEmpty())
    }

    @Test
    fun classCandidateDescriptorFormIsNormalized() {
        val query = RecordingDescriptorQuery { MatcherFixtures.classDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        val results = scope.findClasses(classSpec { className("Lcom/test/Alpha;") })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        assertTrue(
            query.queries.single().contains("c.descriptor = 'Lcom/test/Alpha;'"),
            query.queries.single(),
        )
    }

    @Test
    fun invalidArrayDescriptorsAreSkippedBeforeMetadataLookup() {
        val throwingProvider = DexMetadataProvider { kind, descriptor ->
            if (descriptor.startsWith("[")) {
                throw IllegalArgumentException("malformed class descriptor $descriptor")
            }
            MatcherFixtures.provider.query(kind, descriptor)
        }
        val metadata = DexMetadata(throwingProvider)
        val methodQuery = RecordingDescriptorQuery {
            listOf("[Lcom/x/Y;", "[B", MatcherFixtures.ALPHA_HELPER)
        }
        val methodScope = QueryMatcherScope(metadata, methodQuery)
        val methods = methodScope.findMethods(MethodSpec(name = NameSpec("helper")))
        assertEquals(listOf(MatcherFixtures.ALPHA_HELPER), methods.map { it.descriptor })

        val classQuery = RecordingDescriptorQuery {
            listOf("[Lcom/x/Y;", "[B", MatcherFixtures.ALPHA)
        }
        val classScope = QueryMatcherScope(metadata, classQuery)
        val classes =
            classScope.findClasses(
                classSpec { className("com.test", NameMatch.StartsWith) },
            )
        assertEquals(listOf(MatcherFixtures.ALPHA), classes.map { it.descriptor })
    }

    // ------------------------------------------------------------------
    // Relation queries and native metadata failure containment
    // ------------------------------------------------------------------

    @Test
    fun fieldAccessRelationSelectsAccessingMethodDescriptor() {
        var capturedSql = ""
        val relations =
            BridgeMatcherRelations(
                callersOf = { null },
                query = { sql ->
                    capturedSql = sql
                    DexSqlQueryResult(
                        columns = listOf("descriptor"),
                        rows = listOf(listOf(MatcherFixtures.ALPHA_RUN)),
                    )
                },
                metadata = metadata,
            )
        val methods = relations.fieldAccessMethods(MatcherFixtures.ALPHA_COUNT_FIELD, "get")
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), methods.map { it.descriptor })
        assertTrue(capturedSql.contains("SELECT DISTINCT m.descriptor AS method"), capturedSql)
        assertFalse(capturedSql.contains("SELECT DISTINCT fm0.descriptor"), capturedSql)
        assertTrue(
            capturedSql.contains("JOIN method_field_accesses fa0 ON fa0.method = m.descriptor"),
            capturedSql,
        )
        assertTrue(capturedSql.contains("fa0.file_id = m.file_id"), capturedSql)
        assertTrue(capturedSql.contains("fa0.method_idx = m.method_idx"), capturedSql)
    }

    @Test
    fun nativeMethodDocumentFailureSkipsCandidateInsteadOfAborting() {
        val failingDescriptor = MatcherFixtures.ALPHA_RUN
        val throwingProvider = DexMetadataProvider { kind, descriptor ->
            if (kind == DexMetadataKind.Method && descriptor == failingDescriptor) {
                throw DexSqlException(
                    "malformed class descriptor \"[B\": expected Lpkg/Class;",
                )
            }
            MatcherFixtures.provider.query(kind, descriptor)
        }
        val metadata = DexMetadata(throwingProvider)
        val query = RecordingDescriptorQuery {
            listOf(MatcherFixtures.ALPHA_RUN, MatcherFixtures.ALPHA_HELPER)
        }
        val scope =
            QueryMatcherScope(
                metadata = metadata,
                query = query,
                rawMetadata = throwingProvider::query,
            )
        val results = scope.findMethods(MethodSpec(name = NameSpec("helper")))
        assertEquals(listOf(MatcherFixtures.ALPHA_HELPER), results.map { it.descriptor })
    }

    @Test
    fun classStringsEvaluationNeverReadsNativeMethodDocuments() {
        var methodDocumentQueries = 0
        val throwingProvider = DexMetadataProvider { kind, descriptor ->
            if (kind == DexMetadataKind.Method) {
                methodDocumentQueries++
                throw DexSqlException(
                    "malformed class descriptor \"[B\": expected Lpkg/Class;",
                )
            }
            MatcherFixtures.provider.query(kind, descriptor)
        }
        val metadata = DexMetadata(throwingProvider)
        val query = RecordingDescriptorQuery { sql ->
            when {
                sql.contains("FROM classes") -> {
                    MatcherFixtures.classDescriptors()
                }

                sql.contains("class_descriptor = '") -> {
                    if (sql.contains("class_descriptor = 'Lcom/test/Alpha;'")) {
                        MatcherFixtures.methodDescriptors()
                    } else {
                        emptyList()
                    }
                }

                else -> {
                    MatcherFixtures.methodDescriptors()
                }
            }
        }
        val scope =
            QueryMatcherScope(
                metadata = metadata,
                query = query,
                rawMetadata = throwingProvider::query,
            )
        // classStorageFeatureService-like shape: class can only be found through package scan and
        // its method specs constrain return-type classes usingStrings (no metadata method docs).
        val spec = classSpec {
            method { returnType { usingString("alpha marker") } }
            method { returnType { usingString("other") } }
        }
        scope.findClasses(spec.copy(searchPackages = listOf("com.test")))
        assertEquals(0, methodDocumentQueries)
    }

    @Test
    fun classBaseSettingItemShapeMapsCandidatesWithoutMethodDocuments() {
        var methodDocumentQueries = 0
        val throwingProvider = DexMetadataProvider { kind, descriptor ->
            if (kind == DexMetadataKind.Method) {
                methodDocumentQueries++
                throw DexSqlException(
                    "malformed class descriptor \"[B\": expected Lpkg/Class;",
                )
            }
            MatcherFixtures.provider.query(kind, descriptor)
        }
        val metadata = DexMetadata(throwingProvider)
        val query = RecordingDescriptorQuery { sql ->
            when {
                sql.contains("class_descriptor = '") -> {
                    if (sql.contains("class_descriptor = 'Lcom/test/Alpha;'")) {
                        MatcherFixtures.methodDescriptors()
                    } else {
                        emptyList()
                    }
                }

                sql.contains("FROM classes") -> {
                    MatcherFixtures.classDescriptors()
                }

                else -> {
                    MatcherFixtures.methodDescriptors()
                }
            }
        }
        val scope =
            QueryMatcherScope(
                metadata = metadata,
                query = query,
                rawMetadata = throwingProvider::query,
            )
        // classBaseSettingItem-like shape: class-level strings + nested init method + interfaces.
        scope.findClasses(
            classSpec {
                usingEqString("")
                usingEqString("activity")
                method {
                    name("<init>")
                    paramTypes(TypeSpec("androidx.appcompat.app.AppCompatActivity"))
                }
                interfaceSpec { className("com.test", NameMatch.StartsWith) }
            },
        )
        assertEquals(0, methodDocumentQueries)
    }

    @Test
    fun emittedSqlNeverUsesArrayClassDescriptorLiterals() {
        val queries = mutableListOf<String>()
        val query = RecordingDescriptorQuery { sql ->
            queries += sql
            emptyList()
        }
        val scope = QueryMatcherScope(metadata, query)
        scope.findMethods(
            methodSpec {
                declaredClass { className("Lcom/test/Alpha;") }
                returnType { className("[B") }
                paramTypes(TypeSpec("[B"), TypeSpec("[Lx/Y;"))
            },
        )
        scope.findFields(
            fieldSpec {
                name("names")
                type { className("com.x.Y[][]") }
            },
        )
        for (sql in queries) {
            assertFalse(sql.contains("class_descriptor = '["), sql)
            assertFalse(sql.contains("superclass_descriptor = '["), sql)
            assertFalse(sql.contains("declared_class = '["), sql)
            assertFalse(sql.contains("L[L"), sql)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun fieldInfo(
        descriptor: String,
        name: String,
        typeName: String,
        declaredClassName: String,
    ): DexFieldInfo =
        DexFieldInfo(
            buildJsonObject {
                put("descriptor", descriptor)
                put("name", name)
                put("type_name", typeName)
                put("declared_class_name", declaredClassName)
            },
            metadata,
        )

    private fun methodInfo(
        descriptor: String,
        name: String,
        declaredClassName: String,
        returnTypeName: String,
        paramTypeNames: List<String>,
        paramCount: Int,
    ): DexMethodInfo =
        DexMethodInfo(
            buildJsonObject {
                put("descriptor", descriptor)
                put("name", name)
                put("declared_class_name", declaredClassName)
                put("method_sign", "(...)...")
                put("modifiers", 1)
                put("access_flags", 1)
                put("return_type_name", returnTypeName)
                put(
                    "param_type_names",
                    JsonArray(paramTypeNames.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )
                put("param_count", paramCount)
                put("is_constructor", false)
                put("is_static_initializer", false)
                put("is_method", true)
            },
            metadata,
        )
}
