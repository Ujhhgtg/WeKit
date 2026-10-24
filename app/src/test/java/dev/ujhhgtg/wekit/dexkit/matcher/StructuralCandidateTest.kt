package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadataKind
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadataProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Canonical-fast-path candidate coverage. The pinned DexSQL engine fast-paths only the shapes the
 * SQL DSL emits; hand-built relation joins fall into the generic executor and OOM a 10G worker, so
 * these tests assert both the generated SQL shape and the matcher behaviour.
 */
class StructuralCandidateTest {
    private val metadata = MatcherFixtures.metadata
    private val relations = FakeMatcherRelations(
        metadata = metadata,
        callersByMethod = mapOf(MatcherFixtures.ALPHA_RUN to listOf(MatcherFixtures.CALL_HELPER)),
        fieldAccessByKey = mapOf(
            MatcherFixtures.ALPHA_COUNT_FIELD + "|get" to listOf(MatcherFixtures.ALPHA_RUN),
        ),
    )

    private fun scopeFor(query: DexDescriptorQuery): QueryMatcherScope = QueryMatcherScope(
        metadata = metadata,
        query = query,
        relations = relations,
        rawMetadata = MatcherFixtures.provider::query,
    )

    private fun methodScope(): Pair<QueryMatcherScope, RecordingDescriptorQuery> {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        return scopeFor(query) to query
    }

    private fun classScope(): Pair<QueryMatcherScope, RecordingDescriptorQuery> {
        val query = RecordingDescriptorQuery { MatcherFixtures.classDescriptors() }
        return scopeFor(query) to query
    }

    private fun fieldScope(): Pair<QueryMatcherScope, RecordingDescriptorQuery> {
        val query = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        return scopeFor(query) to query
    }

    // ------------------------------------------------------------------
    // Canonical candidate SQL shapes
    // ------------------------------------------------------------------

    @Test
    fun methodCandidateUsesCanonicalMethodLookupShape() {
        val (scope, query) = methodScope()
        val results = scope.findMethods(
            methodSpec {
                name("helper")
                paramCount = 1..1
                paramTypes(TypeSpec("int"))
                usingStrings("other")
            },
        )
        assertEquals(listOf(MatcherFixtures.ALPHA_HELPER), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.startsWith("SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor"), sql)
        assertTrue(sql.contains("ms0.file_id = m.file_id"), sql)
        assertTrue(sql.contains("ms0.method_idx = m.method_idx"), sql)
        assertTrue(sql.contains("m.name = 'helper'"), sql)
        assertTrue(sql.contains("m.param_count = 1"), sql)
        assertTrue(sql.contains("m.param_descriptors = 'I'"), sql)
        assertTrue(sql.contains("instr(ms0.value, 'other') > 0"), sql)
        assertTrue(sql.endsWith("ORDER BY m.descriptor;"), sql)
        assertNoRawRelationTables(sql)
        assertEveryJoinOnIsSingleEquality(sql)
    }

