import kotlin.test.Test
import kotlin.test.assertEquals

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
