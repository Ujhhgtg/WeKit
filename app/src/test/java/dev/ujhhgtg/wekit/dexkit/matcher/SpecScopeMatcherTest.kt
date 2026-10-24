package dev.ujhhgtg.wekit.dexkit.matcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Matcher-fallback coverage for the scope extensions: spec-level searchPackages, class-level
 * usingNumbers and their canonical candidate generation. SQL-expressible specs never reach this
 * path; these tests pin the semantics the fallback must implement.
 */
class SpecScopeMatcherTest {
    private val metadata = MatcherFixtures.metadata
    private val evaluator = DexMatcherEvaluator(metadata)

    private fun run() = metadata.methodInfo(MatcherFixtures.ALPHA_RUN)!!

    private fun alpha() = metadata.classInfo(MatcherFixtures.ALPHA)!!

    private fun gamma() = metadata.classInfo(MatcherFixtures.GAMMA)!!

    private fun countField() = metadata.fieldInfo(MatcherFixtures.ALPHA_COUNT_FIELD)!!

    private fun scopeFor(query: DexDescriptorQuery): QueryMatcherScope =
        QueryMatcherScope(
            metadata = metadata,
            query = query,
            relations = FakeMatcherRelations(metadata),
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
    // packages
    // ------------------------------------------------------------------

    @Test
    fun methodPackagesMatchTheDeclaringClass() {
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(packages = listOf("com.test"))))
        assertTrue(
            evaluator.matchesMethod(run(), MethodSpec(packages = listOf("com.test.Alpha"))),
        )
        assertTrue(
            evaluator.matchesMethod(
                run(),
                MethodSpec(packages = listOf("com.other", "com.test.Alpha")),
            ),
        )
        assertFalse(evaluator.matchesMethod(run(), MethodSpec(packages = listOf("com.test.Other"))))
        assertFalse(evaluator.matchesMethod(run(), MethodSpec(packages = listOf("com.other"))))
    }

    @Test
    fun classPackagesMatchTheClassName() {
        assertTrue(evaluator.matchesClass(alpha(), ClassSpec(packages = listOf("com.test"))))
        assertTrue(evaluator.matchesClass(alpha(), ClassSpec(packages = listOf("com.test.Alpha"))))
        assertFalse(evaluator.matchesClass(alpha(), ClassSpec(packages = listOf("com.other"))))
        assertFalse(evaluator.matchesClass(alpha(), ClassSpec(packages = listOf("com.tes"))))
    }

    @Test
    fun fieldPackagesMatchTheDeclaringClass() {
        assertTrue(evaluator.matchesField(countField(), FieldSpec(packages = listOf("com.test"))))
        assertFalse(
            evaluator.matchesField(countField(), FieldSpec(packages = listOf("com.other"))),
        )
    }

    @Test
    fun methodPackagesDriveCanonicalCandidateQuery() {
        val (scope, query) = methodScope()
        val matches = scope.findMethods(methodSpec { searchPackages("com.test") })
        assertEquals(MatcherFixtures.methodDescriptors(), matches.map { it.descriptor })
        assertTrue(
            query.queries.single().contains("(instr(m.class_descriptor, 'Lcom/test/') = 1)"),
            query.queries.single(),
        )
    }

    @Test
    fun classPackagesDriveCanonicalClassScan() {
        val (scope, query) = classScope()
        val matches = scope.findClasses(classSpec { searchPackages("com.test") })
        assertEquals(MatcherFixtures.classDescriptors(), matches.map { it.descriptor })
        assertTrue(
            query.queries
                .single()
                .startsWith(
                    "SELECT DISTINCT c.descriptor AS descriptor FROM classes c",
                ),
            query.queries.single(),
        )
        assertTrue(
            query.queries.single().contains("(instr(c.descriptor, 'Lcom/test/') = 1)"),
            query.queries.single(),
        )
    }

    @Test
    fun fieldPackagesDriveCanonicalFieldScan() {
        val (scope, query) = fieldScope()
        val matches = scope.findFields(fieldSpec { searchPackages("com.test") })
        assertEquals(MatcherFixtures.fieldDescriptors(), matches.map { it.descriptor })
        assertTrue(
            query.queries.single().contains("(instr(f.class_descriptor, 'Lcom/test/') = 1)"),
            query.queries.single(),
        )
    }

    @Test
    fun fallbackEvaluatesDeclaredClassPackages() {
        val (scope, query) = methodScope()
        val matches =
            scope.findMethods(
                methodSpec {
                    name("run")
                    declaredClass {
                        className("com.test.Alpha")
                        searchPackages("com.test")
                    }
                },
            )
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), matches.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
        assertTrue(sql.contains("m.class_descriptor = 'Lcom/test/Alpha;'"), sql)
        assertTrue(sql.contains("(instr(m.class_descriptor, 'Lcom/test/') = 1)"), sql)
    }

    // ------------------------------------------------------------------
    // class-level usingNumbers
    // ------------------------------------------------------------------

    @Test
    fun classLevelUsingNumbersRequireEveryValueExistentially() {
        assertTrue(evaluator.matchesClass(alpha(), ClassSpec(usingNumbers = listOf<Number>(42L))))
        assertTrue(evaluator.matchesClass(alpha(), ClassSpec(usingNumbers = listOf<Number>(0.5))))
        // Both values live in Alpha.run(); values may come from different methods, too.
        assertTrue(
            evaluator.matchesClass(alpha(), ClassSpec(usingNumbers = listOf<Number>(42L, 0.5))),
        )
        assertFalse(evaluator.matchesClass(alpha(), ClassSpec(usingNumbers = listOf<Number>(7L))))
        assertFalse(
            evaluator.matchesClass(alpha(), ClassSpec(usingNumbers = listOf<Number>(42L, 7L))),
        )
        // Gamma.run() has no numeric operands.
        assertFalse(evaluator.matchesClass(gamma(), ClassSpec(usingNumbers = listOf<Number>(42L))))
    }

    @Test
    fun classLevelUsingNumbersDriveCanonicalMethodUnitCandidates() {
        val (scope, query) = classScope()
        val matches = scope.findClasses(classSpec { usingNumbers(42L) })
        assertEquals(listOf(MatcherFixtures.ALPHA), matches.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(
            sql.startsWith(
                "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_using_numbers mn0 ON mn0.method = cm.descriptor",
            ),
            sql,
        )
        assertTrue(sql.contains("mn0.long_value = 42"), sql)
    }

    @Test
    fun classLevelUsingNumbersFallbackWithNonSqlPredicates() {
        // fieldCount is not SQL-expressible together with class-level numbers, so the whole spec
        // falls back to the matcher while the numbers still drive candidate generation.
        val spec = classSpec {
            usingNumbers(42L)
            fieldCount = 2..2
        }
        val (scope, query) = classScope()
        val matches = scope.findClasses(spec)
        assertEquals(listOf(MatcherFixtures.ALPHA), matches.map { it.descriptor })
        assertTrue(query.queries.single().contains("method_using_numbers"), query.queries.single())
    }

    // ------------------------------------------------------------------
    // Structural superClass fallback
    // ------------------------------------------------------------------

    @Test
    fun structuralSuperClassFallbackEvaluatesResolvedSuperclass() {
        val spec = classSpec {
            superClass {
                className("com.test.Base")
                modifiers(1)
            }
        }
        val (scope, query) = classScope()
        val matches = scope.findClasses(spec)
        assertEquals(listOf(MatcherFixtures.ALPHA), matches.map { it.descriptor })
        assertTrue(
            query.queries
                .single()
                .startsWith(
                    "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.superclass_descriptor = 'Lcom/test/Base;'",
                ),
            query.queries.single(),
        )
    }
}
