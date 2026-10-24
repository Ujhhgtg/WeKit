package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.FieldUsingType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryMatcherTest {
    private val metadata = MatcherFixtures.metadata
    private val evaluator = DexMatcherEvaluator(metadata)

    private fun run() = metadata.methodInfo(MatcherFixtures.ALPHA_RUN)!!

    private fun helper() = metadata.methodInfo(MatcherFixtures.ALPHA_HELPER)!!

    private fun alpha() = metadata.classInfo(MatcherFixtures.ALPHA)!!

    private fun gamma() = metadata.classInfo(MatcherFixtures.GAMMA)!!

    private fun scope(): QueryMatcherScope =
        QueryMatcherScope(
            metadata,
            RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() },
        )

    @Test
    fun methodNameMatchModes() {
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(name = NameSpec("run"))))
        assertTrue(
            evaluator.matchesMethod(run(), MethodSpec(name = NameSpec("ru", NameMatch.StartsWith))),
        )
        assertTrue(
            evaluator.matchesMethod(run(), MethodSpec(name = NameSpec("u", NameMatch.Contains))),
        )
        assertTrue(
            evaluator.matchesMethod(run(), MethodSpec(name = NameSpec("un", NameMatch.EndsWith))),
        )
        assertFalse(evaluator.matchesMethod(run(), MethodSpec(name = NameSpec("ru"))))
    }

    @Test
    fun modifiersUseContainsSemantics() {
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(modifiers = 1)))
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(modifiers = 0)))
        assertFalse(evaluator.matchesMethod(run(), MethodSpec(modifiers = 8)))
    }

    @Test
    fun paramTypesWildcardArityAndModes() {
        assertTrue(
            evaluator.matchesMethod(helper(), MethodSpec(paramTypes = listOf(TypeSpec("int")))),
        )
        assertTrue(
            evaluator.matchesMethod(helper(), MethodSpec(paramTypes = listOf<TypeSpec?>(null))),
        )
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(paramTypes = emptyList())))
        assertFalse(evaluator.matchesMethod(helper(), MethodSpec(paramTypes = emptyList())))
        assertFalse(evaluator.matchesMethod(helper(), MethodSpec(paramTypes = listOf(null, null))))
        assertFalse(
            evaluator.matchesMethod(helper(), MethodSpec(paramTypes = listOf(TypeSpec("long")))),
        )
        assertTrue(
            evaluator.matchesMethod(
                helper(),
                MethodSpec(paramTypes = listOf(TypeSpec("nt", NameMatch.EndsWith))),
            ),
        )
    }

    @Test
    fun paramCountRange() {
        assertTrue(evaluator.matchesMethod(helper(), MethodSpec(paramCount = 1..1)))
        assertFalse(evaluator.matchesMethod(helper(), MethodSpec(paramCount = 2..3)))
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(paramCount = 0..0)))
    }

    @Test
    fun usingStringsRequireEveryLiteralExistentially() {
        val spec =
            MethodSpec(
                usingStrings = listOf(StringSpec("alpha marker"), StringSpec("marker")),
            )
        assertTrue(evaluator.matchesMethod(run(), spec))
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(usingStrings = listOf(StringSpec("missing"))),
            ),
        )
        assertFalse(evaluator.matchesMethod(helper(), spec))
    }

    @Test
    fun usingFieldsAreInjectiveAndFilterByDirection() {
        val readCount =
            UsingFieldSpec(
                field = FieldSpec(name = NameSpec("count")),
                usingType = FieldUsingType.Read,
            )
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(usingFields = listOf(readCount))))
        assertTrue(
            evaluator.matchesMethod(
                run(),
                MethodSpec(usingFields = listOf(readCount.copy(usingType = null))),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(usingFields = listOf(readCount.copy(usingType = FieldUsingType.Write))),
            ),
        )
        // Each spec needs a distinct field access; run() only reads one field.
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(usingFields = listOf(readCount, readCount)),
            ),
        )
    }

    @Test
    fun invokeMethodsAreInjective() {
        assertTrue(
            evaluator.matchesMethod(
                run(),
                MethodSpec(invokeMethods = listOf(MethodSpec(name = NameSpec("helper")))),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(invokeMethods = listOf(MethodSpec(name = NameSpec("other")))),
            ),
        )
        // Both invoke specs would match the same called method: injective matching must fail.
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(
                    invokeMethods =
                        listOf(
                            MethodSpec(name = NameSpec("helper")),
                            MethodSpec(name = NameSpec("helper")),
                        ),
                ),
            ),
        )
    }

    @Test
    fun anyOfAndAllOf() {
        val anyOf =
            MethodSpec(
                anyOf =
                    listOf(
                        MethodSpec(name = NameSpec("other")),
                        MethodSpec(name = NameSpec("run")),
                    ),
            )
        assertTrue(evaluator.matchesMethod(run(), anyOf))
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(anyOf = listOf(MethodSpec(name = NameSpec("other")))),
            ),
        )
        assertTrue(
            evaluator.matchesMethod(
                run(),
                MethodSpec(
                    allOf = listOf(MethodSpec(name = NameSpec("run")), MethodSpec(modifiers = 1)),
                ),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(
                    allOf = listOf(MethodSpec(name = NameSpec("run")), MethodSpec(modifiers = 8)),
                ),
            ),
        )
    }

    @Test
    fun classMembersAndCountsAreInjective() {
        assertTrue(
            evaluator.matchesClass(alpha(), ClassSpec(className = NameSpec("com.test.Alpha"))),
        )
        assertTrue(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(className = NameSpec("com.test", NameMatch.StartsWith)),
            ),
        )
        assertFalse(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(className = NameSpec("com.test.Beta", NameMatch.StartsWith)),
            ),
        )
        assertTrue(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(methodCount = 3..3, fieldCount = 2..2, interfaceCount = 1..1),
            ),
        )
        assertFalse(evaluator.matchesClass(alpha(), ClassSpec(methodCount = 1..2)))
        assertFalse(evaluator.matchesClass(alpha(), ClassSpec(modifiers = 8)))
        assertTrue(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(interfaces = listOf(ClassSpec(className = NameSpec("com.test.I1")))),
            ),
        )
        assertTrue(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(
                    methods =
                        listOf(
                            MethodSpec(name = NameSpec("run")),
                            MethodSpec(name = NameSpec("helper")),
                        ),
                ),
            ),
        )
        // Two specs may not bind the same method.
        assertFalse(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(
                    methods =
                        listOf(
                            MethodSpec(name = NameSpec("run")),
                            MethodSpec(name = NameSpec("run")),
                        ),
                ),
            ),
        )
    }

    @Test
    fun classUsingStringsAreExistentialAcrossMethods() {
        val spec =
            ClassSpec(
                usingStrings = listOf(StringSpec("alpha marker"), StringSpec("other")),
            )
        assertTrue(evaluator.matchesClass(alpha(), spec))
        assertFalse(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(usingStrings = listOf(StringSpec("missing"))),
            ),
        )
    }

    @Test
    fun definedAndExternalSuperClassSpecs() {
        assertTrue(
            evaluator.matchesClass(
                alpha(),
                ClassSpec(superClass = ClassSpec(className = NameSpec("com.test.Base"))),
            ),
        )
        assertTrue(
            evaluator.matchesClass(
                gamma(),
                ClassSpec(superClass = ClassSpec(className = NameSpec("java.lang.Object"))),
            ),
        )
        // External superclass metadata is name-only; structural constraints cannot be satisfied.
        assertFalse(
            evaluator.matchesClass(
                gamma(),
                ClassSpec(superClass = ClassSpec(methodCount = 0..100)),
            ),
        )
    }

    @Test
    fun fieldSpecMatchesNameTypeDeclaredClassAndModifiers() {
        val count = metadata.fieldInfo(MatcherFixtures.ALPHA_COUNT_FIELD)!!
        val label = metadata.fieldInfo(MatcherFixtures.ALPHA_NAME_FIELD)!!
        assertTrue(evaluator.matchesField(count, FieldSpec(name = NameSpec("count"))))
        assertTrue(
            evaluator.matchesField(count, FieldSpec(name = NameSpec("cou", NameMatch.StartsWith))),
        )
        assertTrue(evaluator.matchesField(count, FieldSpec(modifiers = 8)))
        assertFalse(evaluator.matchesField(count, FieldSpec(modifiers = 1)))
        assertTrue(
            evaluator.matchesField(count, FieldSpec(type = ClassSpec(className = NameSpec("int")))),
        )
        assertTrue(
            evaluator.matchesField(
                count,
                FieldSpec(declaredClass = ClassSpec(className = NameSpec("com.test.Alpha"))),
            ),
        )
        assertTrue(
            evaluator.matchesField(
                label,
                FieldSpec(type = ClassSpec(className = NameSpec("java.lang.String"))),
            ),
        )
    }

    @Test
    fun candidateMethodQueryUsesCheapPredicates() {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        val results =
            scope.findMethods(
                MethodSpec(
                    name = NameSpec("helper"),
                    paramCount = 1..1,
                    usingStrings = listOf(StringSpec("other")),
                ),
            )
        assertEquals(listOf(MatcherFixtures.ALPHA_HELPER), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'helper'"), sql)
        assertTrue(sql.contains("m.param_count = 1"), sql)
        assertTrue(sql.contains("instr(ms0.value, 'other') > 0"), sql)
    }

    @Test
    fun relationOnlySpecIsRejectedAsUnsupportedCandidateShape() {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        // Broad table scans are forbidden (the generic executor OOMs on the pinned engine):
        // relation-only specs must add a fast-path predicate or fail loudly during development.
        assertThrows(UnsupportedOperationException::class.java) {
            scope.findMethods(
                MethodSpec(invokeMethods = listOf(MethodSpec(name = NameSpec("helper")))),
            )
        }
        assertTrue(query.queries.isEmpty())
    }

    @Test
    fun packagesAndExcludePackagesFilterCandidates() {
        val scope = scope()
        val inPackage =
            scope.findMethods(MethodSpec(paramCount = 0..10), packages = listOf("com.test"))
        assertEquals(MatcherFixtures.methodDescriptors().size, inPackage.size)
        val excluded =
            scope.findMethods(
                MethodSpec(paramCount = 0..10),
                packages = listOf("com.test"),
                excludePackages = listOf("com.test"),
            )
        assertTrue(excluded.isEmpty())
    }

    @Test
    fun classCandidateQueryUsesClassNameAndUsingStrings() {
        val query = RecordingDescriptorQuery { sql ->
            when {
                // Batched class-level usingStrings lookup: method_strings -> method descriptors.
                !sql.contains("class_descriptor = '") && sql.contains("JOIN method_strings") ->
                    if (sql.contains("'alpha marker'")) listOf(MatcherFixtures.ALPHA_RUN)
                    else MatcherFixtures.methodDescriptors()
                !sql.contains("class_descriptor = '") -> MatcherFixtures.classDescriptors()
                sql.contains("class_descriptor = 'Lcom/test/Alpha;'") ->
                    MatcherFixtures.methodDescriptors()
                else -> emptyList()
            }
        }
        val scope = QueryMatcherScope(metadata, query)
        val results =
            scope.findClasses(
                ClassSpec(
                    className = NameSpec("com.test", NameMatch.StartsWith),
                    usingStrings = listOf(StringSpec("alpha marker")),
                ),
            )
        assertEquals(listOf(MatcherFixtures.ALPHA), results.map { it.descriptor })
        val classScan = query.queries.first { it.contains("instr(c.descriptor, 'Lcom/test') = 1") }
        assertFalse(classScan.contains("JOIN method_strings"))
    }

    @Test
    fun fieldCandidateQueryUsesFieldPredicates() {
        val query = RecordingDescriptorQuery { MatcherFixtures.fieldDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        val results =
            scope.findFields(
                FieldSpec(
                    name = NameSpec("count"),
                    declaredClass = ClassSpec(className = NameSpec("com.test.Alpha")),
                ),
            )
        assertEquals(listOf(MatcherFixtures.ALPHA_COUNT_FIELD), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("f.name = 'count'"), sql)
        assertTrue(sql.contains("f.class_descriptor = 'Lcom/test/Alpha;'"), sql)
    }

    @Test
    fun uniqueHelpersEnforceSingleResultContract() {
        val scope = scope()
        assertEquals(
            MatcherFixtures.ALPHA_RUN,
            scope.uniqueMethod(
                MethodSpec(
                    name = NameSpec("run"),
                    declaredClass = ClassSpec(className = NameSpec("com.test.Alpha")),
                ),
            ),
        )
        assertNull(scope.uniqueMethod(MethodSpec(name = NameSpec("missing"))))
        assertThrows(IllegalStateException::class.java) {
            scope.uniqueMethod(MethodSpec(name = NameSpec("run")))
        }
    }

    @Test
    fun exactDescriptorEqualityMatchesOnlyThatMethod() {
        assertTrue(
            evaluator.matchesMethod(
                helper(),
                MethodSpec(descriptor = MatcherFixtures.ALPHA_HELPER),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                helper(),
                MethodSpec(descriptor = MatcherFixtures.ALPHA_RUN),
            ),
        )
    }

    @Test
    fun usingNumbersMatchLongAndDoubleOperandsInjective() {
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(usingNumbers = listOf(42L))))
        assertTrue(evaluator.matchesMethod(run(), MethodSpec(usingNumbers = listOf(0.5))))
        assertFalse(evaluator.matchesMethod(run(), MethodSpec(usingNumbers = listOf(7L))))
        // A floating matcher does not match a long operand (and vice versa).
        assertFalse(evaluator.matchesMethod(run(), MethodSpec(usingNumbers = listOf(42.0))))
        // Injective: two identical matchers require two distinct operands.
        assertFalse(evaluator.matchesMethod(run(), MethodSpec(usingNumbers = listOf(42L, 42L))))
    }

    @Test
    fun callerMethodsUseInjectedRelations() {
        val evaluator = evaluatorWithRelations()
        assertTrue(
            evaluator.matchesMethod(
                run(),
                MethodSpec(callerMethods = listOf(MethodSpec(name = NameSpec("helper")))),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(callerMethods = listOf(MethodSpec(name = NameSpec("other")))),
            ),
        )
        assertFalse(
            evaluator.matchesMethod(
                run(),
                MethodSpec(
                    callerMethods =
                        listOf(
                            MethodSpec(name = NameSpec("helper")),
                            MethodSpec(name = NameSpec("helper")),
                        ),
                ),
            ),
        )
    }

    @Test
    fun fieldReadWriteMethodsUseInjectedRelations() {
        val evaluator = evaluatorWithRelations()
        val count = metadata.fieldInfo(MatcherFixtures.ALPHA_COUNT_FIELD)!!
        assertTrue(
            evaluator.matchesField(
                count,
                FieldSpec(readMethods = listOf(MethodSpec(name = NameSpec("run")))),
            ),
        )
        assertFalse(
            evaluator.matchesField(
                count,
                FieldSpec(readMethods = listOf(MethodSpec(name = NameSpec("helper")))),
            ),
        )
        // The fake relation provider only reports run() as a reader, so a writer spec fails.
        assertFalse(
            evaluator.matchesField(
                count,
                FieldSpec(writeMethods = listOf(MethodSpec(name = NameSpec("run")))),
            ),
        )
    }

    @Test
    fun callerFilteringUsesRelationsWithCheapCandidatePredicate() {
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        val scope = QueryMatcherScope(metadata, query, evaluatorRelations())
        val results =
            scope.findMethods(
                MethodSpec(
                    name = NameSpec("run"),
                    callerMethods = listOf(MethodSpec(name = NameSpec("helper"))),
                ),
            )
        assertEquals(listOf(MatcherFixtures.ALPHA_RUN), results.map { it.descriptor })
        val sql = query.queries.single()
        assertTrue(sql.contains("m.name = 'run'"), sql)
        assertFalse(sql.contains("JOIN classes"), sql)
    }

    @Test
    fun unsupportedRelationsFailLoudly() {
        assertThrows(UnsupportedOperationException::class.java) {
            evaluator.matchesMethod(run(), MethodSpec(callerMethods = listOf(MethodSpec())))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            evaluator.matchesField(
                metadata.fieldInfo(MatcherFixtures.ALPHA_COUNT_FIELD)!!,
                FieldSpec(readMethods = listOf(MethodSpec())),
            )
        }
    }

    private fun evaluatorRelations(): FakeMatcherRelations =
        FakeMatcherRelations(
            metadata = metadata,
            callersByMethod =
                mapOf(MatcherFixtures.ALPHA_RUN to listOf(MatcherFixtures.CALL_HELPER)),
            fieldAccessByKey =
                mapOf(
                    MatcherFixtures.ALPHA_COUNT_FIELD + "|get" to listOf(MatcherFixtures.ALPHA_RUN),
                ),
        )

    private fun evaluatorWithRelations(): DexMatcherEvaluator =
        DexMatcherEvaluator(metadata, evaluatorRelations())
}
