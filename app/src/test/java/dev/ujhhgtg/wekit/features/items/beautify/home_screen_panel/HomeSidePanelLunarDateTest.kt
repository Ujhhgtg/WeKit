package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeSidePanelLunarDateTest {

    @Test
    fun simplifiedChineseFormatsOrdinaryLunarDate() {
        assertEquals(
            "农历七月初八",
            formatHomeSidePanelLunarDate(
                date = HomeSidePanelLunarDate(month = 7, day = 8, isLeapMonth = false),
                text = chineseText(prefix = "农历", leapPrefix = "闰"),
            ),
        )
    }

    @Test
    fun traditionalChineseFormatsLeapMonth() {
        assertEquals(
            "農曆閏六月初八",
            formatHomeSidePanelLunarDate(
                date = HomeSidePanelLunarDate(month = 6, day = 8, isLeapMonth = true),
                text = chineseText(prefix = "農曆", leapPrefix = "閏"),
            ),
        )
    }

    @Test
    fun englishFormatsLocalizedMonthAndDay() {
        assertEquals(
            "Lunar 7th month, day 8",
            formatHomeSidePanelLunarDate(
                date = HomeSidePanelLunarDate(month = 7, day = 8, isLeapMonth = false),
                text = HomeSidePanelLunarDateText(
                    prefix = "Lunar ",
                    leapPrefix = "leap ",
                    separator = ", ",
                    monthNames = (1..12).map { "$it month" }.toMutableList().also {
                        it[6] = "7th month"
                    },
                    dayNames = (1..30).map { "day $it" },
                ),
            ),
        )
    }

    private fun chineseText(prefix: String, leapPrefix: String) =
        HomeSidePanelLunarDateText(
            prefix = prefix,
            leapPrefix = leapPrefix,
            separator = "",
            monthNames = listOf(
                "正月", "二月", "三月", "四月", "五月", "六月",
                "七月", "八月", "九月", "十月", "冬月", "腊月",
            ),
            dayNames = listOf(
                "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
                "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
                "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
            ),
        )
}
