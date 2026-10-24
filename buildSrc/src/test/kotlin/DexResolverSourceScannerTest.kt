import kotlin.test.Test
import kotlin.test.assertEquals

class DexResolverSourceScannerTest {
    @Test
    fun extractsCustomAndSqlDelegateExpressions() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                package sample
                object Sample : IResolveDex {
                    private val method by dexMethod(
                        "SELECT descriptor FROM methods ORDER BY descriptor LIMIT 1",
                        allowFailure = true,
                    )
                    private val field by dexField(sqlField { type = "int" })
                    override fun resolveDex() {
                        method.find(methodSpec { name("m") })
                    }
                }
            """.trimIndent(),
        )!!

        assertEquals(
            listOf(ResolveBlockKind.INLINE_SQL, ResolveBlockKind.INLINE_SQL, ResolveBlockKind.CUSTOM),
            source.blocks.map { it.kind },
        )
    }

    @Test
    fun includesSqlDslDelegateExpressionsInResolverHashInput() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    private val method by dexMethod(
                        sqlMethod {
                            name = "onCreate"
                            returnType = "void"
                        },
                        allowFailure = true,
                    )
                    private val clazz by dexClass(sqlClass { className = "com.example.Host" })
                    private val field by dexField(sqlField { type = "int" })
                    private val ctor by dexConstructor(sqlConstructor { paramCount = 0 })
                }
            """.trimIndent(),
        )!!

        assertEquals(List(4) { ResolveBlockKind.INLINE_SQL }, source.blocks.map { it.kind })
        val methodBlock = source.blocks.first()
        assertEquals(3, methodBlock.startLine)
        assertEquals(true, methodBlock.text.contains("sqlMethod"))
        assertEquals(true, methodBlock.text.contains("allowFailure = true"))
    }

    @Test
    fun extractsQueryMatcherDelegateKinds() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    private val clazz by dexClassQuery { uniqueClass(classSpec { className = "C" }) }
                    private val method by dexMethodQuery { uniqueMethod(methodSpec { name("m") }) }
                    private val field by dexFieldQuery { uniqueField(fieldSpec { name("f") }) }
                    private val ctor by dexConstructorQuery { uniqueMethod(methodSpec { paramCount = 0 }) }
                }
            """.trimIndent(),
        )!!

        assertEquals(
            listOf(
                ResolveBlockKind.INLINE_QUERY_CLASS,
                ResolveBlockKind.INLINE_QUERY_METHOD,
                ResolveBlockKind.INLINE_QUERY_FIELD,
                ResolveBlockKind.INLINE_QUERY_CONSTRUCTOR,
            ),
            source.blocks.map { it.kind },
        )
    }

    @Test
    fun flagsHostReflectionOnlyInsideResolutionBlocks() {
        val source = scanDexResolverSource("Sample.kt", sampleSource)!!

        assertEquals(
            setOf("classOwner.clazz", "HostInfo.versionCode"),
            findDesktopIncompatibleAccesses(source).map { it.expression }.toSet(),
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
                    private val method by dexMethod(sqlMethod { name = "m" })
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
                    private val ignored by dexMethod(sqlMethod { name = "ignored" })
                }

                object Sample : IResolveDex {
                    private val included by dexClass(sqlClass { className = "sample" })
                }
            """.trimIndent(),
        )!!

        assertEquals(listOf(ResolveBlockKind.INLINE_SQL), source.blocks.map { it.kind })
        assertEquals(emptyList(), findDesktopIncompatibleAccesses(source))
    }

    @Test
    fun ignoresForbiddenAccessTextInsideStrings() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    private val method by dexMethod(
                        sqlMethod { usingStrings("classOwner.clazz HostInfo.versionCode") },
                    )
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
                    private val method by dexMethod(
                        sqlMethod {
                            /*
                             * This comment must not shift diagnostics.
                             */
                            className = classOwner.clazz
                        },
                    )
                }
            """.trimIndent(),
        )!!

        assertEquals(7, findDesktopIncompatibleAccesses(source).single().line)
        val block = source.blocks.single()
        assertEquals(7, source.sourceLinesByBlock[block]!![block.text.indexOf("classOwner.clazz")])
    }

    @Test
    fun extractsTechnicalIdFromResolverDeclaration() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                package sample
                object Sample : BaseFeature(), IResolveDex {
                    override val technicalId = "防撤回"
                    private val method by dexMethod(sqlMethod { name = "m" })
                }
            """.trimIndent(),
        )!!

        assertEquals("防撤回", source.technicalId)
    }

    @Test
    fun technicalIdIsNullOrMissingOrNonLiteral() {
        assertEquals(
            null,
            scanDexResolverSource(
                "Missing.kt",
                """
                    object Sample : IResolveDex {
                        private val method by dexMethod(sqlMethod { name = "m" })
                    }
                """.trimIndent(),
            )!!.technicalId,
        )
        assertEquals(
            null,
            scanDexResolverSource(
                "NonLiteral.kt",
                """
                    object Sample : IResolveDex {
                        override val technicalId = BuildConfig.ID
                        private val method by dexMethod(sqlMethod { name = "m" })
                    }
                """.trimIndent(),
            )!!.technicalId,
        )
    }

    @Test
    fun unescapesTechnicalIdLiteral() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : IResolveDex {
                    override val technicalId = "a\"b\\c"
                    private val method by dexMethod(sqlMethod { name = "m" })
                }
            """.trimIndent(),
        )!!

        assertEquals("""a"b\c""", source.technicalId)
    }

    private companion object {
        val sampleSource =
            """
                package sample
                object Sample : IResolveDex {
                    private val method by dexMethod(
                        sqlMethod { className = classOwner.clazz },
                    )

                    override fun resolveDex() {
                        if (HostInfo.versionCode > 1) {
                            method.find(methodSpec { name("m") })
                        }
                    }

                    fun install() {
                        classOwner.clazz.getDeclaredMethod("outsideResolution")
                    }
                }
            """.trimIndent()
    }
}
