import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenerateDexResolutionMetadataTaskTest {
    @Test
    fun renderedMetadataUsesSha256ForDeclaredProducerSource() {
        val owner = resolver(
            ownerName = "sample.Sample",
            producers = listOf(
                producer("sample.Sample#fine", "fine", "producer source"),
            ),
        )

        val rendered = renderMetadataSource(
            namespace = "dev.example",
            owners = listOf(owner),
        )

        assertTrue(
            rendered.contains(
                "localFingerprint = \"4d426d1a1835e4b4a3e42af27507413a9063aed3c49a2e860366e8931e85d19b\"",
            ),
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
    ) = DexProducerSource(
        stableId = stableId,
        propertyName = propertyName,
        kind = ResolveBlockKind.INLINE_METHOD,
        startLine = 1,
        fingerprintSource = source,
    )
}
