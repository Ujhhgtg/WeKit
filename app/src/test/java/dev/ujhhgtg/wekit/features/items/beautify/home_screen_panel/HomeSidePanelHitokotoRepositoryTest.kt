package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class HomeSidePanelHitokotoRepositoryTest {

    @Test
    fun hitokotoRequestContainsConfiguredCategoriesAndLengths() {
        val url = buildHitokotoUrl(
            HitokotoSettings(
                categories = setOf("d", "a"),
                minLength = 8,
                maxLength = 24,
                charset = "utf-8",
            ),
        )
        assertEquals(listOf("a", "d"), url.queryParameterValues("c"))
        assertEquals("json", url.queryParameter("encode"))
        assertEquals("8", url.queryParameter("min_length"))
        assertEquals("24", url.queryParameter("max_length"))
        assertEquals("utf-8", url.queryParameter("charset"))
    }

    @Test
    fun malformedHitokotoResponseReturnsError() = runBlocking {
        val repository = fixtureRepository(responseBody = { "{not-json" })
        val result = repository.fetchRandom()
        assertEquals("一言数据解析失败", assertInstanceOf(HitokotoResult.Error::class.java, result).message)
    }

    @Test
    fun hitokotoJsonMapsSourceAndAuthor() = runBlocking {
        val result = fixtureRepository(responseBody = { validHitokotoJson }).fetchRandom()
        val snapshot = assertInstanceOf(HitokotoResult.Success::class.java, result).snapshot
        assertEquals("用代码表达言语的魅力。", snapshot.text)
        assertEquals("一言开发者中心", snapshot.source)
        assertEquals("一言", snapshot.author)
        assertEquals("DreamOne", snapshot.creator)
    }

    @Test
    fun repeatedFetchesWithinOneSecondShareTheInFlightRequest() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val repository = fixtureRepository(
            nowMs = { 1_000L },
            responseBody = {
                requestCount.incrementAndGet()
                gate.await()
                validHitokotoJson
            },
        )
        val fetches = List(20) { async { repository.fetchRandom() } }
        delay(50)
        assertEquals(1, requestCount.get())
        gate.complete(Unit)
        assertEquals(1, fetches.map { it.await() }.distinct().size)
    }

    @Test
    fun fetchWithinOneSecondUsesCachedSuccess() = runBlocking {
        val requestCount = AtomicInteger()
        val repository = fixtureRepository(
            nowMs = { 1_000L },
            responseBody = {
                requestCount.incrementAndGet()
                validHitokotoJson
            },
        )
        val first = repository.fetchRandom()
        val second = repository.fetchRandom()
        assertEquals(1, requestCount.get())
        assertEquals(first, second)
    }

    @Test
    fun invalidSettingsAreRejectedBeforeAnyNetworkRequest() = runBlocking {
        val preferences = InMemoryHomeSidePanelHitokotoPreferences().apply {
            hitokotoSettings = HitokotoSettings(categories = emptySet())
        }
        val requestCount = AtomicInteger()
        val repository = DefaultHomeSidePanelHitokotoRepository(
            preferences = preferences,
            client = OkHttpClient(),
            fetchPayload = {
                requestCount.incrementAndGet()
                validHitokotoJson
            },
        )
        val result = assertInstanceOf(HitokotoResult.Error::class.java, repository.fetchRandom())
        assertEquals("至少选择一个分类", result.message)
        assertEquals(0, requestCount.get())
    }

    private fun fixtureRepository(
        nowMs: () -> Long = { 1_000L },
        responseBody: suspend () -> String,
    ) = DefaultHomeSidePanelHitokotoRepository(
        preferences = InMemoryHomeSidePanelHitokotoPreferences(),
        client = OkHttpClient(),
        nowMs = nowMs,
        fetchPayload = { responseBody() },
    )

    private companion object {
        const val validHitokotoJson = """
            {
              "uuid": "75a45fd4-4f2f-45eb-80cb-6f0a7bcdfaf2",
              "hitokoto": "用代码表达言语的魅力。",
              "type": "f",
              "from": "一言开发者中心",
              "from_who": "一言",
              "creator": "DreamOne",
              "created_at": "1621833280",
              "length": 11
            }
        """
    }
}

private class InMemoryHomeSidePanelHitokotoPreferences : HomeSidePanelHitokotoPreferences {
    override var hitokotoSettings: HitokotoSettings = HitokotoSettings()
    override var hitokotoLastSuccess: HitokotoSnapshot? = null
}
