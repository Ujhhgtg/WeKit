package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.icu.text.Transliterator
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.beautify.BeautifyText
import dev.ujhhgtg.wekit.features.items.beautify.beautifyText
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class WeatherCity(
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
internal data class WeatherSnapshot(
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

internal sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Ready(
        val snapshot: WeatherSnapshot,
        val refreshing: Boolean = false,
    ) : WeatherUiState

    data class Error(
        val message: BeautifyText,
        val cached: WeatherSnapshot?,
    ) : WeatherUiState
}

internal sealed interface WeatherResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherResult
    data class Error(val message: BeautifyText, val cached: WeatherSnapshot?) : WeatherResult
}

internal sealed interface WeatherCityMatchResult {
    data class Success(val city: WeatherCity) : WeatherCityMatchResult
    data class Error(val reason: WeatherCityMatchFailure) : WeatherCityMatchResult
}

internal enum class WeatherCityMatchFailure(@StringRes val messageRes: Int) {
    UNSUPPORTED_COUNTRY(R.string.home_side_panel_weather_unsupported_country),
    MISSING_REGION(R.string.home_side_panel_weather_missing_region),
    MISSING_CITY(R.string.home_side_panel_weather_missing_city),
    NO_MATCH(R.string.home_side_panel_weather_no_match),
    READ_ERROR(R.string.home_side_panel_weather_profile_read_error),
}

internal data class WeatherSettingsUiState(
    val selectedCity: WeatherCity = DEFAULT_WEATHER_CITY,
    val searchQuery: String = "",
    val searchResults: List<WeatherCity> = emptyList(),
    val actionInProgress: Boolean = false,
)

internal fun isEligibleWeatherCountry(code: String): Boolean =
    code.trim().uppercase() in setOf("CN", "HK", "MO", "TW")

enum class WeatherIconKind {
    SUNNY,
    PARTLY_CLOUDY,
    OVERCAST,
    SHOWER,
    THUNDERSTORM,
    HAIL,
    SLEET,
    LIGHT_RAIN,
    RAIN,
    HEAVY_RAIN,
    RAINSTORM,
    SNOW_SHOWER,
    LIGHT_SNOW,
    SNOW,
    HEAVY_SNOW,
    BLIZZARD,
    FOG,
    FREEZING_RAIN,
    DUST_STORM,
    DUST,
    SAND,
    SQUALL,
    TORNADO,
    HAZE,
    UNKNOWN,
}

internal fun weatherIconKind(code: String): WeatherIconKind = when (code.toIntOrNull()) {
    0 -> WeatherIconKind.SUNNY
    1 -> WeatherIconKind.PARTLY_CLOUDY
    2 -> WeatherIconKind.OVERCAST
    3 -> WeatherIconKind.SHOWER
    4 -> WeatherIconKind.THUNDERSTORM
    5 -> WeatherIconKind.HAIL
    6 -> WeatherIconKind.SLEET
    7 -> WeatherIconKind.LIGHT_RAIN
    8 -> WeatherIconKind.RAIN
    9 -> WeatherIconKind.HEAVY_RAIN
    10, 11, 12 -> WeatherIconKind.RAINSTORM
    13 -> WeatherIconKind.SNOW_SHOWER
    14 -> WeatherIconKind.LIGHT_SNOW
    15 -> WeatherIconKind.SNOW
    16 -> WeatherIconKind.HEAVY_SNOW
    17 -> WeatherIconKind.BLIZZARD
    18 -> WeatherIconKind.FOG
    19 -> WeatherIconKind.FREEZING_RAIN
    20 -> WeatherIconKind.DUST_STORM
    21, 22 -> WeatherIconKind.RAIN
    23 -> WeatherIconKind.HEAVY_RAIN
    24, 25 -> WeatherIconKind.RAINSTORM
    26 -> WeatherIconKind.SNOW
    27 -> WeatherIconKind.HEAVY_SNOW
    28 -> WeatherIconKind.BLIZZARD
    29 -> WeatherIconKind.DUST
    30 -> WeatherIconKind.SAND
    31 -> WeatherIconKind.DUST_STORM
    32 -> WeatherIconKind.SQUALL
    33 -> WeatherIconKind.TORNADO
    34 -> WeatherIconKind.BLIZZARD
    35 -> WeatherIconKind.FOG
    53 -> WeatherIconKind.HAZE
    else -> WeatherIconKind.UNKNOWN
}

