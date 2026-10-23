package dev.ujhhgtg.wekit.dexkit.meta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DexMetadataTest {

    @Test
    fun classInfoParsesTopLevelMetadataAndInlineChildren() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)
        val info = metadata.classInfo(SnapshotFixture.SAMPLE_CLASS)!!

        assertEquals("Lcom/tencent/mm/pluginsdk/model/app/c0;", info.descriptor)
        assertEquals("com.tencent.mm.pluginsdk.model.app.c0", info.name)
        assertEquals("c0", info.simpleName)
        assertEquals("SourceFile", info.sourceFile)
        assertEquals(1, info.modifiers)
        assertEquals(1, info.accessFlags)
        assertFalse(info.isArray)

        val superClass = info.superClass!!
        assertEquals("Ljava/lang/Object;", superClass.descriptor)
        assertEquals("java.lang.Object", superClass.name)
        assertNull(superClass.sourceFile) // inline reference, no full class JSON

        val interfaceInfo = info.interfaces.single()
        assertEquals("Ljava/lang/Runnable;", interfaceInfo.descriptor)
        assertEquals("java.lang.Runnable", interfaceInfo.name)

        assertEquals(2, info.methods.size)
        assertEquals(1, info.fields.size)
        assertEquals(1, info.interfaceCount)
        assertEquals(2, info.methodCount)
        assertEquals(1, info.fieldCount)

        // Inline members must not trigger provider queries; external super/interface misses are cached.
        assertEquals(1, provider.count(DexMetadataKind.Class, SnapshotFixture.SAMPLE_CLASS))
        assertEquals(1, provider.count(DexMetadataKind.Class, "Ljava/lang/Object;"))
        assertEquals(1, provider.count(DexMetadataKind.Class, "Ljava/lang/Runnable;"))
        assertEquals(3, provider.totalQueries)
    }

    @Test
    fun classMembersExposeDexKitCompatibleProperties() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)
        val info = metadata.classInfo(SnapshotFixture.SAMPLE_CLASS)!!

        val ctor = info.methods[0]
        assertEquals("<init>", ctor.name)
        assertEquals(ctor.name, ctor.methodName)
        assertEquals("(Lcom/tencent/mm/storage/e9;)V", ctor.methodSign)
        assertEquals("com.tencent.mm.pluginsdk.model.app.c0", ctor.declaredClassName)
        assertEquals(ctor.declaredClassName, ctor.className)
        assertEquals(65537, ctor.accessFlags)
        assertEquals(1, ctor.modifiers) // engine strips ACC_CONSTRUCTOR from modifiers
        assertEquals("void", ctor.returnTypeName)
        assertEquals(listOf("com.tencent.mm.storage.e9"), ctor.paramTypeNames)
        assertEquals(1, ctor.paramCount)
        assertTrue(ctor.isConstructor)
        assertFalse(ctor.isStaticInitializer)
        assertFalse(ctor.isMethod)

        val run = info.methods[1]
        assertEquals("run", run.name)
        assertEquals("()V", run.methodSign)
        assertTrue(run.isMethod)

        val field = info.fields.single()
        assertEquals("d", field.name)
        assertEquals(field.name, field.fieldName)
        assertEquals("com.tencent.mm.pluginsdk.model.app.c0", field.declaredClassName)
        assertEquals("com.tencent.mm.storage.e9", field.typeName)
        assertEquals("Lcom/tencent/mm/storage/e9;", field.typeDescriptor)
        assertEquals(field.typeDescriptor, field.typeSign)
        assertEquals(4113, field.modifiers)

        // declaredClass of inline members resolves through the session cache (already parsed class).
        assertSame(info, run.declaredClass)
        assertSame(info, field.declaredClass)
        assertEquals(1, provider.totalQueries)
    }

    @Test
    fun methodInfoParsesInvokesUsingFieldsAndStrings() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)
        val method = metadata.methodInfo(SnapshotFixture.SAMPLE_METHOD)!!

        assertEquals("Lcom/tencent/mm/ui/chatting/x0;->G(Z)V", method.descriptor)
        assertEquals("G", method.name)
        assertEquals("G", method.methodName)
        assertEquals("Lcom/tencent/mm/ui/chatting/x0;->G(Z)V", method.descriptor)
        assertEquals("(Z)V", method.methodSign)
        assertEquals("com.tencent.mm.ui.chatting.x0", method.declaredClassName)
        assertEquals("boolean", method.paramTypeNames.single())
        assertEquals("void", method.returnTypeName)
        assertEquals(1, method.paramCount)
        assertTrue(method.isMethod)
        assertFalse(method.isConstructor)

        assertEquals(2, method.invokes.size)
        val valueOf = method.invokes[0]
        assertEquals("java.lang.Boolean", valueOf.declaredClassName)
        assertEquals("valueOf", valueOf.methodName)
        assertEquals("java.lang.Boolean", valueOf.returnTypeName)
        assertEquals(listOf("boolean"), valueOf.paramTypeNames)
        assertFalse(valueOf.isConstructor)
        val log = method.invokes[1]
        assertEquals("java.lang.Object[]", log.paramTypeNames.last())
        assertEquals(
            listOf("Ljava/lang/String;", "Ljava/lang/String;", "[Ljava/lang/Object;"),
            methodSignParamDescriptors(log.methodSign),
        )

        val using = method.usingFields.single()
        assertEquals(FieldUsingType.Write, using.usingType)
        assertTrue(using.usingType.isWrite)
        assertEquals("w", using.field.name)
        assertEquals("boolean", using.field.typeName)
        assertEquals("Lcom/tencent/mm/ui/chatting/x0;->w:Z", using.field.descriptor)
        assertEquals(emptyList<DexFieldInfo>(), method.readFields())
        assertEquals(listOf(using.field), method.writeFields())
        assertEquals(listOf("MicroMsg.AutoPlay", "speakerOn has been set %s"), method.usingStrings)

        // Everything above is inline in the method JSON.
        assertEquals(1, provider.totalQueries)

        // External declared class has no metadata in the fixture.
        assertNull(valueOf.declaredClass)
        assertEquals(1, provider.count(DexMetadataKind.Class, "Ljava/lang/Boolean;"))
    }

    @Test
    fun readFieldsFiltersReadAccesses() {
        val metadata = DexMetadata(CountingMetadataProvider())
        val method = metadata.methodInfo(SnapshotFixture.SAMPLE_METHOD_READ_WRITE)!!

        assertEquals(5, method.usingFields.size)
        assertEquals(1, method.readFields().size)
        assertEquals("g", method.readFields().single().name)
        assertEquals("fm.f10", method.readFields().single().typeName)
        assertEquals(4, method.writeFields().size)
        assertEquals(FieldUsingType.Read, method.usingFields[0].usingType)
        assertTrue(method.usingFields[0].usingType.isRead)
        assertFalse(method.usingFields[0].usingType.isWrite)
        assertEquals(listOf(""), method.usingStrings)
        assertTrue(method.invokes.isEmpty())
    }

    @Test
    fun fieldInfoParsesTypeAndDeclaredClass() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)

        val intField = metadata.fieldInfo(SnapshotFixture.SAMPLE_FIELD)!!
        assertEquals("c", intField.name)
        assertEquals("c", intField.fieldName)
        assertEquals("wp.b", intField.declaredClassName)
        assertEquals("wp.b", intField.className)
        assertEquals("int", intField.typeName)
        assertEquals("I", intField.typeDescriptor)
        assertEquals("I", intField.typeSign)
        assertEquals(9, intField.modifiers)
        assertEquals(9, intField.accessFlags)
        assertNull(intField.declaredClass) // fixture has no class|wp.b metadata
        assertEquals(1, provider.count(DexMetadataKind.Class, "Lwp/b;"))

        val objectField = metadata.fieldInfo(SnapshotFixture.SAMPLE_FIELD_OBJECT)!!
        assertEquals("android.view.View", objectField.typeName)
        assertEquals("Landroid/view/View;", objectField.typeDescriptor)
        assertEquals("com.tencent.mm.ui.conversation.ConversationListView", objectField.className)
        assertNull(objectField.declaredClass)
    }

    @Test
    fun missingDescriptorsResolveToNullAndAreCached() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)

        assertNull(metadata.classInfo("Lno/Such;"))
        assertNull(metadata.classInfo("Lno/Such;"))
        assertEquals(1, provider.count(DexMetadataKind.Class, "Lno/Such;"))

        assertNull(metadata.methodInfo("Lno/Such;->x()V"))
        assertNull(metadata.fieldInfo("Lno/Such;->x:I"))
        assertNull(metadata.classInfoByName("no.Such"))
    }

    @Test
    fun classInfoByNameAcceptsDottedAndDescriptorForms() {
        val metadata = DexMetadata(CountingMetadataProvider())
        val byName = metadata.classInfoByName("com.tencent.mm.pluginsdk.model.app.c0")!!
        assertSame(byName, metadata.classInfo(SnapshotFixture.SAMPLE_CLASS))
        assertSame(byName, metadata.classInfoByName(SnapshotFixture.SAMPLE_CLASS))
    }

    @Test
    fun definedSuperClassResolvesFullMetadataAndExternalFallsBackToShallow() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)

        // External superclass (java.lang.Object) -> shallow descriptor/name fallback.
        val externalSuper = metadata.classInfo(SnapshotFixture.SAMPLE_CLASS)!!.superClass!!
        assertEquals("Ljava/lang/Object;", externalSuper.descriptor)
        assertEquals("java.lang.Object", externalSuper.name)
        assertNull(externalSuper.sourceFile)
        assertTrue(externalSuper.methods.isEmpty())
        assertEquals(1, provider.count(DexMetadataKind.Class, "Ljava/lang/Object;"))

        // Defined superclass -> full metadata through the session provider.
        val definedSuper = metadata.classInfo(SnapshotFixture.SAMPLE_CLASS_WITH_SUPER)!!.superClass!!
        assertEquals(SnapshotFixture.SAMPLE_SUPER_CLASS, definedSuper.descriptor)
        assertEquals("com.tencent.mm.plugin.luckymoney.model.h6", definedSuper.name)
        assertEquals("h6", definedSuper.simpleName)
        assertNotNull(definedSuper.sourceFile)
        assertEquals(7, definedSuper.methods.size)
        assertEquals(16, definedSuper.fields.size)
        assertSame(definedSuper, metadata.classInfo(SnapshotFixture.SAMPLE_SUPER_CLASS))
        assertEquals(1, provider.count(DexMetadataKind.Class, SnapshotFixture.SAMPLE_SUPER_CLASS))
    }

    @Test
    fun definedInterfaceMetadataResolvesThroughProvider() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)

        val interfaceInfo = metadata.classInfo(SnapshotFixture.SAMPLE_CLASS_NESTED_INTERFACE)!!
            .interfaces.single { it.descriptor == SnapshotFixture.SAMPLE_INTERFACE }
        assertEquals("com.tencent.mm.network.l0", interfaceInfo.name)
        assertNotNull(interfaceInfo.sourceFile)
        assertEquals(1, interfaceInfo.methods.size)
        assertTrue(interfaceInfo.interfaces.isEmpty())
        assertSame(interfaceInfo, metadata.classInfo(SnapshotFixture.SAMPLE_INTERFACE))
        assertEquals(1, provider.count(DexMetadataKind.Class, SnapshotFixture.SAMPLE_INTERFACE))

        // External interface (c0 implements java.lang.Runnable) keeps the shallow fallback.
        val externalInterface = metadata.classInfo(SnapshotFixture.SAMPLE_CLASS)!!.interfaces.single()
        assertEquals("java.lang.Runnable", externalInterface.name)
        assertNull(externalInterface.sourceFile)
    }

    @Test
    fun objectReturnTypeResolvesClassMetadata() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)
        val method = metadata.methodInfo(SnapshotFixture.SAMPLE_METHOD_OBJECT_RETURN)!!
        assertEquals("com.tencent.mm.modelbase.f", method.returnTypeName)
        val returnType = method.returnType!!
        assertEquals(SnapshotFixture.SAMPLE_CLASS_OBJECT_RETURN, returnType.descriptor)
        assertSame(returnType, metadata.classInfo(SnapshotFixture.SAMPLE_CLASS_OBJECT_RETURN))
        assertSame(returnType, method.declaredClass)
        assertEquals(1, provider.count(DexMetadataKind.Class, SnapshotFixture.SAMPLE_CLASS_OBJECT_RETURN))
    }

    @Test
    fun primitiveAndArrayReturnTypesAreNullWithoutProviderLookup() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)

        assertNull(metadata.methodInfo(SnapshotFixture.SAMPLE_METHOD)!!.returnType) // void
        assertNull(metadata.methodInfo(SnapshotFixture.SAMPLE_METHOD_READ_WRITE)!!.returnType) // boolean
        val queriesBeforeArray = provider.totalQueries
        assertEquals(2, queriesBeforeArray)

        val arrayMethod = DexMethodInfo(
            Json.parseToJsonElement(
                """{"descriptor":"Lcom/x/Y;->bytes()[B","name":"bytes","declared_class_name":"com.x.Y","method_sign":"()[B","return_type_name":"byte[]","param_type_names":[],"param_count":0}""",
            ).jsonObject,
            metadata,
        )
        assertNull(arrayMethod.returnType)
        assertEquals(queriesBeforeArray, provider.totalQueries)
    }

    @Test
    fun sessionCacheIsThreadSafeAndParsesOnce() {
        val provider = CountingMetadataProvider()
        val metadata = DexMetadata(provider)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map { index ->
                pool.submit<Any> {
                    if (index % 2 == 0) {
                        metadata.classInfo(SnapshotFixture.SAMPLE_CLASS)!!
                    } else {
                        metadata.methodInfo(SnapshotFixture.SAMPLE_METHOD)!!
                    }
                }
            }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.filterIsInstance<DexClassInfo>().distinct().size)
            assertEquals(1, results.filterIsInstance<DexMethodInfo>().distinct().size)
            assertEquals(1, provider.count(DexMetadataKind.Class, SnapshotFixture.SAMPLE_CLASS))
            assertEquals(1, provider.count(DexMetadataKind.Method, SnapshotFixture.SAMPLE_METHOD))
        } finally {
            pool.shutdownNow()
        }
    }
}
