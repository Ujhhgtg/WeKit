package dev.ujhhgtg.wekit.dexkit.sql

import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MatcherSpecJson
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameMatch
import dev.ujhhgtg.wekit.dexkit.matcher.classSpec
import dev.ujhhgtg.wekit.dexkit.matcher.fieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.methodSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Spec -> legacy SQL DSL parity for the scope extensions that the migration needs beyond the base
 * predicate set:
 *
 * - searchPackages on method/constructor (declaring-class descriptor), class (class descriptor) and
 *   field (declaring-class descriptor) specs;
 * - class-level usingNumbers (one canonical method_using_numbers class unit);
 * - structural superClass {} constraints (class-level literal EXISTS units over the superclass).
 *
 * The "old" text is produced by the internal Sql*Query builders, which is exactly what the legacy
 * authoring shims emitted; the spec planner must be byte-identical. Non-expressible combinations
 * must degrade to the query-matcher fallback (null SQL), never throw or silently drop a predicate.
 */
class SpecSqlScopeParityTest {
    private fun assertMethodParity(
        old: DexSqlQuery,
        spec: MethodSpec,
        constructor: Boolean = false,
    ) {
        assertEquals(old.toSql(), spec.toSqlOrNull(constructor))
    }

    private fun assertClassParity(old: DexSqlQuery, spec: ClassSpec) {
        assertEquals(old.toSql(), spec.toSqlOrNull())
    }

    private fun assertFieldParity(old: DexSqlQuery, spec: FieldSpec) {
        assertEquals(old.toSql(), spec.toSqlOrNull())
    }

    // ------------------------------------------------------------------
    // searchPackages
    // ------------------------------------------------------------------

