package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import kotlinx.serialization.Serializable

internal val HITOKOTO_CATEGORY_CODES = ('a'..'l').map(Char::toString).toSet()

data class HomeSidePanelUiState(
    val profile: HomeSidePanelProfile,
    val weather: WeatherUiState,
    val weatherSettings: WeatherSettingsUiState,
    val hitokoto: HitokotoUiState,
    val hitokotoSettings: HitokotoSettings,
    val cardMode: HomeSidePanelCardMode,
)

enum class HomeSidePanelCardMode {
    CONTENT,
    WEATHER_SETTINGS,
    HITOKOTO_SETTINGS,
}

data class HomeSidePanelProfile(
    val wxId: String,
    val nickname: String,
    val avatarUrl: String,
    val status: HomeSidePanelStatusUiState,
)

sealed interface HomeSidePanelStatusUiState {
    data object Loading : HomeSidePanelStatusUiState
    data class Ready(val status: HomeSidePanelStatus) : HomeSidePanelStatusUiState
    data object NoStatus : HomeSidePanelStatusUiState
    data class Error(val message: String) : HomeSidePanelStatusUiState
}

data class HomeSidePanelStatus(
    val statusId: String,
    val description: String,
    val iconId: String,
    val emoji: HomeSidePanelStatusEmoji?,
)

data class HomeSidePanelStatusEmoji(
    val md5: String?,
    val url: String?,
    val thumbUrl: String?,
    val attachedText: String?,
)

@Serializable
data class WeatherCity(
    val countryCode: String,
    val province: String,
    val city: String,
    val district: String?,
    val cityNum: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

internal val DEFAULT_WEATHER_CITY = WeatherCity(
    countryCode = "CN",
    province = "北京",
    city = "北京",
    district = null,
    cityNum = "101010100",
)

@Serializable
data class WeatherSnapshot(
    val city: WeatherCity,
    val weatherCode: String,
    val temperature: String,
    val feelsLike: String,
    val high: String,
    val low: String,
    val humidity: String,
    val windSpeed: String,
    val publishedAt: String,
    val fetchedAt: Long,
)

sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Ready(
        val snapshot: WeatherSnapshot,
        val refreshing: Boolean = false,
    ) : WeatherUiState

    data class Error(
        val message: String,
        val cached: WeatherSnapshot?,
    ) : WeatherUiState
}

sealed interface WeatherResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherResult
    data class Error(val message: String, val cached: WeatherSnapshot?) : WeatherResult
}

sealed interface WeatherCityMatchResult {
    data class Success(val city: WeatherCity) : WeatherCityMatchResult
    data class Error(val reason: WeatherCityMatchFailure) : WeatherCityMatchResult
}

enum class WeatherCityMatchFailure(val message: String) {
    UNSUPPORTED_COUNTRY("不支持的资料地区"),
    MISSING_REGION("个人资料中没有地区"),
    MISSING_CITY("个人资料中没有城市"),
    NO_MATCH("无法在天气城市库中匹配该城市"),
    READ_ERROR("读取个人资料失败"),
}

data class WeatherSettingsUiState(
    val selectedCity: WeatherCity = DEFAULT_WEATHER_CITY,
    val searchQuery: String = "",
    val searchResults: List<WeatherCity> = emptyList(),
    val actionInProgress: Boolean = false,
    val message: String? = null,
)

@Serializable
data class HitokotoSnapshot(
    val uuid: String,
    val text: String,
    val type: String?,
    val source: String?,
    val author: String?,
    val creator: String?,
    val createdAt: String?,
    val fetchedAt: Long,
)

@Serializable
data class HitokotoSettings(
    val categories: Set<String> = HITOKOTO_CATEGORY_CODES,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val charset: String = "utf-8",
    val showSource: Boolean = true,
    val showAuthor: Boolean = true,
)

sealed interface HitokotoUiState {
    data object Loading : HitokotoUiState
    data class Ready(
        val snapshot: HitokotoSnapshot,
        val refreshing: Boolean = false,
    ) : HitokotoUiState

    data class Error(
        val message: String,
        val cached: HitokotoSnapshot?,
    ) : HitokotoUiState
}

sealed interface HitokotoResult {
    data class Success(val snapshot: HitokotoSnapshot) : HitokotoResult
    data class Error(val message: String, val cached: HitokotoSnapshot?) : HitokotoResult
}
