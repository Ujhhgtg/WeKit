package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.net.ExternalServiceId
import dev.ujhhgtg.wekit.features.api.agent.WeNetToolBindings.SPILL_THRESHOLD_CHARS
import dev.ujhhgtg.wekit.features.api.agent.WeNetToolBindings.braveSearch
import dev.ujhhgtg.wekit.features.api.agent.WeNetToolBindings.exaSearch
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentToolParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Network `@AgentTool`s: general HTTP client, page text extraction, and external search APIs.
 *
 * All four tools live in the [AgentTool.BUILTIN_NET] group. The two search tools
 * ([exaSearch], [braveSearch]) are only advertised to the model when the corresponding API key is
 * present (see [dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider.exaKeyPresent]).
 *
 * Large responses (over [SPILL_THRESHOLD_CHARS]) are truncated to keep tool results bounded.
 */
object WeNetToolBindings {

    /** Inline-response character cap (~50 KiB of UTF-8). */
    private const val SPILL_THRESHOLD_CHARS = 50_000

    private const val DEFAULT_TIMEOUT_SEC = 30

    /**
     * Shared OkHttpClient. Per-call timeouts and redirect behaviour are applied by cloning
     * this instance's builder, which is cheaper than constructing a new dispatcher/thread-pool.
     */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun boundResponse(content: String): String {
        if (content.length <= SPILL_THRESHOLD_CHARS) return content
        return content.take(SPILL_THRESHOLD_CHARS) +
                "\n\n[Output truncated at $SPILL_THRESHOLD_CHARS of ${content.length} characters]"
    }

    // -----------------------------------------------------------------------------------------
    // fetch-text
    // -----------------------------------------------------------------------------------------

