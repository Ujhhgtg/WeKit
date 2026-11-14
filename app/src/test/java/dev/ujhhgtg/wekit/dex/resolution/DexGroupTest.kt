package dev.ujhhgtg.wekit.dex.resolution

import dev.ujhhgtg.dexsql.meta.DexMetadata
import dev.ujhhgtg.wekit.dex.cache.DexItemState
import dev.ujhhgtg.wekit.dex.dsl.DexGroup
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexClassFrom
import dev.ujhhgtg.wekit.dex.dsl.dexConstructorFrom
import dev.ujhhgtg.wekit.dex.dsl.dexFieldFrom
import dev.ujhhgtg.wekit.dex.dsl.dexGroup
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.features.core.BaseFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DexGroupTest {
    private val host = DexHostMetadata(1, "test", false)
    private val metadata = DexMetadata { _, _ ->
        Json.parseToJsonElement(
                """{"descriptor":"Ltest/Probe;","name":"test.Probe","modifiers":1}""",
            )
            .jsonObject
    }

    private abstract class Fixture : BaseFeature() {
        override val technicalId = "fixture"
        override val nameRes = 0
        override val categoryIds = emptyList<String>()
    }

    @Test
    fun featureBecomesUnresolvedWhenItsDelegatesAreRegistered() {
        val plain = object : Fixture() {}
        val resolver =
            object : Fixture() {
                val target by dexClassFrom(allowFailure = true) { null }
            }

        assertFalse(plain.hasDexDelegates)
        assertEquals(DexItemState.Valid, plain.dexState)
        assertTrue(resolver.hasDexDelegates)
        assertEquals(DexItemState.NeedsResolution, resolver.dexState)
    }

    @Test
    fun absentProbeSkipsEveryFactoryAndSelectsFallback() {
        var ran = false
        val feature =
            object : Fixture() {
                val probe by dexClassFrom(allowFailure = true) { null }
                val modern = dexGroup(whenFound = { probe })
                val legacy = dexGroup(whenAbsent = { probe })
                val method by probe.dexMethodFrom { error("must not run") }
                val field by modern.dexFieldFrom { error("must not run") }
                val constructor by modern.dexConstructorFrom { error("must not run") }
                val clazz by probe.dexClass { error("matcher must not run") }
                val fallback by legacy.dexClassFrom {
                    ran = true
                    metadata.classInfo("Ltest/Probe;")!!
                }
            }

        feature.resolveAllDex(host)

        assertTrue(ran)
        assertTrue(feature.fallback.isFound)
        assertEquals(5, feature.dexDelegates.count { !it.isFound })
        assertTrue(
            feature.collectDescriptors().filterKeys { it != "fallback" }.values.all { it == null },
        )
    }

    @Test
    fun dependencyIsResolvedOnceEvenWhenDeclaredAfterItsConsumer() {
        var calls = 0
        val feature =
            object : Fixture() {
                val group = dexGroup(whenFound = { probe })
                val child by group.dexClassFrom { metadata.classInfo("Ltest/Probe;")!! }
                val probe by dexClassFrom {
                    calls++
                    metadata.classInfo("Ltest/Probe;")!!
                }
            }

        feature.resolveAllDex(host)

        assertEquals(1, calls)
        assertTrue(feature.child.isFound)
    }

    @Test
    fun selectedChildFailureDoesNotSelectFallback() {
        val feature =
            object : Fixture() {
                val probe by dexClassFrom { metadata.classInfo("Ltest/Probe;")!! }
                val child by probe.dexMethodFrom { error("ambiguous child") }
                val legacy = dexGroup(whenAbsent = { probe })
                val fallback by legacy.dexClassFrom { error("must not run") }
            }

        assertThrows(IllegalStateException::class.java) { feature.resolveAllDex(host) }

        assertTrue(feature.child.state is DexResolutionState.UnexpectedFailure)
        assertEquals(DexResolutionState.Blocked("child"), feature.fallback.state)
    }

    @Test
    fun probeErrorBlocksChildrenInsteadOfMakingThemAbsent() {
        val feature =
            object : Fixture() {
                val probe by dexClassFrom(allowFailure = true) { error("ambiguous probe") }
                val child by probe.dexClassFrom { error("must not run") }
            }

        assertThrows(IllegalStateException::class.java) { feature.resolveAllDex(host) }

        assertTrue(feature.probe.state is DexResolutionState.UnexpectedFailure)
        assertEquals(DexResolutionState.Blocked("probe"), feature.child.state)
    }

    @Test
    fun chainedAbsenceSkipsTransitiveChildren() {
        val feature =
            object : Fixture() {
                val probe by dexClassFrom(allowFailure = true) { null }
                val child by probe.dexClassFrom { error("must not run") }
                val grandchild by child.dexMethodFrom { error("must not run") }
            }

        feature.resolveAllDex(host)

        assertTrue(feature.dexDelegates.all { it.state == DexResolutionState.ExpectedFailure })
    }

    @Test
    fun crossFeatureProbeUsesTheSameCoordinator() {
        var calls = 0
        val provider =
            object : Fixture() {
                val probe by
                    dexClassFrom(allowFailure = true) {
                        calls++
                        null
                    }
            }
        val consumer =
            object : Fixture() {
                val group = dexGroup(whenFound = { provider.probe })
                val child by group.dexClassFrom { error("must not run") }
            }

        consumer.resolveAllDex(host)

        assertEquals(1, calls)
        assertFalse(consumer.child.isFound)
    }

    @Test
    fun groupCycleFailsInsteadOfRecursingForever() {
        val feature =
            object : Fixture() {
                val group: DexGroup = dexGroup(whenFound = { child })
                val child by group.dexClassFrom { metadata.classInfo("Ltest/Probe;")!! }
            }

        assertThrows(IllegalStateException::class.java) { feature.resolveAllDex(host) }
        assertTrue(feature.child.state is DexResolutionState.Blocked)
    }

    @Test
    fun requiredComputedTargetCannotSilentlyReturnNull() {
        val feature =
            object : Fixture() {
                val target by dexClassFrom { null }
            }

        assertThrows(IllegalStateException::class.java) { feature.resolveAllDex(host) }
        assertTrue(feature.target.state is DexResolutionState.UnexpectedFailure)
    }

    @Test
    fun availabilityIsReevaluatedAfterResetAndCacheRestoreDoesNotRunProviders() {
        var present = false
        var calls = 0
        val feature =
            object : Fixture() {
                val probe by
                    dexClassFrom(allowFailure = true) {
                        if (present) metadata.classInfo("Ltest/Probe;")!! else null
                    }
                val child by probe.dexClassFrom {
                    calls++
                    metadata.classInfo("Ltest/Probe;")!!
                }
            }

        feature.resolveAllDex(host)
        val cache = feature.collectDescriptors()
        present = true
        feature.resolveAllDex(host)
        assertTrue(feature.child.isFound)
        assertEquals(1, calls)

        feature.loadFromCache(cache)
        assertFalse(feature.child.isFound)
        assertEquals(1, calls)
    }

    @Test
    fun eachComputedMemberKindStoresItsOwnDescriptor() {
        val members = DexMetadata { kind, _ ->
            Json.parseToJsonElement(
                    when (kind.key) {
                        "field" ->
                            """{"descriptor":"Ltest/Probe;->value:I","name":"value","declared_class_name":"test.Probe","type_name":"int"}"""
                        else ->
                            """{"descriptor":"Ltest/Probe;-><init>()V","name":"<init>","declared_class_name":"test.Probe","return_type_name":"void","is_constructor":true}"""
                    },
                )
                .jsonObject
        }
        val feature =
            object : Fixture() {
                val field by dexFieldFrom { members.fieldInfo("Ltest/Probe;->value:I")!! }
                val constructor by dexConstructorFrom {
                    members.methodInfo("Ltest/Probe;-><init>()V")!!
                }
            }

        feature.resolveAllDex(host)

        assertEquals("Ltest/Probe;->value:I", feature.field.requireDescriptor())
        assertEquals("Ltest/Probe;-><init>()V", feature.constructor.requireDescriptor())
    }
}
