package dev.ujhhgtg.wekit.agent.mcp

import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.agent.data.entity.McpTransport
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.agent.tool.ToolProvider
import dev.ujhhgtg.wekit.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Connection state of an [McpToolProvider], surfaced in the settings UI (§4). */
enum class McpConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/**
 * Everything the settings UI shows about one MCP server, in a single immutable snapshot so a
 * composable can observe it with one collector instead of polling three independent fields.
 */
data class McpProviderStatus(
    val state: McpConnectionState = McpConnectionState.DISCONNECTED,
    val lastError: String? = null,
    val tools: List<ProviderTool> = emptyList(),
)

/**
 * One configured MCP server, adapted to the [ToolProvider] contract so it is structurally identical
 * to the builtin provider from the model's perspective (§3.4). Owns its MCP [Client] + transport,
 * caches its `tools/list` result, and reports connection [state]/[lastError] (§4).
 *
 * Reconnection with exponential backoff is orchestrated by [McpClientManager]; this class exposes
 * [connect]/[disconnect]/[refreshTools] as the primitives.
 */
class McpToolProvider(
    override val id: String,
    override val name: String,
    private val transport: McpTransport,
    private val endpointUrl: String,
    private val headers: Map<String, String>,
    private val httpClient: HttpClient,
) : ToolProvider {

    override val kind: ProviderKind = ProviderKind.MCP

    /**
     * Observable connection state + last error + cached tools/list. A [MutableStateFlow] rather than
     * plain `@Volatile` fields because the settings screen reads these from a composable: plain
     * fields never trigger recomposition, so the status line and tool list stayed frozen at whatever
     * they were when the screen was first composed (a successful refresh appeared to do nothing).
     * Updated from the connect/refresh coroutines via atomic [update], so it stays thread-safe.
     */
    private val _status = MutableStateFlow(McpProviderStatus())
    val status: StateFlow<McpProviderStatus> = _status.asStateFlow()

    val state: McpConnectionState get() = _status.value.state
    val lastError: String? get() = _status.value.lastError

    override val isAvailable: Boolean get() = state == McpConnectionState.CONNECTED

    private val connectMutex = Mutex()
    private var client: Client? = null

    /** Cached tools/list, refreshed on connect and on manual refresh. */
    override fun listTools(): List<ProviderTool> = _status.value.tools

    private fun requestBuilder(): HttpRequestBuilder.() -> Unit {
        val customHeaders = headers
        return { customHeaders.forEach { (k, v) -> headers.append(k, v) } }
    }

    /** Connects (idempotent guard via [connectMutex]) and caches the tool list. */
    suspend fun connect() = connectMutex.withLock {
        if (state == McpConnectionState.CONNECTED) return@withLock
        _status.update { it.copy(state = McpConnectionState.CONNECTING, lastError = null) }
        runCatching {
            val t = when (transport) {
                McpTransport.STREAMABLE_HTTP ->
                    StreamableHttpClientTransport(httpClient, endpointUrl, requestBuilder = requestBuilder())

                McpTransport.SSE ->
                    SseClientTransport(httpClient, endpointUrl, requestBuilder = requestBuilder())
            }
            t.onClose { onTransportClosed() }
            t.onError { e -> onTransportError(e) }

            val c = Client(Implementation(name = "wekit-mcp-client", version = BuildConfig.VERSION_NAME))
            c.connect(t)
            client = c
            val tools = fetchTools(c)
            _status.value = McpProviderStatus(McpConnectionState.CONNECTED, null, tools)
            WeLogger.i(TAG, "connected to MCP server '$name' ($endpointUrl), ${tools.size} tools")
        }.onFailure { e ->
            _status.update {
                it.copy(
                    state = McpConnectionState.FAILED,
                    lastError = e.message ?: e.javaClass.simpleName,
                )
            }
            WeLogger.e(TAG, "failed to connect MCP server '$name'", e)
            runCatching { client?.close() }
            client = null
        }
    }

    suspend fun disconnect() = connectMutex.withLock {
        runCatching { client?.close() }
        client = null
        _status.value = McpProviderStatus(McpConnectionState.DISCONNECTED)
    }

    /** Re-fetches tools/list from a connected server. No-op if disconnected. */
    suspend fun refreshTools(): Boolean = connectMutex.withLock {
        val c = client ?: return@withLock false
        runCatching {
            val tools = fetchTools(c)
            _status.update { it.copy(tools = tools) }
            true
        }.getOrElse {
            WeLogger.w(TAG, "refreshTools failed for '$name'", it); false
        }
    }

    private suspend fun fetchTools(c: Client): List<ProviderTool> =
        c.listTools().tools.map { tool ->
            ProviderTool(
                name = tool.name,
                description = tool.description ?: "",
                jsonSchema = buildSchema(tool.inputSchema.properties, tool.inputSchema.required),
                // Remote tools count as side-effecting, like side-effecting built-ins: the server
                // alone decides what each tool does, and its name/description go verbatim into the
                // model's context — so with a MESSAGE trigger someone else's chat message could
                // otherwise drive a destructive tool with no approval card under permissive levels.
                sideEffect = true,
            )
        }

    private fun buildSchema(properties: JsonObject?, required: List<String>?): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties ?: JsonObject(emptyMap()))
        if (required != null) {
            put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): String {
        val c = client ?: return "MCP server '$name' is not connected."
        return runCatching {
            val result = c.callTool(name = toolName, arguments = arguments.toPlainMap())
            val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (result.isError == true) "工具调用返回错误：$text" else text.ifEmpty { "(no textual content)" }
        }.getOrElse { "MCP tool '$toolName' failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** MCP callTool takes Map<String, Any?>; flatten our JsonObject to plain Kotlin values. */
    private fun JsonObject.toPlainMap(): Map<String, Any?> = mapValues { (_, v) -> McpJsonBridge.toPlain(v) }

    private fun onTransportClosed() {
        val changed = _status.getAndUpdate {
            if (it.state == McpConnectionState.CONNECTED) it.copy(state = McpConnectionState.DISCONNECTED) else it
        }.state == McpConnectionState.CONNECTED
        if (changed) WeLogger.w(TAG, "MCP server '$name' transport closed")
    }

    private fun onTransportError(e: Throwable) {
        val message = e.message ?: e.javaClass.simpleName
        _status.update { it.copy(lastError = message) }
        WeLogger.w(TAG, "MCP server '$name' transport error: $message")
    }

    companion object {
        private const val TAG = "McpToolProvider"
    }
}
