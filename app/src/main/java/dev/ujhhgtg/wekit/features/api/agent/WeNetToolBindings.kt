package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.net.ExternalServiceId
import dev.ujhhgtg.wekit.agent.workspace.VfsContext
import dev.ujhhgtg.wekit.agent.workspace.WorkspaceVfs
import dev.ujhhgtg.wekit.features.api.agent.WeNetToolBindings.SPILL_THRESHOLD_CHARS
import dev.ujhhgtg.wekit.features.api.agent.WeNetToolBindings.braveSearch
import dev.ujhhgtg.wekit.features.api.agent.WeNetToolBindings.exaSearch
import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.core.WeKitOperation
import kotlinx.coroutines.currentCoroutineContext
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
 * Large responses (over [SPILL_THRESHOLD_CHARS]) are written to a randomly-named `/cache/` file
 * and the model receives a truncation notice + path instead of the raw content, so it can use
 * [WeAgentFsToolBindings.readFile] with `startLine`/`endLine` to page through the result.
 */
object WeNetToolBindings {

    /** Inline-response character cap before spilling to /cache/ (~50 KiB of UTF-8). */
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

    private suspend fun vfs(): WorkspaceVfs? = currentCoroutineContext()[VfsContext]?.vfs

    /**
     * Returns [content] unchanged if it fits within [SPILL_THRESHOLD_CHARS]; otherwise writes the
     * full content to a `/cache/` file and returns a truncation notice with the path so the model
     * can read the file in chunks using [read_file] with line-range params.
     */
    private suspend fun maybeSpill(content: String, prefix: String, extension: String): String {
        if (content.length <= SPILL_THRESHOLD_CHARS) return content
        val v = vfs()
        return if (v != null) {
            val path = v.writeToCacheSpill(prefix, extension, content)
            val preview = content.take(500)
            "[Output truncated — ${content.length} chars total. Full content saved to $path. " +
                    "Use read_file with startLine/endLine to read it in parts.]\n\n$preview\n…"
        } else {
            content.take(SPILL_THRESHOLD_CHARS) +
                    "\n\n[Truncated at $SPILL_THRESHOLD_CHARS chars — VFS unavailable for spill]"
        }
    }

    // -----------------------------------------------------------------------------------------
    // fetch-text
    // -----------------------------------------------------------------------------------------

    @WeKitOperation(
        name = "fetch-text",
        description = "Fetch a web page and return its main text content (HTML tags stripped by Jsoup). " +
                "If the page text exceeds the inline size cap, the full content is saved to a /cache/ file " +
                "and a truncation notice with the path is returned; use read_file to page through it.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_NET,
    )
    suspend fun fetchText(
        @Param("URL to fetch") url: String,
        @Param("Request timeout in seconds (default 15, max 120)") timeoutSeconds: Int?,
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

            maybeSpill(title + bodyText, "fetch", "txt")
        }.getOrElse { "fetch-text failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    // -----------------------------------------------------------------------------------------
    // http-request
    // -----------------------------------------------------------------------------------------

    @WeKitOperation(
        name = "http-request",
        description = "Execute an arbitrary HTTP request and return a JSON object with statusCode, headers, and body. " +
                "If the response body exceeds the inline size cap it is saved to /cache/ and a truncation notice is returned.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_NET,
    )
    suspend fun httpRequest(
        @Param("Target URL") url: String,
        @Param("HTTP method: GET, POST, PUT, PATCH, DELETE, HEAD") method: String,
        @Param(
            "Request headers as a JSON object, " +
                    "e.g. {\"Authorization\":\"Bearer …\",\"Content-Type\":\"application/json\"}"
        ) headersJson: String?,
        @Param("Request body string (for JSON also set Content-Type in headers)") body: String?,
        @Param("Request timeout in seconds (default 30, max 300)") timeoutSeconds: Int?,
        @Param("Follow HTTP redirects automatically (default true)") followRedirects: Boolean?,
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
            maybeSpill(result, "http", "json")
        }.getOrElse { "http-request failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    // -----------------------------------------------------------------------------------------
    // exa-search
    // -----------------------------------------------------------------------------------------

    @WeKitOperation(
        name = "exa-search",
        description = "Search the web with Exa AI (requires an Exa API key in External Services settings). " +
                "Returns a JSON list of results: title, url, publishedDate, snippet.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_NET,
    )
    suspend fun exaSearch(
        @Param("Search query") query: String,
        @Param("Number of results, 1–10 (default 5)") numResults: Int?,
        @Param("Search type: \"neural\" (semantic, default) or \"keyword\"") type: String?,
        @Param("Comma-separated domain allowlist, e.g. \"github.com,stackoverflow.com\"") includeDomains: String?,
        @Param("Comma-separated domain blocklist") excludeDomains: String?,
        @Param("Include a text snippet in each result (default true)") includeText: Boolean?,
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
            maybeSpill(compact.toString(), "exa", "json")
        }.getOrElse { "exa-search failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    // -----------------------------------------------------------------------------------------
    // brave-search
    // -----------------------------------------------------------------------------------------

    @WeKitOperation(
        name = "brave-search",
        description = "Search the web with Brave Search (requires a Brave Search API key in External Services settings). " +
                "Returns a JSON list of results: title, url, description.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_NET,
    )
    suspend fun braveSearch(
        @Param("Search query") query: String,
        @Param("Number of results, 1–20 (default 10)") count: Int?,
        @Param("Result offset for pagination (default 0)") offset: Int?,
        @Param("Country code, e.g. \"US\", \"CN\"") country: String?,
        @Param("Result freshness: \"pd\" (24 h), \"pw\" (week), \"pm\" (month), \"py\" (year)") freshness: String?,
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
            maybeSpill(compact.toString(), "brave", "json")
        }.getOrElse { "brave-search failed: ${it.message ?: it.javaClass.simpleName}" }
    }
}
