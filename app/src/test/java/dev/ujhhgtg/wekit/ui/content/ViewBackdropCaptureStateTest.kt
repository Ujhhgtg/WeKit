package dev.ujhhgtg.wekit.ui.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewBackdropCaptureStateTest {
    private val source = Any()
    private val window = Any()

    private fun key(
        source: Any = this.source,
        window: Any = this.window,
        generation: Int = 1,
        width: Int = 1080,
        height: Int = 2200,
        scrollX: Int = 0,
        scrollY: Int = 0,
        density: Float = 3f,
        fontScale: Float = 1f,
        layoutDirection: Int = 0,
    ) = ViewBackdropCaptureKey(
        source = ViewBackdropCaptureIdentity(source),
        window = ViewBackdropCaptureIdentity(window),
        generation = generation,
        width = width,
        height = height,
        scrollX = scrollX,
        scrollY = scrollY,
        density = density,
        fontScale = fontScale,
        layoutDirection = layoutDirection,
    )

    @Test
    fun firstKeyCapturesAndSameKeyConsumersReuse() {
        val state = ViewBackdropCaptureState()
        val key = key()

        assertEquals(ViewBackdropCaptureDecision.CAPTURE, state.decide(key))
        state.captureSucceeded(key)
        assertEquals(ViewBackdropCaptureDecision.REUSE, state.decide(key))
        assertTrue(state.canDrawFor(key))
    }

    @Test
    fun identityUsesReferenceEquality() {
        data class EqualValue(val value: Int)
        val first = EqualValue(1)
        val second = EqualValue(1)

        assertFalse(ViewBackdropCaptureIdentity(first) == ViewBackdropCaptureIdentity(second))
        assertTrue(ViewBackdropCaptureIdentity(first) == ViewBackdropCaptureIdentity(first))
    }

    @Test
    fun everyCaptureKeyDimensionTriggersOneNewCapture() {
        val state = ViewBackdropCaptureState()
        val base = key()
        state.decide(base)
        state.captureSucceeded(base)

        val changed = listOf(
            key(generation = 2),
            key(width = 1200),
            key(height = 2400),
            key(scrollX = 1080),
            key(scrollY = 20),
            key(density = 2.75f),
            key(fontScale = 1.1f),
            key(layoutDirection = 1),
            key(source = Any()),
            key(window = Any()),
        )

        changed.forEach { next ->
            assertEquals(ViewBackdropCaptureDecision.CAPTURE, state.decide(next), next.toString())
            assertEquals(ViewBackdropCaptureDecision.SKIP, state.decide(next), next.toString())
            state.captureSucceeded(next)
        }
    }

    @Test
    fun failedAttemptIsNotRepeatedButNextGenerationRetries() {
        val state = ViewBackdropCaptureState()
        val failed = key(generation = 3, width = 0)

        assertEquals(ViewBackdropCaptureDecision.CAPTURE, state.decide(failed))
        assertEquals(ViewBackdropCaptureDecision.SKIP, state.decide(failed))
        assertEquals(ViewBackdropCaptureDecision.CAPTURE, state.decide(key(generation = 4)))
    }

    @Test
    fun oldCaptureIsDrawableOnlyForSameSourceAndWindow() {
        val state = ViewBackdropCaptureState()
        val captured = key(generation = 1)
        state.decide(captured)
        state.captureSucceeded(captured)

        assertTrue(state.canDrawFor(key(generation = 2, width = 0)))
        assertFalse(state.canDrawFor(key(source = Any(), generation = 2)))
        assertFalse(state.canDrawFor(key(window = Any(), generation = 2)))
        state.invalidate()
        assertFalse(state.canDrawFor(captured))
    }
}
