package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeSidePanelShortcutMappingTest {

    @Test
    fun accountHeaderUsesTheNicknameInsteadOfTheWxId() {
        val profile = HomeSidePanelProfile(
            wxId = "wxid_should_not_be_rendered",
            nickname = "真实昵称",
            avatarUrl = "",
            status = HomeSidePanelStatusUiState.NoStatus,
        )

        assertEquals("真实昵称", homeSidePanelProfileDisplayName(profile))
    }

    @Test
    fun hitokotoAttributionUsesTheApprovedChineseBookTitleFormat() {
        assertEquals("—— 作者「出处」", homeSidePanelAttribution(author = "作者", source = "出处"))
        assertEquals("—— 作者", homeSidePanelAttribution(author = "作者", source = null))
        assertEquals("——「出处」", homeSidePanelAttribution(author = null, source = "出处"))
        assertNull(homeSidePanelAttribution(author = null, source = null))
    }

    @Test
    fun everyShortcutHasTheApprovedLabelAndSemanticIcon() {
        assertEquals(
            listOf(
                Triple(HomeSidePanelShortcut.SCAN, "扫一扫", HomeSidePanelIconKind.QR_CODE_SCANNER),
                Triple(HomeSidePanelShortcut.PAYMENTS, "收付款", HomeSidePanelIconKind.PAYMENTS),
                Triple(HomeSidePanelShortcut.FAVORITES, "收藏", HomeSidePanelIconKind.COLLECTIONS_BOOKMARK),
                Triple(HomeSidePanelShortcut.MOMENTS, "朋友圈", HomeSidePanelIconKind.PHOTO_LIBRARY),
                Triple(HomeSidePanelShortcut.VIDEO_CHANNELS, "视频号", HomeSidePanelIconKind.VIDEO_LIBRARY),
                Triple(HomeSidePanelShortcut.MARK_ALL_READ, "清空未读", HomeSidePanelIconKind.MARK_EMAIL_READ),
                Triple(HomeSidePanelShortcut.WEKIT_SETTINGS, "WeKit 设置", HomeSidePanelIconKind.SETTINGS),
            ),
            HomeSidePanelShortcut.entries.map {
                shortcutSpec(it).let { spec -> Triple(spec.shortcut, spec.label, spec.icon) }
            },
        )
    }

    @Test
    fun scanPaymentsAndFavoritesRemainTilesWhileTheRestRemainListItems() {
        assertEquals(
            listOf(
                HomeSidePanelShortcut.SCAN,
                HomeSidePanelShortcut.PAYMENTS,
                HomeSidePanelShortcut.FAVORITES,
            ),
            HomeSidePanelShortcut.entries.filter { shortcutSpec(it).placement == HomeSidePanelShortcutPlacement.TILE },
        )
        assertEquals(
            listOf(
                HomeSidePanelShortcut.MOMENTS,
                HomeSidePanelShortcut.VIDEO_CHANNELS,
                HomeSidePanelShortcut.MARK_ALL_READ,
                HomeSidePanelShortcut.WEKIT_SETTINGS,
            ),
            HomeSidePanelShortcut.entries.filter { shortcutSpec(it).placement == HomeSidePanelShortcutPlacement.LIST_ITEM },
        )
    }

    @Test
    fun cachedWeatherFailureKeepsContentWithoutAnInlineFailureMessage() {
        val weather = WeatherSnapshot(
            city = DEFAULT_WEATHER_CITY,
            weatherCode = "0",
            temperature = "20",
            feelsLike = "20",
            high = "25",
            low = "15",
            humidity = "50",
            windSpeed = "2",
            publishedAt = "",
            fetchedAt = 0L,
        )
        val quote = HitokotoSnapshot("id", "一句话", null, null, null, null, null, 0L)
        assertEquals(weather, weatherCardSnapshot(WeatherUiState.Error("天气请求超时", weather)))
        assertEquals("一言服务不可用", hitokotoCardErrorMessage(HitokotoUiState.Error("一言服务不可用", quote)))
    }

    @Test
    fun tabCallbacksAreScopedToTheirAdapter() {
        val adapter = Any()
        assertEquals(true, homeSidePanelOwnsTabsAdapter(adapter, adapter))
        assertEquals(false, homeSidePanelOwnsTabsAdapter(adapter, Any()))
    }
}
