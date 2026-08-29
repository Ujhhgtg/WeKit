package dev.ujhhgtg.wekit.agent.mcp

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the live set of [McpToolProvider]s (§4). Builds one per enabled MCP [ProviderEntity], drives
 * connect + exponential-backoff reconnect, keeps its tools/list cached, seeds their factory-default
 * permissions, and pushes the provider set into the [dev.ujhhgtg.wekit.agent.tool.ToolRegistry].
 *
 * A single shared Ktor [HttpClient] with the SSE plugin backs all transports (both Streamable HTTP
 * and SSE client sessions require it).
 */
object McpClientManager {

    private const val TAG = "McpClientManager"

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) { install(SSE) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // providerId -> live provider
    private val providerMap = ConcurrentHashMap<String, McpToolProvider>()

    // providerId -> its reconnect loop job
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    /**
     * The live provider set, observable so the settings UI recomposes when a server is added or
     * removed. Each provider's own connection state / tools live in [McpToolProvider.status].
     */
    private val _providers = MutableStateFlow<List<McpToolProvider>>(emptyList())
    val providers: StateFlow<List<McpToolProvider>> = _providers.asStateFlow()

    private fun publishProviders() {
        _providers.value = providerMap.values.toList()
    }

    /** Called when the connected-provider set changes, so the caller can refresh the tool registry. */
    @Volatile
    var onProvidersChanged: (() -> Unit)? = null

    fun connectedProviders(): List<McpToolProvider> = _providers.value

    /**
     * Reconciles the live provider set against the enabled MCP rows in Room. New enabled servers get
     * a provider + reconnect loop; removed/disabled ones are torn down. Idempotent — safe to call on
     * startup and whenever the server list changes.
     */
    suspend fun sync() {
        val rows = WeAgentRepository.getAllProviders()
            .filter { it.kind == ProviderKind.MCP && it.enabled }

        val wantedIds = rows.map { it.id }.toSet()

        // Tear down removed/disabled providers.
        providerMap.keys.filter { it !in wantedIds }.forEach { removeProvider(it) }

        // Add/keep wanted ones.
        for (row in rows) {
            if (providerMap.containsKey(row.id)) continue
            val provider = build(row) ?: continue
            providerMap[row.id] = provider
            startReconnectLoop(provider)
        }
        publishProviders()
        onProvidersChanged?.invoke()
    }

    private fun build(row: ProviderEntity): McpToolProvider? {
        val transport = row.transport ?: return null
        val url = row.endpointUrl?.takeIf { it.isNotBlank() } ?: return null
        val headers = parseHeaders(row.headersJson)
        return McpToolProvider(
            id = row.id,
            name = row.name,
            transport = transport,
            endpointUrl = url,
            headers = headers,
            httpClient = httpClient,
        )
    }

    private fun startReconnectLoop(provider: McpToolProvider) {
        reconnectJobs[provider.id]?.cancel()
        reconnectJobs[provider.id] = scope.launch {
            var attempt = 0
            while (isActive) {
                if (provider.state == McpConnectionState.CONNECTED) {
                    delay(HEALTHCHECK_INTERVAL_MS.milliseconds)
                    continue
                }
                provider.connect()
                if (provider.state == McpConnectionState.CONNECTED) {
                    attempt = 0
                    onProvidersChanged?.invoke()
                } else {
                    attempt++
                    val backoff = minOf(BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 6)), MAX_BACKOFF_MS)
                    WeLogger.w(TAG, "reconnect '${provider.name}' in ${backoff}ms (attempt $attempt)")
                    delay(backoff.milliseconds)
                }
            }
        }
    }

    private fun removeProvider(id: String) {
        reconnectJobs.remove(id)?.cancel()
        providerMap.remove(id)?.let { p -> scope.launch { p.disconnect() } }
        publishProviders()
    }

    /**
     * Manually re-fetch a server's tools/list (§4 "refresh tools"). A server that isn't connected is
     * connected first, so the button also works as "retry now" instead of silently no-op'ing while
     * the backoff loop sleeps. Both paths publish to [McpToolProvider.status], so the UI follows.
     */
    /** Rebuilds one provider from its stored row after its connection settings changed. */
    suspend fun reload(id: String) {
        removeProvider(id)
        sync()
    }

    suspend fun refreshTools(providerId: String): Boolean {
        val provider = providerMap[providerId] ?: return false
        if (provider.state != McpConnectionState.CONNECTED) {
            provider.connect()
            if (provider.state != McpConnectionState.CONNECTED) return false
            onProvidersChanged?.invoke()
            return true // connect() already refreshed tools/list
        }
        return provider.refreshTools().also { onProvidersChanged?.invoke() }
    }

    private fun parseHeaders(headersJson: String?): Map<String, String> {
        if (headersJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            (Json.parseToJsonElement(headersJson) as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        }.getOrElse {
            WeLogger.w(TAG, "failed to parse MCP headers json", it); emptyMap()
        }
    }

    private const val HEALTHCHECK_INTERVAL_MS = 30_000L
    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 60_000L
}
