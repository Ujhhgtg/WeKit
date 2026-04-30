package dev.ujhhgtg.wekit.hooks.items.system.servers

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.Inet4Address
import java.net.NetworkInterface

@HookItem(path = "系统与隐私/API 服务器", description = "启用 MCP 与 REST API 服务器, 让人类与 AI 能够访问微信能力")
object ApiServer : ClickableHookItem() {

    private const val KEY_AUTH_TOKEN = "api_auth_token"
    private var AUTH_TOKEN
        get() = WePrefs.getStringOrDef(KEY_AUTH_TOKEN, "your_token")
        set(value) = WePrefs.putString(KEY_AUTH_TOKEN, value)

    private const val KEY_SERVER_PORT = "api_port"
    private var SERVER_PORT
        get() = WePrefs.getIntOrDef(KEY_SERVER_PORT, 3001)
        set(value) = WePrefs.putInt(KEY_SERVER_PORT, value)

    // -------------------------------------------------------------------------
    // REST response models
    // -------------------------------------------------------------------------

    @Serializable
    data class ErrorResponse(val error: String)

    @Serializable
    data class SuccessResponse(val success: Boolean = true)

    @Serializable
    data class WxIdResponse(val wxId: String)

    @Serializable
    data class DisplayNameResponse(val displayName: String)

    // -------------------------------------------------------------------------
    // Shared adapters: Result → protocol-specific output
    // -------------------------------------------------------------------------

    /** Converts a [WeChatService.Result] to a [CallToolResult] for MCP tool handlers. */
    private fun <T> WeChatService.Result<T>.toCallToolResult(transform: (T) -> CallToolResult): CallToolResult = when (this) {
        is WeChatService.Result.Success -> transform(data)
        is WeChatService.Result.Error -> CallToolResult(listOf(TextContent(message)), isError = true)
    }

    /**
     * Responds to an HTTP call based on a [WeChatService.Result]:
     * - [WeChatService.Result.Success] → invoke [onSuccess] with the unwrapped data
     * - [WeChatService.Result.Error]   → 400 Bad Request with [ErrorResponse]
     */
    private suspend fun <T> ApplicationCall.respondResult(
        result: WeChatService.Result<T>,
        onSuccess: suspend ApplicationCall.(T) -> Unit,
    ) = when (result) {
        is WeChatService.Result.Success -> onSuccess(result.data)
        is WeChatService.Result.Error -> respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
    }

    // Convenience helpers kept for MCP tool brevity
    private fun textRes(text: String, isError: Boolean? = null): CallToolResult =
        CallToolResult(listOf(TextContent(text)), isError)

    private fun textsRes(texts: List<String>): CallToolResult =
        CallToolResult(texts.map { TextContent(it) })

    // -------------------------------------------------------------------------
    // MCP server
    // -------------------------------------------------------------------------

