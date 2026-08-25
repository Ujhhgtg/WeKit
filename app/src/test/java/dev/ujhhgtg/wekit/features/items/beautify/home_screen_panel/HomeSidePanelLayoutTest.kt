package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
                HomeSidePanelCardType.VERTICAL_ACTIONS,
                HomeSidePanelCardType.HITOKOTO,
            ),
            layout.cards.map { it.type },
        )
        assertEquals("101020100", (layout.cards[1] as WeatherCardConfig).city.cityNum)
        assertTrue((layout.cards[2] as WalletCardConfig).hideBalanceByDefault)
        assertEquals(
            listOf(
                HomeSidePanelActionKind.ADD_FRIEND,
                HomeSidePanelActionKind.MOMENTS,
                HomeSidePanelActionKind.CHANNELS,
                HomeSidePanelActionKind.MARK_ALL_READ,
                HomeSidePanelActionKind.WEKIT_SETTINGS,
            ),
            (layout.cards[3] as VerticalActionsCardConfig).actions.map { it.kind },
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
    fun dateTimeLunarSettingDefaultsOffAndRoundTripsWhenEnabled() {
        val legacyRaw = """
            {
              "version": 1,
              "cards": [
                {"cardType": "date_time", "id": "legacy-date"}
              ]
            }
        """.trimIndent()

        assertFalse(
            (HomeSidePanelLayoutCodec.decode(legacyRaw).cards.single() as DateTimeCardConfig)
                .showLunarCalendar,
        )

        val enabled = HomeSidePanelLayout(
            cards = listOf(DateTimeCardConfig("date", showLunarCalendar = true)),
        )
        assertEquals(
            enabled,
            HomeSidePanelLayoutCodec.decode(HomeSidePanelLayoutCodec.encode(enabled)),
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

    @Test
    fun validationRejectsUnsupportedVersionAndBlankCardId() {
        assertThrows(InvalidHomeSidePanelLayoutException::class.java) {
            validateHomeSidePanelLayout(HomeSidePanelLayout(version = 2, cards = emptyList()))
        }
        assertThrows(InvalidHomeSidePanelLayoutException::class.java) {
            validateHomeSidePanelLayout(
                HomeSidePanelLayout(cards = listOf(DateTimeCardConfig(" "))),
            )
        }
    }

    @Test
    fun validationRejectsBlankAndDuplicateActionIdsWithinOneCard() {
        assertThrows(InvalidHomeSidePanelLayoutException::class.java) {
            validateHomeSidePanelLayout(
                HomeSidePanelLayout(
                    cards = listOf(
                        HorizontalActionsCardConfig(
                            "actions",
                            listOf(HomeSidePanelActionConfig("", HomeSidePanelActionKind.SCAN)),
                        ),
                    ),
                ),
            )
        }
        assertThrows(InvalidHomeSidePanelLayoutException::class.java) {
            validateHomeSidePanelLayout(
                HomeSidePanelLayout(
                    cards = listOf(
                        HorizontalActionsCardConfig(
                            "actions",
                            listOf(
                                HomeSidePanelActionConfig("same", HomeSidePanelActionKind.SCAN),
                                HomeSidePanelActionConfig("same", HomeSidePanelActionKind.WALLET),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun validationRejectsInvalidHitokotoSettings() {
        assertThrows(InvalidHomeSidePanelLayoutException::class.java) {
            validateHomeSidePanelLayout(
                HomeSidePanelLayout(
                    cards = listOf(
                        HitokotoCardConfig(
                            "hitokoto",
                            HitokotoSettings(categories = emptySet()),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun semanticallyInvalidJsonBecomesFallback() {
        val raw = """{"version":2,"cards":[]}"""

        assertThrows(InvalidHomeSidePanelLayoutException::class.java) {
            HomeSidePanelLayoutCodec.decode(raw)
        }
        val fallback = HomeSidePanelLayoutCodec.load(
            raw,
            LegacyHomeSidePanelSnapshot.defaults(),
            SequenceIds(),
        ) as HomeSidePanelLayoutLoad.Fallback

        assertEquals(raw, fallback.invalidRaw)
    }

    @Test
    fun legacyRedundantTypeCannotOverrideTheSealedCardSubtype() {
        val raw = """
            {
              "version": 1,
              "cards": [
                {
                  "cardType": "weather",
                  "id": "weather",
                  "city": {
                    "countryCode": "CN",
                    "province": "\u5317\u4eac",
                    "city": "\u5317\u4eac",
                    "district": null,
                    "cityNum": "101010100",
                    "latitude": null,
                    "longitude": null
                  },
                  "type": "WALLET"
                }
              ]
            }
        """.trimIndent()

        val decoded = HomeSidePanelLayoutCodec.decode(raw)
        val card = decoded.cards.single()

        assertTrue(card is WeatherCardConfig)
        assertEquals(HomeSidePanelCardType.WEATHER, card.type)
        val reencoded = HomeSidePanelLayoutCodec.encode(decoded)
        assertFalse("\"type\"" in reencoded)
        assertEquals(decoded, HomeSidePanelLayoutCodec.decode(reencoded))
    }

    @Test
    fun discriminatorAndPayloadShapeMismatchBecomesFallback() {
        val raw = """
            {
              "version": 1,
              "cards": [
                {
                  "cardType": "weather",
                  "id": "mismatched",
                  "hideBalanceByDefault": true,
                  "type": "WALLET"
                }
              ]
            }
        """.trimIndent()

        val loaded = HomeSidePanelLayoutCodec.load(
            raw = raw,
            legacy = LegacyHomeSidePanelSnapshot.defaults(),
            idGenerator = SequenceIds(),
        )

        assertTrue(loaded is HomeSidePanelLayoutLoad.Fallback)
        assertEquals(raw, (loaded as HomeSidePanelLayoutLoad.Fallback).invalidRaw)
    }
}
