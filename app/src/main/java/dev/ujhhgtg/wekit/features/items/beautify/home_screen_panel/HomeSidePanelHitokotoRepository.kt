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
import kotlinx.serialization.SerialName
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

interface HomeSidePanelHitokotoRepository {
    fun loadSettings(): HitokotoSettings

    fun saveSettings(settings: HitokotoSettings)

    suspend fun loadCached(): HitokotoSnapshot?

    suspend fun preload(): HitokotoResult

    suspend fun fetchRandom(): HitokotoResult
}

internal interface HomeSidePanelHitokotoPreferences {
    var hitokotoSettings: HitokotoSettings
    var hitokotoLastSuccess: HitokotoSnapshot?
}

internal object PersistedHomeSidePanelHitokotoPreferences : HomeSidePanelHitokotoPreferences {
    override var hitokotoSettings: HitokotoSettings
        get() = HomeSidePanelPreferences.hitokotoSettings
        set(value) {
            HomeSidePanelPreferences.hitokotoSettings = value
        }

    override var hitokotoLastSuccess: HitokotoSnapshot?
        get() = HomeSidePanelPreferences.hitokotoLastSuccess
        set(value) {
            HomeSidePanelPreferences.hitokotoLastSuccess = value
        }
}

internal fun buildHitokotoUrl(settings: HitokotoSettings): HttpUrl = HITOKOTO_ENDPOINT.toHttpUrl()
    .newBuilder()
    .addQueryParameter("encode", "json")
    .apply {
        settings.categories.sorted().forEach { addQueryParameter("c", it) }
        settings.minLength?.let { addQueryParameter("min_length", it.toString()) }
        settings.maxLength?.let { addQueryParameter("max_length", it.toString()) }
        addQueryParameter("charset", settings.charset)
    }
    .build()

internal fun parseHitokotoPayload(payload: String, fetchedAt: Long): HitokotoSnapshot {
    val decoded = DefaultJson.decodeFromString<HitokotoPayload>(payload)
    return HitokotoSnapshot(
        uuid = decoded.uuid.takeIf(String::isNotBlank)
            ?: throw InvalidHitokotoPayloadException("缺少 uuid"),
        text = decoded.text.takeIf(String::isNotBlank)
            ?: throw InvalidHitokotoPayloadException("缺少 hitokoto"),
        type = decoded.type?.takeIf(String::isNotBlank),
        source = decoded.source?.takeIf(String::isNotBlank),
        author = decoded.author?.takeIf(String::isNotBlank),
        creator = decoded.creator?.takeIf(String::isNotBlank),
        createdAt = decoded.createdAt?.takeIf(String::isNotBlank),
        fetchedAt = fetchedAt,
    )
}

