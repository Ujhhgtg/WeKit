package dev.ujhhgtg.wekit.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootstrapFailureTest {
    @Test
    fun `reports the shortest frame cycle of a looping stack`() {
        assertEquals(
            listOf("A.x", "B.y"),
            BootstrapFailure.cycleFrames(overflow(emptyList(), listOf("a.A.x", "b.B.y"))),
        )
    }

    @Test
    fun `ignores entry frames above the loop`() {
        val overflow = overflow(listOf("entry.Main.run"), listOf("a.A.x", "b.B.y", "c.C.z"))
        assertEquals(listOf("A.x", "B.y", "C.z"), BootstrapFailure.cycleFrames(overflow))
    }

    @Test
    fun `reports no cycle for a deep but non-repeating stack`() {
        val frames = (0 until 200).map { frame("a.A.f$it") }.toTypedArray()
        val overflow = StackOverflowError().apply { stackTrace = frames }
        assertEquals(emptyList<String>(), BootstrapFailure.cycleFrames(overflow))
    }

    @Test
    fun `describes the overflow site instead of the ART stack size`() {
        val described = BootstrapFailure.describeOverflow(
            overflow(emptyList(), listOf("a.A.x", "b.B.y")),
        )
        assertTrue("loop A.x -> B.y -> A.x" in described, described)
        assertTrue("innermost a.A.x" in described, described)
        assertTrue("stack overflow after 120 frames" in described, described)
    }

    /** A stack that repeats [cycle] downwards, as a real overflow of that cycle would. */
    private fun overflow(prefix: List<String>, cycle: List<String>): StackOverflowError {
        val frames = prefix.map(::frame) + (0 until 60).flatMap { cycle }.map(::frame)
        return StackOverflowError().apply { stackTrace = frames.toTypedArray() }
    }

    private fun frame(name: String): StackTraceElement =
        StackTraceElement(name.substringBeforeLast('.'), name.substringAfterLast('.'), "F.kt", 1)
}