private fun buildWeatherUrl(city: WeatherCity): HttpUrl = WEATHER_ENDPOINT.toHttpUrl()
    .newBuilder()
    .addQueryParameter("latitude", "0")
    .addQueryParameter("longitude", "0")
    .addQueryParameter("locationKey", "weathercn:${city.cityNum}")
    .addQueryParameter("sign", WEATHER_SIGN)
    .addQueryParameter("isGlobal", "false")
    .addQueryParameter("locale", "zh_cn")
    .addQueryParameter("days", "5")
    .addQueryParameter("appKey", WEATHER_APP_KEY)
    .build()

private fun parseWeatherPayload(
    city: WeatherCity,
    payload: String,
    fetchedAt: Long,
): WeatherSnapshot {
    val decoded = DefaultJson.decodeFromString<XiaomiWeatherPayload>(payload)
    val current = decoded.current ?: throw InvalidWeatherPayloadException("缺少当前天气")
    val daily = decoded.forecastDaily?.temperature?.value?.firstOrNull()
        ?: throw InvalidWeatherPayloadException("缺少每日天气")
    return WeatherSnapshot(
        city = city,
        weatherCode = current.weather.requireWeatherValue("天气代码"),
        temperature = current.temperature?.value.requireWeatherValue("当前温度"),
        feelsLike = current.feelsLike?.value.requireWeatherValue("体感温度"),
        high = daily.from.requireWeatherValue("最高温度"),
        low = daily.to.requireWeatherValue("最低温度"),
        humidity = current.humidity?.value.requireWeatherValue("湿度"),
        windSpeed = current.wind?.speed?.value.requireWeatherValue("风速"),
        publishedAt = current.pubTime.requireWeatherValue("发布时间"),
        fetchedAt = fetchedAt,
    )
}

internal class HomeSidePanelWeather(
    private val cityIndex: HomeSidePanelCityIndex,
    private val client: OkHttpClient,
) {

    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = HomeSidePanelRequestPool<String, WeatherResult>(requestScope)
    private val lastRequestStartedAt = ConcurrentHashMap<String, Long>()

    suspend fun refresh(
        city: WeatherCity,
        cached: WeatherSnapshot?,
    ): WeatherResult {
        if (city.cityNum.isBlank()) {
            return WeatherResult.Error(beautifyText(R.string.home_side_panel_weather_no_city), cached)
        }
        val requestKey = city.cityNum
        return requests.await(requestKey) request@{
            val now = System.currentTimeMillis()
            val previousStart = lastRequestStartedAt[requestKey]
            if (previousStart != null && now - previousStart < MIN_REFRESH_INTERVAL_MS) {
                return@request if (cached?.city?.cityNum == requestKey) {
                    WeatherResult.Success(cached)
                } else {
                    WeatherResult.Error(beautifyText(R.string.home_side_panel_refresh_too_frequent), cached)
                }
            }
            lastRequestStartedAt[requestKey] = now
            try {
                performRefresh(city, cached)
            } catch (error: CancellationException) {
                lastRequestStartedAt.remove(requestKey, now)
                throw error
            }
        }
    }

    suspend fun searchCities(query: String): List<WeatherCity> = cityIndex.search(query)

    fun close() {
        requests.close()
        requestScope.cancel()
    }

    private suspend fun performRefresh(
        city: WeatherCity,
        cached: WeatherSnapshot?,
    ): WeatherResult {
        val request = Request.Builder().url(buildWeatherUrl(city)).get().build()
        return try {
            val payload = client.newCall(request).awaitWeatherPayload()
            val snapshot = parseWeatherPayload(city, payload, System.currentTimeMillis())
            WeatherResult.Success(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: WeatherHttpException) {
            WeLogger.w(TAG, "weather request failed with HTTP ${error.code}")
            WeatherResult.Error(beautifyText(R.string.home_side_panel_weather_http_error, error.code), cached)
        } catch (error: SocketTimeoutException) {
            WeLogger.w(TAG, "weather request timed out", error)
            WeatherResult.Error(beautifyText(R.string.home_side_panel_weather_timeout), cached)
        } catch (error: InvalidWeatherPayloadException) {
            WeLogger.w(TAG, "weather payload is incomplete", error)
            WeatherResult.Error(beautifyText(R.string.home_side_panel_weather_incomplete), cached)
        } catch (error: SerializationException) {
            WeLogger.w(TAG, "weather payload is malformed", error)
            WeatherResult.Error(beautifyText(R.string.home_side_panel_weather_invalid), cached)
        } catch (error: IOException) {
            WeLogger.w(TAG, "weather request failed", error)
            WeatherResult.Error(beautifyText(R.string.home_side_panel_weather_connection_failed), cached)
        }
    }

    private companion object {
        const val TAG = "HomeSidePanelWeather"
        const val MIN_REFRESH_INTERVAL_MS = 1_000L
    }

}

@Serializable
private data class XiaomiWeatherPayload(
    val current: XiaomiCurrentWeather? = null,
    val forecastDaily: XiaomiDailyForecast? = null,
)

@Serializable
private data class XiaomiCurrentWeather(
    val feelsLike: XiaomiWeatherValue? = null,
    val humidity: XiaomiWeatherValue? = null,
    val temperature: XiaomiWeatherValue? = null,
    val weather: String = "",
    val wind: XiaomiWind? = null,
    val pubTime: String = "",
)

@Serializable
private data class XiaomiWeatherValue(
    val value: String = "",
)

@Serializable
private data class XiaomiWind(
    val speed: XiaomiWeatherValue? = null,
)

@Serializable
private data class XiaomiDailyForecast(
    val temperature: XiaomiDailyTemperature? = null,
)

@Serializable
private data class XiaomiDailyTemperature(
    val value: List<XiaomiDailyRange> = emptyList(),
)

@Serializable
private data class XiaomiDailyRange(
    val from: String = "",
    val to: String = "",
)

private class InvalidWeatherPayloadException(message: String) : IllegalArgumentException(message)

private class WeatherHttpException(val code: Int) : IOException()

private fun String?.requireWeatherValue(name: String): String =
    this?.takeIf(String::isNotBlank) ?: throw InvalidWeatherPayloadException("缺少$name")

private suspend fun Call.awaitWeatherPayload(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!continuation.isActive) return
                if (!it.isSuccessful) {
                    continuation.resumeWithException(WeatherHttpException(it.code))
                } else {
                    continuation.resume(it.body.string())
                }
            }
        }
    })
}

