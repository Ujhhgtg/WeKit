package dev.ujhhgtg.wekit.i18n

import dev.ujhhgtg.wekit.R
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeowResourceFilterTest {
    @Test
    fun acceptsOnlyWeKitResourcePackageIds() {
        assertTrue(MeowResourceFilter.isWeKitResource(R.string.settings_title))
        assertFalse(MeowResourceFilter.isWeKitResource(0x7f010001))
        assertFalse(MeowResourceFilter.isWeKitResource(android.R.string.ok))
    }
}
