package dev.ujhhgtg.wekit.dex.sql

import dev.ujhhgtg.wekit.dex.matcher.AnnotationSpec
import dev.ujhhgtg.wekit.dex.matcher.classSpec
import dev.ujhhgtg.wekit.dex.matcher.fieldSpec
import dev.ujhhgtg.wekit.dex.matcher.methodSpec
import dev.ujhhgtg.wekit.dex.matcher.usingField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Spec -> canonical DexSQL parity. Fixtures are the frozen migration-extractor SQL; the spec is the
 * only authoring form. SQL-inexpressible specs must fall back to the matcher, not throw.
 */
class SpecSqlParityTest {
    @Test
    fun methodSpecEmitsCanonicalMethodSql() {
        val spec = methodSpec {
            usingEqStrings("exact")
            name("doThing")
            declaredClass("com.tencent.mm.Foo")
            returnType("void")
            paramCount(2)
            paramTypes("java.lang.String", "int")
        }
        assertEquals(
            "SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor WHERE ms0.file_id = m.file_id AND ms0.method_idx = m.method_idx AND ms0.value = 'exact' AND m.name = 'doThing' AND m.class_descriptor = 'Lcom/tencent/mm/Foo;' AND m.return_descriptor = 'V' AND m.param_count = 2 AND m.param_descriptors = 'Ljava/lang/String;,I' ORDER BY m.descriptor;",
            spec.toSqlOrNull(),
        )
    }

    @Test
    fun classSpecEmitsCanonicalClassSql() {
        val spec = classSpec {
            usingStrings("<appmsg appid=\"", "parse amessage xml failed")
        }
        assertEquals(
            "SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN methods cm1 ON cm1.class_descriptor = cm.class_descriptor JOIN method_strings ms2 ON ms2.method = cm1.descriptor WHERE ms0.file_id = cm.file_id AND ms0.method_idx = cm.method_idx AND instr(ms0.value, '<appmsg appid=\"') > 0 AND cm1.file_id = cm.file_id AND cm1.class_idx = cm.class_idx AND ms2.file_id = cm1.file_id AND ms2.method_idx = cm1.method_idx AND instr(ms2.value, 'parse amessage xml failed') > 0 ORDER BY cm.class_descriptor;",
            spec.toSqlOrNull(),
        )
    }

    @Test
    fun fieldSpecEmitsCanonicalFieldSql() {
        val spec = fieldSpec {
            name("field")
            declaredClass("com.tencent.mm.Foo")
            type("int")
        }
        assertEquals(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f WHERE f.name = 'field' AND f.class_descriptor = 'Lcom/tencent/mm/Foo;' AND f.type_descriptor = 'I' ORDER BY f.descriptor;",
            spec.toSqlOrNull(),
        )
    }

    @Test
    fun fieldSpecWithNestedAccessorEmitsCanonicalSql() {
        val spec = fieldSpec {
            declaredClass("com.tencent.mm.ui.conversation.ConversationListView")
            type("android.view.View")
            readMethod {
                usingEqStrings("resetActionBarView")
                returnType("void")
                paramCount(0)
            }
        }
        assertEquals(
            "SELECT DISTINCT f.descriptor AS descriptor FROM fields f JOIN method_field_accesses fa0 ON fa0.field = f.descriptor JOIN methods fm0 ON fm0.descriptor = fa0.method JOIN method_strings ms1 ON ms1.method = fm0.descriptor WHERE f.class_descriptor = 'Lcom/tencent/mm/ui/conversation/ConversationListView;' AND f.type_descriptor = 'Landroid/view/View;' AND fa0.file_id = f.file_id AND fa0.field_idx = f.field_idx AND fm0.file_id = fa0.file_id AND fm0.method_idx = fa0.method_idx AND fa0.using_type = 'get' AND ms1.file_id = fm0.file_id AND ms1.method_idx = fm0.method_idx AND ms1.value = 'resetActionBarView' AND fm0.return_descriptor = 'V' AND fm0.param_count = 0 ORDER BY f.descriptor;",
            spec.toSqlOrNull(),
        )
    }

    @Test
    fun structuralSpecIsNotSqlExpressible() {
        val spec = methodSpec {
            name("foo")
            usingField(usingField(fieldSpec { name("f") }))
            annotations = listOf(AnnotationSpec())
        }
        assertFalse(spec.isSqlExpressible())
        assertNull(spec.toSqlOrNull())
    }

    @Test
    fun classMemberConstraintFallsBackToMatcher() {
        val spec = classSpec {
            field { readMethod { name("accessor") } }
            fieldCount = 2..2
        }
        assertFalse(spec.isSqlExpressible())
    }
}