    @AgentTool(
        name = "fetch-text",
        description = "Fetch a web page and return its main text content (HTML tags stripped by Jsoup). " +
                "Responses exceeding the inline size cap are truncated.",
        sideEffect = false,
        group = AgentTool.BUILTIN_NET,
    )
    suspend fun fetchText(
        @AgentToolParam("URL to fetch") url: String,
        @AgentToolParam("Request timeout in seconds (default 15, max 120)") timeoutSeconds: Int?,
    ): String {
        val timeout = (timeoutSeconds ?: 15).coerceIn(1, 120).toLong()
        return runCatching {
            val client = baseClient.newBuilder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build()

            val html = client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return "HTTP ${resp.code}: ${resp.message}"
                resp.body.string()
            }

            val doc = Jsoup.parse(html, url)
            val title = doc.title().takeIf { it.isNotBlank() }?.let { "# $it\n\n" } ?: ""
            // wholeText() preserves whitespace structure; collapse 3+ consecutive newlines.
            val bodyText = doc.body().wholeText().trim()
                .replace(Regex("\n{3,}"), "\n\n")

            boundResponse(title + bodyText)
        }.getOrElse { "fetch-text failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    // -----------------------------------------------------------------------------------------
    // http-request
    // -----------------------------------------------------------------------------------------

    @AgentTool(
        name = "http-request",
        description = "Execute an arbitrary HTTP request and return a JSON object with statusCode, headers, and body. " +
                "Responses exceeding the inline size cap are truncated.",
        sideEffect = true,
        group = AgentTool.BUILTIN_NET,
    )
    suspend fun httpRequest(
        @AgentToolParam("Target URL") url: String,
        @AgentToolParam("HTTP method: GET, POST, PUT, PATCH, DELETE, HEAD") method: String,
        @AgentToolParam(
            "Request headers as a JSON object, " +
                    "e.g. {\"Authorization\":\"Bearer …\",\"Content-Type\":\"application/json\"}"
        ) headersJson: String?,
        @AgentToolParam("Request body string (for JSON also set Content-Type in headers)") body: String?,
        @AgentToolParam("Request timeout in seconds (default 30, max 300)") timeoutSeconds: Int?,
        @AgentToolParam("Follow HTTP redirects automatically (default true)") followRedirects: Boolean?,
    ): String {
        val timeout = (timeoutSeconds ?: DEFAULT_TIMEOUT_SEC).coerceIn(1, 300).toLong()
        val follow = followRedirects ?: true
        return runCatching {
            val client = baseClient.newBuilder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .followRedirects(follow)
                .followSslRedirects(follow)
                .build()

            val reqBuilder = Request.Builder().url(url)

            // Parse and apply optional headers.
            headersJson?.takeIf { it.isNotBlank() }?.let { json ->
                runCatching {
                    Json.parseToJsonElement(json).jsonObject
                        .forEach { (k, v) -> reqBuilder.header(k, v.jsonPrimitive.content) }
                }
            }

            // Derive Content-Type for body construction.
            val contentType = headersJson?.let {
                runCatching {
                    Json.parseToJsonElement(it).jsonObject["Content-Type"]?.jsonPrimitive?.content
                }.getOrNull()
            }?.toMediaTypeOrNull()

            val reqBody = when (method.uppercase()) {
                "GET", "HEAD" -> null
                else -> (body ?: "").toRequestBody(contentType)
            }
            reqBuilder.method(method.uppercase(), reqBody)

            val result = client.newCall(reqBuilder.build()).execute().use { resp ->
                val respHeaders = buildJsonObject {
                    resp.headers.forEach { (name, value) -> put(name, value) }
                }
                buildJsonObject {
                    put("statusCode", resp.code)
                    put("headers", respHeaders)
                    put("body", resp.body.string())
                }.toString()
            }
            boundResponse(result)
        }.getOrElse { "http-request failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    // -----------------------------------------------------------------------------------------
    // exa-search
    // -----------------------------------------------------------------------------------------

    @AgentTool(
        name = "exa-search",
        description = "Search the web with Exa AI (requires an Exa API key in External Services settings). " +
                "Returns a JSON list of results: title, url, publishedDate, snippet.",
        sideEffect = false,
        group = AgentTool.BUILTIN_NET,
    )
    suspend fun exaSearch(
        @AgentToolParam("Search query") query: String,
        @AgentToolParam("Number of results, 1–10 (default 5)") numResults: Int?,
        @AgentToolParam("Search type: \"neural\" (semantic, default) or \"keyword\"") type: String?,
        @AgentToolParam("Comma-separated domain allowlist, e.g. \"github.com,stackoverflow.com\"") includeDomains: String?,
        @AgentToolParam("Comma-separated domain blocklist") excludeDomains: String?,
        @AgentToolParam("Include a text snippet in each result (default true)") includeText: Boolean?,
    ): String {
        val apiKey = WeAgentRepository.getExternalServiceKey(ExternalServiceId.EXA)
            ?: return "Exa API key not configured — add it in WeAgent Settings → 外部服务."

        val n = (numResults ?: 5).coerceIn(1, 10)
        val searchType = if (type?.lowercase() == "keyword") "keyword" else "neural"

        return runCatching {
            val payload = buildJsonObject {
                put("query", query)
                put("numResults", n)
                put("type", searchType)
                if (!includeDomains.isNullOrBlank())
                    put("includeDomains", buildJsonArray {
                        includeDomains.split(',').forEach { add(it.trim()) }
                    })
                if (!excludeDomains.isNullOrBlank())
                    put("excludeDomains", buildJsonArray {
                        excludeDomains.split(',').forEach { add(it.trim()) }
                    })
                if (includeText != false)
                    put("contents", buildJsonObject { put("text", true) })
            }.toString()

            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .build()

            val raw = baseClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful)
                    return "Exa API error ${resp.code}: ${resp.body.string()}"
                resp.body.string()
            }

            // Reshape: flatten each result to {title, url, publishedDate, snippet}.
            val results = Json.parseToJsonElement(raw).jsonObject["results"]?.jsonArray
                ?: buildJsonArray {}
            val compact = buildJsonArray {
                results.forEach { item ->
                    val o = item.jsonObject
                    add(buildJsonObject {
                        o["title"]?.let { put("title", it) }
                        o["url"]?.let { put("url", it) }
                        o["publishedDate"]?.let { put("publishedDate", it) }
                        // Exa returns text either at top-level or nested under contents.text.
                        val snippet = o["text"] ?: o["contents"]?.jsonObject?.get("text")
                        snippet?.let { put("snippet", it) }
                    })
                }
            }
            boundResponse(compact.toString())
        }.getOrElse { "exa-search failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    // -----------------------------------------------------------------------------------------
    // brave-search
    // -----------------------------------------------------------------------------------------

    @AgentTool(
        name = "brave-search",
        description = "Search the web with Brave Search (requires a Brave Search API key in External Services settings). " +
                "Returns a JSON list of results: title, url, description.",
        sideEffect = false,
        group = AgentTool.BUILTIN_NET,
    )
    suspend fun braveSearch(
        @AgentToolParam("Search query") query: String,
        @AgentToolParam("Number of results, 1–20 (default 10)") count: Int?,
        @AgentToolParam("Result offset for pagination (default 0)") offset: Int?,
        @AgentToolParam("Country code, e.g. \"US\", \"CN\"") country: String?,
        @AgentToolParam("Result freshness: \"pd\" (24 h), \"pw\" (week), \"pm\" (month), \"py\" (year)") freshness: String?,
    ): String {
        val apiKey = WeAgentRepository.getExternalServiceKey(ExternalServiceId.BRAVE)
            ?: return "Brave Search API key not configured — add it in WeAgent Settings → 外部服务."

        val n = (count ?: 10).coerceIn(1, 20)
        val off = (offset ?: 0).coerceAtLeast(0)

        return runCatching {
            val urlBuilder = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.search.brave.com")
                .addPathSegments("res/v1/web/search")
                .addQueryParameter("q", query)
                .addQueryParameter("count", n.toString())
                .addQueryParameter("offset", off.toString())
            country?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("country", it) }
            freshness?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("freshness", it) }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-Subscription-Token", apiKey)
                .build()

            val raw = baseClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful)
                    return "Brave API error ${resp.code}: ${resp.body.string()}"
                resp.body.string()
            }

            val webResults = Json.parseToJsonElement(raw).jsonObject["web"]
                ?.jsonObject?.get("results")?.jsonArray ?: buildJsonArray {}
            val compact = buildJsonArray {
                webResults.forEach { item ->
                    val o = item.jsonObject
                    add(buildJsonObject {
                        o["title"]?.let { put("title", it) }
                        o["url"]?.let { put("url", it) }
                        o["description"]?.let { put("description", it) }
                    })
                }
            }
            boundResponse(compact.toString())
        }.getOrElse { "brave-search failed: ${it.message ?: it.javaClass.simpleName}" }
    }
}
