package dev.ujhhgtg.wekit.features.items.chat.panel.voice

import android.annotation.SuppressLint
import android.util.Xml
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSource
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceItem
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceProviderPage
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxServiceClient
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxVoiceRepository
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VoiceProvider {
    val id: String
    val name: String

    suspend fun browse(parent: VoiceItem? = null, page: Int = 0): Result<VoiceProviderPage>
    suspend fun search(query: String, page: Int = 0): Result<VoiceProviderPage>
    suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem>
}

/** Fixed built-in provider registry. */
object VoiceProviderRegistry {
    val providers: List<VoiceProvider> = listOf(
        FunBoxShareVoiceProvider,
        RingDuoDuoVoiceProvider,
        UoiceVoiceProvider,
    )

    fun get(id: String): VoiceProvider = providers.firstOrNull { it.id == id } ?: providers.first()

    fun forItem(item: VoiceItem): VoiceProvider? = when {
        item.id.startsWith("funbox:") -> FunBoxShareVoiceProvider
        item.id.startsWith("ring:") -> RingDuoDuoVoiceProvider
        item.id.startsWith("uoice:") -> UoiceVoiceProvider
        else -> null
    }
}

private val providerHttpClient = OkHttpClient.Builder().build()
private const val PROVIDER_TAG = "VoiceProviderNetwork"