    @Test
    fun declaredClassUsingStringsCandidateUsesCanonicalLiteralUnits() {
        // Candidate generation keeps the canonical class-literal-unit shape. Class-level strings
        // evaluation uses the batched literal->class set, so the fake must answer the global
        // method_strings query with the methods that actually carry the literal.
        val query = RecordingDescriptorQuery { sql ->
            when {
                sql.contains("class_descriptor = '") -> MatcherFixtures.methodDescriptors()
                else -> globalClassStringRows(sql)
            }
        }
        val scope = scopeFor(query)
        val results = scope.findMethods(
            methodSpec {
                name("run")
                declaredClass { usingString("alpha marker") }
            },
        )
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), results.map { it.descriptor })
        // Fixture truth: Gamma carries no "alpha marker" string constant; only Alpha does.
        assertFalse(
            DexMatcherEvaluator(metadata).matchesClass(
                metadata.classInfo(MatcherFixtures.GAMMA)!!,
                ClassSpec(usingStrings = listOf(StringSpec("alpha marker"))),
            ),
        )
        val candidateSql = query.queries.filter {
            it.contains("JOIN methods ccm0") && !it.contains("class_descriptor = '")
        }
        val sql = candidateSql.single()
        assertTrue(sql.contains("JOIN methods ccm0 ON ccm0.class_descriptor = m.class_descriptor"), sql)
        assertTrue(sql.contains("JOIN method_strings ms0 ON ms0.method = ccm0.descriptor"), sql)
        assertTrue(sql.contains("ccm0.file_id = m.file_id"), sql)
        assertTrue(sql.contains("ccm0.class_idx = m.class_idx"), sql)
        assertTrue(sql.contains("ms0.file_id = ccm0.file_id"), sql)
        assertTrue(sql.contains("ms0.method_idx = ccm0.method_idx"), sql)
        assertTrue(sql.contains("instr(ms0.value, 'alpha marker') > 0"), sql)
        assertNoRawRelationTables(sql)
        assertEveryJoinOnIsSingleEquality(sql)
    }

    @Test
    fun classUsingStringsCandidateResolvesInTwoCanonicalSteps() {
        val query = RecordingDescriptorQuery { sql ->
            // Candidate generation and evaluation share the batched literal->class set.
            globalClassStringRows(sql)
        }
        val scope = scopeFor(query)
        val results = scope.findClasses(
            classSpec {
                usingString("alpha marker")
                usingString("other")
            },
        )
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val candidateQueries = query.queries.filter { !it.contains("class_descriptor = '") }
        assertEquals(2, candidateQueries.size)
        for (sql in candidateQueries) {
            assertTrue(sql.startsWith("SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings"), sql)
            assertTrue(sql.contains("ON ms0.method = m.descriptor"), sql)
            assertTrue(sql.contains("ORDER BY m.descriptor;"), sql)
            assertFalse(sql.contains("JOIN classes"), sql)
            assertNoRawRelationTables(sql)
            assertEveryJoinOnIsSingleEquality(sql)
        }
    }

    @Test
    fun classNestedMethodCandidateResolvesViaCanonicalMethodLookup() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM methods")) MatcherFixtures.methodDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        val scope = scopeFor(query)
        val results = scope.findClasses(classSpec { method { name("run") } })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
        assertFalse(sql.contains("JOIN classes"), sql)
        assertNoRawRelationTables(sql)
    }

    @Test
    fun classNestedFieldCandidateResolvesViaCanonicalFieldLookup() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM fields")) MatcherFixtures.fieldDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        val scope = scopeFor(query)
        val results = scope.findClasses(classSpec { field { name("count") } })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.startsWith("SELECT DISTINCT f.descriptor AS descriptor FROM fields f"), sql)
        assertTrue(sql.contains("f.name = 'count'"), sql)
        assertNoRawRelationTables(sql)
    }

    @Test
    fun classSuperClassCandidateUsesCanonicalSuperclassShape() {
        val (scope, query) = classScope()
        val results = scope.findClasses(classSpec { superClass { className("com.test.Base") } })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("c.superclass_descriptor = 'Lcom/test/Base;'"), sql)
    }

    @Test
    fun classGenericRespShapeDrivesFromOpMethodNotConstructor() {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        scopeFor(query).findClasses(
            classSpec {
                method {
                    name("<init>")
                    opcodes = OpcodeSpec(names = listOf("new-instance"))
                }
                method {
                    name("op")
                    paramTypes(TypeSpec("int"), TypeSpec("java.lang.Object[]"))
                    returnType { className("int") }
                    opcodes = OpcodeSpec(count = 0..10)
                }
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'op'"), sql)
        assertTrue(sql.contains("m.param_descriptors = 'I,[Ljava/lang/Object;'"), sql)
        assertTrue(sql.contains("m.return_descriptor = 'I'"), sql)
        assertTrue(sql.contains("m.opcode_count >= 0"), sql)
        assertTrue(sql.contains("m.opcode_count <= 10"), sql)
        assertFalse(sql.contains("<init>"), sql)
    }

    @Test
    fun classRawReqShapeDrivesFromOpMethodNotConstructor() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("c.field_count")) MatcherFixtures.classDescriptors()
            else MatcherFixtures.methodDescriptors()
        }
        scopeFor(query).findClasses(
            classSpec {
                field { type { className("byte[]") } }
                fieldCount = 1..1
                method {
                    name("<init>")
                    opcodes = OpcodeSpec(names = listOf("new-instance"))
                }
                method {
                    name("op")
                    paramTypes(TypeSpec("int"), TypeSpec("java.lang.Object[]"))
                    returnType { className("int") }
                    opcodes = OpcodeSpec(count = 0..10)
                }
                method {
                    name("toByteArray")
                    returnType { className("byte[]") }
                }
            },
        )
        val sql = query.queries.first { it.contains("m.name = 'op'") }
        assertTrue(sql.contains("m.name = 'op'"), sql)
        assertFalse(sql.contains("<init>"), sql)
        assertFalse(sql.contains("toByteArray"), sql)
        // fieldCount is pushed into a separate canonical classes.field_count intersection.
        assertTrue(query.queries.any { it.contains("c.field_count >= 1") }, query.queries.joinToString())
        assertTrue(query.queries.any { it.contains("c.field_count <= 1") }, query.queries.joinToString())
    }

    @Test
    fun opcodeOnlyClassSpecIsUnsupportedWithZeroSql() {
        val query = RecordingDescriptorQuery { MatcherFixtures.classDescriptors() }
        assertThrows(UnsupportedOperationException::class.java) {
            scopeFor(query).findClasses(
                classSpec {
                    method {
                        name("<init>")
                        opcodes = OpcodeSpec(names = listOf("new-instance"))
                    }
                },
            )
        }
        assertTrue(query.queries.isEmpty())
    }

    @Test
    fun nestedMethodSelectivityPrefersParamTypesOverReturnType() {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        scopeFor(query).findClasses(
            classSpec {
                method { returnType { className("boolean") } }
                method { paramTypes(TypeSpec("int")) }
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("m.param_descriptors = 'I'"), sql)
        assertFalse(sql.contains("m.return_descriptor"), sql)
    }

    @Test
    fun nestedFieldSelectivityPrefersNamedFieldOverNarrowType() {
        val query = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        scopeFor(query).findClasses(
            classSpec {
                field { type { className("com.test.Alpha") } }
                field { name("count") }
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("f.name = 'count'"), sql)
        assertFalse(sql.contains("f.type_descriptor"), sql)
    }

    @Test
    fun classInterfaceOnlySpecIsUnsupportedWithoutFastPath() {
        // Interface/count-only class specs cannot be prefiltered without fetching the full
        // metadata of every class; fail fast (zero SQL) instead of enumerating hundreds of MB/GBs.
        val (scope, query) = classScope()
        assertThrows(UnsupportedOperationException::class.java) {
            scope.findClasses(classSpec { interfaceSpec { className("com.test.I1") } })
        }
        assertTrue(query.queries.isEmpty())
    }

    @Test
    fun classMemberCountOnlySpecIsUnsupportedWithoutFastPath() {
        val (scope, query) = classScope()
        assertThrows(UnsupportedOperationException::class.java) {
            scope.findClasses(classSpec { methodCount = 3..3 })
        }
        assertTrue(query.queries.isEmpty())
    }

    @Test
    fun classModifiersOnlySpecUsesCanonicalAccessFlagScan() {
        val (scope, query) = classScope()
        scope.findClasses(classSpec { modifiers = 512 })
        val sql = query.queries.single()
        assertTrue(sql.contains("(c.access_flags & 512) = 512"), sql)
        assertTrue(sql.contains("FROM classes c"), sql)
    }

    @Test
    fun classIOnSceneEndShapeUsesNestedMethodTwoStep() {
        // Real shape: no className/package/usingStrings; modifiers INTERFACE + interfaceCount 0..0
        // + methodCount 1..1 + a selective nested method (name/paramCount/paramTypes/returnType).
        // It must derive candidates from the canonical method lookup, never from class enumeration.
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM methods")) MatcherFixtures.methodDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        scopeFor(query).findClasses(
            classSpec {
                modifiers = 512
                interfaceCount = 0..0
                methodCount = 1..1
                method {
                    name("onSceneEnd")
                    paramCount = 4..4
                    paramTypes(
                        TypeSpec("int"),
                        TypeSpec("int"),
                        TypeSpec("java.lang.String"),
                        TypeSpec("java.lang.String"),
                    )
                    returnType { className("void") }
                }
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.startsWith("SELECT DISTINCT m.descriptor AS method FROM methods m"), sql)
        assertTrue(sql.contains("m.name = 'onSceneEnd'"), sql)
        assertTrue(sql.contains("m.param_count = 4"), sql)
        assertTrue(sql.contains("m.param_descriptors = 'I,I,Ljava/lang/String;,Ljava/lang/String;'"), sql)
        assertTrue(sql.contains("m.return_descriptor = 'V'"), sql)
        assertFalse(sql.contains("FROM classes"), sql)
    }

    @Test
    fun classEmptyUsingStringLiteralIsSkippedInCandidateSql() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM classes")) MatcherFixtures.classDescriptors()
            else MatcherFixtures.methodDescriptors()
        }
        scopeFor(query).findClasses(
            classSpec {
                usingEqString("")
                usingString("alpha marker")
            },
        )
        // Candidate generation (no class-name constraint) must only use the non-empty literal.
        // Class-level string evaluation may add its own batched literal query; exclude it too so
        // this test keeps asserting the candidate shape.
        val candidateSql = query.queries.filter {
            it.contains("JOIN method_strings") &&
                !it.contains("class_descriptor = '") &&
                it.contains("instr(ms0.value, 'alpha marker')")
        }
        assertTrue(candidateSql.isNotEmpty(), query.queries.joinToString(" | "))
        for (sql in candidateSql) {
            assertTrue(sql.contains("instr(ms0.value, 'alpha marker') > 0"), sql)
            assertFalse(sql.contains("value = ''"), sql)
        }
    }

    @Test
    fun emptyMethodUsingStringLiteralProducesNoMethodStringsPredicate() {
        val (scope, query) = methodScope()
        scope.findMethods(
            methodSpec {
                name("run")
                usingEqString("")
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
        assertFalse(sql.contains("JOIN method_strings"), sql)
        assertFalse(sql.contains("value = ''"), sql)
    }

    @Test
    fun emptyMethodLiteralWithoutCheapPredicateIsUnsupported() {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        assertThrows(UnsupportedOperationException::class.java) {
            scopeFor(query).findMethods(
                MethodSpec(usingStrings = listOf(StringSpec("", NameMatch.Equals))),
            )
        }
        assertTrue(query.queries.isEmpty())
    }

    @Test
    fun fieldGlobalUicChildrenShapeUsesReadMethodsTwoStep() {
        // The pinned engine returns empty for ANDed multi-access-unit queries, so candidate
        // generation must issue one canonical SINGLE-unit query per read/write method and intersect
        // the field descriptor sets in Kotlin.
        val query = RecordingDescriptorQuery { sql ->
            when {
                sql.contains("fm0.name = 'onBackPressed'") -> listOf(
                    MatcherFixtures.ALPHA_COUNT_FIELD,
                    MatcherFixtures.ALPHA_NAME_FIELD,
                )
                sql.contains("fm0.param_descriptors = 'Landroid/app/Activity;'") -> listOf(
                    MatcherFixtures.ALPHA_COUNT_FIELD,
                )
                else -> emptyList()
            }
        }
        scopeFor(query).findFields(
            fieldSpec {
                declaredClass { superClass { className("android.view.View") } }
                type { className("java.util.HashMap") }
                readMethod {
                    name("onBackPressed")
                    paramCount = 0..0
                    returnType { className("boolean") }
                }
                readMethod {
                    paramTypes(TypeSpec("android.app.Activity"))
                    returnType { className("void") }
                }
            },
        )
        assertEquals(2, query.queries.size)
        val readerQuery = query.queries.first { it.contains("fm0.name = 'onBackPressed'") }
        val fragmentQuery = query.queries.first { it.contains("fm0.param_descriptors = 'Landroid/app/Activity;'") }
        for (sql in listOf(readerQuery, fragmentQuery)) {
            assertTrue(sql.startsWith("SELECT DISTINCT f.descriptor AS descriptor FROM fields f"), sql)
            assertTrue(sql.contains("JOIN method_field_accesses fa0 ON fa0.field = f.descriptor"), sql)
            assertTrue(sql.contains("JOIN methods fm0 ON fm0.descriptor = fa0.method"), sql)
            assertTrue(sql.contains("fa0.using_type = 'get'"), sql)
            // Exactly one access unit per query.
            assertEquals(1, Regex("JOIN method_field_accesses").findAll(sql).count(), sql)
            assertFalse(sql.contains("JOIN method_field_accesses fa1"), sql)
            // The broad java.util.HashMap type is still not the DRIVER, but it is a necessary
            // conjunct next to each access unit (the FieldSpec requires that type).
            assertTrue(sql.contains("f.type_descriptor = 'Ljava/util/HashMap;'"), sql)
            assertEveryJoinOnIsSingleEquality(sql)
        }
        assertTrue(readerQuery.contains("fm0.param_count = 0"), readerQuery)
        assertTrue(readerQuery.contains("fm0.return_descriptor = 'Z'"), readerQuery)
        assertTrue(fragmentQuery.contains("fm0.return_descriptor = 'V'"), fragmentQuery)
    }

    @Test
    fun fieldReadMethodsTwoStepIntersectsSingleUnitQueries() {
        val countField = MatcherFixtures.ALPHA_COUNT_FIELD
        val nameField = MatcherFixtures.ALPHA_NAME_FIELD
        val query = RecordingDescriptorQuery { sql ->
            when {
                sql.contains("fm0.name = 'run'") -> listOf(countField, nameField)
                sql.contains("fm0.name = '<init>'") -> listOf(countField)
                else -> emptyList()
            }
        }
        val relations = FakeMatcherRelations(
            metadata = metadata,
            fieldAccessByKey = mapOf(
                countField + "|get" to listOf(MatcherFixtures.ALPHA_RUN, MatcherFixtures.ALPHA_INIT),
                nameField + "|get" to listOf(MatcherFixtures.ALPHA_RUN),
            ),
        )
        val scope = QueryMatcherScope(
            metadata = metadata,
            query = query,
            relations = relations,
            rawMetadata = MatcherFixtures.provider::query,
        )
        val results = scope.findFields(
            fieldSpec {
                readMethod { name("run") }
                readMethod { name("<init>") }
            },
        )
        // Unit A returns {count, label}, unit B returns {count}: intersection is {count}, and the
        // evaluator confirms count is read by both run() and <init>().
        assertEquals(listOf(MatcherFixtures.ALPHA_COUNT_FIELD), results.map { it.descriptor })
        assertEquals(2, query.queries.size)
        for (sql in query.queries) {
            assertEquals(1, Regex("JOIN method_field_accesses").findAll(sql).count(), sql)
            assertTrue(sql.contains("ORDER BY f.descriptor"), sql)
        }
    }

    @Test
    fun broadFieldTypeOnlyRemainsCandidateWithoutAccessMethods() {
        val query = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        scopeFor(query).findFields(
            fieldSpec { type { className("java.util.HashMap") } },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("f.type_descriptor = 'Ljava/util/HashMap;'"), sql)
        assertTrue(sql.contains("ORDER BY f.descriptor"), sql)
    }

    @Test
    fun narrowTypeOnlyFieldDrivesClassCandidate() {
        // classImageFeatureService shape: the only cheap predicates are type-only fields whose
        // class names are narrow (typically resolved from another delegate's .data).
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM fields")) MatcherFixtures.fieldDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        scopeFor(query).findClasses(
            classSpec {
                field { type { className("com.test.Alpha") } }
                field { type { className("com.test.Base") } }
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("FROM fields f"), sql)
        assertTrue(sql.contains("f.type_descriptor = 'Lcom/test/Alpha;'"), sql)
        assertFalse(sql.contains("FROM classes"), sql)
    }

    @Test
    fun descriptorFormNarrowTypeOnlyFieldDrivesClassCandidate() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM fields")) MatcherFixtures.fieldDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        scopeFor(query).findClasses(
            classSpec { field { type { className("Lcom/test/Alpha;") } } },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("FROM fields f"), sql)
        assertTrue(sql.contains("f.type_descriptor = 'Lcom/test/Alpha;'"), sql)
        assertFalse(sql.contains("FROM classes"), sql)
    }

    @Test
    fun narrowTypeOnlyFieldWinsOverUnusableSuperClassStrings() {
        // classMvvmConversationAdapter shape: superClass only has usingStrings (no canonical
        // superclass name source), so the narrow type-only field lookup is the candidate source.
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM fields")) MatcherFixtures.fieldDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        scopeFor(query).findClasses(
            classSpec {
                superClass { usingString("mvvm adapter") }
                field { type { className("com.test.Alpha") } }
            },
        )
        // The field two-step candidate is one of several queries now (class-string evaluation for
        // the superclass constraint is class-scoped SQL); assert the relevant field lookup.
        val sql = query.queries.first { it.contains("FROM fields f") }
        assertTrue(sql.contains("FROM fields f"), sql)
        assertTrue(sql.contains("f.type_descriptor = 'Lcom/test/Alpha;'"), sql)
        assertFalse(sql.contains("FROM classes"), sql)
    }

    @Test
    fun broadTypeOnlyFieldWithoutOtherSourceIsUnsupported() {
        // Broad library/primitive type-only fields never drive class candidates; with no other
        // canonical source the spec fails loudly before any SQL instead of scanning.
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM fields")) MatcherFixtures.fieldDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            scopeFor(query).findClasses(
                classSpec { field { type { className("java.lang.String") } } },
            )
        }
        assertTrue(query.queries.isEmpty())
    }

    @Test
    fun weakFieldTypeDoesNotWinOverPackageScanForClassMenuItemDataShape() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM fields")) MatcherFixtures.fieldDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        val results = scopeFor(query).findClasses(
            classSpec {
                field { type { className("java.lang.String") } }
                field { type { className("int") } }
                fieldCount = 2..2
                method { usingEqString("") }
            },
            packages = listOf("com.test"),
        )
        val sql = query.queries.single()
        // The fieldCount constraint is now a canonical classes.field_count candidate and wins over
        // the package-only scan; weak field types stay non-driving.
        assertTrue(sql.contains("c.field_count >= 2"), sql)
        assertTrue(sql.contains("c.field_count <= 2"), sql)
        assertFalse(sql.contains("FROM fields"), sql)
        assertFalse(sql.contains("FROM methods"), sql)
        assertTrue(results.isEmpty())
    }

    @Test
    fun weakFieldTypeDoesNotWinOverSuperClassCandidate() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM fields")) MatcherFixtures.fieldDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        scopeFor(query).findClasses(
            classSpec {
                superClass { className("com.test.Base") }
                field { type { className("android.widget.TextView") } }
            },
        )
        val sql = query.queries.single()
        assertTrue(sql.contains("c.superclass_descriptor = 'Lcom/test/Base;'"), sql)
        assertFalse(sql.contains("FROM fields"), sql)
    }

    @Test
    fun classMemberCandidateBeatsPackageOnlyScan() {
        val query = RecordingDescriptorQuery { sql ->
            if (sql.contains("FROM methods")) MatcherFixtures.methodDescriptors()
            else MatcherFixtures.classDescriptors()
        }
        val results = scopeFor(query).findClasses(
            classSpec { method { name("run") } },
            packages = listOf("com.test"),
        )
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
        assertFalse(sql.contains("FROM classes"), sql)
    }

    @Test
    fun packageOnlyScanIsLastResortBeforeEnumeration() {
        val (scope, query) = classScope()
        val results = scope.findClasses(
            classSpec { methodCount = 3..3 },
            packages = listOf("com.test"),
        )
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("instr(c.descriptor, 'Lcom/test/') = 1"), sql)
    }

    @Test
    fun unsupportedCandidateShapesFailLoudlyWithoutRunningSql() {
        val methodQuery = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        assertThrows(UnsupportedOperationException::class.java) {
            scopeFor(methodQuery).findMethods(
                MethodSpec(invokeMethods = listOf(MethodSpec(name = NameSpec("helper")))),
            )
        }
        assertTrue(methodQuery.queries.isEmpty())
        assertTrue(
            assertThrows(UnsupportedOperationException::class.java) {
                scopeFor(methodQuery).uniqueMethod(
                    MethodSpec(callerMethods = listOf(MethodSpec(name = NameSpec("helper")))),
                )
            }.message!!.contains("candidate shape is unsupported"),
        )

        val classQuery = RecordingDescriptorQuery { MatcherFixtures.classDescriptors() }
        assertThrows(UnsupportedOperationException::class.java) {
            scopeFor(classQuery).findClasses(classSpec {})
        }
        assertTrue(classQuery.queries.isEmpty())

        val fieldQuery = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        assertThrows(UnsupportedOperationException::class.java) {
            // A read/write method matcher with no selective canonical predicate cannot drive the
            // field candidate (nothing pushable), so this shape remains unsupported.
            scopeFor(fieldQuery).findFields(
                fieldSpec { readMethod { } },
            )
        }
        assertTrue(fieldQuery.queries.isEmpty())
    }

    @Test
    fun anyOfRunsUnionOfCanonicalChildCandidates() {
        val (scope, query) = methodScope()
        val results = scope.findMethods(
            methodSpec {
                anyOf { name("run") }
                anyOf { name("helper") }
            },
        )
        assertEquals(
            listOf(
                MatcherFixtures.ALPHA_RUN,
                MatcherFixtures.ALPHA_HELPER,
                MatcherFixtures.CALL_HELPER,
                MatcherFixtures.GAMMA_RUN,
            ).sorted(),
            results.map { it.descriptor }.sorted(),
        )
        assertEquals(2, query.queries.size)
        assertTrue(query.queries.all { it.endsWith("ORDER BY m.descriptor;") }, query.queries.joinToString(" | "))
    }

    @Test
    fun allOfUsesFirstCanonicalChildCandidate() {
        val (scope, query) = methodScope()
        val results = scope.findMethods(
            methodSpec {
                allOf { name("run") }
                allOf { invokeMethod { name("helper") } }
            },
        )
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
    }

    @Test
    fun anyOfWithUnsupportedChildFailsLoudly() {
        val (scope, query) = methodScope()
        assertThrows(UnsupportedOperationException::class.java) {
            scope.findMethods(
                methodSpec {
                    anyOf { name("run") }
                    anyOf { invokeMethod { name("helper") } }
                },
            )
        }
        assertTrue(query.queries.isEmpty())
    }

    // ------------------------------------------------------------------
    // Bounded unique search
    // ------------------------------------------------------------------

    @Test
    fun uniqueStopsEvaluatingAfterSecondMethodMatch() {
        var methodDocumentFetches = 0
        val countingProvider = object : DexMetadataProvider {
            override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? {
                if (kind == DexMetadataKind.Method) methodDocumentFetches++
                return MatcherFixtures.provider.query(kind, descriptor)
            }
        }
        val metadata = DexMetadata(countingProvider)
        val query = RecordingDescriptorQuery {
            listOf(
                MatcherFixtures.ALPHA_RUN,
                MatcherFixtures.GAMMA_RUN,
                MatcherFixtures.ALPHA_HELPER,
                MatcherFixtures.CALL_HELPER,
            )
        }
        val scope = QueryMatcherScope(metadata, query, rawMetadata = countingProvider::query)
        assertThrows(IllegalStateException::class.java) {
            scope.uniqueMethod(MethodSpec(name = NameSpec("run")))
        }
        assertEquals(2, methodDocumentFetches)
    }

    @Test
    fun uniqueClassStopsEvaluatingAfterSecondMatch() {
        // A candidate may legitimately be fetched twice (raw JSON prefilter + parsed model), so count
        // distinct class descriptors: early exit must stop before the whole candidate set is visited.
        val classDescriptorsFetched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val countingProvider = object : DexMetadataProvider {
            override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? {
                if (kind == DexMetadataKind.Class) classDescriptorsFetched += descriptor
                return MatcherFixtures.provider.query(kind, descriptor)
            }
        }
        val metadata = DexMetadata(countingProvider)
        val query = RecordingDescriptorQuery { MatcherFixtures.classDescriptors() }
        val scope = QueryMatcherScope(metadata, query, rawMetadata = countingProvider::query)
        assertThrows(IllegalStateException::class.java) {
            scope.uniqueClass(ClassSpec(className = NameSpec("com.test", NameMatch.StartsWith)))
        }
        assertTrue(
            classDescriptorsFetched.size < MatcherFixtures.classDescriptors().size,
            "expected early exit before visiting every candidate, fetched=" + classDescriptorsFetched,
        )
    }

    @Test
    fun uniquePagesFilteredCandidatesUntilMatch() {
        val query = PagingDescriptorQuery(MatcherFixtures.methodDescriptors(), pageSize = 2)
        val scope = QueryMatcherScope(
            metadata = MatcherFixtures.metadata,
            query = query,
            relations = relations,
            rawMetadata = MatcherFixtures.provider::query,
            candidatePageSize = 2,
        )
        val descriptor = scope.uniqueMethod(MethodSpec(name = NameSpec("make")))
        assertEquals(MatcherFixtures.CALL_MAKE, descriptor)
        assertTrue(query.queries.size >= 3, query.queries.joinToString(" | "))
        assertTrue(query.queries.all { it.contains("LIMIT 2") }, query.queries.joinToString(" | "))
        assertTrue(query.queries.all { it.contains("OFFSET") }, query.queries.joinToString(" | "))
    }

    // ------------------------------------------------------------------
    // T4 regressions: primitive returnType candidate and array-class data
    // ------------------------------------------------------------------

    @Test
    fun primitiveReturnTypeUsesDescriptorConditionInsteadOfClassJoin() {
        val (scope, query) = methodScope()
        val spec = methodSpec {
            declaredClass {
                usingString("VFS.NativeFileSystem")
                usingString("Base directory exists but is not a directory, delete and proceed.Base path: ")
            }
            paramTypes(TypeSpec("java.lang.String"))
            returnType { className("boolean") }
            invokeMethod {
                declaredClass { className("java.io.File") }
                name("delete")
            }
        }
        scope.findMethods(spec)
        val sql = query.queries.first { it.contains("m.return_descriptor = 'Z'") }
        assertFalse(sql.contains("JOIN classes"), sql)
        assertTrue(sql.contains("m.return_descriptor = 'Z'"), sql)
        assertTrue(sql.contains("m.param_descriptors = 'Ljava/lang/String;'"), sql)
        assertNoRawRelationTables(sql)
        assertEveryJoinOnIsSingleEquality(sql)
    }

    @Test
    fun arrayClassMethodCandidatesAreSkippedBeforeMetadataLookup() {
        val throwingProvider = object : DexMetadataProvider {
            override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? {
                if (descriptor.startsWith("[")) {
                    throw IllegalArgumentException("malformed class descriptor " + descriptor)
                }
                return MatcherFixtures.provider.query(kind, descriptor)
            }
        }
        val metadata = DexMetadata(throwingProvider)
        val query = RecordingDescriptorQuery {
            listOf(
                "[Lcom/test/Arg;->clone()Ljava/lang/Object;",
                MatcherFixtures.ALPHA_HELPER,
            )
        }
        val scope = QueryMatcherScope(metadata, query)
        val results = scope.findMethods(MethodSpec(name = NameSpec("helper")))
        assertEquals(listOf(MatcherFixtures.ALPHA_HELPER), results.map { it.descriptor })
    }

    @Test
    fun arrayClassMemberRefsDoNotBreakClassLevelUsingStrings() {
        val throwingProvider = object : DexMetadataProvider {
            override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? {
                if (descriptor.startsWith("[")) {
                    throw IllegalArgumentException("malformed class descriptor " + descriptor)
                }
                return MatcherFixtures.provider.query(kind, descriptor)
            }
        }
        val metadata = DexMetadata(throwingProvider)
        val arrayMember = buildJsonObject {
            put("descriptor", "[Lcom/test/Arg;->clone()Ljava/lang/Object;")
            put("name", "clone")
            put("declared_class_name", "[Lcom/test/Arg;")
            put("method_sign", "()Ljava/lang/Object;")
            put("modifiers", 1)
            put("access_flags", 1)
            put("return_type_name", "java.lang.Object")
            put("param_type_names", JsonArray(emptyList()))
            put("param_count", 0)
            put("is_constructor", false)
            put("is_static_initializer", false)
            put("is_method", true)
        }
        val weirdClass = DexClassInfo(
            buildJsonObject {
                put("descriptor", "Lcom/test/Weird;")
                put("name", "com.test.Weird")
                put("simple_name", "Weird")
                put("modifiers", 1)
                put("access_flags", 1)
                put("is_array", false)
                put("interfaces", JsonArray(emptyList()))
                put("methods", JsonArray(listOf(arrayMember)))
                put("fields", JsonArray(emptyList()))
            },
            metadata,
        )
        val evaluator = DexMatcherEvaluator(metadata)
        assertFalse(
            evaluator.matchesClass(
                weirdClass,
                ClassSpec(usingStrings = listOf(StringSpec("anything"))),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Shared canonical-shape assertions
    // ------------------------------------------------------------------

    private fun assertNoRawRelationTables(sql: String) {
        listOf(
            "JOIN class_interfaces",
            "JOIN class_fields",
            "JOIN class_methods",
            "JOIN fields cf",
            "JOIN method_field_accesses fa",
            "JOIN methods cm0",
        ).forEach { forbidden ->
            assertFalse(sql.contains(forbidden), "raw relation join '" + forbidden + "' in: " + sql)
        }
    }

    private fun assertEveryJoinOnIsSingleEquality(sql: String) {
        var searchFrom = 0
        var joinCount = 0
        while (true) {
            val joinIndex = sql.indexOf(" JOIN ", searchFrom)
            if (joinIndex < 0) break
            joinCount++
            val onIndex = sql.indexOf(" ON ", joinIndex)
            assertTrue(onIndex > joinIndex, "JOIN without ON condition: " + sql)
            val clauseEnds = listOf(
                sql.indexOf(" JOIN ", onIndex + 4),
                sql.indexOf(" WHERE ", onIndex + 4),
                sql.indexOf(" ORDER ", onIndex + 4),
            ).filter { it >= 0 }
            val end = clauseEnds.minOrNull() ?: sql.length
            val onClause = sql.substring(onIndex + 4, end).trim()
            assertFalse(
                onClause.contains(" AND "),
                "multiple conditions in ON clause: " + onClause + " [" + sql + "]",
            )
            assertTrue(
                onClause.contains(" = "),
                "non-equality ON clause: " + onClause + " [" + sql + "]",
            )
            searchFrom = onIndex + 4
        }
        assertTrue(joinCount > 0, "expected at least one JOIN in: " + sql)
    }

    /** Fake executor that honours LIMIT/OFFSET so the scope's paging can be exercised. */
    private class PagingDescriptorQuery(
        private val rows: List<String>,
        private val pageSize: Int,
    ) : DexDescriptorQuery {
        val queries = mutableListOf<String>()

        override fun descriptors(sql: String): List<String> {
            queries += sql
            val limit = Regex("LIMIT (\\d+)").find(sql)?.groupValues?.get(1)?.toInt() ?: pageSize
            val offset = Regex("OFFSET (\\d+)").find(sql)?.groupValues?.get(1)?.toInt() ?: 0
            return rows.drop(offset).take(limit)
        }
    }


    @Test
    fun classStringLiteralLookupIsBatchedAndCachedAcrossScopes() {
        var literalQueries = 0
        var missingQueries = 0
        val query = RecordingDescriptorQuery { sql ->
            when {
                sql.contains("alpha marker") -> {
                    literalQueries++
                    listOf(MatcherFixtures.ALPHA_RUN)
                }
                sql.contains("missing literal") -> {
                    missingQueries++
                    emptyList()
                }
                else -> MatcherFixtures.methodDescriptors()
            }
        }
        val session = Any()
        val sessionCache = MatcherSessionCache.forSession(session)
        fun scope() = QueryMatcherScope(
            metadata = metadata,
            query = query,
            relations = relations,
            rawMetadata = MatcherFixtures.provider::query,
            sessionCache = sessionCache,
        )
        val spec = classSpec { usingString("alpha marker") }
        assertEquals(listOf(MatcherFixtures.ALPHA), scope().findClasses(spec).map { it.descriptor })
        assertEquals(1, literalQueries)
        // A second scope for the same DexSQL session reuses the literal->class set: no second query.
        assertEquals(listOf(MatcherFixtures.ALPHA), scope().findClasses(spec).map { it.descriptor })
        assertEquals(1, literalQueries)
        // Negative (empty) results are cached as well.
        val missing = classSpec { usingString("missing literal") }
        assertTrue(scope().findClasses(missing).isEmpty())
        assertTrue(scope().findClasses(missing).isEmpty())
        assertEquals(1, missingQueries)
    }

    private fun globalClassStringRows(sql: String): List<String> = when {
        sql.contains("alpha marker") -> listOf(MatcherFixtures.ALPHA_RUN)
        sql.contains("'other'") -> listOf(MatcherFixtures.ALPHA_HELPER)
        else -> MatcherFixtures.methodDescriptors()
    }
}
