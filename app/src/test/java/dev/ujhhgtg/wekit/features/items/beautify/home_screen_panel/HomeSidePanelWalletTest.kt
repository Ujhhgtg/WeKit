package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeSidePanelWalletTest {

    @Test
    fun unavailableBalanceUsesPlaceholder() {
        assertEquals("¥ --", formatHomeSidePanelWalletBalance(null))
    }

    @Test
    fun zeroBalanceRemainsAConfirmedZero() {
        assertEquals("¥ 0.00", formatHomeSidePanelWalletBalance(0L))
    }

    @Test
    fun balanceUsesFenAndGrouping() {
        assertEquals("¥ 2,480.60", formatHomeSidePanelWalletBalance(248060L))
    }

    @Test
    fun balancePreservesTwoDecimalPlaces() {
        assertEquals("¥ 12.30", formatHomeSidePanelWalletBalance(1230L))
    }
}