private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
    val endpoint = providerEndpoint(url)
    WeLogger.d(PROVIDER_TAG, "GET start endpoint=$endpoint thread=${Thread.currentThread().name}")
    try {
        providerHttpClient.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            WeLogger.d(PROVIDER_TAG, "GET HTTP ${response.code} endpoint=$endpoint")
            check(response.isSuccessful) { "HTTP ${response.code}: ${response.message}" }
            response.body.string().also { body ->
                WeLogger.i(PROVIDER_TAG, "GET completed endpoint=$endpoint chars=${body.length}")
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        WeLogger.e(PROVIDER_TAG, "GET failed endpoint=$endpoint", error)
        throw error
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

private fun providerEndpoint(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "invalid-url"
    return "${parsed.scheme}://${parsed.host}${parsed.encodedPath}"
}

object UoiceVoiceProvider : VoiceProvider {
    override val id = "uoice"
    override val name = "千变语音2"

    private fun categoryUrl(title: String): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val encoded = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
        return when (title) {
            "最新发布", "最近更新" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=2"
            "热榜总榜" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0"
            "热榜周榜" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0&startTime=" +
                    URLEncoder.encode(formatter.format(Date(System.currentTimeMillis() - 604_800_000L)), StandardCharsets.UTF_8.name())

            "热榜月榜" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0&startTime=" +
                    URLEncoder.encode(formatter.format(Date(System.currentTimeMillis() - 2_592_000_000L)), StandardCharsets.UTF_8.name())

            else -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0&tag=$encoded"
        }
    }

    private data class Category(val sourceName: String, @StringRes val titleRes: Int)

    private val categories = listOf(
        Category("最新发布", R.string.chat_voice_category_latest),
        Category("最近更新", R.string.chat_voice_category_recently_updated),
        Category("热榜总榜", R.string.chat_voice_category_top_overall),
        Category("热榜周榜", R.string.chat_voice_category_top_weekly),
        Category("热榜月榜", R.string.chat_voice_category_top_monthly),
        Category("抖音快手B站热门", R.string.chat_voice_category_short_video_popular),
        Category("搞笑", R.string.chat_voice_category_funny),
        Category("DJ蹦迪", R.string.chat_voice_category_dj),
        Category("聊天日常", R.string.chat_voice_category_daily_chat),
        Category("铃声多多", R.string.chat_voice_category_ringtones),
        Category("鬼畜&卡点", R.string.chat_voice_category_remix),
        Category("二次元", R.string.chat_voice_category_anime),
        Category("萝莉音", R.string.chat_voice_category_cute_voice),
        Category("诱惑软妹音", R.string.chat_voice_category_soft_voice),
    )

    override suspend fun browse(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> = runCatching {
        if (parent == null) {
            return@runCatching VoiceProviderPage(
                categories.map { category ->
                    VoiceItem(
                        id = "uoice:category:${category.sourceName}",
                        title = localizedChatString(category.titleRes),
                        source = PanelSource.ONLINE,
                        isContainer = true,
                        metadata = mapOf(
                            "category" to category.sourceName,
                            "localPackId" to "uoice-${category.sourceName}",
                            "legacyPackName" to category.sourceName,
                        ),
                    )
                },
                page = 0,
                hasMore = false,
            )
        }
        val voiceId = parent.metadata["voiceId"]
        if (voiceId != null) {
            return@runCatching parseVoiceItems(
                getText("https://uoice.com/v1/voice?id=$voiceId&keyword=null&fromType=0"),
                parent.title,
            )
        }
        val category = parent.metadata["category"] ?: error(localizedChatString(R.string.chat_voice_category_invalid))
        parseCatalog(getText(categoryUrl(category).replace("{{PAGE}}", (page + 1).toString())), page)
    }.logProviderResult(name, "browse", page)

    override suspend fun search(query: String, page: Int): Result<VoiceProviderPage> = runCatching {
        require(query.isNotBlank()) { localizedChatString(R.string.chat_funbox_search_empty) }
        val url = "https://uoice.com/v1/search/voice?page=${page + 1}&take=50&keyword=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        parseCatalog(getText(url), page)
    }.logProviderResult(name, "search", page)

    override suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem> = runCatching {
        require(item.id.startsWith("uoice:")) { localizedChatString(R.string.chat_voice_item_invalid) }
        item.copy(
            remoteUrl = "https://uoice.com/v1/voice/audition?id=${item.id.substringAfterLast(':')}",
            format = item.format.ifBlank { mimeExtension(item.metadata["mimeType"]) },
        )
    }

    private fun parseCatalog(body: String, page: Int): VoiceProviderPage {
        val result = DefaultJson.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["result"]!!.jsonArray
        val items = result.map { element ->
            val obj = element.jsonObject
            val itemId = obj["id"]!!.jsonPrimitive.content
            VoiceItem(
                id = "uoice:$itemId",
                title = obj["name"]?.jsonPrimitive?.content ?: itemId,
                source = PanelSource.ONLINE,
                isContainer = true,
                metadata = mapOf("voiceId" to itemId),
            )
        }
        return VoiceProviderPage(items, page, items.size >= 50)
    }

    private fun parseVoiceItems(body: String, packTitle: String): VoiceProviderPage {
        val result = DefaultJson.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["item"]!!.jsonArray
        val items = result.mapNotNull { element ->
            val obj = element.jsonObject
            val state = obj["state"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (state != 0) return@mapNotNull null
            val itemId = obj["id"]!!.jsonPrimitive.content
            val mimeType = obj["mimeType"]?.jsonPrimitive?.content.orEmpty()
            VoiceItem(
                id = "uoice:$itemId",
                title = obj["name"]?.jsonPrimitive?.content ?: itemId,
                source = PanelSource.ONLINE,
                packId = packTitle,
                durationMs = (obj["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 1_000L,
                format = mimeExtension(mimeType),
                metadata = mapOf(
                    "mimeType" to mimeType,
                    "size" to (obj["size"]?.jsonPrimitive?.content ?: "0"),
                ),
            )
        }
        return VoiceProviderPage(items, 0, false)
    }

    private fun mimeExtension(mimeType: String?): String = when (mimeType?.lowercase()) {
        "audio/aac" -> "aac"
        "audio/vnd.wave", "audio/wav", "audio/x-wav" -> "wav"
        "audio/mp4", "audio/m4a" -> "m4a"
        else -> "mp3"
    }
}

object RingDuoDuoVoiceProvider : VoiceProvider {
    override val id = "ring_duoduo"
    override val name = "铃声多多"

    private data class Category(
        val id: Int,
        val legacyName: String,
        @StringRes val titleRes: Int,
    )

    private val categories = listOf(
        Category(20, "彩铃", R.string.chat_voice_category_ringback),
        Category(1, "最热", R.string.chat_voice_category_hottest),
        Category(5, "短信", R.string.chat_voice_category_sms),
        Category(6, "DJ榜", R.string.chat_voice_category_dj_chart),
        Category(8, "情感", R.string.chat_voice_category_emotion),
        Category(11, "铃声", R.string.chat_voice_category_ringtone),
        Category(33, "欧美馆", R.string.chat_voice_category_western),
    )

    override suspend fun browse(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> = runCatching {
        if (parent == null) {
            return@runCatching VoiceProviderPage(
                categories.map { category ->
                    VoiceItem(
                        id = "ring:category:${category.id}",
                        title = localizedChatString(category.titleRes),
                        source = PanelSource.ONLINE,
                        isContainer = true,
                        metadata = mapOf(
                            "category" to category.id.toString(),
                            "localPackId" to "ring-${category.id}",
                            "legacyPackName" to category.legacyName,
                        ),
                    )
                },
                0,
                false,
            )
        }
        val category = parent.metadata["category"]?.toIntOrNull() ?: error(localizedChatString(R.string.chat_voice_category_invalid))
        val plain = CATEGORY_PREFIX + category +
                "&from=&page=${page + 1}&pagesize=25&uid=&ptime=2023-08-24&tstamp=${System.currentTimeMillis()}"
        parseRingXml(getText(wrappedUrl(plain)), page)
    }.logProviderResult(name, "browse", page)

    override suspend fun search(query: String, page: Int): Result<VoiceProviderPage> = runCatching {
        require(query.isNotBlank()) { localizedChatString(R.string.chat_funbox_search_empty) }
        val plain = SEARCH_PREFIX + query +
                "&src=input&page=${page + 1}&pagesize=15&include=all&ctdb=1&cudb=1" +
                "&ptime=2023-08-24&tstamp=${System.currentTimeMillis()}"
        parseRingXml(getText(wrappedUrl(plain)), page)
    }.logProviderResult(name, "search", page)

    override suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem> = runCatching {
        require(!item.remoteUrl.isNullOrBlank()) { localizedChatString(R.string.chat_voice_download_url_unavailable) }
        item
    }

    @SuppressLint("GetInstance")
    private fun wrappedUrl(plain: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("hikmpuF9".toByteArray(), "DES"))
        val encoded = Base64.getMimeEncoder().encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
        return "http://ring.shoujiduoduo.com/ring_enc.php?q=" +
                URLEncoder.encode(encoded, StandardCharsets.UTF_8.name()) +
                "&os=ar&ver=8.9.36.0&startid=YKkVXsjl1wiS8lwqPrvFcqFHuVzshHz6"
    }

    private fun parseRingXml(body: String, page: Int): VoiceProviderPage {
        val parser = Xml.newPullParser().apply { setInput(StringReader(body)) }
        val items = mutableListOf<VoiceItem>()
        var baseUrl = "http://cdnringbd.shoujiduoduo.com"
        var hasMore = false
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "page" -> {
                        baseUrl = parser.getAttributeValue(null, "baseurl")?.trimEnd('/') ?: baseUrl
                        hasMore = parser.getAttributeValue(null, "hasmore").equals("true", ignoreCase = true)
                    }

                    "ring" -> {
                        val rawUrl = parser.getAttributeValue(null, "mp3url")
                        val title = parser.getAttributeValue(null, "name")
                        if (!rawUrl.isNullOrBlank() && !title.isNullOrBlank()) {
                            val itemId = parser.getAttributeValue(null, "rid")
                                ?: parser.getAttributeValue(null, "uid")
                                ?: parser.getAttributeValue(null, "cid")
                                ?: rawUrl
                            items += VoiceItem(
                                id = "ring:$itemId",
                                title = title,
                                remoteUrl = if (rawUrl.startsWith("http")) rawUrl else "$baseUrl/${rawUrl.trimStart('/')}",
                                source = PanelSource.ONLINE,
                                // The endpoint currently reports the same placeholder duration for
                                // unrelated tracks. FunBox also ignores this field and resolves the
                                // real duration only after the audio is opened.
                                durationMs = 0L,
                                format = "mp3",
                            )
                        }
                    }
                }
            }
            parser.next()
        }
        return VoiceProviderPage(items, page, hasMore)
    }

    private const val CATEGORY_PREFIX =
        "user=12345678&prod=RingDD_ar_8.9.36.0&isrc=RingDD_ar_8.9.36.0_qq.apk" +
                "&dev=UAWEIP90Kelin114514&vc=60089360&loc=CN&sp=cm&type=getlist&listid="

    private const val SEARCH_PREFIX =
        "user=12345678&prod=RingDD_ar_8.9.36.0&isrc=RingDD_ar_8.9.36.0_qq.apk" +
                "&dev=HUAWEIP90Kelin114514&vc=60089360&loc=CN&sp=cm&type=search&keyword="
}

object FunBoxShareVoiceProvider : VoiceProvider {
    override val id = "funbox_share"
    override val name = "FunBox分享"

    override suspend fun browse(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> =
        FunBoxVoiceRepository.browseSharedVoices(parent, page).logProviderResult(name, "browse", page)

    override suspend fun search(query: String, page: Int): Result<VoiceProviderPage> =
        FunBoxVoiceRepository.searchSharedVoices(query, page).logProviderResult(name, "search", page)

    override suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem> = runCatching {
        val objectId = item.remoteObjectId ?: error(localizedChatString(R.string.chat_voice_object_unavailable))
        item.copy(remoteUrl = FunBoxServiceClient.objectUrl("voice", objectId), format = "mp3")
    }.onSuccess {
        WeLogger.i(PROVIDER_TAG, "provider=$name audio URL resolved")
    }.onFailure { error ->
        WeLogger.e(PROVIDER_TAG, "provider=$name audio URL resolution failed", error)
    }
}

private fun Result<VoiceProviderPage>.logProviderResult(
    provider: String,
    action: String,
    requestedPage: Int,
): Result<VoiceProviderPage> = onSuccess { result ->
    WeLogger.i(
        PROVIDER_TAG,
        "provider=$provider action=$action requestedPage=$requestedPage resultPage=${result.page} items=${result.items.size}",
    )
}.onFailure { error ->
    WeLogger.e(PROVIDER_TAG, "provider=$provider action=$action page=$requestedPage failed", error)
}
