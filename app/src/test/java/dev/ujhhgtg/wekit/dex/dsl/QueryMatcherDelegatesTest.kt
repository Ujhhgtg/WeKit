package dev.ujhhgtg.wekit.dex.dsl

import dev.ujhhgtg.wekit.dex.matcher.ClassSpec
import dev.ujhhgtg.wekit.dex.matcher.MatcherFixtures
import dev.ujhhgtg.wekit.dex.matcher.MethodSpec
import dev.ujhhgtg.wekit.dex.matcher.NameSpec
import dev.ujhhgtg.wekit.dex.matcher.QueryMatcherScope
import dev.ujhhgtg.wekit.dex.matcher.RecordingDescriptorQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryMatcherDelegatesTest {
    private val metadata = MatcherFixtures.metadata

    private fun scope(): QueryMatcherScope =
        QueryMatcherScope(
            metadata,
            RecordingDescriptorQuery { MatcherFixtures.methodDescriptors() },
        )

    @Test
    fun nullResultIsExpectedFailureOnlyWhenAllowed() {
        assertNull(QueryMatcherSpec(allowFailure = true) { null }.resolve(scope()))
        assertThrows(IllegalStateException::class.java) {
            QueryMatcherSpec(allowFailure = false) { null }.resolve(scope())
        }
    }

    @Test
    fun descriptorResultIsReturned() {
        val spec =
            QueryMatcherSpec(allowFailure = false) {
                uniqueMethod(
                    MethodSpec(
                        name = NameSpec("helper"),
                        declaredClass = ClassSpec(className = NameSpec("com.test.Alpha")),
                    ),
                )
            }
        assertEquals(MatcherFixtures.ALPHA_HELPER, spec.resolve(scope()))
    }

    @Test
    fun blockRunsLazilyAtResolutionTime() {
        var wantedName = "missing"
        val spec =
            QueryMatcherSpec(allowFailure = true) {
                uniqueMethod(
                    MethodSpec(
                        name = NameSpec(wantedName),
                        declaredClass = ClassSpec(className = NameSpec("com.test.Alpha")),
                    ),
                )
            }
        assertNull(spec.resolve(scope()))
        // The block re-reads captured state on every resolution attempt (lazy prelude semantics).
        wantedName = "helper"
        assertEquals(MatcherFixtures.ALPHA_HELPER, spec.resolve(scope()))
    }

    @Test
    fun ambiguousMatchIsAnError() {
        val spec =
            QueryMatcherSpec(allowFailure = true) {
                uniqueMethod(MethodSpec(name = NameSpec("run")))
            }
        assertThrows(IllegalStateException::class.java) { spec.resolve(scope()) }
    }

    @Test
    fun matcherSpecHasNoStaticSqlText() {
        val sqlSpec = QueryMatcherSpec(allowFailure = true) { null }.asSqlSpec()
        assertThrows(IllegalStateException::class.java) { sqlSpec.sql() }
    }
}