private const val WEATHER_ENDPOINT = "https://weatherapi.market.xiaomi.com/wtr-v3/weather/all"
private const val WEATHER_SIGN = "zUFJoAR2ZVrDy1vF3D07"
private const val WEATHER_APP_KEY = "weather20151024"

private val cityQueryTransliterator: Transliterator? by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Transliterator.getInstance("Han-Latin; Latin-ASCII")
    } else {
        null
    }
}

@SuppressLint("NewApi") // guarded by cityQueryTransliterator
private fun transliterateCityQuery(value: String): String =
    cityQueryTransliterator?.let { synchronized(it) { it.transliterate(value) } } ?: value

internal class HomeSidePanelCityMatcher(
    private val cities: List<WeatherCity>,
) {

    fun search(query: String): List<WeatherCity> {
        val normalizedQuery = normalizeSearchValue(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        return cities.asSequence()
            .filter { city ->
                city.searchValues().any { value ->
                    normalizeSearchValue(value).contains(normalizedQuery) ||
                        normalizeSearchValue(transliterateCityQuery(value)).contains(normalizedQuery)
                }
            }
            .distinctBy { it.labelKey() }
            .toList()
    }

    fun matchProfile(
        countryCode: String,
        province: String,
        city: String,
    ): WeatherCityMatchResult {
        if (!isEligibleWeatherCountry(countryCode)) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.UNSUPPORTED_COUNTRY)
        }
        if (province.isBlank()) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.MISSING_REGION)
        }
        if (city.isBlank()) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.MISSING_CITY)
        }
        return match(
            countryCode = countryCode.trim().uppercase(),
            province = province,
            city = city,
        )
    }

    fun matchLocation(province: String, city: String): WeatherCityMatchResult {
        if (city.isBlank()) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.MISSING_CITY)
        }
        return match(countryCode = null, province = province, city = city)
    }

    private fun match(
        countryCode: String?,
        province: String,
        city: String,
    ): WeatherCityMatchResult {
        val normalizedProvince = normalizeRegionValue(province)
        val normalizedCity = normalizeRegionValue(city)
        val countryMatches: (WeatherCity) -> Boolean = { candidate ->
            countryCode == null || candidate.countryCode == countryCode
        }
        val provinceMatches: (WeatherCity) -> Boolean = { candidate ->
            normalizeRegionValue(candidate.province) == normalizedProvince
        }
        val matched = cities.firstOrNull { candidate ->
            countryMatches(candidate) && provinceMatches(candidate) &&
                candidate.normalizedCombinedCity() == normalizedCity
        } ?: cities.firstOrNull { candidate ->
            countryMatches(candidate) && provinceMatches(candidate) &&
                normalizeRegionValue(candidate.city) == normalizedCity
        } ?: cities.firstOrNull { candidate ->
            countryMatches(candidate) && candidate.matchesCityValue(normalizedCity)
        } ?: cities.firstOrNull { candidate ->
            candidate.matchesCityValue(normalizedCity)
        }
        return matched?.let(WeatherCityMatchResult::Success)
            ?: WeatherCityMatchResult.Error(WeatherCityMatchFailure.NO_MATCH)
    }

    private fun WeatherCity.matchesCityValue(value: String): Boolean =
        normalizedCombinedCity() == value ||
            normalizeRegionValue(city) == value ||
            district?.let(::normalizeRegionValue) == value

    private fun WeatherCity.normalizedCombinedCity(): String =
        normalizeRegionValue(city + district.orEmpty())

    private fun WeatherCity.searchValues(): List<String> = listOf(
        province,
        city,
        city + district.orEmpty(),
        province + city + district.orEmpty(),
    )

    private fun WeatherCity.labelKey(): String =
        listOf(countryCode, province, city, district.orEmpty()).joinToString("\u0000")
}