    @Test
    fun methodPackagesEmitDeclaringClassPrefixPredicate() {
        val old = sqlMethod { searchPackages("com.tencent.mm.ui") }
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE (instr(m.class_descriptor, 'Lcom/tencent/mm/ui/') = 1) ORDER BY m.descriptor;",
            old.toSql(),
        )
        assertMethodParity(
            old,
            methodSpec { packages(listOf("com.tencent.mm.ui")) },
        )
    }

    @Test
    fun methodPackagesFollowMethodPropertiesInCanonicalOrder() {
        val old = sqlMethod {
            searchPackages("com.tencent.mm.model")
            name = "getType"
            returnType = "int"
            usingEqStrings("plain")
        }
        val spec = methodSpec {
            searchPackages("com.tencent.mm.model")
            name("getType")
            returnType("int")
            usingEqStrings("plain")
        }
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'plain' AND m.name = 'getType' AND m.return_descriptor = 'I' AND (instr(m.class_descriptor, 'Lcom/tencent/mm/model/') = 1) ORDER BY m.descriptor;",
            old.toSql(),
        )
        assertMethodParity(old, spec)
    }

    @Test
    fun methodPackagesAreOrGroupedInDeclarationOrder() {
        val old = sqlMethod {
            searchPackages("com.tencent.mm.a", "com.tencent.mm.b")
        }
        val spec = methodSpec { searchPackages("com.tencent.mm.a", "com.tencent.mm.b") }
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE (instr(m.class_descriptor, 'Lcom/tencent/mm/a/') = 1 OR instr(m.class_descriptor, 'Lcom/tencent/mm/b/') = 1) ORDER BY m.descriptor;",
            old.toSql(),
        )
        assertMethodParity(old, spec)
    }

    @Test
    fun constructorPackagesEmitAfterForcedConstructorName() {
        val old = sqlConstructor {
            searchPackages("com.tencent.mm.pluginsdk.model")
            usingEqStrings("MicroMsg.NetSceneVerifyUser.dkverify")
            paramCount = 5
        }
        val spec = methodSpec {
            searchPackages("com.tencent.mm.pluginsdk.model")
            usingEqStrings("MicroMsg.NetSceneVerifyUser.dkverify")
            paramCount(5)
        }
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE m.name = '<init>' AND ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'MicroMsg.NetSceneVerifyUser.dkverify' AND m.param_count = 5 AND (instr(m.class_descriptor, 'Lcom/tencent/mm/pluginsdk/model/') = 1) ORDER BY m.descriptor;",
            old.toSql(),
        )
        assertMethodParity(old, spec, constructor = true)
    }

    @Test
    fun classPackagesEmitAgainstTheClassDescriptor() {
        val old = sqlClass { searchPackages("com.tencent.mm.ui.conversation") }
        assertEquals(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE (instr(c.descriptor, 'Lcom/tencent/mm/ui/conversation/') = 1) ORDER BY c.descriptor;",
            old.toSql(),
        )
        assertClassParity(old, classSpec { searchPackages("com.tencent.mm.ui.conversation") })
    }

    @Test
    fun classPackagesWithMethodUnitsUseTheMethodAnchorAlias() {
        val old = sqlClass {
            searchPackages("com.tencent.mm.ui.chatting.component")
            usingEqStrings("MicroMsg.ChattingComponent")
            method {
                name = "<init>"
                paramCount = 2
            }
        }
        val spec = classSpec {
            searchPackages("com.tencent.mm.ui.chatting.component")
            usingEqString("MicroMsg.ChattingComponent")
            method {
                name("<init>")
                paramCount(2)
            }
        }
        assertEquals(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'MicroMsg.ChattingComponent' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND cm1.name = '<init>' AND cm1.param_count = 2 AND (instr(cm.class_descriptor, 'Lcom/tencent/mm/ui/chatting/component/') = 1) ORDER BY cm.class_descriptor;",
            old.toSql(),
        )
        assertClassParity(old, spec)
    }

    @Test
    fun fieldPackagesEmitAgainstTheDeclaringClassDescriptor() {
        val old = sqlField {
            type = "java.lang.String"
            searchPackages("com.tencent.mm")
        }
        assertEquals(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f WHERE f.type_descriptor = 'Ljava/lang/String;' AND (instr(f.class_descriptor, 'Lcom/tencent/mm/') = 1) ORDER BY f.descriptor;",
            old.toSql(),
        )
        assertFieldParity(
            old,
            fieldSpec {
                type("java.lang.String")
                searchPackages("com.tencent.mm")
            },
        )
    }

    @Test
    fun fieldPackagesCombineWithNameAndAccessUnits() {
        val old = sqlField {
            name = "field"
            searchPackages("com.tencent.mm.ui")
            readMethod {
                name = "reader"
                returnType = "void"
            }
        }
        val spec = fieldSpec {
            name("field")
            searchPackages("com.tencent.mm.ui")
            readMethod {
                name("reader")
                returnType("void")
            }
        }
        assertFieldParity(old, spec)
    }

    // ------------------------------------------------------------------
    // Class-level usingNumbers
    // ------------------------------------------------------------------

    @Test
    fun classUsingNumbersEmitOneCanonicalMethodUnit() {
        val old = sqlClass { usingNumbers(681) }
        assertEquals(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_using_numbers mn0 ON mn0.method = cm.descriptor WHERE mn0.file_id = cm.file_id AND mn0.method_idx = cm.method_idx AND mn0.long_value = 681 ORDER BY cm.class_descriptor;",
            old.toSql(),
        )
        assertClassParity(old, classSpec { usingNumbers(681) })
    }

    @Test
    fun classUsingNumbersKeepsOneCallAsOneMethodUnit() {
        val old = sqlClass { usingNumbers(1, 2) }
        assertEquals(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_using_numbers mn0 ON mn0.method = cm.descriptor JOIN method_using_numbers mn1 ON mn1.method = cm.descriptor WHERE mn0.file_id = cm.file_id AND mn0.method_idx = cm.method_idx AND mn0.long_value = 1 AND mn1.file_id = cm.file_id AND mn1.method_idx = cm.method_idx AND mn1.long_value = 2 ORDER BY cm.class_descriptor;",
            old.toSql(),
        )
        assertClassParity(old, classSpec { usingNumbers(1, 2) })
    }

    @Test
    fun classUsingNumbersCombineWithClassLiteralsAndPackages() {
        val old = sqlClass {
            searchPackages("com.tencent.mm.storage")
            usingEqStrings("marker")
            usingNumbers(57, 0.5)
            method {
                name = "run"
                returnType = "void"
            }
        }
        val spec = classSpec {
            searchPackages("com.tencent.mm.storage")
            usingEqString("marker")
            usingNumbers(57, 0.5)
            method {
                name("run")
                returnType("void")
            }
        }
        assertClassParity(old, spec)
    }

    // ------------------------------------------------------------------
    // Structural superClass
    // ------------------------------------------------------------------

    @Test
    fun structuralSuperClassWithLiteralUnitsMatchesLegacyConstraint() {
        val old = sqlClass {
            superClass { usingEqStrings("type") }
        }
        assertEquals(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c JOIN methods scm0 ON scm0.class_descriptor = c.superclass_descriptor JOIN method_strings ms0 ON ms0.method = scm0.descriptor WHERE ms0.file_id = scm0.file_id AND ms0.method_idx = scm0.method_idx AND ms0.value = 'type' ORDER BY c.descriptor;",
            old.toSql(),
        )
        assertClassParity(old, classSpec { superClass { usingEqString("type") } })
    }

    @Test
    fun structuralSuperClassWithNameLiteralsNumbersAndPackages() {
        val old = sqlClass {
            searchPackages("com.tencent.mm.ui")
            className = "com.tencent.mm.ui.Foo"
            superClass {
                className(SqlStringMatchMode.StartsWith, "com.tencent.mm.ui.Base")
                usingEqStrings("super string")
                usingNumbers(42)
            }
        }
        val spec = classSpec {
            searchPackages("com.tencent.mm.ui")
            className("com.tencent.mm.ui.Foo")
            superClass {
                className("com.tencent.mm.ui.Base", NameMatch.StartsWith)
                usingEqString("super string")
                usingNumbers(42)
            }
        }
        assertClassParity(old, spec)
    }

    @Test
    fun structuralSuperClassWithNumberUnitsOnlyMatchesLegacyConstraint() {
        val old = sqlClass { superClass { usingNumbers(1, 2) } }
        val spec = classSpec { superClass { usingNumbers(1, 2) } }
        assertClassParity(old, spec)
    }

    @Test
    fun structuralSuperClassAndMethodDeclaredClassShareLiteralShapes() {
        val oldMethod = sqlMethod {
            name = "run"
            declaredClass {
                className("com.tencent.mm.Host")
                usingEqStrings("host literal")
                usingNumbers(8)
            }
        }
        val specMethod = methodSpec {
            name("run")
            declaredClass {
                className("com.tencent.mm.Host")
                usingEqString("host literal")
                usingNumbers(8)
            }
        }
        assertMethodParity(oldMethod, specMethod)
    }

    // ------------------------------------------------------------------
    // Fallback: non-expressible specs must return null, not throw
    // ------------------------------------------------------------------

    @Test
    fun classSpecFallbackForStructuralSuperClassConstraints() {
        assertNull(classSpec { superClass { modifiers = 1 } }.toSqlOrNull())
        assertNull(classSpec { superClass { method { name("x") } } }.toSqlOrNull())
        assertNull(classSpec { superClass { searchPackages("com.tencent.mm") } }.toSqlOrNull())
        assertNull(
            classSpec { superClass { superClass("com.tencent.mm.Base") } }.toSqlOrNull(),
        )
        assertFalse(classSpec { superClass { modifiers = 1 } }.isSqlExpressible())
    }

    @Test
    fun classSpecFallbackForClassNumbersCombinedWithStructuralMembers() {
        val spec = classSpec {
            usingNumbers(42)
            fieldCount = 2..2
        }
        assertNull(spec.toSqlOrNull())
        assertFalse(spec.isSqlExpressible())
    }

    @Test
    fun methodSpecFallbackForNestedDeclaredClassPackagesAndNumbers() {
        // A class-literal constraint cannot carry packages; the matcher fallback evaluates them.
        assertNull(
            methodSpec {
                name("run")
                declaredClass {
                    className("com.tencent.mm.Host")
                    searchPackages("com.tencent.mm")
                }
            }
                .toSqlOrNull(),
        )
        // Nested method units (class/field accessors) never carry class-literal constraint joins.
        assertNull(
            classSpec {
                method {
                    name("run")
                    declaredClass {
                        className("com.tencent.mm.Host")
                        usingEqString("literal")
                    }
                }
            }
                .toSqlOrNull(),
        )
        assertNull(
            fieldSpec {
                name("field")
                readMethod {
                    name("reader")
                    searchPackages("com.tencent.mm")
                }
            }
                .toSqlOrNull(),
        )
    }

    @Test
    fun directQueryBuilderConstructorsMatchTheSameCanonicalText() {
        // The legacy authoring shims are thin wrappers over these internal builders; construct them
        // directly to prove the parity is against the emitter itself, not a helper.
        val method = SqlMethodQuery().apply { searchPackages("com.tencent.mm.ui") }
        assertMethodParity(
            DexSqlQuery { emitMethod(method, constructor = false) },
            methodSpec { searchPackages("com.tencent.mm.ui") },
        )
        val clazz =
            SqlClassQuery().apply {
                searchPackages("com.tencent.mm.ui.conversation")
                usingNumbers(42)
            }
        assertClassParity(
            DexSqlQuery { emitClass(clazz) },
            classSpec {
                searchPackages("com.tencent.mm.ui.conversation")
                usingNumbers(42)
            },
        )
        val field = SqlFieldQuery().apply { searchPackages("com.tencent.mm") }
        assertFieldParity(
            DexSqlQuery { emitField(field) },
            fieldSpec { searchPackages("com.tencent.mm") },
        )
    }

    // ------------------------------------------------------------------
    // JSON codec stays in sync with the additive fields
    // ------------------------------------------------------------------

    @Test
    fun jsonCodecParsesScopeExtensions() {
        val method =
            MatcherSpecJson.methodSpec(
                """{"name":"run","packages":["com.tencent.mm","com.tencent.mm.ui"]}""",
            )
        assertEquals(listOf("com.tencent.mm", "com.tencent.mm.ui"), method.packages)

        val classSpec =
            MatcherSpecJson.classSpec(
                """{"packages":["com.tencent.mm"],"using_numbers":[42,0.5]}""",
            )
        assertEquals(listOf("com.tencent.mm"), classSpec.packages)
        assertEquals(listOf<Number>(42L, 0.5), classSpec.usingNumbers)

        val field = MatcherSpecJson.fieldSpec("""{"packages":["com.tencent.mm"]}""")
        assertEquals(listOf("com.tencent.mm"), field.packages)
    }
}
