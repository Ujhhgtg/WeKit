package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface HomeSidePanelWeatherRepository {
    suspend fun loadCached(): WeatherSnapshot?

    suspend fun refresh(city: WeatherCity): WeatherResult

    suspend fun searchCities(query: String): List<WeatherCity>

    fun selectedCity(): WeatherCity

    fun selectCity(city: WeatherCity)

    fun resetForAccount()
}

internal interface HomeSidePanelWeatherPreferences {
    var selectedWeatherCity: WeatherCity
    var weatherLastSuccess: WeatherSnapshot?
}

internal object PersistedHomeSidePanelWeatherPreferences : HomeSidePanelWeatherPreferences {
    override var selectedWeatherCity: WeatherCity
        get() = HomeSidePanelPreferences.selectedWeatherCity
        set(value) {
            HomeSidePanelPreferences.selectedWeatherCity = value
        }

    override var weatherLastSuccess: WeatherSnapshot?
        get() = HomeSidePanelPreferences.weatherLastSuccess
        set(value) {
            HomeSidePanelPreferences.weatherLastSuccess = value
        }
}

enum class WeatherIconKind {
    SUNNY,
    CLOUDY,
    RAIN,
    SNOW,
    FOG,
    THUNDER,
    UNKNOWN,
}

internal fun weatherIconKind(code: String): WeatherIconKind = when (code.toIntOrNull()) {
    0 -> WeatherIconKind.SUNNY
    1, 2 -> WeatherIconKind.CLOUDY
    4, 5, 32, 33 -> WeatherIconKind.THUNDER
    6, 13, 14, 15, 16, 17, 26, 27, 28, 34 -> WeatherIconKind.SNOW
    3, 7, 8, 9, 10, 11, 12, 19, 21, 22, 23, 24, 25 -> WeatherIconKind.RAIN
    18, 20, 29, 30, 31, 35, 53 -> WeatherIconKind.FOG
    else -> WeatherIconKind.UNKNOWN
}

internal fun buildWeatherUrl(city: WeatherCity): HttpUrl = WEATHER_ENDPOINT.toHttpUrl()
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

internal fun parseWeatherPayload(
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

internal class DefaultHomeSidePanelWeatherRepository(
    private val preferences: HomeSidePanelWeatherPreferences = PersistedHomeSidePanelWeatherPreferences,
    private val cityIndex: HomeSidePanelCityIndex,
    private val client: OkHttpClient = defaultWeatherHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val fetchPayload: suspend (Request) -> String = { request ->
        client.newCall(request).awaitWeatherPayload()
    },
) : HomeSidePanelWeatherRepository {

    private val inFlight = AtomicReference<InFlightWeatherRequest?>(null)
    private val lastRequestStartedAt = AtomicReference<WeatherRequestStart?>(null)

    override suspend fun loadCached(): WeatherSnapshot? = preferences.weatherLastSuccess

    override suspend fun refresh(city: WeatherCity): WeatherResult {
        if (city.cityNum.isBlank()) {
            return WeatherResult.Error("未选择天气城市", preferences.weatherLastSuccess)
        }
        val now = nowMs()
        inFlight.get()?.let { current ->
            if (current.cityNum == city.cityNum) return current.deferred.await()
            current.deferred.cancel()
            inFlight.compareAndSet(current, null)
        }

        val previousStart = lastRequestStartedAt.get()
        if (previousStart?.cityNum == city.cityNum && now - previousStart.startedAt < MIN_REFRESH_INTERVAL_MS) {
            val cached = preferences.weatherLastSuccess
            return if (cached?.city?.cityNum == city.cityNum) {
                WeatherResult.Success(cached)
            } else {
                WeatherResult.Error("刷新过于频繁，请稍后再试", cached)
            }
        }

        return coroutineScope {
            val created = async(Dispatchers.IO, start = CoroutineStart.LAZY) { performRefresh(city) }
            val entry = InFlightWeatherRequest(city.cityNum, created)
            if (inFlight.compareAndSet(null, entry)) {
                lastRequestStartedAt.set(WeatherRequestStart(city.cityNum, now))
                created.start()
                try {
                    val result = created.await()
                    if (inFlight.get() === entry && result is WeatherResult.Success) {
                        preferences.selectedWeatherCity = city
                        preferences.weatherLastSuccess = result.snapshot
                    }
                    result
                } finally {
                    inFlight.compareAndSet(entry, null)
                }
            } else {
                created.cancel()
                refresh(city)
            }
        }
    }

    override suspend fun searchCities(query: String): List<WeatherCity> = cityIndex.search(query)

    override fun selectedCity(): WeatherCity = preferences.selectedWeatherCity

    override fun selectCity(city: WeatherCity) {
        preferences.selectedWeatherCity = city
    }

    override fun resetForAccount() {
        preferences.selectedWeatherCity = DEFAULT_WEATHER_CITY
        preferences.weatherLastSuccess = null
    }

    private suspend fun performRefresh(city: WeatherCity): WeatherResult {
        val cached = preferences.weatherLastSuccess
        val request = Request.Builder().url(buildWeatherUrl(city)).get().build()
        return try {
            val snapshot = parseWeatherPayload(city, fetchPayload(request), nowMs())
            WeatherResult.Success(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: WeatherHttpException) {
            WeLogger.w(TAG, "weather request failed with HTTP ${error.code}")
            WeatherResult.Error("天气服务请求失败：HTTP ${error.code}", cached)
        } catch (error: SocketTimeoutException) {
            WeLogger.w(TAG, "weather request timed out", error)
            WeatherResult.Error("天气请求超时", cached)
        } catch (error: InvalidWeatherPayloadException) {
            WeLogger.w(TAG, "weather payload is incomplete", error)
            WeatherResult.Error("天气服务返回的数据不完整", cached)
        } catch (error: SerializationException) {
            WeLogger.w(TAG, "weather payload is malformed", error)
            WeatherResult.Error("天气服务返回了无效数据", cached)
        } catch (error: IOException) {
            WeLogger.w(TAG, "weather request failed", error)
            WeatherResult.Error("无法连接天气服务", cached)
        }
    }

    private companion object {
        const val TAG = "HomeSidePanelWeather"
        const val MIN_REFRESH_INTERVAL_MS = 1_000L
    }

    private data class InFlightWeatherRequest(
        val cityNum: String,
        val deferred: Deferred<WeatherResult>,
    )

    private data class WeatherRequestStart(
        val cityNum: String,
        val startedAt: Long,
    )
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

private val defaultWeatherHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(10))
        .callTimeout(Duration.ofSeconds(10))
        .build()
}

private const val WEATHER_ENDPOINT = "https://weatherapi.market.xiaomi.com/wtr-v3/weather/all"
private const val WEATHER_SIGN = "zUFJoAR2ZVrDy1vF3D07"
private const val WEATHER_APP_KEY = "weather20151024"
