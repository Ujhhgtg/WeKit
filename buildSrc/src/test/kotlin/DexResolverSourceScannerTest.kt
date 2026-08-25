import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DexResolverSourceScannerTest {
    @Test
    fun extractsCustomAndAllInlineDelegateKinds() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                package sample
                object Sample : IResolveDex {
                    private val field by dexField { matcher { name = "f" } }
                    private val method by dexMethod(allowFailure = true) {
                        matcher { declaredClass(classOwner.clazz) }
                    }
                    override fun resolveDex(dexKit: DexKitBridge) {
                        method.find(dexKit) { matcher { name = "m" } }
                    }
                }
            """.trimIndent(),
        )!!

        assertEquals(
            listOf(ResolveBlockKind.INLINE_FIELD, ResolveBlockKind.INLINE_METHOD, ResolveBlockKind.CUSTOM),
            source.blocks.map { it.kind },
        )
    }

    @Test
    fun flagsHostReflectionOnlyInsideResolutionBlocks() {
        val source = scanDexResolverSource("Sample.kt", sampleSource)!!

        assertEquals(
            listOf("classOwner.clazz", "HostInfo.versionCode"),
            findDesktopIncompatibleAccesses(source).map { it.expression },
        )
    }

    @Test
    fun retainsOriginalLineNumbersAfterCommentsAreStripped() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                /*
                 * A multiline comment removed by the scanner.
                 */

                object Sample : IResolveDex {
                    private val method by dexMethod { matcher { name = "m" } }
                }
            """.trimIndent(),
        )!!

        assertEquals(6, source.blocks.single().startLine)
    }

    @Test
    fun extractsBlocksOnlyFromTheIResolveDexDeclaration() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Helper {
                    private val ignored by dexMethod {
                        matcher { declaredClass(classHelper.clazz) }
                    }

                    override fun resolveDex(dexKit: DexKitBridge) {
                        error("not a resolver")
                    }
                }

                object Sample : IResolveDex {
                    private val included by dexClass { matcher { name = "sample" } }
                }
            """.trimIndent(),
        )!!

        assertEquals(listOf(ResolveBlockKind.INLINE_CLASS), source.blocks.map { it.kind })
        assertEquals(emptyList(), findDesktopIncompatibleAccesses(source))
    }

    @Test
    fun extractsClassAndConstructorInlineDelegateKinds() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    private val clazz by dexClass { matcher { name = "C" } }
                    private val ctor by dexConstructor { matcher { declaredClass(clazz.clazz) } }
                }
            """.trimIndent(),
        )!!

        assertEquals(
            listOf(ResolveBlockKind.INLINE_CLASS, ResolveBlockKind.INLINE_CONSTRUCTOR),
            source.blocks.map { it.kind },
        )
    }

    @Test
    fun ignoresForbiddenAccessTextInsideStrings() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    private val method by dexMethod {
                        matcher { usingStrings("classOwner.clazz HostInfo.versionCode") }
                    }
                }
            """.trimIndent(),
        )!!

        assertEquals(emptyList(), findDesktopIncompatibleAccesses(source))
    }

    @Test
    fun reportsViolationLineAfterMultilineComment() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    private val method by dexMethod {
                        /*
                         * This comment must not shift diagnostics.
                         */
                        matcher { declaredClass(classOwner.clazz) }
                    }
                }
            """.trimIndent(),
        )!!

        assertEquals(6, findDesktopIncompatibleAccesses(source).single().line)
        val block = source.blocks.single()
        assertEquals(6, source.sourceLinesByBlock[block]!![block.text.indexOf("classOwner.clazz")])
    }

    @Test
    fun extractsStableInlineProducerMetadataIncludingFactoryArguments() {
        val source = scanDexResolverSource(
            "src/main/java/dev/example/SomeFeature.kt",
            """
                package dev.example
                object SomeFeature : BaseFeature(), IResolveDex {
                    internal val methodTarget by dexMethod(allowFailure = true, resultIndex = 1) {
                        matcher { usingEqStrings("anchor") }
                    }
                }
            """.trimIndent(),
        )!!

        val producer = source.producers.single()
        assertEquals("dev.example.SomeFeature#methodTarget", producer.stableId)
        assertEquals("methodTarget", producer.propertyName)
        assertEquals(ResolveBlockKind.INLINE_METHOD, producer.kind)
        assertTrue(producer.fingerprintSource.contains("allowFailure = true"))
        assertTrue(producer.fingerprintSource.contains("resultIndex = 1"))
        assertTrue(producer.fingerprintSource.contains("usingEqStrings(\"anchor\")"))
    }

    @Test
    fun fullyQualifiedOwnerNamesKeepSameSimpleNameProducerIdsDistinct() {
        fun scan(packageName: String) = scanDexResolverSource(
            "src/main/java/${packageName.replace('.', '/')}/SameName.kt",
            """
                package $packageName
                object SameName : IResolveDex {
                    val target by dexClass { matcher { name = "Target" } }
                }
            """.trimIndent(),
        )!!.producers.single().stableId

        assertEquals("dev.example.first.SameName#target", scan("dev.example.first"))
        assertEquals("dev.example.second.SameName#target", scan("dev.example.second"))
        assertNotEquals(scan("dev.example.first"), scan("dev.example.second"))
    }

    @Test
    fun mapsMultipleNonInlineOutputsToOneCustomResolveDexProducer() {
        val source = scanDexResolverSource(
            "Custom.kt",
            """
                package sample
                object Custom : IResolveDex {
                    val targetClass by dexClass()
                    val targetMethod by dexMethod()

                    override fun resolveDex(dexKit: DexKitBridge) {
                        targetClass.find(dexKit) { matcher { name = "Target" } }
                        targetMethod.find(dexKit) { matcher { name = "run" } }
                    }
                }
            """.trimIndent(),
        )!!

        val producer = source.producers.single()
        assertEquals("sample.Custom#resolveDex", producer.stableId)
        assertEquals(null, producer.propertyName)
        assertEquals(ResolveBlockKind.CUSTOM, producer.kind)
        assertEquals(setOf("targetClass", "targetMethod"), source.customOutputPropertyNames)
        assertTrue(producer.fingerprintSource.contains("targetClass.find"))
        assertTrue(producer.fingerprintSource.contains("targetMethod.find"))
    }

    @Test
    fun factoryArgumentChangesProduceDistinctFingerprintInputs() {
        fun producerSource(allowFailure: Boolean) = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    val target by dexMethod(allowFailure = $allowFailure, resultIndex = 1) {
                        matcher { name = "run" }
                    }
                }
            """.trimIndent(),
        )!!.producers.single().fingerprintSource

        assertNotEquals(producerSource(false), producerSource(true))
    }

    @Test
    fun discoversEveryResolverAndUsesNestedQualifiedOwnerNames() {
        val sources = scanDexResolverSources(
            "Nested.kt",
            """
                package sample

                object First : IResolveDex {
                    val firstTarget by dexClass { matcher { name = "First" } }
                }

                object Outer {
                    object Nested : IResolveDex {
                        val nestedTarget by dexClass { matcher { name = "Nested" } }
                    }
                }
            """.trimIndent(),
        )

        assertEquals(listOf("sample.First", "sample.Outer.Nested"), sources.map { it.qualifiedClassName })
        assertEquals(
            "sample.Outer.Nested#nestedTarget",
            sources.single { it.qualifiedClassName.endsWith(".Nested") }.producers.single().stableId,
        )
    }

    @Test
    fun rejectsMetadataThatDoesNotCoverEveryDiscoveredResolverOwner() {
        val source = scanDexResolverSource(
            "One.kt",
            "object One : IResolveDex { val target by dexClass { matcher { name = \"One\" } } }",
        )!!

        val error = assertFailsWith<IllegalArgumentException> {
            requireCompleteDexResolverMetadata(
                discoveredOwnerClassNames = setOf("One", "Missing"),
                metadataOwners = listOf(source),
            )
        }

        assertTrue(error.message!!.contains("Missing"))
    }

    @Test
    fun unrelatedMemberDoesNotAffectProvenProducerSource() {
        fun producerSource(unrelatedAnchor: String) = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    val target by dexMethod {
                        matcher { helper() }
                    }

                    private fun helper() {
                        usingEqStrings("target anchor")
                    }

                    private fun unrelated() {
                        usingEqStrings("$unrelatedAnchor")
                    }
                }
            """.trimIndent(),
        )!!.producers.single().fingerprintSource

        assertEquals(producerSource("first"), producerSource("second"))
    }

    private companion object {
        val sampleSource =
            """
                package sample
                object Sample : IResolveDex {
                    private val method by dexMethod {
                        matcher { declaredClass(classOwner.clazz) }
                    }

                    override fun resolveDex(dexKit: DexKitBridge) {
                        if (HostInfo.versionCode > 1) {
                            method.find(dexKit) { matcher { name = "m" } }
                        }
                    }

                    fun install() {
                        classOwner.clazz.getDeclaredMethod("outsideResolution")
                    }
                }
            """.trimIndent()
    }
}