    private val mcpServer by lazy {
        Server(
            serverInfo = Implementation(
                name = "wechat-mcp-server",
                version = BuildConfig.VERSION_NAME,
                title = "WeChat MCP Server (powered by WeKit)",
                websiteUrl = "https://github.com/Ujhhgtg/WeKit"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(true))
            )
        ).apply { registerMcpTools() }
    }

    private fun Server.registerMcpTools() {
        addTool(
            name = "send-message",
            description = "Send a message to a specific conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("type", "Type of message; can be 'text'")
                    addField("conv-id", "Conversation ID of target")
                    addField("content", "Content of message")
                },
                required = listOf("type", "conv-id", "content")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty")
            val type = args["type"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid type", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val content = args["content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid content", true)
            WeChatService.sendMessage(type, convId, content)
                .toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "list-contacts",
            description = "List all contacts",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("type", "Type of contacts to list; can be 'all', 'friends', 'groups', 'official_accounts'")
                },
                required = listOf("type")
            )
        ) { req ->
            val type = req.arguments?.get("type")?.jsonPrimitive?.content ?: return@addTool textRes("Invalid type")
            WeChatService.listContacts(type).toCallToolResult { contacts ->
                textsRes(contacts.map { c ->
                    if (type == "friends")
                        "WxId='${c.wxId}',Nickname='${c.nickname}',CustomWxid='${c.customWxid}',RemarkName='${c.remarkName}'"
                    else
                        "WxId='${c.wxId}',Nickname='${c.nickname}'"
                })
            }
        }

        addTool(
            name = "list-messages",
            description = "List paged messages of specific conversation; latest messages first",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("page-index", "Page index; defaults to 1, starts from 1", "integer")
                    addField("page-size", "Page size; defaults to 20", "integer")
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val pageIndex = args["page-index"]?.jsonPrimitive?.intOrNull ?: 1
            val pageSize = args["page-size"]?.jsonPrimitive?.intOrNull ?: 20
            WeChatService.listMessages(convId, pageIndex, pageSize).toCallToolResult { messages ->
                CallToolResult(messages.map { TextContent("${it.sender}: '${it.content}'") })
            }
        }

        addTool(
            name = "list-group-members",
            description = "List all members of specific group",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of group; must end with '@chatroom'")
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val groupId = req.arguments?.get("conv-id")?.jsonPrimitive?.content
                ?: return@addTool textRes("Invalid group ID", true)
            WeChatService.listGroupMembers(groupId).toCallToolResult { members ->
                textsRes(members.map {
                    "WxId='${it.wxId}',Nickname='${it.nickname}',CustomWxid='${it.customWxid}',RemarkName='${it.remarkName}'"
                })
            }
        }

        addTool(
            name = "get-conv-id-by-display-name",
            description = "Get conversation (friend or group) ID (also known as wxid) by its nickname or remark name; " +
                    "friend ID starts with 'wxid_', group ID ends with '@chatroom'; " +
                    "matches by its group-specific nickname if group-id is provided",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("display-name", "Display name of target")
                    addField("group-id", "Group ID; optional; must end with '@chatroom'")
                },
                required = listOf("display-name")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty")
            val displayName = args["display-name"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid target")
            val groupId = args["group-id"]?.jsonPrimitive?.content
            WeChatService.getConvIdByDisplayName(displayName, groupId)
                .toCallToolResult { textRes("WxId=$it") }
        }

        addTool(
            name = "get-display-name-by-conv-id",
            description = "Get display name by conversation ID (friend or group)",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField(
                        "conv-id",
                        "Conversation ID of target; friend ID starts with 'wxid_', group ID ends with '@chatroom'"
                    )
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            WeChatService.getDisplayNameByConvId(convId).toCallToolResult { textRes(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Ktor application setup
    // -------------------------------------------------------------------------

    private const val AUTH_PROVIDER_NAME = "server-bearer"

    private fun Application.configureServer() {
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowNonSimpleContentTypes = true
            allowHeader("Mcp-Session-Id")
            allowHeader("Mcp-Protocol-Version")
            allowHeader(HttpHeaders.Authorization)
            exposeHeader("Mcp-Session-Id")
            exposeHeader("Mcp-Protocol-Version")
        }

        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        install(Authentication) {
            bearer(AUTH_PROVIDER_NAME) {
                authenticate { credential ->
                    if (credential.token == AUTH_TOKEN)
                        UserIdPrincipal("client") else null
                }
            }
        }

        val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

        routing {
            authenticate(AUTH_PROVIDER_NAME) {
                mcpRoutes(transports)
                restRoutes()
            }
        }
    }

    // -------------------------------------------------------------------------
    // MCP routes  (/mcp)
    // -------------------------------------------------------------------------

    private fun Route.mcpRoutes(transports: ConcurrentMap<String, StreamableHttpServerTransport>) {
        route("/mcp") {
            sse {
                val transport = findTransport(call, transports) ?: return@sse
                transport.handleRequest(this, call)
            }
            post {
                val transport = getOrCreateTransport(call, transports) ?: return@post
                transport.handleRequest(null, call)
            }
            delete {
                val transport = findTransport(call, transports) ?: return@delete
                transport.handleRequest(null, call)
            }
        }
    }

    // -------------------------------------------------------------------------
    // REST API routes  (/api)
    //
    //  POST   /api/messages
    //  GET    /api/contacts?type=all|friends|groups|official_accounts
    //  GET    /api/contacts/lookup?display-name=...&group-id=...
    //  GET    /api/conversations/{convId}/messages?page-index=1&page-size=20
    //  GET    /api/conversations/{convId}/members
    //  GET    /api/conversations/{convId}/display-name
    // -------------------------------------------------------------------------

    private fun Route.restRoutes() {
        route("/api") {

            // POST /api/messages
            post("messages") {
                val req = runCatching { call.receive<WeChatService.SendMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendMessage(req)) {
                    respond(HttpStatusCode.OK, SuccessResponse())
                }
            }

            route("contacts") {
                // GET /api/contacts?type=...
                get {
                    val type = call.request.queryParameters["type"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'type' query parameter"))
                    call.respondResult(WeChatService.listContacts(type)) { contacts ->
                        respond(HttpStatusCode.OK, contacts)
                    }
                }

                // GET /api/contacts/lookup?display-name=...&group-id=...
                get("lookup") {
                    val displayName = call.request.queryParameters["display-name"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'display-name' query parameter"))
                    val groupId = call.request.queryParameters["group-id"]
                    call.respondResult(WeChatService.getConvIdByDisplayName(displayName, groupId)) { wxId ->
                        respond(HttpStatusCode.OK, WxIdResponse(wxId))
                    }
                }
            }

            route("conversations/{convId}") {
                // GET /api/conversations/{convId}/messages?page-index=1&page-size=20
                get("messages") {
                    val convId = call.parameters["convId"]!!
                    val pageIndex = call.request.queryParameters["page-index"]?.toIntOrNull() ?: 1
                    val pageSize = call.request.queryParameters["page-size"]?.toIntOrNull() ?: 20
                    call.respondResult(WeChatService.listMessages(convId, pageIndex, pageSize)) { messages ->
                        respond(HttpStatusCode.OK, messages)
                    }
                }

                // GET /api/conversations/{convId}/members
                get("members") {
                    val convId = call.parameters["convId"]!!
                    call.respondResult(WeChatService.listGroupMembers(convId)) { members ->
                        respond(HttpStatusCode.OK, members)
                    }
                }

                // GET /api/conversations/{convId}/display-name
                get("display-name") {
                    val convId = call.parameters["convId"]!!
                    call.respondResult(WeChatService.getDisplayNameByConvId(convId)) { name ->
                        respond(HttpStatusCode.OK, DisplayNameResponse(name))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Streamable transport helpers
    // -------------------------------------------------------------------------

    private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

    private suspend fun findTransport(
        call: ApplicationCall,
        transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    ): StreamableHttpServerTransport? {
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
            return null
        }
        val transport = transports[sessionId]
        if (transport == null) call.respond(HttpStatusCode.NotFound, "Session not found")
        return transport
    }

    private suspend fun getOrCreateTransport(
        call: ApplicationCall,
        transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    ): StreamableHttpServerTransport? {
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId != null) {
            val transport = transports[sessionId]
            if (transport == null) call.respond(HttpStatusCode.NotFound, "Session not found")
            return transport
        }

        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
        )
        transport.setOnSessionInitialized { id -> transports[id] = transport }
        transport.setOnSessionClosed { id -> transports.remove(id) }
        mcpServer.onClose { transport.sessionId?.let { transports.remove(it) } }
        mcpServer.createSession(transport)
        return transport
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private fun JsonObjectBuilder.addField(name: String, description: String, type: String = "string") {
        putJsonObject(name) {
            put("type", type)
            put("description", description)
        }
    }

    private fun getLanAddress(): String {
        val addr = NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { addr ->
                !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        addr is Inet4Address
            }
            ?.hostAddress
            ?: "0.0.0.0"
        return addr
    }

    private lateinit var netServer: EmbeddedServer<*, *>

    override fun onEnable() {
        val addr = getLanAddress()
        netServer = embeddedServer(Netty, host = addr, port = SERVER_PORT) {
            configureServer()
        }.start(wait = false)
        showToast("MCP 服务器启动于 http://$addr:$SERVER_PORT/mcp")
        showToast("REST API 服务器启动于 http://$addr:$SERVER_PORT/api")
    }

    override fun onDisable() {
        netServer.stop(1000, 2000)
        showToast("服务器已停止")
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var authToken by remember { mutableStateOf(AUTH_TOKEN) }
            var serverPortInput by remember { mutableStateOf(SERVER_PORT.toString()) }

            AlertDialogContent(
                title = { Text("API 服务器") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = authToken,
                            onValueChange = { authToken = it },
                            label = { Text("认证令牌") })
                        TextField(
                            value = serverPortInput,
                            onValueChange = { serverPortInput = it },
                            label = { Text("端口") })
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        val serverPort = serverPortInput.toIntOrNull()
                        if (serverPort == null || serverPort < 1024 || serverPort > 65536) {
                            showToast("端口格式不正确!")
                            return@Button
                        }

                        SERVER_PORT = serverPort
                        AUTH_TOKEN = authToken
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}
