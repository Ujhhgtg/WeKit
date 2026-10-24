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
        val (scope, query) = methodScope()
        val results = scope.findMethods(
            methodSpec {
                name("run")
                declaredClass { usingString("alpha marker") }
            },
        )
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), results.map { it.descriptor })
        val sql = query.queries.single()
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
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        val scope = scopeFor(query)
        val results = scope.findClasses(
            classSpec {
                usingString("alpha marker")
                usingString("other")
            },
        )
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        assertEquals(2, query.queries.size)
        for (sql in query.queries) {
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
            scopeFor(classQuery).findClasses(
                classSpec { interfaceSpec { className("com.test.I1") } },
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            scopeFor(classQuery).findClasses(classSpec { methodCount = 3..3 })
        }
        assertTrue(classQuery.queries.isEmpty())

        val fieldQuery = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        assertThrows(UnsupportedOperationException::class.java) {
            scopeFor(fieldQuery).findFields(
                fieldSpec { readMethod { name("run") } },
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
        val sql = query.queries.single()
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
}
