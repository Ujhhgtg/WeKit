package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeSidePanelShortcutMappingTest {

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
}
