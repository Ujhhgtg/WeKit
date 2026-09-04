package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import java.time.LocalDateTime
import java.time.ZoneId

data class HomeSidePanelLunarDate(
    val month: Int,
    val day: Int,
    val isLeapMonth: Boolean,
) {
    init {
        require(month in 1..12) { "Lunar month must be in 1..12" }
        require(day in 1..30) { "Lunar day must be in 1..30" }
    }
}

data class HomeSidePanelLunarDateText(
    val prefix: String,
    val leapPrefix: String,
    val separator: String,
    val monthNames: List<String>,
    val dayNames: List<String>,
) {
    init {
        require(monthNames.size == 12) { "Lunar month names must contain 12 entries" }
        require(dayNames.size == 30) { "Lunar day names must contain 30 entries" }
    }
}

fun formatHomeSidePanelLunarDate(
    date: HomeSidePanelLunarDate,
    text: HomeSidePanelLunarDateText,
): String = buildString {
    append(text.prefix)
    if (date.isLeapMonth) append(text.leapPrefix)
    append(text.monthNames[date.month - 1])
    append(text.separator)
    append(text.dayNames[date.day - 1])
}

fun homeSidePanelLunarDate(dateTime: LocalDateTime): HomeSidePanelLunarDate {
    val calendar = ChineseCalendar().apply {
        timeInMillis = dateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    return HomeSidePanelLunarDate(
        month = calendar.get(Calendar.MONTH) + 1,
        day = calendar.get(Calendar.DAY_OF_MONTH),
        isLeapMonth = calendar.get(Calendar.IS_LEAP_MONTH) == 1,
    )
}
