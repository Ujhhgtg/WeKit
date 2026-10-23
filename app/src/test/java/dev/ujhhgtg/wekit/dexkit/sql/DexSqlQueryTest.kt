package dev.ujhhgtg.wekit.dexkit.sql

import dev.ujhhgtg.wekit.dexkit.dsl.DexSqlSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Canonical emission tests for the DexSQL matcher DSL.
 *
 * Every expected string is the byte-for-byte canonical text of the hardened migration extractor
 * (~/coding/dexsql/tools/extract_wekit_queries.py), including its relation-alias numbering quirks.
 */
class DexSqlQueryTest {

    private fun assertSql(expected: String, query: DexSqlQuery) {
        assertEquals(expected, query.toSql())
    }

    // ------------------------------------------------------------------
    // sqlMethod
    // ------------------------------------------------------------------

    @Test
    fun methodEmitsNameCondition() {
        val query = sqlMethod {
            name = "doThing"
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = 'doThing' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEmitsAllPropertyConditionsInCanonicalOrder() {
        val query = sqlMethod {
            usingEqStrings("exact")
            name = "doThing"
            declaredClass = "com.tencent.mm.Foo"
            returnType = "void"
            paramCount = 2
            paramTypes("java.lang.String", "int")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'exact' AND m.name = 'doThing' AND m.class_descriptor = 'Lcom/tencent/mm/Foo;' AND m.return_descriptor = 'V' AND m.param_count = 2 AND m.param_descriptors = 'Ljava/lang/String;,I' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEmitsEachStringModeInDeclarationOrder() {
        val query = sqlMethod {
            usingStrings("hello")
            usingEqStrings("exact")
            usingPrefixStrings("pre")
            usingSuffixStrings("fix")
            addUsingString("single")
            addEqString("one")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor JOIN method_strings ms1 ON ms1.method = m.descriptor JOIN method_strings ms2 ON ms2.method = m.descriptor JOIN method_strings ms3 ON ms3.method = m.descriptor JOIN method_strings ms4 ON ms4.method = m.descriptor JOIN method_strings ms5 ON ms5.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE '%hello%' AND ms1.file_id = m.file_id AND ms1.method_idx = m.method_idx AND ms1.value = 'exact' AND ms2.file_id = m.file_id AND ms2.method_idx = m.method_idx AND ms2.value LIKE 'pre%' AND ms3.file_id = m.file_id AND ms3.method_idx = m.method_idx AND ms3.value LIKE '%fix' AND ms4.file_id = m.file_id AND ms4.method_idx = m.method_idx AND ms4.value LIKE '%single%' AND ms5.file_id = m.file_id AND ms5.method_idx = m.method_idx AND ms5.value = 'one' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEscapesLikeWildcardsAndQuotes() {
        val query = sqlMethod {
            usingStrings("a_b%c[d]e")
            usingEqStrings("it's")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor JOIN method_strings ms1 ON ms1.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE '%a\\_b\\%c[d]e%' ESCAPE '\\' AND ms1.file_id = m.file_id AND ms1.method_idx = m.method_idx AND ms1.value = 'it''s' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEmitsNumbersAfterStringsWithContinuingAliasIndex() {
        val query = sqlMethod {
            usingEqStrings("x")
            usingNumbers(42, 0.5)
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor JOIN method_using_numbers mn1 ON mn1.method = m.descriptor JOIN method_using_numbers mn2 ON mn2.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'x' AND mn1.file_id = m.file_id AND mn1.method_idx = m.method_idx AND mn1.long_value = 42 AND mn2.file_id = m.file_id AND mn2.method_idx = m.method_idx AND mn2.double_value = 0.5 ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEmitsIntegerAndFloatNumbersWithoutStrings() {
        val query = sqlMethod {
            usingNumbers(57, -3, 0.5)
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_using_numbers mn0 ON mn0.method = m.descriptor JOIN method_using_numbers mn1 ON mn1.method = m.descriptor JOIN method_using_numbers mn2 ON mn2.method = m.descriptor WHERE mn0.file_id = m.file_id AND mn0.method_idx = m.method_idx AND mn0.long_value = 57 AND mn1.file_id = m.file_id AND mn1.method_idx = m.method_idx AND mn1.long_value = -3 AND mn2.file_id = m.file_id AND mn2.method_idx = m.method_idx AND mn2.double_value = 0.5 ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEmitsOrJoinedPackages() {
        val query = sqlMethod {
            usingEqStrings("x")
            searchPackages("com.tencent.mm", "com.tencent.mm.plugin")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'x' AND (m.class_descriptor LIKE 'Lcom/tencent/mm/%' OR m.class_descriptor LIKE 'Lcom/tencent/mm/plugin/%') ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEmitsLimitAndOffsetAfterOrderBy() {
        val query = sqlMethod {
            name = "doThing"
            limit(1)
            offset(1)
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = 'doThing' ORDER BY m.descriptor LIMIT 1 OFFSET 1;",
            query,
        )
    }

    @Test
    fun methodEmitsLimitOnly() {
        val query = sqlMethod {
            name = "doThing"
            limit(3)
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = 'doThing' ORDER BY m.descriptor LIMIT 3;",
            query,
        )
    }

    @Test
    fun methodConvertsJavaTypesToDescriptors() {
        val query = sqlMethod {
            name = "typed"
            declaredClass = "Lcom/foo/Bar;"
            returnType = "byte[]"
            paramCount = 2
            paramTypes("java.lang.String[]", "int", "Lfoo/Bar;")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = 'typed' AND m.class_descriptor = 'Lcom/foo/Bar;' AND m.return_descriptor = '[B' AND m.param_count = 2 AND m.param_descriptors = '[Ljava/lang/String;,I,Lfoo/Bar;' ORDER BY m.descriptor;",
            query,
        )
    }

    // ------------------------------------------------------------------
    // sqlConstructor
    // ------------------------------------------------------------------

    @Test
    fun constructorForcesInitNameAsFirstCondition() {
        val query = sqlConstructor {
            usingEqStrings("MicroMsg.NetSceneVerifyUser.dkverify", "getLabelIdList, %s")
            paramCount = 2
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor JOIN method_strings ms1 ON ms1.method = m.descriptor WHERE m.name = '<init>' AND ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'MicroMsg.NetSceneVerifyUser.dkverify' AND ms1.file_id = m.file_id AND ms1.method_idx = m.method_idx AND ms1.value = 'getLabelIdList, %s' AND m.param_count = 2 ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun emptyConstructorMatchesAllConstructors() {
        val query = sqlConstructor {}
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = '<init>' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun constructorEmitsPropertyConditionsAfterInitName() {
        val query = sqlConstructor {
            declaredClass = "com.tencent.mm.Foo"
            paramTypes("int", "java.lang.String")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = '<init>' AND m.class_descriptor = 'Lcom/tencent/mm/Foo;' AND m.param_descriptors = 'I,Ljava/lang/String;' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun constructorAcceptsExplicitInitName() {
        val query = sqlConstructor {
            name = "<init>"
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = '<init>' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun constructorEmitsContainsStringCondition() {
        val query = sqlConstructor {
            usingStrings("constructor marker")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE m.name = '<init>' AND ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE '%constructor marker%' ORDER BY m.descriptor;",
            query,
        )
    }

    // ------------------------------------------------------------------
    // sqlClass
    // ------------------------------------------------------------------

    @Test
    fun classQueryEmitsClassNameFromClassesTable() {
        val query = sqlClass {
            className = "com.tencent.mm.Foo"
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.descriptor = 'Lcom/tencent/mm/Foo;' ORDER BY c.descriptor;",
            query,
        )
    }

    @Test
    fun classQueryEmitsSuperClassFromClassesTable() {
        val query = sqlClass {
            superClass = "android.app.Dialog"
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.superclass_descriptor = 'Landroid/app/Dialog;' ORDER BY c.descriptor;",
            query,
        )
    }

    @Test
    fun classQueryEmitsOrJoinedPackages() {
        val query = sqlClass {
            searchPackages("com.tencent.mm.ui", "com.tencent.mm.plugin")
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE (c.descriptor LIKE 'Lcom/tencent/mm/ui/%' OR c.descriptor LIKE 'Lcom/tencent/mm/plugin/%') ORDER BY c.descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithOneLiteralStartsFromMethods() {
        val query = sqlClass {
            usingEqStrings("a")
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'a' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithTwoLiteralsUsesIndependentMethodUnits() {
        val query = sqlClass {
            usingEqStrings("a")
            usingStrings("b")
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN method_strings ms2 ON ms2.method = cm1.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'a' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND ms2.file_id = cm1.file_id AND ms2.method_idx = cm1.method_idx AND ms2.value LIKE '%b%' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithLiteralsEmitsClassNameAndSuperClassAfterMethodUnits() {
        val query = sqlClass {
            usingEqStrings("a", "b")
            className = "com.tencent.mm.Foo"
            superClass = "android.app.Activity"
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN method_strings ms2 ON ms2.method = cm1.descriptor JOIN classes c ON c.descriptor = cm.class_descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'a' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND ms2.file_id = cm1.file_id AND ms2.method_idx = cm1.method_idx AND ms2.value = 'b' AND cm.class_descriptor = 'Lcom/tencent/mm/Foo;' AND c.file_id = cm.file_id AND c.class_idx = cm.class_idx AND c.superclass_descriptor = 'Landroid/app/Activity;' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithLiteralsEmitsPackagesAfterClassName() {
        val query = sqlClass {
            usingEqStrings("a")
            usingPrefixStrings("b")
            searchPackages("com.tencent")
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN method_strings ms2 ON ms2.method = cm1.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'a' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND ms2.file_id = cm1.file_id AND ms2.method_idx = cm1.method_idx AND ms2.value LIKE 'b%' AND (cm.class_descriptor LIKE 'Lcom/tencent/%') ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithNestedMethodUsesBaseMethodRowAndStartsAtAliasZero() {
        val query = sqlClass {
            method {
                usingEqStrings("MicroMsg.NetSceneReceiveLuckyMoney")
                name = "<init>"
                paramCount = 2
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'MicroMsg.NetSceneReceiveLuckyMoney' AND cm.name = '<init>' AND cm.param_count = 2 ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithNestedNumberAndPropertyMatchers() {
        val query = sqlClass {
            method {
                usingNumbers(681)
                name = "getFuncId"
                returnType = "int"
            }
            method {
                name = "toProtoBuf"
                returnType = "byte[]"
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_using_numbers mn0 ON mn0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor WHERE mn0.file_id = cm.file_id AND mn0.method_idx = cm.method_idx AND mn0.long_value = 681 AND cm.name = 'getFuncId' AND cm.return_descriptor = 'I' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND cm1.name = 'toProtoBuf' AND cm1.return_descriptor = '[B' AND NOT (cm.file_id = cm1.file_id AND cm.method_idx = cm1.method_idx) ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithMethodsSuperClassAndPackages() {
        val query = sqlClass {
            superClass = "android.widget.BaseAdapter"
            searchPackages("com.tencent.mm.ui.base.preference")
            method {
                name = "getView"
                paramCount = 3
            }
            method {
                name = "<init>"
                paramCount = 3
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN methods cm0 ON cm0.class_descriptor = cm.class_descriptor JOIN classes c ON c.descriptor = cm.class_descriptor WHERE cm.name = 'getView' AND cm.param_count = 3 AND cm0.file_id = cm.file_id AND cm0.class_idx = cm.class_idx AND cm0.name = '<init>' AND cm0.param_count = 3 AND c.file_id = cm.file_id AND c.class_idx = cm.class_idx AND c.superclass_descriptor = 'Landroid/widget/BaseAdapter;' AND (cm.class_descriptor LIKE 'Lcom/tencent/mm/ui/base/preference/%') AND NOT (cm.file_id = cm0.file_id AND cm.method_idx = cm0.method_idx) ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithThreeNestedMethodMatchersAddsPairwiseDistinctness() {
        val query = sqlClass {
            method {
                name = "first"
                returnType = "void"
            }
            method {
                name = "second"
                paramCount = 1
            }
            method {
                name = "<init>"
                paramCount = 2
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN methods cm0 ON cm0.class_descriptor = cm.class_descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor WHERE cm.name = 'first' AND cm.return_descriptor = 'V' AND cm0.file_id = cm.file_id AND cm0.class_idx = cm.class_idx AND cm0.name = 'second' AND cm0.param_count = 1 AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND cm1.name = '<init>' AND cm1.param_count = 2 AND NOT (cm.file_id = cm0.file_id AND cm.method_idx = cm0.method_idx) AND NOT (cm.file_id = cm1.file_id AND cm.method_idx = cm1.method_idx) AND NOT (cm0.file_id = cm1.file_id AND cm0.method_idx = cm1.method_idx) ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithClassNameAndLiteralUsesMethodScope() {
        val query = sqlClass {
            usingEqStrings("a")
            className = "com.tencent.mm.Foo"
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'a' AND cm.class_descriptor = 'Lcom/tencent/mm/Foo;' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithClassNameAndNestedMethodUsesBaseMethodRow() {
        val query = sqlClass {
            className = "com.tencent.mm.Foo"
            method {
                name = "run"
                returnType = "void"
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm WHERE cm.name = 'run' AND cm.return_descriptor = 'V' AND cm.class_descriptor = 'Lcom/tencent/mm/Foo;' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithDirectAndNestedLiteralsContinuesAliasIndex() {
        val query = sqlClass {
            usingEqStrings("a")
            method {
                usingStrings("b")
                name = "run"
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN method_strings ms2 ON ms2.method = cm1.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'a' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND ms2.file_id = cm1.file_id AND ms2.method_idx = cm1.method_idx AND ms2.value LIKE '%b%' AND cm1.name = 'run' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithLiteralAndTwoNestedMethodMatchersConstrainsOnlyNestedUnits() {
        val query = sqlClass {
            usingEqStrings("marker")
            method {
                name = "first"
            }
            method {
                name = "second"
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN methods cm2 ON cm2.class_descriptor = cm.class_descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'marker' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND cm1.name = 'first' AND cm2.file_id = cm.file_id AND cm2.class_idx = cm.class_idx AND cm2.name = 'second' AND NOT (cm1.file_id = cm2.file_id AND cm1.method_idx = cm2.method_idx) ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryWithNumberLiteralUsesMethodScope() {
        val query = sqlClass {
            usingNumbers(681)
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_using_numbers mn0 ON mn0.method = cm.descriptor WHERE mn0.file_id = cm.file_id AND mn0.method_idx = cm.method_idx AND mn0.long_value = 681 ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryKeepsOneNumberCallAsOneMethodUnit() {
        val query = sqlClass {
            usingNumbers(1, 2)
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_using_numbers mn0 ON mn0.method = cm.descriptor JOIN method_using_numbers mn1 ON mn1.method = cm.descriptor WHERE mn0.file_id = cm.file_id AND mn0.method_idx = cm.method_idx AND mn0.long_value = 1 AND mn1.file_id = cm.file_id AND mn1.method_idx = cm.method_idx AND mn1.long_value = 2 ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQuerySplitsSeparateNumberCallsIntoMethodUnits() {
        val query = sqlClass {
            usingNumbers(1)
            usingNumbers(2)
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_using_numbers mn0 ON mn0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN method_using_numbers mn2 ON mn2.method = cm1.descriptor WHERE mn0.file_id = cm.file_id AND mn0.method_idx = cm.method_idx AND mn0.long_value = 1 AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND mn2.file_id = cm1.file_id AND mn2.method_idx = cm1.method_idx AND mn2.long_value = 2 ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classQueryEmitsLimitAfterOrderBy() {
        val query = sqlClass {
            className = "com.tencent.mm.Foo"
            limit(5)
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.descriptor = 'Lcom/tencent/mm/Foo;' ORDER BY c.descriptor LIMIT 5;",
            query,
        )
    }

    @Test
    fun classQueryPassesThroughClassDescriptor() {
        val query = sqlClass {
            className = "Lcom/foo/Bar;"
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.descriptor = 'Lcom/foo/Bar;' ORDER BY c.descriptor;",
            query,
        )
    }


    // ------------------------------------------------------------------
    // sqlField
    // ------------------------------------------------------------------

    @Test
    fun fieldQueryEmitsNameDeclaredClassAndType() {
        val query = sqlField {
            name = "field"
            declaredClass = "com.tencent.mm.Foo"
            type = "int"
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f WHERE f.name = 'field' AND f.class_descriptor = 'Lcom/tencent/mm/Foo;' AND f.type_descriptor = 'I' ORDER BY f.descriptor;",
            query,
        )
    }

    @Test
    fun fieldQueryEmitsPackagesAgainstClassDescriptor() {
        val query = sqlField {
            type = "java.lang.String"
            searchPackages("com.tencent.mm")
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f WHERE f.type_descriptor = 'Ljava/lang/String;' AND (f.class_descriptor LIKE 'Lcom/tencent/mm/%') ORDER BY f.descriptor;",
            query,
        )
    }

    @Test
    fun fieldQueryEmitsReadMethodAccess() {
        val query = sqlField {
            declaredClass = "com.tencent.mm.ui.conversation.ConversationListView"
            type = "android.view.View"
            readMethod {
                usingEqStrings("resetActionBarView")
                returnType = "void"
                paramCount = 0
            }
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f JOIN method_field_accesses fa0 ON fa0.field = f.descriptor JOIN methods fm0 ON fm0.descriptor = fa0.method JOIN method_strings ms1 ON ms1.method = fm0.descriptor WHERE f.class_descriptor = 'Lcom/tencent/mm/ui/conversation/ConversationListView;' AND f.type_descriptor = 'Landroid/view/View;' AND fa0.file_id = f.file_id AND fa0.field_idx = f.field_idx AND fm0.file_id = fa0.file_id AND fm0.method_idx = fa0.method_idx AND fa0.using_type = 'get' AND ms1.file_id = fm0.file_id AND ms1.method_idx = fm0.method_idx AND ms1.value = 'resetActionBarView' AND fm0.return_descriptor = 'V' AND fm0.param_count = 0 ORDER BY f.descriptor;",
            query,
        )
    }

    @Test
    fun fieldQueryEmitsWriteMethodAccess() {
        val query = sqlField {
            type = "java.lang.String"
            writeMethod {
                usingEqStrings("setMarker")
                returnType = "void"
            }
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f JOIN method_field_accesses fa0 ON fa0.field = f.descriptor JOIN methods fm0 ON fm0.descriptor = fa0.method JOIN method_strings ms1 ON ms1.method = fm0.descriptor WHERE f.type_descriptor = 'Ljava/lang/String;' AND fa0.file_id = f.file_id AND fa0.field_idx = f.field_idx AND fm0.file_id = fa0.file_id AND fm0.method_idx = fa0.method_idx AND fa0.using_type = 'put' AND ms1.file_id = fm0.file_id AND ms1.method_idx = fm0.method_idx AND ms1.value = 'setMarker' AND fm0.return_descriptor = 'V' ORDER BY f.descriptor;",
            query,
        )
    }

    @Test
    fun fieldQueryEmitsReadThenWriteAccessWithCanonicalAliasIndexing() {
        val query = sqlField {
            declaredClass = "com.tencent.mm.Foo"
            readMethod {
                usingEqStrings("getter")
            }
            writeMethod {
                usingNumbers(3)
                name = "setter"
                paramTypes("int")
            }
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f JOIN method_field_accesses fa0 ON fa0.field = f.descriptor JOIN methods fm0 ON fm0.descriptor = fa0.method JOIN method_strings ms1 ON ms1.method = fm0.descriptor JOIN method_field_accesses fa1 ON fa1.field = f.descriptor JOIN methods fm1 ON fm1.descriptor = fa1.method JOIN method_using_numbers mn2 ON mn2.method = fm1.descriptor WHERE f.class_descriptor = 'Lcom/tencent/mm/Foo;' AND fa0.file_id = f.file_id AND fa0.field_idx = f.field_idx AND fm0.file_id = fa0.file_id AND fm0.method_idx = fa0.method_idx AND fa0.using_type = 'get' AND ms1.file_id = fm0.file_id AND ms1.method_idx = fm0.method_idx AND ms1.value = 'getter' AND fa1.file_id = f.file_id AND fa1.field_idx = f.field_idx AND fm1.file_id = fa1.file_id AND fm1.method_idx = fa1.method_idx AND fa1.using_type = 'put' AND mn2.file_id = fm1.file_id AND mn2.method_idx = fm1.method_idx AND mn2.long_value = 3 AND fm1.name = 'setter' AND fm1.param_descriptors = 'I' ORDER BY f.descriptor;",
            query,
        )
    }

    @Test
    fun fieldQueryEmitsAccessWithPropertyOnlyMatcher() {
        val query = sqlField {
            readMethod {
                name = "getX"
                returnType = "boolean"
            }
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f JOIN method_field_accesses fa0 ON fa0.field = f.descriptor JOIN methods fm0 ON fm0.descriptor = fa0.method WHERE fa0.file_id = f.file_id AND fa0.field_idx = f.field_idx AND fm0.file_id = fa0.file_id AND fm0.method_idx = fa0.method_idx AND fa0.using_type = 'get' AND fm0.name = 'getX' AND fm0.return_descriptor = 'Z' ORDER BY f.descriptor;",
            query,
        )
    }

    @Test
    fun fieldQueryEmitsLimitAndOffsetAfterOrderBy() {
        val query = sqlField {
            name = "field"
            limit(10)
            offset(2)
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f WHERE f.name = 'field' ORDER BY f.descriptor LIMIT 10 OFFSET 2;",
            query,
        )
    }

    // ------------------------------------------------------------------
    // declaredClass { } / superClass { } class-literal constraints
    // ------------------------------------------------------------------

    @Test
    fun methodDeclaredClassLiteralEmitsCcmUnit() {
        val query = sqlMethod {
            name = "target"
            declaredClass {
                usingEqStrings("marker")
            }
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN methods ccm0 ON ccm0.class_descriptor = m.class_descriptor JOIN method_strings ms0 ON ms0.method = ccm0.descriptor WHERE m.name = 'target' AND ccm0.file_id = m.file_id AND ccm0.class_idx = m.class_idx AND ms0.file_id = ccm0.file_id AND ms0.method_idx = ccm0.method_idx AND ms0.value = 'marker' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodDeclaredClassUnitsContinueSharedLiteralCounter() {
        val query = sqlMethod {
            usingStrings("outer")
            declaredClass {
                usingEqStrings("first")
                usingPrefixStrings("second")
            }
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor JOIN methods ccm0 ON ccm0.class_descriptor = m.class_descriptor JOIN method_strings ms1 ON ms1.method = ccm0.descriptor JOIN methods ccm1 ON ccm1.class_descriptor = m.class_descriptor JOIN method_strings ms2 ON ms2.method = ccm1.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE '%outer%' AND ccm0.file_id = m.file_id AND ccm0.class_idx = m.class_idx AND ms1.file_id = ccm0.file_id AND ms1.method_idx = ccm0.method_idx AND ms1.value = 'first' AND ccm1.file_id = m.file_id AND ccm1.class_idx = m.class_idx AND ms2.file_id = ccm1.file_id AND ms2.method_idx = ccm1.method_idx AND ms2.value LIKE 'second%' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodDeclaredClassNumbersSplitIntoPerValueUnits() {
        val query = sqlMethod {
            declaredClass {
                usingNumbers(57, 0.5)
            }
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN methods ccm0 ON ccm0.class_descriptor = m.class_descriptor JOIN method_using_numbers mn0 ON mn0.method = ccm0.descriptor JOIN methods ccm1 ON ccm1.class_descriptor = m.class_descriptor JOIN method_using_numbers mn1 ON mn1.method = ccm1.descriptor WHERE ccm0.file_id = m.file_id AND ccm0.class_idx = m.class_idx AND mn0.file_id = ccm0.file_id AND mn0.method_idx = ccm0.method_idx AND mn0.long_value = 57 AND ccm1.file_id = m.file_id AND ccm1.class_idx = m.class_idx AND mn1.file_id = ccm1.file_id AND mn1.method_idx = ccm1.method_idx AND mn1.double_value = 0.5 ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodDeclaredClassMultiValueCallSplitsIntoPerValueUnits() {
        val query = sqlMethod {
            declaredClass {
                usingEqStrings("a", "b")
            }
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN methods ccm0 ON ccm0.class_descriptor = m.class_descriptor JOIN method_strings ms0 ON ms0.method = ccm0.descriptor JOIN methods ccm1 ON ccm1.class_descriptor = m.class_descriptor JOIN method_strings ms1 ON ms1.method = ccm1.descriptor WHERE ccm0.file_id = m.file_id AND ccm0.class_idx = m.class_idx AND ms0.file_id = ccm0.file_id AND ms0.method_idx = ccm0.method_idx AND ms0.value = 'a' AND ccm1.file_id = m.file_id AND ccm1.class_idx = m.class_idx AND ms1.file_id = ccm1.file_id AND ms1.method_idx = ccm1.method_idx AND ms1.value = 'b' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun classSuperClassLiteralOnMethodsBasedQuery() {
        val query = sqlClass {
            method {
                name = "target"
            }
            superClass {
                usingEqStrings("marker")
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN classes cc ON cc.descriptor = cm.class_descriptor JOIN methods scm0 ON scm0.class_descriptor = cc.superclass_descriptor JOIN method_strings ms0 ON ms0.method = scm0.descriptor WHERE cm.name = 'target' AND cc.file_id = cm.file_id AND cc.class_idx = cm.class_idx AND ms0.file_id = scm0.file_id AND ms0.method_idx = scm0.method_idx AND ms0.value = 'marker' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classSuperClassLiteralOnBareClassQuery() {
        val query = sqlClass {
            superClass {
                usingEqStrings("marker")
            }
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c JOIN methods scm0 ON scm0.class_descriptor = c.superclass_descriptor JOIN method_strings ms0 ON ms0.method = scm0.descriptor WHERE ms0.file_id = scm0.file_id AND ms0.method_idx = scm0.method_idx AND ms0.value = 'marker' ORDER BY c.descriptor;",
            query,
        )
    }

    @Test
    fun classSuperClassLiteralsContinueSharedCounter() {
        val query = sqlClass {
            usingEqStrings("outer")
            method {
                name = "m"
            }
            superClass {
                usingEqStrings("sc")
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN classes cc ON cc.descriptor = cm.class_descriptor JOIN methods scm0 ON scm0.class_descriptor = cc.superclass_descriptor JOIN method_strings ms2 ON ms2.method = scm0.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'outer' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND cm1.name = 'm' AND cc.file_id = cm.file_id AND cc.class_idx = cm.class_idx AND ms2.file_id = scm0.file_id AND ms2.method_idx = scm0.method_idx AND ms2.value = 'sc' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classLiteralConstraintsRejectEmptyCallsAndBlocks() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                declaredClass {}
            }.toSql()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                declaredClass {
                    usingStrings()
                }
            }.toSql()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sqlClass {
                superClass {}
            }.toSql()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                declaredClass {
                    usingEqStrings("a")
                }
                declaredClass {
                    usingEqStrings("b")
                }
            }.toSql()
        }
    }

    // ------------------------------------------------------------------
    // LIKE wildcard escaping (Pass B1)
    // ------------------------------------------------------------------

    @Test
    fun methodContainsPercentLiteralUsesBackslashEscape() {
        val query = sqlMethod {
            usingStrings("id:50%")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE '%id:50\\%%' ESCAPE '\\' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodPrefixUnderscoreLiteralUsesBackslashEscape() {
        val query = sqlMethod {
            usingPrefixStrings("a_b")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE 'a\\_b%' ESCAPE '\\' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodSuffixBackslashLiteralIsEscaped() {
        val query = sqlMethod {
            usingSuffixStrings("x\\y")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE '%x\\\\y' ESCAPE '\\' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodPlainContainsKeepsOldBytesWithoutEscapeClause() {
        val query = sqlMethod {
            usingStrings("plain")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value LIKE '%plain%' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodEqualityLiteralsKeepWildcardsAndBracketsRaw() {
        val query = sqlMethod {
            usingEqStrings("loadData[0]", "a%b")
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor JOIN method_strings ms1 ON ms1.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'loadData[0]' AND ms1.file_id = m.file_id AND ms1.method_idx = m.method_idx AND ms1.value = 'a%b' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun classLevelLiteralUsesBackslashEscape() {
        val query = sqlClass {
            usingStrings("a_b")
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value LIKE '%a\\_b%' ESCAPE '\\' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun declaredClassConstraintLiteralUsesBackslashEscape() {
        val query = sqlMethod {
            declaredClass {
                usingPrefixStrings("a_b")
            }
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN methods ccm0 ON ccm0.class_descriptor = m.class_descriptor JOIN method_strings ms0 ON ms0.method = ccm0.descriptor WHERE ccm0.file_id = m.file_id AND ccm0.class_idx = m.class_idx AND ms0.file_id = ccm0.file_id AND ms0.method_idx = ccm0.method_idx AND ms0.value LIKE 'a\\_b%' ESCAPE '\\' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun superClassConstraintLiteralUsesBackslashEscape() {
        val query = sqlClass {
            superClass {
                usingStrings("100%")
            }
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c JOIN methods scm0 ON scm0.class_descriptor = c.superclass_descriptor JOIN method_strings ms0 ON ms0.method = scm0.descriptor WHERE ms0.file_id = scm0.file_id AND ms0.method_idx = scm0.method_idx AND ms0.value LIKE '%100\\%%' ESCAPE '\\' ORDER BY c.descriptor;",
            query,
        )
    }

    // ------------------------------------------------------------------
    // Access-flag modifiers (Pass B2)
    // ------------------------------------------------------------------

    @Test
    fun methodModifiersEmitDecimalMaskAfterPackageCondition() {
        val query = sqlMethod {
            usingEqStrings("x")
            searchPackages("com.tencent.mm")
            modifiers = 9
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'x' AND (m.class_descriptor LIKE 'Lcom/tencent/mm/%') AND ((m.access_flags & 9) = 9) ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun constructorModifiersUseFunctionForm() {
        val query = sqlConstructor {
            paramCount = 2
            modifiers(8)
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = '<init>' AND m.param_count = 2 AND ((m.access_flags & 8) = 8) ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun methodModifiersPrecedeDeclaredClassConstraint() {
        val query = sqlMethod {
            modifiers = 16
            declaredClass {
                usingEqStrings("m")
            }
        }
        assertSql(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN methods ccm0 ON ccm0.class_descriptor = m.class_descriptor JOIN method_strings ms0 ON ms0.method = ccm0.descriptor WHERE ((m.access_flags & 16) = 16) AND ccm0.file_id = m.file_id AND ccm0.class_idx = m.class_idx AND ms0.file_id = ccm0.file_id AND ms0.method_idx = ccm0.method_idx AND ms0.value = 'm' ORDER BY m.descriptor;",
            query,
        )
    }

    @Test
    fun classModifiersReuseSuperClassClassJoin() {
        val query = sqlClass {
            superClass = "android.app.Activity"
            usingEqStrings("x")
            modifiers = 1024
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN classes c ON c.descriptor = cm.class_descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'x' AND c.file_id = cm.file_id AND c.class_idx = cm.class_idx AND c.superclass_descriptor = 'Landroid/app/Activity;' AND ((c.access_flags & 1024) = 1024) ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun classModifiersAddClassJoinWhenSuperClassStringAbsent() {
        val query = sqlClass {
            usingEqStrings("x")
            modifiers = 6
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN classes c ON c.descriptor = cm.class_descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'x' AND c.file_id = cm.file_id AND c.class_idx = cm.class_idx AND ((c.access_flags & 6) = 6) ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun bareClassModifiersEmitFlagCondition() {
        val query = sqlClass {
            className = "com.foo.Bar"
            modifiers(1024)
        }
        assertSql(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.descriptor = 'Lcom/foo/Bar;' AND ((c.access_flags & 1024) = 1024) ORDER BY c.descriptor;",
            query,
        )
    }

    @Test
    fun classModifiersPrecedeSuperClassConstraintBlock() {
        val query = sqlClass {
            usingEqStrings("x")
            modifiers = 1
            superClass {
                usingEqStrings("sc")
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN classes c ON c.descriptor = cm.class_descriptor JOIN classes cc ON cc.descriptor = cm.class_descriptor JOIN methods scm0 ON scm0.class_descriptor = cc.superclass_descriptor JOIN method_strings ms1 ON ms1.method = scm0.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'x' AND c.file_id = cm.file_id AND c.class_idx = cm.class_idx AND ((c.access_flags & 1) = 1) AND cc.file_id = cm.file_id AND cc.class_idx = cm.class_idx AND ms1.file_id = scm0.file_id AND ms1.method_idx = scm0.method_idx AND ms1.value = 'sc' ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun nestedMethodUnitModifiersFollowUnitProperties() {
        val query = sqlClass {
            method {
                name = "first"
                modifiers = 8
            }
            method {
                name = "second"
                modifiers(4)
            }
        }
        assertSql(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN methods cm0 ON cm0.class_descriptor = cm.class_descriptor WHERE cm.name = 'first' AND ((cm.access_flags & 8) = 8) AND cm0.file_id = cm.file_id AND cm0.class_idx = cm.class_idx AND cm0.name = 'second' AND ((cm0.access_flags & 4) = 4) AND NOT (cm.file_id = cm0.file_id AND cm.method_idx = cm0.method_idx) ORDER BY cm.class_descriptor;",
            query,
        )
    }

    @Test
    fun fieldAccessMatcherModifiersFollowAccessProperties() {
        val query = sqlField {
            name = "field"
            readMethod {
                name = "getX"
                modifiers = 1
            }
        }
        assertSql(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f JOIN method_field_accesses fa0 ON fa0.field = f.descriptor JOIN methods fm0 ON fm0.descriptor = fa0.method WHERE f.name = 'field' AND fa0.file_id = f.file_id AND fa0.field_idx = f.field_idx AND fm0.file_id = fa0.file_id AND fm0.method_idx = fa0.method_idx AND fa0.using_type = 'get' AND fm0.name = 'getX' AND ((fm0.access_flags & 1) = 1) ORDER BY f.descriptor;",
            query,
        )
    }

    // ------------------------------------------------------------------
    // Lazy builders (Pass C2)
    // ------------------------------------------------------------------

    @Test
    fun builderBlockRunsOnEachToSqlCall() {
        var runs = 0
        val query = sqlField {
            runs += 1
            name = "field"
        }
        assertEquals(0, runs)
        assertEquals(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f WHERE f.name = 'field' ORDER BY f.descriptor;",
            query.toSql(),
        )
        assertEquals(1, runs)
        assertEquals(query.toSql(), query.toSql())
        assertEquals(3, runs)
    }

    @Test
    fun builderReflectsValuesReadAtToSqlTime() {
        var marker = "first"
        val query = sqlMethod {
            declaredClass = marker
        }
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.class_descriptor = 'Lfirst;' ORDER BY m.descriptor;",
            query.toSql(),
        )
        marker = "second"
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.class_descriptor = 'Lsecond;' ORDER BY m.descriptor;",
            query.toSql(),
        )
    }

    @Test
    fun lazyBuilderReadsDelegateStyleRuntimeValues() {
        var fakeDeclaredName = "com.foo.First"
        var fakeReturnTypeName = "void"
        var fakeParamTypeName = "int"
        val query = sqlMethod {
            declaredClass = fakeDeclaredName
            returnType = fakeReturnTypeName
            paramTypes(fakeParamTypeName, "java.lang.String")
        }
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.class_descriptor = 'Lcom/foo/First;' AND m.return_descriptor = 'V' AND m.param_descriptors = 'I,Ljava/lang/String;' ORDER BY m.descriptor;",
            query.toSql(),
        )
        fakeDeclaredName = "com.foo.Second"
        fakeReturnTypeName = "int"
        fakeParamTypeName = "long"
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.class_descriptor = 'Lcom/foo/Second;' AND m.return_descriptor = 'I' AND m.param_descriptors = 'J,Ljava/lang/String;' ORDER BY m.descriptor;",
            query.toSql(),
        )
    }

    @Test
    fun lazyFunctionSlotFormsReadRuntimeValues() {
        var classNameValue = "com.foo.Old"
        var superName = "android.app.Old"
        val query = sqlClass {
            className(classNameValue)
            superClass(superName)
            modifiers = 1
        }
        assertEquals(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.descriptor = 'Lcom/foo/Old;' AND c.superclass_descriptor = 'Landroid/app/Old;' AND ((c.access_flags & 1) = 1) ORDER BY c.descriptor;",
            query.toSql(),
        )
        classNameValue = "Lcom/foo/New;"
        superName = "android.app.New"
        assertEquals(
            "SELECT DISTINCT c.descriptor AS descriptor FROM classes c WHERE c.descriptor = 'Lcom/foo/New;' AND c.superclass_descriptor = 'Landroid/app/New;' AND ((c.access_flags & 1) = 1) ORDER BY c.descriptor;",
            query.toSql(),
        )
    }

    @Test
    fun staticQueryBytesAreStableAcrossToSqlCalls() {
        val query = sqlClass {
            usingEqStrings("a")
            method {
                name = "run"
            }
        }
        val expected = "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND ms0.value = 'a' AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND cm1.name = 'run' ORDER BY cm.class_descriptor;"
        assertEquals(expected, query.toSql())
        assertEquals(expected, query.toSql())
    }

    @Test
    fun dexSqlSpecBuildsSqlLazily() {
        var marker = "one"
        val query = sqlMethod {
            name = marker
        }
        val spec = DexSqlSpec({ query.toSql() })
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = 'one' ORDER BY m.descriptor;",
            spec.sql(),
        )
        marker = "two"
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.name = 'two' ORDER BY m.descriptor;",
            spec.sql(),
        )
    }

    // ------------------------------------------------------------------
    // Rejected declarations
    // ------------------------------------------------------------------


    @Test
    fun emptyQueriesThrow() {
        assertThrows(IllegalArgumentException::class.java) { sqlMethod {}.toSql() }
        assertThrows(IllegalArgumentException::class.java) { sqlClass {}.toSql() }
        assertThrows(IllegalArgumentException::class.java) { sqlField {}.toSql() }
    }

    @Test
    fun classQueryWithOnlyEmptyNestedMethodThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlClass {
                method {}
            }.toSql()
        }
    }

    @Test
    fun classQueryWithUnconstrainedNestedMethodThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlClass {
                usingEqStrings("a")
                method {}
            }.toSql()
        }
    }

    @Test
    fun methodQueryWithOnlyRangeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                limit(1)
            }.toSql()
        }
    }

    @Test
    fun constructorRejectsNonInitName() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlConstructor {
                name = "notAConstructor"
            }.toSql()
        }
    }

    @Test
    fun offsetWithoutLimitThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                name = "doThing"
                offset(1)
            }.toSql()
        }
    }

    @Test
    fun negativeRangeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                name = "doThing"
                limit(-1)
            }.toSql()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                name = "doThing"
                limit(1)
                offset(-2)
            }.toSql()
        }
    }

    @Test
    fun nestedMethodQueryOptionsThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlClass {
                className = "com.tencent.mm.Foo"
                method {
                    searchPackages("com.tencent.mm")
                }
            }.toSql()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sqlField {
                name = "field"
                readMethod {
                    limit(1)
                }
            }.toSql()
        }
    }

    @Test
    fun blankClassOrTypeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                declaredClass = "   "
            }.toSql()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                returnType = " "
            }.toSql()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sqlClass {
                className = ""
            }.toSql()
        }
    }

    @Test
    fun paramTypesWithoutArgumentsThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            sqlMethod {
                paramTypes()
            }.toSql()
        }
    }
}
