import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DexResolverSourceScannerTest {
    @Test
    fun findsDelegatesAfterANonDexFeatureInTheSameFile() {
        val source = scanDexResolverSource("Sample.kt", """
            object Plain : SwitchFeature() {
                override val technicalId = "plain"
            }
            object Resolver : ApiFeature() {
                override val technicalId = "resolver"
                private val target by dexMethodFrom { findTarget() }
            }
        """.trimIndent())!!

        assertEquals("Resolver", source.qualifiedClassName)
        assertEquals("resolver", source.technicalId)
        assertEquals(1, source.blocks.size)
    }

    @Test
    fun ignoresFeaturesWithoutDelegateDeclarations() {
        assertNull(
            scanDexResolverSource("Sample.kt", """
                object Sample : BaseFeature() {
                    fun initialize() { println("not a resolver") }
                }
            """.trimIndent()),
        )
    }

    @Test
    fun conditionalAndComputedDeclarationsIncludeTheirPolicyInTheHashInput() {
        val text = """
            object Sample : BaseFeature() {
                override val technicalId = "sample"
                private val modern = dexGroup(whenFound = { probe })
                private val probe by dexClass(allowFailure = true) { className = "Probe" }
                private val method by modern.dexMethod { name = "run" }
                private val field by probe.dexFieldFrom { probe.data.fields.single() }
            }
        """.trimIndent()
        val source = scanDexResolverSource("Sample.kt", text)!!
        assertEquals(4, source.blocks.size)
        assertTrue(source.blocks.any { it.text.contains("whenFound") })
        assertTrue(source.blocks.any { it.text.startsWith("by modern.dexMethod") })
        assertTrue(source.blocks.any { it.text.startsWith("by probe.dexFieldFrom") })
        assertNotEquals(
            source.blocks.map { it.text },
            scanDexResolverSource("Sample.kt", text.replace("whenFound", "whenAbsent"))!!.blocks.map { it.text },
        )
        assertNotEquals(
            source.blocks.map { it.text },
            scanDexResolverSource("Sample.kt", text.replace("modern.dexMethod", "probe.dexMethod"))!!.blocks.map { it.text },
        )
    }

    @Test
    fun computedAndGroupBlocksAreCheckedForDesktopUnsafeAccess() {
        val source = scanDexResolverSource("Sample.kt", """
            object Sample : BaseFeature() {
                override val technicalId = "sample"
                private val modern = dexGroup(whenFound = { classProbe.clazz; classProbe })
                private val target by modern.dexMethodFrom { methodProbe.method; null }
            }
        """.trimIndent())!!
        assertEquals(2, findDesktopIncompatibleAccesses(source).size)
    }
    @Test
    fun extractsComputedAndSqlDelegateExpressions() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                package sample
                object Sample : BaseFeature() {
                    private val method by dexMethod(
                        "SELECT descriptor FROM methods ORDER BY descriptor LIMIT 1",
                        allowFailure = true,
                    )
                    private val field by dexField(sqlField { type = "int" })
                    private val derived by dexMethodFrom {
                        method.data.invokes.single()
                    }
                }
            """.trimIndent(),
        )!!

        assertEquals(
            listOf(ResolveBlockKind.INLINE_SQL, ResolveBlockKind.INLINE_SQL, ResolveBlockKind.INLINE_QUERY_METHOD),
            source.blocks.map { it.kind },
        )
    }

    @Test
    fun includesSqlDslDelegateExpressionsInResolverHashInput() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : BaseFeature() {
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
        assertEquals(2, methodBlock.startLine)
        assertEquals(true, methodBlock.text.contains("sqlMethod"))
        assertEquals(true, methodBlock.text.contains("allowFailure = true"))
    }

    @Test
    fun extractsQueryMatcherDelegateKinds() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Sample : BaseFeature() {
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

                object Sample : BaseFeature() {
                    private val method by dexMethod(sqlMethod { name = "m" })
                }
            """.trimIndent(),
        )!!

        assertEquals(6, source.blocks.single().startLine)
    }

    @Test
    fun extractsBlocksOnlyFromTheFeatureDeclaration() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                object Helper {
                    private val ignored by dexMethod(sqlMethod { name = "ignored" })
                }

                object Sample : BaseFeature() {
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
                object Sample : BaseFeature() {
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
                object Sample : BaseFeature() {
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
                object Sample : BaseFeature() {
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
                    object Sample : BaseFeature() {
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
                    object Sample : BaseFeature() {
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
                object Sample : BaseFeature() {
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
                object Sample : BaseFeature() {
                    private val method by dexMethod(
                        sqlMethod { className = classOwner.clazz },
                    )

                    private val derived by dexMethodFrom {
                        check(HostInfo.versionCode > 1)
                        method.data.invokes.single()
                    }

                    fun install() {
                        classOwner.clazz.getDeclaredMethod("outsideResolution")
                    }
                }
            """.trimIndent()
    }
}
