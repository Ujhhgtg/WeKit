package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerKind
import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.dexkit.resolution.effectiveFingerprint
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DexCacheManagerV2Test {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json { encodeDefaults = true; prettyPrint = true }

    @Test
    fun descriptorValidationIsKindSpecificAndStructural() {
        assertTrue(isValidDexClassDescriptor("com.tencent.mm.Foo\$Inner"))
        assertTrue(isValidDexFieldDescriptor("Lcom/tencent/mm/Foo;->count:I"))
        assertTrue(isValidDexMethodDescriptor("Lcom/tencent/mm/Foo;->run([Ljava/lang/String;)V"))
        assertTrue(isValidDexConstructorDescriptor("Lcom/tencent/mm/Foo;-><init>(I)V"))

        assertFalse(isValidDexClassDescriptor("Lcom/tencent/mm/Foo;->run()V"))
        assertFalse(isValidDexFieldDescriptor("Lcom/tencent/mm/Foo;->run()V"))
        assertFalse(isValidDexMethodDescriptor("Lcom/tencent/mm/Foo;-><init>()V"))
        assertFalse(isValidDexConstructorDescriptor("Lcom/tencent/mm/Foo;->run()V"))
        assertFalse(isValidDexMethodDescriptor("Lcom/tencent/mm/Foo;->run(Q)V"))
        assertFalse(isValidDexFieldDescriptor("Lcom/tencent/mm/Foo;->count:V"))
    }

    @Test
    fun unchangedTransitiveGraphRestoresEveryOwner() {
        val restored = mutableListOf<String>()
        val graph = graph(restored)
        writeGraph(graph)

        val result = restoreValidOwners(tempDir, graph)

        assertEquals(setOf("owner.A", "owner.B", "owner.C", "owner.D"), result.validOwners)
        assertEquals(listOf("A", "B", "C", "D"), restored.sorted())
    }

    @Test
    fun transitiveFingerprintChangeInvalidatesConsumerClosureButNotUnrelatedOwner() {
        val restored = mutableListOf<String>()
        val original = graph(restored)
        writeGraph(original)
        val changed = graph(restored, cFingerprint = "c-v2")

        val result = restoreValidOwners(tempDir, changed)

        assertEquals(setOf("owner.D"), result.validOwners)
        assertEquals(setOf("owner.A", "owner.B", "owner.C"), result.invalidOwners.keys)
        assertEquals(listOf("D"), restored)
    }

    @Test
    fun directFingerprintChangeInvalidatesProducerAndConsumer() {
        val restored = mutableListOf<String>()
        val original = graph(restored)
        writeGraph(original)

        val result = restoreValidOwners(tempDir, graph(restored, bFingerprint = "b-v2"))

        assertEquals(setOf("owner.C", "owner.D"), result.validOwners)
        assertEquals(setOf("owner.A", "owner.B"), result.invalidOwners.keys)
    }

    @Test
    fun unrelatedOwnerChangeDoesNotInvalidateConsumerClosure() {
        val restored = mutableListOf<String>()
        val original = graph(restored)
        writeGraph(original)
        val changed = original.map { owner ->
            if (owner.ownerId == "owner.D") {
                currentOwner("owner.D", "D", "owner.D#target", restored, "d-v2", "D")
            } else owner
        }

        val result = restoreValidOwners(tempDir, changed)

        assertEquals(setOf("owner.A", "owner.B", "owner.C"), result.validOwners)
        assertEquals(setOf("owner.D"), result.invalidOwners.keys)
    }

    @Test
    fun dependencyOrderDoesNotAffectValidation() {
        val restored = mutableListOf<String>()
        val graph = graph(restored)
        writeGraph(graph, reverseDependencies = true, directCDependency = true)

        val result = restoreValidOwners(tempDir, graph)

        assertTrue(result.invalidOwners.isEmpty())
    }

    @Test
    fun changedDependencyMembershipInvalidatesEffectiveFingerprint() {
        val restored = mutableListOf<String>()
        val graph = graph(restored)
        writeGraph(graph)
        val path = tempDir.resolve("A.json")
        path.writeText(path.toFile().readText().replace("\"owner.B#target\"", "\"owner.D#target\""))

        val result = restoreValidOwners(tempDir, graph)

        assertTrue("owner.A" in result.invalidOwners)
    }

    @Test
    fun invalidOwnerNeverPartiallyRestoresDescriptors() {
        val restored = mutableListOf<String>()
        val owner = DexCacheCurrentOwner(
            ownerId = "owner.Pair",
            technicalId = "Pair",
            delegates = mapOf(
                "owner.Pair#first" to currentDelegate("owner.Pair#first", "pair-first", restored, "first"),
                "owner.Pair#second" to currentDelegate("owner.Pair#second", "pair-second", restored, "second"),
            ),
        )
        val first = entry("first", "pair-first")
        write(owner, mapOf("owner.Pair#first" to first))

        val result = restoreValidOwners(tempDir, listOf(owner))

        assertEquals(DexCacheInvalidReason.MISSING_CURRENT_DELEGATE, result.invalidOwners.getValue(owner.ownerId).reason)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun emptyDescriptorAndUnexpectedPlaceholderAreRejected() {
        val restored = mutableListOf<String>()
        val owner = currentOwner("owner.Bad", "Bad", "owner.Bad#target", restored)
        write(owner, mapOf(owner.delegates.keys.single() to entry("", "local")))
        var result = restoreValidOwners(tempDir, listOf(owner))
        assertEquals(DexCacheInvalidReason.INVALID_DESCRIPTOR, result.invalidOwners.getValue(owner.ownerId).reason)

        write(owner, mapOf(owner.delegates.keys.single() to entry("PLACEHOLDER", "local")))
        result = restoreValidOwners(tempDir, listOf(owner))
        assertEquals(DexCacheInvalidReason.INVALID_PLACEHOLDER_CLASSIFICATION, result.invalidOwners.getValue(owner.ownerId).reason)
    }

    @Test
    fun malformedOrCrossKindDescriptorPreventsEveryOwnerLoadCallback() {
        val restored = mutableListOf<String>()
        val owner = DexCacheCurrentOwner(
            ownerId = "owner.Kinds",
            technicalId = "Kinds",
            delegates = mapOf(
                "owner.Kinds#method" to currentDelegate(
                    "owner.Kinds#method",
                    "method-local",
                    restored,
                    "method",
                    ::isValidDexMethodDescriptor,
                ),
                "owner.Kinds#class" to currentDelegate(
                    "owner.Kinds#class",
                    "class-local",
                    restored,
                    "class",
                    ::isValidDexClassDescriptor,
                ),
            ),
        )
        val methodId = "owner.Kinds#method"
        val classId = "owner.Kinds#class"
        write(
            owner,
            mapOf(
                methodId to entry(
                    "Lowner/Kinds;->run()V",
                    "method-local",
                    effective = effective(methodId, "method-local"),
                ),
                classId to entry(
                    "Lowner/Kinds;->wrong()V",
                    "class-local",
                    effective = effective(classId, "class-local"),
                ),
            ),
        )

        val result = restoreValidOwners(tempDir, listOf(owner))

        assertEquals(DexCacheInvalidReason.INVALID_DESCRIPTOR, result.invalidOwners.getValue(owner.ownerId).reason)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun duplicateJsonKeysAreMalformed() {
        val restored = mutableListOf<String>()
        val owner = currentOwner("owner.Duplicate", "Duplicate", "owner.Duplicate#target", restored)
        tempDir.resolve("Duplicate.json").writeText(
            """{"schema":2,"owner":"owner.Duplicate","owner":"owner.Other","timestamp":1,"delegates":{}}""",
        )

        val result = restoreValidOwners(tempDir, listOf(owner))

        assertEquals(DexCacheInvalidReason.MALFORMED_CACHE, result.invalidOwners.getValue(owner.ownerId).reason)
    }

    @Test
    fun repairRootSelectionIsOwnerDeduplicated() {
        val invalid = DexCacheValidation.Invalid(DexCacheInvalidReason.MISSING_FILE, "missing")
        val restore = DexCacheRestoreResult(
            validOwners = setOf("owner.Dependency"),
            invalidOwners = mapOf("owner.Consumer" to invalid),
        )

        assertEquals(
            setOf("owner.Consumer"),
            selectRepairOwnerIds(
                listOf("owner.Consumer", "owner.Consumer", "owner.Dependency"),
                restore,
            ),
        )
    }

    private fun graph(
        restored: MutableList<String>,
        bFingerprint: String = "b-v1",
        cFingerprint: String = "c-v1",
    ) = listOf(
        currentOwner("owner.A", "A", "owner.A#target", restored, "a-v1", "A"),
        currentOwner("owner.B", "B", "owner.B#target", restored, bFingerprint, "B"),
        currentOwner("owner.C", "C", "owner.C#target", restored, cFingerprint, "C"),
        currentOwner("owner.D", "D", "owner.D#target", restored, "d-v1", "D"),
    )

    private fun writeGraph(
        graph: List<DexCacheCurrentOwner>,
        reverseDependencies: Boolean = false,
        directCDependency: Boolean = false,
    ) {
        val c = effective("owner.C#target", "c-v1")
        val b = effective("owner.B#target", "b-v1", mapOf("owner.C#target" to c))
        val d = effective("owner.D#target", "d-v1")
        val aDependencies = linkedMapOf("owner.B#target" to b).apply {
            if (directCDependency) put("owner.C#target", c)
        }
        val a = effective("owner.A#target", "a-v1", aDependencies)
        graph.forEach { owner ->
            val delegate = owner.delegates.values.single()
            val dependencies = when (owner.ownerId) {
                "owner.A" -> if (reverseDependencies) aDependencies.keys.reversed() else aDependencies.keys.toList()
                "owner.B" -> listOf("owner.C#target")
                else -> emptyList()
            }
            val effective = when (owner.ownerId) {
                "owner.A" -> a
                "owner.B" -> b
                "owner.C" -> c
                else -> d
            }
            write(owner, mapOf(delegate.id to entry(owner.technicalId, delegate.producerFingerprint, dependencies, effective)))
        }
    }

    private fun write(owner: DexCacheCurrentOwner, delegates: Map<String, DexCacheDelegateEntry>) {
        tempDir.resolve(DexCacheManager.cacheFileName(owner.technicalId)).writeText(
            json.encodeToString(DexCacheManifest(owner = owner.ownerId, timestamp = 1, delegates = delegates)),
        )
    }
}

internal fun currentOwner(
    ownerId: String,
    technicalId: String,
    delegateId: String,
    restored: MutableList<String>,
    fingerprint: String = "local",
    restoredValue: String = technicalId,
) = DexCacheCurrentOwner(
    ownerId,
    technicalId,
    mapOf(delegateId to currentDelegate(delegateId, fingerprint, restored, restoredValue)),
)

private fun currentDelegate(
    id: String,
    fingerprint: String,
    restored: MutableList<String>,
    restoredValue: String,
    isValidDescriptor: (String) -> Boolean = { true },
) = DexCacheCurrentDelegate(
    id = id,
    producerId = id,
    producerFingerprint = fingerprint,
    isValidDescriptor = isValidDescriptor,
    isPlaceholderDescriptor = { it == "PLACEHOLDER" },
    loadDescriptor = { _, _ -> restored += restoredValue },
)

private fun entry(
    descriptor: String,
    fingerprint: String,
    dependencies: List<String> = emptyList(),
    effective: String = effective("ignored", fingerprint),
) = DexCacheDelegateEntry(
    descriptor = descriptor,
    status = DexResolutionStatus.SUCCESS,
    isPlaceholder = false,
    producerFingerprint = fingerprint,
    effectiveFingerprint = effective,
    dependencies = dependencies,
)

private fun effective(id: String, local: String, dependencies: Map<String, String> = emptyMap()): String =
    effectiveFingerprint(
        DexProducerMetadata(id, "owner", null, DexProducerKind.INLINE_METHOD, local),
        dependencies,
    )
