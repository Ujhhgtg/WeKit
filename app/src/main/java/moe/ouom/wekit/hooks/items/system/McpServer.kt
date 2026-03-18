package moe.ouom.wekit.hooks.items.system

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.nameof.nameof
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
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.api.core.WeDatabaseApi
import moe.ouom.wekit.hooks.api.core.WeMessageApi
import moe.ouom.wekit.hooks.api.core.model.MessageType
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.LruCache
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.logging.WeLogger
import java.net.NetworkInterface

@HookItem(path = "系统与隐私/MCP 服务器", desc = "启用 MCP 服务器, 让 AI 能够访问宿主能力")
object McpServer : ClickableHookItem() {

    private val TAG = nameof(McpServer)

    private const val KEY_MCP_AUTH_TOKEN = "mcp_auth_token"
    private val MCP_AUTH_TOKEN
        get() = WePrefs.getStringOrDef("mcp_auth_token", "your_token")

    private val GROUP_MESSAGE_SENDER_REGEX = Regex("""^([^\n:]+):\n(.+)""", setOf(RegexOption.DOT_MATCHES_ALL))

    // groupId: String, Map<wxId: String, displayName: String>
    private val groupMembers = LruCache<String, Map<String, String>>()

    private val mcpServer by lazy {
        val server = Server(
            serverInfo = Implementation(
                name = "wechat-mcp-server",
                version = "1.0.0",
                title = "WeChat MCP Server",
                websiteUrl = "https://github.com/Ujhhgtg/WeKit"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(true)
                )
            )
        )

