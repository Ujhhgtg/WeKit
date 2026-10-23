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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T5/T6-oriented candidate-query coverage: structural class/method/field shapes and groups. */
class StructuralCandidateTest {
    private val metadata = MatcherFixtures.metadata
    private val relations = FakeMatcherRelations(
        metadata = metadata,
        callersByMethod = mapOf(MatcherFixtures.ALPHA_RUN to listOf(MatcherFixtures.CALL_HELPER)),
        fieldAccessByKey = mapOf(
            MatcherFixtures.ALPHA_COUNT_FIELD + "|get" to listOf(MatcherFixtures.ALPHA_RUN),
        ),
    )

    private fun methodScope(): Pair<QueryMatcherScope, RecordingDescriptorQuery> {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        return QueryMatcherScope(metadata, query, relations) to query
    }

    private fun classScope(): Pair<QueryMatcherScope, RecordingDescriptorQuery> {
        val query = RecordingDescriptorQuery { MatcherFixtures.classDescriptors() }
        return QueryMatcherScope(metadata, query, relations) to query
    }

    private fun fieldScope(): Pair<QueryMatcherScope, RecordingDescriptorQuery> {
        val query = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        return QueryMatcherScope(metadata, query, relations) to query
    }

    @Test
    fun classCandidateFromInterfaceJoin() {
        val (scope, query) = classScope()
        val results = scope.findClasses(classSpec { interfaceSpec { className("com.test.I1") } })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("JOIN class_interfaces"), sql)
        assertTrue(sql.contains("interface_descriptor = 'Lcom/test/I1;'"), sql)
    }

    @Test
    fun classCandidateFromFieldJoin() {
        val (scope, query) = classScope()
        val results = scope.findClasses(classSpec { field { name("count") } })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("JOIN fields"), sql)
        assertTrue(sql.contains("cf0.name = 'count'"), sql)
    }

    @Test
    fun classCandidateFromMethodJoin() {
        val (scope, query) = classScope()
        val results = scope.findClasses(classSpec { method { name("run") } })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("JOIN methods"), sql)
        assertTrue(sql.contains("cm0.name = 'run'"), sql)
    }

    @Test
    fun classCandidateFromSuperClass() {
        val (scope, query) = classScope()
        val results = scope.findClasses(classSpec { superClass { className("com.test.Base") } })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        assertTrue(query.queries.single().contains("superclass_descriptor = 'Lcom/test/Base;'"), query.queries.single())
    }

    @Test
    fun countOnlyClassSpecUsesBroadFallback() {
        val (scope, query) = classScope()
        val results = scope.findClasses(classSpec { methodCount = 3..3 })
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        assertEquals(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c ORDER BY c.descriptor;",
            query.queries.single(),
        )
    }

    @Test
    fun methodCandidateFromDeclaredClassSuperClassJoin() {
        val (scope, query) = methodScope()
        val spec = methodSpec {
            name("run")
            declaredClass { superClass { className("com.test.Base") } }
        }
        val results = scope.findMethods(spec)
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("JOIN classes dc0 ON dc0.descriptor = m.class_descriptor"), sql)
        assertTrue(sql.contains("dc0.superclass_descriptor = 'Lcom/test/Base;'"), sql)
        assertTrue(sql.contains("m.name = 'run'"), sql)
    }

    @Test
    fun methodCandidateFromReturnTypeStructuralJoin() {
        val (scope, query) = methodScope()
        val spec = methodSpec {
            name("make")
            returnType { modifiers = 1 }
        }
        val results = scope.findMethods(spec)
        assertEquals(listOf(MatcherFixtures.CALL_MAKE), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("JOIN classes rc0 ON rc0.descriptor = m.return_descriptor"), sql)
        assertTrue(sql.contains("rc0.access_flags & 1"), sql)
    }

    @Test
    fun fieldCandidateFromReadMethodAccessJoin() {
        val (scope, query) = fieldScope()
        val spec = fieldSpec {
            name("count")
            readMethod { name("run") }
        }
        val results = scope.findFields(spec)
        assertEquals(listOf(MatcherFixtures.ALPHA_COUNT_FIELD), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("JOIN method_field_accesses"), sql)
        assertTrue(sql.contains("using_type = 'get'"), sql)
        assertTrue(sql.contains("fam1.name = 'run'"), sql)
    }

    @Test
    fun anyOfRunsUnionOfCheapChildCandidates() {
        val (scope, query) = methodScope()
        val spec = methodSpec {
            anyOf { name("run") }
            anyOf { name("helper") }
        }
        val results = scope.findMethods(spec)
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
        assertTrue(query.queries.any { it.contains("m.name = 'run'") })
        assertTrue(query.queries.any { it.contains("m.name = 'helper'") })
    }

    @Test
    fun allOfUsesFirstCheapChildCandidate() {
        val (scope, query) = methodScope()
        val spec = methodSpec {
            allOf { name("run") }
            allOf { invokeMethod { name("helper") } }
        }
        val results = scope.findMethods(spec)
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
    }

    @Test
    fun anyOfWithUncheapChildFallsBackToBroadScan() {
        val (scope, query) = methodScope()
        val spec = methodSpec {
            anyOf { name("run") }
            anyOf { invokeMethod { name("helper") } }
        }
        val results = scope.findMethods(spec)
        assertEquals(
            listOf(MatcherFixtures.ALPHA_RUN, MatcherFixtures.GAMMA_RUN).sorted(),
            results.map { it.descriptor }.sorted(),
        )
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m ORDER BY m.descriptor;",
            query.queries.single(),
        )
    }

    @Test
    fun primitiveReturnTypeUsesDescriptorConditionInsteadOfClassJoin() {
        // Regression for PreventModuleDataDeletion.methodNativeFileSystemEntryDelete: a
        // structural declaredClass must not turn a name-only primitive returnType into a classes
        // join; the Java type name has to be converted to its descriptor ('Z' for boolean).
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
        assertFalse(sql.contains("JOIN classes rc"), sql)
        assertTrue(sql.contains("m.return_descriptor = 'Z'"), sql)
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
}
