package dev.ujhhgtg.wekit.dexkit.meta

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DexDescriptorConversionsTest {

    @Test
    fun primitiveDescriptorsMapToJavaNames() {
        val expected = mapOf(
            "V" to "void",
            "Z" to "boolean",
            "B" to "byte",
            "S" to "short",
            "C" to "char",
            "I" to "int",
            "J" to "long",
            "F" to "float",
            "D" to "double",
        )
        expected.forEach { (descriptor, name) ->
            assertEquals(name, descriptorToJavaName(descriptor))
            assertEquals(descriptor, javaNameToDescriptor(name))
        }
    }

    @Test
    fun arrayAndObjectDescriptorsConvertBothWays() {
        assertEquals("int[]", descriptorToJavaName("[I"))
        assertEquals("int[][]", descriptorToJavaName("[[I"))
        assertEquals("[I", javaNameToDescriptor("int[]"))
        assertEquals("[[I", javaNameToDescriptor("int[][]"))
        assertEquals("java.lang.String[]", descriptorToJavaName("[Ljava/lang/String;"))
        assertEquals("java.lang.String[][]", descriptorToJavaName("[[Ljava/lang/String;"))
        assertEquals("[[Ljava/lang/String;", javaNameToDescriptor("java.lang.String[][]"))
        assertEquals("com.x.Outer\$Inner", descriptorToJavaName("Lcom/x/Outer\$Inner;"))
        assertEquals("Lcom/x/Outer\$Inner;", javaNameToDescriptor("com.x.Outer\$Inner"))
        assertEquals("Lcom/x/Y;", javaNameToDescriptor("Lcom/x/Y;"))
        assertEquals("Lcom/x/Y;", dottedClassNameToDescriptor("com.x.Y"))
        assertEquals("Lcom/x/Y;", dottedClassNameToDescriptor("Lcom/x/Y;"))
    }

    @Test
    fun methodDescriptorSplitsIntoCanonicalParts() {
        val parts = parseMethodDescriptor(SnapshotFixture.SAMPLE_METHOD)
        assertEquals("Lcom/tencent/mm/ui/chatting/x0;", parts.declaringClassDescriptor)
        assertEquals("G", parts.name)
        assertEquals("(Z)V", parts.methodSign)
        assertEquals("V", parts.returnTypeDescriptor)
        assertEquals(listOf("Z"), parts.paramDescriptors)
        assertEquals("(Z)V", methodDescriptorSign(SnapshotFixture.SAMPLE_METHOD))
    }

    @Test
    fun methodSignHelpersHandlePrimitiveObjectAndArrayTypes() {
        val sign = "(ILjava/lang/String;[[B)Landroid/view/View;"
        assertEquals(listOf("I", "Ljava/lang/String;", "[[B"), methodSignParamDescriptors(sign))
        assertEquals("Landroid/view/View;", methodSignReturnDescriptor(sign))
        assertEquals(
            "Lcom/x/Outer;->run(ILjava/lang/String;[[B)Landroid/view/View;",
            buildMethodDescriptor("Lcom/x/Outer;", "run", sign),
        )
        assertEquals("V", methodSignReturnDescriptor("()V"))
        assertEquals(emptyList<String>(), methodSignParamDescriptors("()V"))
    }

    @Test
    fun invalidDescriptorsFailLoudly() {
        assertThrows(IllegalArgumentException::class.java) { descriptorToJavaName("") }
        assertThrows(IllegalArgumentException::class.java) { descriptorToJavaName("X") }
        assertThrows(IllegalArgumentException::class.java) { parseMethodDescriptor("Lcom/x/Y;") }
        assertThrows(IllegalArgumentException::class.java) { parseMethodDescriptor("Lcom/x/Y;run()V") }
        assertThrows(IllegalArgumentException::class.java) { methodSignReturnDescriptor("(I") }
        assertThrows(IllegalArgumentException::class.java) { buildMethodDescriptor("com/x/Y", "run", "()V") }
    }

    @Test
    fun snapshotEntriesAgreeWithTheSharedConversions() {
        // Class descriptor <-> name.
        val classJson = SnapshotFixture.entry(DexMetadataKind.Class, SnapshotFixture.SAMPLE_CLASS)
        assertEquals(
            classJson.getValue("name").jsonPrimitive.content,
            descriptorToJavaName(classJson.getValue("descriptor").jsonPrimitive.content),
        )
        assertEquals(
            classJson.getValue("descriptor").jsonPrimitive.content,
            dottedClassNameToDescriptor(classJson.getValue("name").jsonPrimitive.content),
        )

        // Method descriptor <-> split parts and Java type names from the snapshot.
        val methodJson = SnapshotFixture.entry(DexMetadataKind.Method, SnapshotFixture.SAMPLE_METHOD)
        val parts = parseMethodDescriptor(methodJson.getValue("descriptor").jsonPrimitive.content)
        assertEquals(methodJson.getValue("name").jsonPrimitive.content, parts.name)
        assertEquals(methodJson.getValue("method_sign").jsonPrimitive.content, parts.methodSign)
        assertEquals(
            methodJson.getValue("return_type_name").jsonPrimitive.content,
            descriptorToJavaName(parts.returnTypeDescriptor),
        )
        val paramNames = methodJson.getValue("param_type_names").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(paramNames, parts.paramDescriptors.map(::descriptorToJavaName))
        assertEquals(
            methodJson.getValue("descriptor").jsonPrimitive.content,
            buildMethodDescriptor(parts.declaringClassDescriptor, parts.name, parts.methodSign),
        )
        assertEquals(
            methodJson.getValue("declared_class_name").jsonPrimitive.content,
            descriptorToJavaName(parts.declaringClassDescriptor),
        )

        // Field type name <-> descriptor.
        val fieldJson = SnapshotFixture.entry(DexMetadataKind.Field, SnapshotFixture.SAMPLE_FIELD)
        assertEquals(
            fieldJson.getValue("type_descriptor").jsonPrimitive.content,
            javaNameToDescriptor(fieldJson.getValue("type_name").jsonPrimitive.content),
        )
        assertEquals(
            fieldJson.getValue("type_name").jsonPrimitive.content,
            descriptorToJavaName(fieldJson.getValue("type_descriptor").jsonPrimitive.content),
        )
    }
}