        server.addTool(
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
            val args = req.arguments ?: return@addTool mcpTextResult("Arguments are empty")
            val type = args["type"]?.jsonPrimitive?.content ?:
                return@addTool mcpTextResult("Invalid type", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?:
                return@addTool mcpTextResult("Invalid conversation ID", true)
            val content = args["content"]?.jsonPrimitive?.content ?:
                return@addTool mcpTextResult("Invalid content", true)
            when (type) {
                "text" ->
                    if (!WeMessageApi.sendText(convId, content))
                        return@addTool mcpTextResult("Failed to send message", true)
                else -> return@addTool mcpTextResult("Unsupported type: $type", true)
            }
            mcpTextResult("Sent successfully")
        }

        server.addTool(
            name = "list-contacts",
            description = "List all contacts",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("type",
                        "Type of contacts to list; can be 'all', 'friends', 'groups', 'official_accounts'")
                },
                required = listOf("type")
            )
        ) { req ->
            val type = req.arguments?.get("type")?.jsonPrimitive?.content ?:
                return@addTool mcpTextResult("Invalid type")
            when (type) {
                "all" -> mcpTextsResult(WeDatabaseApi.getContacts().map {
                    "WxId='${it.wxId}',Nickname='${it.nickname}'"
                })
                "friends" -> mcpTextsResult(WeDatabaseApi.getFriends().map {
                    "WxId='${it.wxId}',Nickname='${it.nickname}',CustomWxid='${it.customWxid}',RemarkName='${it.remarkName}'"
                })
                "groups" -> mcpTextsResult(WeDatabaseApi.getGroups().map {
                    "WxId='${it.wxId}',Nickname='${it.nickname}'"
                })
                "official_accounts" -> mcpTextsResult(WeDatabaseApi.getOfficialAccounts().map {
                    "WxId='${it.wxId}',Nickname='${it.nickname}'"
                })
                else -> mcpTextResult("Unsupported type: $type")
            }
        }

        server.addTool(
            name = "list-messages",
            description = "List partial messages of specific conversation; latest messages first",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id",
                        "Conversation ID of target")
                    addField("page-index",
                        "Page index; defaults to 1, starts from 1", "integer")
                    addField("page-size",
                        "Page size; defaults to 20", "integer")
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool mcpTextResult("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool mcpTextResult("Invalid conversation ID", true)
            val pageIndex = args["page-index"]?.jsonPrimitive?.intOrNull ?: 1
            val pageSize = args["page-size"]?.jsonPrimitive?.intOrNull ?: 20

            val isGroup = convId.endsWith("@chatroom")
            var membersMap = mapOf<String, String>()
            if (isGroup) {
                membersMap = groupMembers.getOrPut(convId) {
                    WeDatabaseApi.getGroupMembers(convId)
                        .associate { it.wxId to if (!it.remarkName.isBlank()) "${it.remarkName} (${it.nickname})" else it.nickname }
                }
            }

            val res = WeDatabaseApi.getMessages(convId, pageIndex, pageSize).map { msg ->
                val match = GROUP_MESSAGE_SENDER_REGEX.find(msg.content).takeIf { isGroup }
                val sender = if (match != null) {
                    val sender = match.groupValues[1]
                    membersMap[sender] ?: sender
                } else {
                    "<myself>"
                }
                val isText = MessageType.isText(msg.type)
                val content = if (isText) match?.groupValues[2] ?: msg.content else "omitted"
                val typeStr = MessageType.fromCode(msg.type)?.name?.lowercase() ?: "unknown"
                val contentStr = if (isText) content else "<type:$typeStr>"
                TextContent("$sender: '$contentStr'")
            }

            return@addTool CallToolResult(res)
        }

        server.addTool(
            name = "list-group-members",
            description = "List all members of specific group",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of group; must end with '@chatroom'")
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val groupId = req.arguments?.get("conv-id")?.jsonPrimitive?.content ?:
                return@addTool mcpTextResult("Invalid group ID", true)
            val members = WeDatabaseApi.getGroupMembers(groupId)
            mcpTextsResult(members.map {
                "WxId='${it.wxId}',Nickname='${it.nickname}',CustomWxid='${it.customWxid}',RemarkName='${it.remarkName}'"
            })
        }

        server.addTool(
            name = "get-conv-id-by-display-name",
            description = "Get conversation (friend or group) ID by its nickname or remark name; matches by its group-specific nickname if group-id is provided",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("display-name", "Display name of target")
                    addField("group-id", "Group ID; optional; must end with '@chatroom'")
                },
                required = listOf("display-name")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool mcpTextResult("Arguments are empty")
            val displayName = args["display-name"]?.jsonPrimitive?.content ?: return@addTool mcpTextResult("Invalid target")
            val groupId = args["group-id"]?.jsonPrimitive?.content

            if (groupId == null) {
                val friend = WeDatabaseApi.getFriends().find { it.nickname == displayName || it.remarkName == displayName }
                if (friend != null) {
                    return@addTool mcpTextResult("WxId=${friend.wxId}")
                }
                val group = WeDatabaseApi.getGroups().find { it.nickname == displayName }
                if (group != null) {
                    return@addTool mcpTextResult("WxId=${group.wxId}")
                }
                val official = WeDatabaseApi.getOfficialAccounts().find { it.nickname == displayName }
                if (official != null) {
                    return@addTool mcpTextResult("WxId=${official.wxId}")
                }
            }
            else {
                val members = WeDatabaseApi.getGroupMembers(groupId)
                val member = members.find { it.nickname == displayName || it.remarkName == displayName }
                if (member != null) {
                    return@addTool mcpTextResult("WxId=${member.wxId}")
                }
            }

            mcpTextResult("search matched 0 contact", true)
        }

        server.addTool(
            name = "get-display-name-by-conv-id",
            description = "Get display name by conversation ID (friend or group)",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target; friend ID starts with 'wxid_', group ID ends with '@chatroom'")
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool mcpTextResult("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool mcpTextResult("Invalid conversation ID", true)

            mcpTextResult(WeDatabaseApi.getDisplayName(convId))
        }

        server
    }

    private fun JsonObjectBuilder.addField(name: String, description: String, type: String = "string") {
        putJsonObject(name) {
            put("type", type)
            put("description", description)
        }
    }

    private fun mcpTextResult(text: String, isError: Boolean? = null): CallToolResult {
        return CallToolResult(listOf(TextContent(text)), isError)
    }

    private fun mcpTextsResult(texts: List<String>): CallToolResult {
        return CallToolResult(texts.map { TextContent(it) })
    }

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

        install(ContentNegotiation) {
            json(McpJson)
        }

        install(SSE)
        install(Authentication) {
            bearer("mcp-bearer") {
                authenticate { credential ->
                    if (credential.token == MCP_AUTH_TOKEN) {
                        UserIdPrincipal("mcp-client")
                    }
                    else {
                        null
                    }
                }
            }
        }

        val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

        routing {
            authenticate("mcp-bearer") {
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
        }
    }

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
        if (transport == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return null
        }
        return transport
    }

    private suspend fun getOrCreateTransport(
        call: ApplicationCall,
        transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    ): StreamableHttpServerTransport? {
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId != null) {
            val transport = transports[sessionId]
            if (transport == null) {
                call.respond(HttpStatusCode.NotFound, "Session not found")
            }
            return transport
        }

        val configuration = StreamableHttpServerTransport.Configuration(
            enableJsonResponse = true,
        )
        val transport = StreamableHttpServerTransport(configuration)

        transport.setOnSessionInitialized { initializedSessionId ->
            transports[initializedSessionId] = transport
        }
        transport.setOnSessionClosed { closedSessionId ->
            transports.remove(closedSessionId)
        }

        mcpServer.onClose {
            transport.sessionId?.let { transports.remove(it) }
        }
        mcpServer.createSession(transport)

        return transport
    }


    private fun getLanAddress(): String {
        val addr = NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { addr ->
                !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        addr is java.net.Inet4Address
            }
            ?.hostAddress
            ?: "0.0.0.0"
        return addr
    }

    private lateinit var netServer: EmbeddedServer<*, *>

    override fun onEnable() {
        val addr = getLanAddress()
        WeLogger.d(TAG, "binding to $addr:3001")
        netServer = embeddedServer(Netty, host = addr, port = 3001) {
            configureServer()
        }.start(wait = false)
        ToastUtils.showToast("MCP 服务器启动于 http://$addr:3001")
    }

    override fun onDisable() {
        netServer.stop(1000, 2000)
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var authToken by remember { mutableStateOf(MCP_AUTH_TOKEN) }
            AlertDialogContent(title = { Text("MCP 服务器") },
                text = {
                    TextField(value = authToken,
                        onValueChange = { authToken = it },
                        label = { Text("认证令牌") })
                },
                dismissButton = { TextButton(dismiss) { Text("取消") } },
                confirmButton = { Button(onClick = {
                    WePrefs.putString(KEY_MCP_AUTH_TOKEN, authToken)
                    dismiss()
                }) { Text("确定") } })
        }
    }
}