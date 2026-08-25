import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GenerateDexResolutionMetadataTaskTest {
    @Test
    fun renderedMetadataUsesSha256ForFineGrainedAndSourceDirSafetyFingerprints() {
        val owner = resolver(
            ownerName = "sample.Sample",
            producers = listOf(
                producer("sample.Sample#fine", "fine", "producer source", conservative = false),
                producer("sample.Sample#conservative", "conservative", "ignored", conservative = true),
            ),
        )

        val rendered = renderMetadataSource(
            namespace = "dev.example",
            owners = listOf(owner),
            sourceDirSafetyDigest = "0".repeat(64),
        )

        assertTrue(
            rendered.contains(
                "localFingerprint = \"4d426d1a1835e4b4a3e42af27507413a9063aed3c49a2e860366e8931e85d19b\"",
            ),
        )
        assertTrue(
            rendered.contains(
                "localFingerprint = \"075f36bd3f5a0c3ce799ee00102dab013587ac5d94288ad110406fce68141643\"",
            ),
        )
    }

    @Test
    fun topLevelHelperBodyOnlyChangeChangesConservativeProducerFingerprint() {
        fun source(anchor: String) =
            """
                package sample

                private fun addAnchor() {
                    usingEqStrings("$anchor")
                }

                object Sample : IResolveDex {
                    val target by dexMethod {
                        matcher { addAnchor() }
                    }
                }
            """.trimIndent()

        assertNotEquals(
            renderedProducerFingerprint("sample.Sample#target", "Sample.kt" to source("first")),
            renderedProducerFingerprint("sample.Sample#target", "Sample.kt" to source("second")),
        )
    }

    @Test
    fun topLevelConstantOnlyChangeChangesConservativeProducerFingerprint() {
        fun source(anchor: String) =
            """
                package sample

                private const val TOP_LEVEL_ANCHOR = "$anchor"

                object Sample : IResolveDex {
                    val target by dexMethod {
                        matcher { usingEqStrings(TOP_LEVEL_ANCHOR) }
                    }
                }
            """.trimIndent()

        assertNotEquals(
            renderedProducerFingerprint("sample.Sample#target", "Sample.kt" to source("first")),
            renderedProducerFingerprint("sample.Sample#target", "Sample.kt" to source("second")),
        )
    }

    @Test
    fun externalHelperFileChangeChangesConservativeProducerFingerprint() {
        val owner =
            """
                package sample

                object Sample : IResolveDex {
                    val target by dexMethod {
                        matcher { addExternalAnchor() }
                    }
                }
            """.trimIndent()

        assertNotEquals(
            renderedProducerFingerprint(
                "sample.Sample#target",
                "Sample.kt" to owner,
                "External.kt" to "package sample\nfun addExternalAnchor() = usingEqStrings(\"first\")",
            ),
            renderedProducerFingerprint(
                "sample.Sample#target",
                "Sample.kt" to owner,
                "External.kt" to "package sample\nfun addExternalAnchor() = usingEqStrings(\"second\")",
            ),
        )
    }

    @Test
    fun unrelatedFileChangeDoesNotChangeProvenProducerFingerprint() {
        val owner =
            """
                package sample

                object Sample : IResolveDex {
                    val target by dexMethod {
                        matcher { usingEqStrings("target") }
                    }
                }
            """.trimIndent()

        assertEquals(
            renderedProducerFingerprint(
                "sample.Sample#target",
                "Sample.kt" to owner,
                "Unrelated.kt" to "package sample\nconst val UNRELATED = \"first\"",
            ),
            renderedProducerFingerprint(
                "sample.Sample#target",
                "Sample.kt" to owner,
                "Unrelated.kt" to "package sample\nconst val UNRELATED = \"second\"",
            ),
        )
    }

    @Test
    fun sourceDirSafetyDigestIsIndependentOfInputOrder() {
        val first = DexResolutionSafetySource("a/First.kt", "package a\nconst val FIRST = 1")
        val second = DexResolutionSafetySource("b/Second.kt", "package b\nconst val SECOND = 2")

        assertEquals(
            dexResolutionSafetyDigest(listOf(first, second)),
            dexResolutionSafetyDigest(listOf(second, first)),
        )
    }

    @Test
    fun duplicateOwnerNamesAreRejectedBeforeRendering() {
        val owner = resolver("sample.Sample", listOf(producer("sample.Sample#target", "target")))

        val error = assertFailsWith<IllegalArgumentException> {
            requireValidDexResolutionMetadata(listOf(owner, owner.copy(file = File("Other.kt"))))
        }

        assertTrue(error.message!!.contains("Duplicate IResolveDex owner class names"))
    }

    @Test
    fun duplicateProducerStableIdsAreRejectedBeforeRendering() {
        val duplicateId = "sample.Shared#target"
        val owners = listOf(
            resolver("sample.First", listOf(producer(duplicateId, "first"))),
            resolver("sample.Second", listOf(producer(duplicateId, "second"))),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            requireValidDexResolutionMetadata(owners)
        }

        assertTrue(error.message!!.contains("Duplicate Dex producer stable IDs"))
    }

    @Test
    fun duplicateDelegatePropertyNamesAreRejectedDuringDiscovery() {
        val error = assertFailsWith<IllegalArgumentException> {
            scanDexResolverSource(
                "Duplicate.kt",
                """
                    object Duplicate : IResolveDex {
                        val target by dexClass { matcher { name = "One" } }
                        val target by dexClass { matcher { name = "Two" } }
                    }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("duplicate Dex delegate property names"))
    }

    @Test
    fun customOutputsWithoutResolveDexAreRejectedDuringDiscovery() {
        val error = assertFailsWith<IllegalStateException> {
            scanDexResolverSource(
                "MissingPhase.kt",
                """
                    object MissingPhase : IResolveDex {
                        val target by dexClass()
                    }
                """.trimIndent(),
            )
        }

        assertTrue(error.message!!.contains("has no resolveDex() body"))
    }

    private fun resolver(
        ownerName: String,
        producers: List<DexProducerSource>,
    ) = DexResolverSource(
        file = File("${ownerName.substringAfterLast('.')}Feature.kt"),
        qualifiedClassName = ownerName,
        producers = producers,
        customOutputPropertyNames = emptySet(),
        blocks = emptyList(),
    )

    private fun producer(
        stableId: String,
        propertyName: String,
        source: String = "producer source",
        conservative: Boolean = false,
    ) = DexProducerSource(
        stableId = stableId,
        propertyName = propertyName,
        kind = ResolveBlockKind.INLINE_METHOD,
        startLine = 1,
        fingerprintSource = source,
        usesSourceDirSafetyFingerprint = conservative,
    )

    private fun renderedProducerFingerprint(
        stableId: String,
        vararg sources: Pair<String, String>,
    ): String {
        val safetySources = sources.map { (relativePath, sourceText) ->
            DexResolutionSafetySource(relativePath, sourceText)
        }
        val owners = sources.flatMap { (relativePath, sourceText) ->
            scanDexResolverSources(relativePath, sourceText)
        }
        val rendered = renderMetadataSource(
            namespace = "dev.example",
            owners = owners,
            sourceDirSafetyDigest = dexResolutionSafetyDigest(safetySources),
        )
        val producer = Regex(
            """stableId = "${Regex.escape(stableId)}",[\s\S]*?localFingerprint = "([0-9a-f]{64})"""",
        ).find(rendered)
        return requireNotNull(producer) { "Missing rendered producer $stableId" }.groupValues[1]
    }
}