internal class HomeSidePanelCityIndex(context: Context) {

    private val appContext = context.applicationContext
    private val matcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HomeSidePanelCityMatcher(loadCities())
    }

    suspend fun search(query: String): List<WeatherCity> = withContext(Dispatchers.IO) {
        matcher.search(query)
    }

    suspend fun matchProfile(
        countryCode: String,
        province: String,
        city: String,
    ): WeatherCityMatchResult = withContext(Dispatchers.IO) {
        matcher.matchProfile(countryCode, province, city)
    }

    suspend fun matchLocation(
        province: String,
        city: String,
    ): WeatherCityMatchResult = withContext(Dispatchers.IO) {
        matcher.matchLocation(province, city)
    }

    private fun loadCities(): List<WeatherCity> {
        val databaseFile = copyDatabaseAssetOnce()
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        return database.use { db ->
            db.rawQuery(CITY_QUERY, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val provinceId = cursor.getInt(0)
                        val rawName = cursor.getString(1)
                        val separatorIndex = rawName.indexOf('.')
                        val city = if (separatorIndex < 0) rawName else rawName.substring(0, separatorIndex)
                        val district = if (separatorIndex < 0) null else rawName.substring(separatorIndex + 1)
                        add(
                            WeatherCity(
                                countryCode = provinceId.toCountryCode(),
                                province = cursor.getString(3),
                                city = city,
                                district = district,
                                cityNum = cursor.getString(2),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun copyDatabaseAssetOnce(): File {
        val directory = File(appContext.noBackupFilesDir, ASSET_DIRECTORY).apply { mkdirs() }
        val databaseFile = File(directory, ASSET_FILE_NAME)
        if (!databaseFile.exists()) {
            ResourcesInjector.injectModuleRes(appContext.resources)
            appContext.assets.open(ASSET_PATH).use { input ->
                databaseFile.outputStream().use(input::copyTo)
            }
        }
        return databaseFile
    }

    private fun Int.toCountryCode(): String = when (this) {
        31 -> "HK"
        32 -> "MO"
        33 -> "TW"
        else -> "CN"
    }

    private companion object {
        const val ASSET_DIRECTORY = "home_side_panel"
        const val ASSET_FILE_NAME = "xiaomi_weather.db"
        const val ASSET_PATH = "$ASSET_DIRECTORY/$ASSET_FILE_NAME"
        const val CITY_QUERY = """
            SELECT c.province_id, c.name, c.city_num, p.name AS province
            FROM citys c
            LEFT JOIN provinces p ON p._id = c.province_id + 1
            ORDER BY c._id
        """
    }
}

internal sealed interface LocationResolution {
    data object NeedPermission : LocationResolution
    data object LocationDisabled : LocationResolution
    data object Timeout : LocationResolution
    data object GeocoderFailed : LocationResolution
    data object CityNotFound : LocationResolution
    data class Success(val city: WeatherCity) : LocationResolution
    data class Error(val message: BeautifyText) : LocationResolution
}

internal fun locationResolutionMessage(resolution: LocationResolution): BeautifyText = when (resolution) {
    LocationResolution.NeedPermission -> beautifyText(R.string.home_side_panel_location_permission_needed)
    LocationResolution.LocationDisabled -> beautifyText(R.string.home_side_panel_location_disabled)
    LocationResolution.Timeout -> beautifyText(R.string.home_side_panel_location_timeout)
    LocationResolution.GeocoderFailed -> beautifyText(R.string.home_side_panel_geocoder_failed)
    LocationResolution.CityNotFound -> beautifyText(R.string.home_side_panel_location_city_not_found)
    is LocationResolution.Success -> BeautifyText.Raw("")
    is LocationResolution.Error -> resolution.message
}

@Suppress("DEPRECATION")
internal class HomeSidePanelLocation(
    private val cityIndex: HomeSidePanelCityIndex,
) {

    fun hasCoarsePermission(activity: Activity): Boolean =
        hostDeclaresCoarsePermission(activity) &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    suspend fun resolve(activity: Activity): LocationResolution {
        if (!hostDeclaresCoarsePermission(activity)) {
            return LocationResolution.Error(beautifyText(R.string.home_side_panel_location_permission_undeclared))
        }
        if (!hasCoarsePermission(activity)) return LocationResolution.NeedPermission

        val locationManager = activity.getSystemService(LocationManager::class.java)
            ?: return LocationResolution.Error(beautifyText(R.string.home_side_panel_location_unavailable))
        val provider = enabledProvider(locationManager)
            ?: return LocationResolution.LocationDisabled
        val location = try {
            withTimeoutOrNull(LOCATION_TIMEOUT) {
                requestLocation(activity, locationManager, provider)
            }
        } catch (error: SecurityException) {
            WeLogger.w(LOCATION_TAG, "location permission was revoked during request", error)
            return LocationResolution.NeedPermission
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            WeLogger.w(LOCATION_TAG, "location request failed", error)
            return LocationResolution.Error(beautifyText(R.string.home_side_panel_location_failed))
        } ?: return LocationResolution.Timeout

        val address = try {
            geocode(activity, location)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            WeLogger.w(LOCATION_TAG, "reverse geocoding failed", error)
            return LocationResolution.GeocoderFailed
        } ?: return LocationResolution.GeocoderFailed

        val province = address.adminArea.orEmpty().ifBlank { address.subAdminArea.orEmpty() }
        val city = address.locality.orEmpty()
            .ifBlank { address.subAdminArea.orEmpty() }
            .ifBlank { address.adminArea.orEmpty() }
        return when (val result = cityIndex.matchLocation(province, city)) {
            is WeatherCityMatchResult.Success -> LocationResolution.Success(result.city)
            is WeatherCityMatchResult.Error -> LocationResolution.CityNotFound
        }
    }

    private fun hostDeclaresCoarsePermission(activity: Activity): Boolean = try {
        activity.packageManager
            .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(Manifest.permission.ACCESS_COARSE_LOCATION) == true
    } catch (error: PackageManager.NameNotFoundException) {
        WeLogger.w(LOCATION_TAG, "failed to read host permission declarations", error)
        false
    }

    private fun enabledProvider(locationManager: LocationManager): String? =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull(locationManager::isProviderEnabled)

    @SuppressLint("MissingPermission")
    private suspend fun requestLocation(
        activity: Activity,
        locationManager: LocationManager,
        provider: String,
    ): Location? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            locationManager.getCurrentLocation(
                provider,
                cancellationSignal,
                activity.mainExecutor,
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
    } else {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    private suspend fun geocode(activity: Activity, location: Location): Address? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            Geocoder(activity, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
        }

    private companion object {
        const val LOCATION_TAG = "HomeSidePanelLocation"
        val LOCATION_TIMEOUT = 12.seconds
    }
}

private val WEATHER_REGION_SUFFIXES = listOf(
    "特别行政区",
    "维吾尔自治区",
    "壮族自治区",
    "回族自治区",
    "自治区",
    "省",
    "市",
    "区",
    "县",
)

private fun normalizeRegionValue(value: String): String {
    var normalized = value.trim().lowercase().replace(Regex("\\s+"), "")
    WEATHER_REGION_SUFFIXES.forEach { suffix ->
        normalized = normalized.replace(suffix, "")
    }
    return normalized
}

private fun normalizeSearchValue(value: String): String =
    normalizeRegionValue(value).replace(Regex("[^\\p{L}\\p{N}]"), "")
