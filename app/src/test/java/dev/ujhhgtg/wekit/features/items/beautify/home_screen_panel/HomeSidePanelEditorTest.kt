package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeSidePanelEditorTest {

    private class SequenceIds : HomeSidePanelIdGenerator {
        private var next = 0
        override fun nextId(): String = "editor-id-" + next++
    }

    @Test
    fun changesStayInDraftUntilCommit() {
        val original = HomeSidePanelLayout(
            cards = listOf(DateTimeCardConfig("date"), WalletCardConfig("wallet")),
        )
        val editor = HomeSidePanelEditSession(original, SequenceIds())

        editor.moveCard(1, 0)
        editor.updateWallet("wallet") { it.copy(hideBalanceByDefault = true) }

        assertEquals(listOf("date", "wallet"), original.cards.map { it.id })
        assertEquals(listOf("wallet", "date"), editor.draft.cards.map { it.id })
        assertTrue(
            (editor.committedLayout().cards.first() as WalletCardConfig)
                .hideBalanceByDefault,
        )
    }

    @Test
    fun discardedLayoutRestoresTheOriginalLayout() {
        val original = HomeSidePanelLayout(cards = listOf(WalletCardConfig("wallet")))
        val editor = HomeSidePanelEditSession(original, SequenceIds())

        editor.updateWallet("wallet") { it.copy(hideBalanceByDefault = true) }

        assertEquals(original, editor.discardedLayout())
    }

    @Test
    fun duplicateCardsAndActionsGetFreshIds() {
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(cards = emptyList<HomeSidePanelCardConfig>()),
            SequenceIds(),
        )

        assertNotEquals(
            editor.addCard(HomeSidePanelCardType.WEATHER),
            editor.addCard(HomeSidePanelCardType.WEATHER),
        )
        val cardId = editor.addCard(HomeSidePanelCardType.HORIZONTAL_ACTIONS)
        assertNotEquals(
            editor.addAction(cardId, HomeSidePanelActionKind.SCAN),
            editor.addAction(cardId, HomeSidePanelActionKind.SCAN),
        )
    }

    @Test
    fun onlyEmptyActionCardShowsWholeCardDelete() {
        val card = HorizontalActionsCardConfig(
            "card",
            listOf(HomeSidePanelActionConfig("action", HomeSidePanelActionKind.SCAN)),
        )

        assertFalse(isWholeCardDeleteVisible(card))
        assertTrue(isWholeCardDeleteVisible(card.copy(actions = emptyList())))
        assertFalse(isWholeCardDeleteVisible(DateTimeCardConfig("date")))
    }

    @Test
    fun removingTheFinalActionLeavesItsCardEmpty() {
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(
                cards = listOf(
                    HorizontalActionsCardConfig(
                        "card",
                        listOf(HomeSidePanelActionConfig("action", HomeSidePanelActionKind.SCAN)),
                    ),
                ),
            ),
            SequenceIds(),
        )

        editor.removeAction("card", "action")

        assertEquals(
            emptyList<HomeSidePanelActionConfig>(),
            (editor.draft.cards.single() as HorizontalActionsCardConfig).actions,
        )
    }

    @Test
    fun movingAnActionOnlyChangesItsOwnCard() {
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(
                cards = listOf(
                    HorizontalActionsCardConfig(
                        "first",
                        listOf(
                            HomeSidePanelActionConfig("a", HomeSidePanelActionKind.SCAN),
                            HomeSidePanelActionConfig("b", HomeSidePanelActionKind.WALLET),
                        ),
                    ),
                    VerticalActionsCardConfig(
                        "second",
                        listOf(
                            HomeSidePanelActionConfig("c", HomeSidePanelActionKind.MOMENTS),
                            HomeSidePanelActionConfig("d", HomeSidePanelActionKind.CHANNELS),
                        ),
                    ),
                ),
            ),
            SequenceIds(),
        )

        editor.moveAction("first", 1, 0)

        assertEquals(
            listOf("b", "a"),
            (editor.draft.cards[0] as HorizontalActionsCardConfig).actions.map { it.id },
        )
        assertEquals(
            listOf("c", "d"),
            (editor.draft.cards[1] as VerticalActionsCardConfig).actions.map { it.id },
        )
    }

    @Test
    fun updatingOneDuplicateWeatherCardLeavesTheOtherUntouched() {
        val firstCity = DEFAULT_WEATHER_CITY.copy(city = "上海", cityNum = "101020100")
        val secondCity = DEFAULT_WEATHER_CITY.copy(city = "北京", cityNum = "101010100")
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(
                cards = listOf(
                    WeatherCardConfig("first", firstCity),
                    WeatherCardConfig("second", secondCity),
                ),
            ),
            SequenceIds(),
        )

        editor.updateWeather("second") { it.copy(city = firstCity) }

        assertEquals(firstCity, (editor.draft.cards[0] as WeatherCardConfig).city)
        assertEquals(firstCity, (editor.draft.cards[1] as WeatherCardConfig).city)
    }

    @Test
    fun updatingOneDuplicateDateCardLeavesTheOtherUntouched() {
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(
                cards = listOf(
                    DateTimeCardConfig("first"),
                    DateTimeCardConfig("second"),
                ),
            ),
            SequenceIds(),
        )

        editor.updateDateTime("second") { it.copy(showLunarCalendar = true) }

        assertFalse((editor.draft.cards[0] as DateTimeCardConfig).showLunarCalendar)
        assertTrue((editor.draft.cards[1] as DateTimeCardConfig).showLunarCalendar)
    }

    @Test
    fun invalidIdsTypesAndIndicesFailLoudly() {
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(cards = listOf(WalletCardConfig("wallet"))),
            SequenceIds(),
        )

        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                editor.removeCard("missing")
            }.message!!.contains("missing"),
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                editor.updateWeather("wallet") { it }
            }.message!!.contains("Weather"),
        )
        assertTrue(
            assertThrows(IndexOutOfBoundsException::class.java) {
                editor.moveCard(0, 1)
            }.message!!.contains("index"),
        )
    }
}
