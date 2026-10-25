package dev.ujhhgtg.wekit.dex.matcher

import dev.ujhhgtg.wekit.dex.meta.FieldUsingType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatcherBuildersTest {
    private val metadata = MatcherFixtures.metadata

    @Test
    fun specBuildersCarryPackageFilters() {
        val method = methodSpec {
            searchPackages("com.tencent.mm")
            excludePackages("com.tencent.mm.ui.conversation")
        }
        assertEquals(listOf("com.tencent.mm"), method.searchPackages)
        assertEquals(listOf("com.tencent.mm.ui.conversation"), method.excludePackages)

        val klass = classSpec { excludePackages("com.tencent.mm.ui") }
        assertEquals(listOf("com.tencent.mm.ui"), klass.excludePackages)

        val field = fieldSpec { excludePackages("com.a.b", "com.a.c") }
        assertEquals(listOf("com.a.b", "com.a.c"), field.excludePackages)
    }

    @Test
    fun classSpecBuilderNestsStructuralConstraints() {
        val spec = classSpec {
            className("com.test.Alpha")
            superClass { className("com.test.Base") }
            interfaceSpec { className("com.test.I1") }
            method {
                name("run")
                paramTypes()
            }
            field {
                name("count", NameMatch.Contains)
                modifiers = 8
            }
            fieldCount = 2..2
            methodCount = 3..3
            modifiers = 1
            usingString("alpha marker")
        }
        assertEquals(NameSpec("com.test.Alpha"), spec.className)
        assertEquals(NameSpec("com.test.Base"), spec.superClass?.className)
        assertEquals(NameSpec("com.test.I1"), spec.interfaces.single().className)
        assertEquals(
            "run",
            spec.methods.single().name?.value,
        )
        assertEquals(8, spec.fields.single().modifiers)
        assertEquals(2..2, spec.fieldCount)
        assertEquals(3..3, spec.methodCount)
        assertEquals(listOf(StringSpec("alpha marker")), spec.usingStrings)
    }

    @Test
    fun methodSpecBuilderSupportsRelationsAndGroups() {
        val spec = methodSpec {
            name("run")
            declaredClass { className("com.test.Alpha", NameMatch.Contains) }
            returnType { className("void") }
            paramTypes()
            paramCount = 0..0
            usingEqString("alpha marker")
            usingNumber(42L)
            readField { name("count") }
            invokeMethod { name("helper") }
            invokeMethod("Lcom/test/Alpha;->helper(I)V")
            callerMethod { name("caller") }
            anyOf { name("a") }
            allOf { name("b") }
        }
        assertEquals(NameSpec("run"), spec.name)
        assertEquals(NameMatch.Contains, spec.declaredClass?.className?.match)
        assertEquals(NameSpec("void"), spec.returnType?.className)
        assertEquals(emptyList<TypeSpec?>(), spec.paramTypes)
        assertEquals(0..0, spec.paramCount)
        assertEquals(listOf(StringSpec("alpha marker", NameMatch.Equals)), spec.usingStrings)
        assertEquals(listOf<Number>(42L), spec.usingNumbers)
        assertEquals(FieldUsingType.Read, spec.usingFields.single().usingType)
        assertEquals("Lcom/test/Alpha;->helper(I)V", spec.invokeMethods[1].descriptor)
        assertEquals(1, spec.callerMethods.size)
        assertEquals(1, spec.anyOf.size)
        assertEquals(1, spec.allOf.size)
    }

    @Test
    fun fieldSpecBuilderAndUsingFieldHelpers() {
        val spec = fieldSpec {
            name("count", NameMatch.StartsWith)
            modifiers = 8
            type { className("int") }
            declaredClass { className("com.test.Alpha") }
            readMethod { name("run") }
            writeMethod { name("set", NameMatch.Contains) }
        }
        assertEquals(NameSpec("count", NameMatch.StartsWith), spec.name)
        assertEquals(8, spec.modifiers)
        assertEquals(NameSpec("int"), spec.type?.className)
        assertEquals(
            "run",
            spec.readMethods.single().name?.value,
        )
        assertEquals(
            "set",
            spec.writeMethods.single().name?.value,
        )

        val read = readField(FieldSpec(name = NameSpec("count")))
        assertEquals(FieldUsingType.Read, read.usingType)
        val write = writeField()
        assertEquals(FieldUsingType.Write, write.usingType)
        assertNull(usingField().usingType)
    }

    @Test
    fun exactDescriptorCandidateRunsNoSql() {
        // Exact descriptor specs short-circuit to a single known candidate: no SQL scan at all.
        val query = RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() }
        val scope = QueryMatcherScope(metadata, query)
        val results = scope.findMethods(MethodSpec(descriptor = MatcherFixtures.ALPHA_HELPER))
        assertEquals(listOf(MatcherFixtures.ALPHA_HELPER), results.map { it.descriptor })
        assertTrue(query.queries.isEmpty())
    }
}