internal class DefaultHomeSidePanelHitokotoRepository(
    private val preferences: HomeSidePanelHitokotoPreferences =
        PersistedHomeSidePanelHitokotoPreferences,
    private val client: OkHttpClient = defaultHitokotoHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val fetchPayload: suspend (Request) -> String = { request ->
        client.newCall(request).awaitHitokotoPayload()
    },
) : HomeSidePanelHitokotoRepository {

    private val inFlight = AtomicReference<InFlightHitokotoRequest?>(null)
    private val lastRequestStartedAt = AtomicReference<HitokotoRequestStart?>(null)
    private val lastError = AtomicReference<HitokotoResult.Error?>(null)

    override fun loadSettings(): HitokotoSettings = preferences.hitokotoSettings

    override fun saveSettings(settings: HitokotoSettings) {
        val validationError = validateHitokotoSettings(
            minLength = settings.minLength,
            maxLength = settings.maxLength,
            categories = settings.categories,
            charset = settings.charset,
        )
        require(validationError == null) { validationError!! }
        preferences.hitokotoSettings = settings
    }

    override suspend fun loadCached(): HitokotoSnapshot? = preferences.hitokotoLastSuccess

    override suspend fun preload(): HitokotoResult = fetchRandom()

    override suspend fun fetchRandom(): HitokotoResult {
        val settings = loadSettings()
        val validationError = validateHitokotoSettings(
            minLength = settings.minLength,
            maxLength = settings.maxLength,
            categories = settings.categories,
            charset = settings.charset,
        )
        if (validationError != null) {
            return HitokotoResult.Error(validationError, preferences.hitokotoLastSuccess)
        }
        val requestKey = settings.requestKey()
        inFlight.get()?.let { current ->
            if (current.requestKey == requestKey) return current.deferred.await()
            current.deferred.cancel()
            inFlight.compareAndSet(current, null)
        }

        val now = nowMs()
        val previousStart = lastRequestStartedAt.get()
        if (previousStart?.requestKey == requestKey && now - previousStart.startedAt < MIN_REFRESH_INTERVAL_MS) {
            return preferences.hitokotoLastSuccess?.let(HitokotoResult::Success)
                ?: lastError.get()
                ?: HitokotoResult.Error("请求过于频繁，请稍后再试", null)
        }

        return coroutineScope {
            val created = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                performFetch(settings)
            }
            val entry = InFlightHitokotoRequest(requestKey, created)
            if (inFlight.compareAndSet(null, entry)) {
                lastRequestStartedAt.set(HitokotoRequestStart(requestKey, now))
                created.start()
                try {
                    val result = created.await()
                    if (inFlight.get() === entry) {
                        when (result) {
                            is HitokotoResult.Success -> {
                                preferences.hitokotoLastSuccess = result.snapshot
                                lastError.set(null)
                            }

                            is HitokotoResult.Error -> lastError.set(result)
                        }
                    }
                    result
                } finally {
                    inFlight.compareAndSet(entry, null)
                }
            } else {
                created.cancel()
                fetchRandom()
            }
        }
    }

    private suspend fun performFetch(settings: HitokotoSettings): HitokotoResult {
        val cached = preferences.hitokotoLastSuccess
        val request = Request.Builder().url(buildHitokotoUrl(settings)).get().build()
        return try {
            val snapshot = parseHitokotoPayload(fetchPayload(request), nowMs())
            HitokotoResult.Success(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HitokotoHttpException) {
            val result = HitokotoResult.Error("一言服务请求失败：HTTP ${error.code}", cached)
            WeLogger.w(TAG, "hitokoto request failed with HTTP ${error.code}")
            result
        } catch (error: SocketTimeoutException) {
            val result = HitokotoResult.Error("一言请求超时", cached)
            WeLogger.w(TAG, "hitokoto request timed out", error)
            result
        } catch (error: InvalidHitokotoPayloadException) {
            val result = HitokotoResult.Error("一言数据解析失败", cached)
            WeLogger.w(TAG, "hitokoto payload is incomplete", error)
            result
        } catch (error: SerializationException) {
            val result = HitokotoResult.Error("一言数据解析失败", cached)
            WeLogger.w(TAG, "hitokoto payload is malformed", error)
            result
        } catch (error: IOException) {
            val result = HitokotoResult.Error("无法连接一言服务", cached)
            WeLogger.w(TAG, "hitokoto request failed", error)
            result
        }
    }

    private companion object {
        const val TAG = "HomeSidePanelHitokoto"
        const val MIN_REFRESH_INTERVAL_MS = 1_000L
    }

    private fun HitokotoSettings.requestKey(): HitokotoRequestKey = HitokotoRequestKey(
        categories = categories.sorted(),
        minLength = minLength,
        maxLength = maxLength,
        charset = charset,
    )

    private data class InFlightHitokotoRequest(
        val requestKey: HitokotoRequestKey,
        val deferred: Deferred<HitokotoResult>,
    )

    private data class HitokotoRequestStart(
        val requestKey: HitokotoRequestKey,
        val startedAt: Long,
    )

    private data class HitokotoRequestKey(
        val categories: List<String>,
        val minLength: Int?,
        val maxLength: Int?,
        val charset: String,
    )
}

@Serializable
private data class HitokotoPayload(
    val uuid: String = "",
    @SerialName("hitokoto") val text: String = "",
    val type: String? = null,
    @SerialName("from") val source: String? = null,
    @SerialName("from_who") val author: String? = null,
    val creator: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

private class InvalidHitokotoPayloadException(message: String) : IllegalArgumentException(message)

private class HitokotoHttpException(val code: Int) : IOException()

private suspend fun Call.awaitHitokotoPayload(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!continuation.isActive) return
                if (!it.isSuccessful) {
                    continuation.resumeWithException(HitokotoHttpException(it.code))
                } else {
                    continuation.resume(it.body.string())
                }
            }
        }
    })
}

private val defaultHitokotoHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(10))
        .callTimeout(Duration.ofSeconds(10))
        .build()
}

private const val HITOKOTO_ENDPOINT = "https://v1.hitokoto.cn/"
