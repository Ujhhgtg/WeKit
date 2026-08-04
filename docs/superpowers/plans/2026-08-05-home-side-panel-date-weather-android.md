# HomeSidePanel Date And Weather Cards Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split HomeSidePanel's combined date/weather Compose card into a display-only date/time card and a redesigned interactive weather-only card matching the approved prototype.

**Architecture:** Keep the change inside the existing `HomeSidePanelUi.kt`. Reuse `rememberHomeSidePanelNow()`, `WeatherUiState`, `WeatherSnapshot`, the existing weather icon mapping, and the existing refresh/settings callbacks; add only small same-file Composables for the repeated weather metric layout.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Material Symbols Outlined

## Global Constraints

- Do not change weather networking, persistence, location, profile-city detection, or navigation behavior.
- The date/time card is display-only and contains no clickable modifier.
- The weather card keeps click-to-refresh and long-press-to-settings behavior.
- Use existing Material 3 theme colors so light and dark mode remain automatic.
- Do not add test-only abstractions or low-value unit tests for static Compose arrangement.
- Validate through compilation with `./x build`, `git diff --check`, and subsequent physical-device visual verification.

---

### Task 1: Split and redesign the Compose cards

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelUi.kt`
- Modify: `docs/superpowers/specs/2026-08-04-home-side-panel-weather-card-design.md`

**Interfaces:**
- Consumes: `WeatherUiState`, `WeatherSnapshot`, `HomeSidePanelState.refreshWeather()`, `HomeSidePanelState.openWeatherSettings()`, `rememberHomeSidePanelNow()`
- Produces: `HomeSidePanelDateTimeCard()`, redesigned `HomeSidePanelWeatherCard()`, and `HomeSidePanelWeatherMetric()`

- [x] **Step 1: Add the date/time card to the home content order**

Insert `HomeSidePanelDateTimeCard()` between the profile header and weather card. Implement it as a `Card` using `surfaceContainerLow` and `RoundedCornerShape(24.dp)`. Its first row uses `Alignment.Bottom`, with large `HH:mm` at left and the small date padded `start = 10.dp, bottom = 5.dp`; the greeting occupies the next row. Do not add `clickable` or `combinedClickable`.

- [x] **Step 2: Replace the weather card content with the approved layout**

Keep the existing clipped `combinedClickable` modifier and `primaryContainer`. Render:

```text
city / district                         update time
current temperature              outlined icon
feels-like temperature          weather label
-------------------------------------------------
high / low          humidity          wind speed
```

Use `Location_on`, `Device_thermostat`, `Humidity_percentage`, and `Air` from `MaterialSymbols.Outlined`. Give the current temperature the strongest typography, keep city/condition single-line with ellipsis, and reserve stable height for loading and refresh states.

- [x] **Step 3: Add weather label and metric helpers in the same file**

Map the existing `WeatherIconKind` values to short Chinese labels:

```kotlin
SUNNY -> "晴"
CLOUDY -> "多云"
RAIN -> "雨"
SNOW -> "雪"
FOG -> "雾"
THUNDER -> "雷雨"
UNKNOWN -> "未知"
```

Implement `HomeSidePanelWeatherMetric(icon, value, label, modifier)` once and reuse it for the three equal-width bottom metrics. Do not introduce a model, repository, interface, or new source file.

- [x] **Step 4: Compile and inspect the scoped change**

Run:

```bash
./x build
git diff --check
```

Inspect the resulting source to confirm the date/time card has no interaction modifier, weather callbacks remain unchanged, and no unrelated dirty-worktree files were reverted or staged. Report that physical-device visual behavior still requires user verification.
