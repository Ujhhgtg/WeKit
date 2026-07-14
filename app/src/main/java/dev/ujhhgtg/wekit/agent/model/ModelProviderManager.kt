package dev.ujhhgtg.wekit.agent.model

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds and caches [LlmClient] adapters per model provider, and resolves a stored [ModelEntity]
 * into a ready-to-send [LlmRequest] shell (model id + reasoning gear + custom-JSON override).
 *
 * One shared [HttpClient] (Ktor CIO) is reused across all adapters, mirroring [dev.ujhhgtg.wekit.utils.EdgeTtsClient]'s
 * singleton pattern. A long read timeout accommodates slow streaming completions.
 */
object ModelProviderManager {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000
            socketTimeoutMillis = 600_000
            connectTimeoutMillis = 30_000
        }
    }

    // providerId -> adapter, rebuilt when the provider's config changes.
    private val clientCache = HashMap<String, CachedClient>()

    private data class CachedClient(val configHash: Int, val client: LlmClient)

    /**
     * Returns an [LlmClient] for [provider], building one if absent or if the provider's config
     * (type/baseUrl/apiKey) changed since last cached. [provider] must already have its apiKey
     * decrypted (use [WeAgentRepository.getDecryptedModelProvider]).
     */
    @Synchronized
    fun clientFor(provider: ModelProviderEntity): LlmClient {
        val hash = provider.type.hashCode() * 31 + provider.baseUrl.hashCode() * 31 + provider.apiKey.hashCode()
        clientCache[provider.id]?.let { if (it.configHash == hash) return it.client }
        val client = build(provider)
        clientCache[provider.id] = CachedClient(hash, client)
        return client
    }

    private fun build(provider: ModelProviderEntity): LlmClient = when (provider.type) {
        ModelProviderType.OPENAI_CHAT_COMPLETION ->
            OpenAiChatCompletionsClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.OPENAI_RESPONSES ->
            OpenAiResponsesClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.ANTHROPIC_MESSAGES ->
            AnthropicMessagesClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.GEMINI_GENERATE_CONTENT ->
            GeminiGenerateContentClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.GEMINI_INTERACTIONS ->
            GeminiInteractionsClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        // WeKit Router uses the OpenAI Chat Completions wire format. The base URL is always the
        // hardcoded [WEKIT_ROUTER_BASE_URL] regardless of what is stored in the entity — this
        // ensures the correct endpoint even if the stored value predates a URL change.
        ModelProviderType.WEKIT_ROUTER ->
            OpenAiChatCompletionsClient(httpClient, WEKIT_ROUTER_BASE_URL, provider.apiKey)
    }

    /**
     * Resolves a [ModelEntity] + a fully-composed message/tool set into an [LlmRequest]. The stored
     * [ModelEntity.reasoningEffort] is passed through literally; the sentinel "off"/"none" and null
     * both mean "omit the field", so callers store a real gear string or null.
     */
    fun buildRequest(
        model: ModelEntity,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        stream: Boolean = true,
    ): LlmRequest {
        val effort = model.reasoningEffort?.takeIf { it.isNotBlank() && it != "off" }
        val override = model.customJsonOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        return LlmRequest(
            modelIdRemote = model.modelIdRemote,
            messages = messages,
            tools = tools,
            reasoningEffort = effort,
            customJsonOverride = override,
            maxTokens = model.maxTokens,
            stream = stream,
        )
    }

    /** Drops a cached adapter (call after a provider is edited/deleted). */
    @Synchronized
    fun invalidate(providerId: String) {
        clientCache.remove(providerId)
    }

    /**
     * Fetches the provider's available model ids. Three list-endpoint shapes are supported:
     *  - **OpenAI / WeKit Router** (`OPENAI_CHAT_COMPLETION` / `OPENAI_RESPONSES` / `WEKIT_ROUTER`):
     *    `GET {baseUrl}/models` → `{ "data": [ { "id": "…" }, … ] }`. Auth: `Authorization: Bearer`.
     *  - **Gemini** (`GEMINI_GENERATE_CONTENT` / `GEMINI_INTERACTIONS`): `GET {baseUrl}/models` →
     *    `{ "models": [ { "name": "models/gemini-…", … }, … ] }`. Auth: `x-goog-api-key`.
     *    We strip the `"models/"` prefix so the stored id matches what goes in the URL / request.
     *  - **Anthropic** (`ANTHROPIC_MESSAGES`): no public list endpoint; returns a failure.
     *
     * [provider] must carry a decrypted API key.
     */
    suspend fun listRemoteModels(provider: ModelProviderEntity): Result<List<String>> {
        if (provider.type == ModelProviderType.ANTHROPIC_MESSAGES) {
            return Result.failure(LlmException("Anthropic 不支持自动获取模型列表，请手动添加。"))
        }
        val isGemini = provider.type == ModelProviderType.GEMINI_GENERATE_CONTENT
            || provider.type == ModelProviderType.GEMINI_INTERACTIONS
        // WeKit Router always uses its hardcoded base URL.
        val resolvedBase = if (provider.type == ModelProviderType.WEKIT_ROUTER) WEKIT_ROUTER_BASE_URL
                           else provider.baseUrl.trimEnd('/')
        val endpoint = "$resolvedBase/models"
        return runCatching {
            val resp = httpClient.get(endpoint) {
                if (provider.apiKey.isNotBlank()) {
                    if (isGemini) {
                        header(GeminiCommon.API_KEY_HEADER, provider.apiKey)
                    } else {
                        header(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                    }
                }
            }
            if (!resp.status.isSuccess()) {
                throw LlmException("HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}")
            }
            val root = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            if (isGemini) {
                // Gemini: { "models": [ { "name": "models/gemini-…" }, … ] }
                root["models"]?.jsonArray
                    ?.mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.content
                            ?.removePrefix("models/")
                            ?.takeIf(String::isNotBlank)
                    }
                    ?.distinct()
                    ?.sorted()
                    ?: emptyList()
            } else {
                // OpenAI: { "data": [ { "id": "…" }, … ] }
                root["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }
                    ?.distinct()
                    ?.sorted()
                    ?: emptyList()
            }
        }
    }

    /**
     * Fixed id for the built-in WeKit Router provider row in the Room database.
     */
    const val WEKIT_ROUTER_BUILTIN_ID = "wekit_router_builtin"

    /**
     * OpenAI-compatible base URL for the WeKit Router LLM endpoint.
     * [OpenAiChatCompletionsClient] appends `/chat/completions` to this, resulting in
     * `https://api.wekit.pro/v1/chat/completions`.
     */
    const val WEKIT_ROUTER_BASE_URL = "https://api.wekit.pro/v1"

}
