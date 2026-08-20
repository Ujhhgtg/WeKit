package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class HomeSidePanelRuntimeRulesTest {

    @Test
    fun deltaSeparatesActivateRemoveAndReconfigure() {
        val old = HomeSidePanelLayout(
            cards = listOf(
                WeatherCardConfig("same", DEFAULT_WEATHER_CITY),
                HitokotoCardConfig("removed"),
            ),
        )
        val new = HomeSidePanelLayout(
            cards = listOf(
                WeatherCardConfig(
                    "same",
                    DEFAULT_WEATHER_CITY.copy(cityNum = "101020100"),
                ),
                WalletCardConfig("added"),
            ),
        )

        assertEquals(
            HomeSidePanelRuntimeDelta(
                activate = setOf("added"),
                deactivate = setOf("removed"),
                reconfigure = setOf("same"),
            ),
            homeSidePanelRuntimeDelta(old, new),
        )
    }

    @Test
    fun deltaIgnoresCardsWithoutRuntimeState() {
        val old = HomeSidePanelLayout(cards = listOf(DateTimeCardConfig("old-static")))
        val new = HomeSidePanelLayout(cards = listOf(DateTimeCardConfig("new-static")))

        assertEquals(HomeSidePanelRuntimeDelta(), homeSidePanelRuntimeDelta(old, new))
    }

    @Test
    fun fingerprintsAreDeterministic() {
        assertNotEquals(
            weatherCacheFingerprint(DEFAULT_WEATHER_CITY),
            weatherCacheFingerprint(
                DEFAULT_WEATHER_CITY.copy(cityNum = "101020100"),
            ),
        )
        assertEquals(
            hitokotoCacheFingerprint(
                HitokotoSettings(categories = linkedSetOf("b", "a")),
            ),
            hitokotoCacheFingerprint(
                HitokotoSettings(categories = linkedSetOf("a", "b")),
            ),
        )
    }

    @Test
    fun promotionRetainsCommittedCardSnapshotsOnly() {
        val snapshots = mapOf(
            "committed" to "kept snapshot",
            "discarded" to "discarded snapshot",
        )
        val committed = HomeSidePanelLayout(
            cards = listOf(WeatherCardConfig("committed", DEFAULT_WEATHER_CITY)),
        )

        assertEquals(
            mapOf("committed" to "kept snapshot"),
            retainCommittedCardEntries(snapshots, committed),
        )
    }

    @Test
    fun walletMasksAreIndependentWhileBalanceIsShared() {
        val sharedBalance = 12_345L
        val hidden = HomeSidePanelWalletUiState(
            balanceFen = sharedBalance,
            displayState = HomeSidePanelWalletDisplayState(
                defaultMaskEnabled = true,
                isMasked = true,
            ),
        )
        val visible = HomeSidePanelWalletUiState(
            balanceFen = sharedBalance,
            displayState = HomeSidePanelWalletDisplayState(
                defaultMaskEnabled = false,
                isMasked = false,
            ),
        )

        assertEquals(sharedBalance, hidden.balanceFen)
        assertEquals(sharedBalance, visible.balanceFen)
        assertNotEquals(hidden.displayState.isMasked, visible.displayState.isMasked)
    }
}
