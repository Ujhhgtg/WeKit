package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeSidePanelLayoutTest {

    private class SequenceIds : HomeSidePanelIdGenerator {
        private var next = 0
        override fun nextId(): String = "id-" + next++
    }

    @Test
    fun migrationPreservesSettingsAndDefaultOrder() {
        val layout = defaultHomeSidePanelLayout(
            LegacyHomeSidePanelSnapshot(
                weatherCity = DEFAULT_WEATHER_CITY.copy(
                    city = "上海",
                    cityNum = "101020100",
                ),
                hideWalletBalance = true,
                hitokotoSettings = HitokotoSettings(
                    categories = setOf("a"),
                    showAuthor = false,
                ),
            ),
            SequenceIds(),
        )

        assertEquals(
            listOf(
                HomeSidePanelCardType.DATE_TIME,
                HomeSidePanelCardType.WEATHER,
                HomeSidePanelCardType.WALLET,
                HomeSidePanelCardType.HORIZONTAL_ACTIONS,
                HomeSidePanelCardType.VERTICAL_ACTIONS,
                HomeSidePanelCardType.HITOKOTO,
            ),
            layout.cards.map { it.type },
        )
        assertEquals("101020100", (layout.cards[1] as WeatherCardConfig).city.cityNum)
        assertTrue((layout.cards[2] as WalletCardConfig).hideBalanceByDefault)
        assertEquals(
            listOf(
                HomeSidePanelActionKind.SCAN,
                HomeSidePanelActionKind.WALLET,
                HomeSidePanelActionKind.FAVORITES,
            ),
            (layout.cards[3] as HorizontalActionsCardConfig).actions.map { it.kind },
        )
        assertEquals(
            listOf(
                HomeSidePanelActionKind.MOMENTS,
                HomeSidePanelActionKind.CHANNELS,
                HomeSidePanelActionKind.MARK_ALL_READ,
                HomeSidePanelActionKind.WEKIT_SETTINGS,
            ),
            (layout.cards[4] as VerticalActionsCardConfig).actions.map { it.kind },
        )
    }

    @Test
    fun codecRoundTripsDuplicateKindsWithUniqueIds() {
        val layout = HomeSidePanelLayout(
            cards = listOf(
                WeatherCardConfig("weather-1", DEFAULT_WEATHER_CITY),
                WeatherCardConfig("weather-2", DEFAULT_WEATHER_CITY),
                HorizontalActionsCardConfig(
                    "actions",
                    listOf(
                        HomeSidePanelActionConfig("scan-1", HomeSidePanelActionKind.SCAN),
                        HomeSidePanelActionConfig("scan-2", HomeSidePanelActionKind.SCAN),
                    ),
                ),
            ),
        )

        assertEquals(
            layout,
            HomeSidePanelLayoutCodec.decode(HomeSidePanelLayoutCodec.encode(layout)),
        )
    }

    @Test
    fun invalidRawIsPreservedAndDuplicateIdsFail() {
        val fallback = HomeSidePanelLayoutCodec.load(
            "{not-json",
            LegacyHomeSidePanelSnapshot.defaults(),
            SequenceIds(),
        ) as HomeSidePanelLayoutLoad.Fallback
        assertEquals("{not-json", fallback.invalidRaw)
        assertThrows(InvalidHomeSidePanelLayoutException::class.java) {
            validateHomeSidePanelLayout(
                HomeSidePanelLayout(
                    cards = listOf(
                        DateTimeCardConfig("same"),
                        WalletCardConfig("same"),
                    ),
                ),
            )
        }
    }
}
