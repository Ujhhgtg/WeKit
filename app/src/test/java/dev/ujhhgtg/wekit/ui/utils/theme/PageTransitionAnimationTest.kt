package dev.ujhhgtg.wekit.ui.utils.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageTransitionAnimationTest {
    @Test
    fun parsesSupportedAnimationsAndDefaultsToAosp() {
        assertEquals(PageTransitionAnimation.AOSP, PageTransitionAnimation.fromName(null))
        assertEquals(PageTransitionAnimation.AOSP, PageTransitionAnimation.fromName("invalid"))
        PageTransitionAnimation.entries.forEach { animation ->
            assertEquals(animation, PageTransitionAnimation.fromName(animation.name))
        }
    }
}
