package dev.ujhhgtg.wekit.features.items.system.servers

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.serialization.asStringOrNull
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import dev.ujhhgtg.wekit.utils.strings.stripWxId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
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
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.io.path.div
import kotlin.time.Duration.Companion.milliseconds

@Feature(name = "API + MCP 服务器", categories = ["系统与隐私"], description = "启用 REST API 与 MCP 服务器, 让人类与 AI 能够访问微信能力")
object ApiServer : ClickableFeature() {

    private var authToken by prefOption("api_auth_token", "your_token")

    private var serverPort by prefOption("api_port", 3001)

    // -------------------------------------------------------------------------
    // REST request models
    // -------------------------------------------------------------------------

    @Serializable
    data class PathRequest(val path: String)

    @Serializable
    data class ImageMessageRequest(val convId: String, val path: String)

    @Serializable
    data class VoiceMessageRequest(val convId: String, val path: String, val durationMs: Int = 0)

    @Serializable
    data class FileMessageRequest(val convId: String, val path: String, val title: String)

    @Serializable
    data class EmojiMessageRequest(val convId: String, val emojiPathOrMd5: String)

    @Serializable
    data class PatMessageRequest(val convId: String, val patTarget: String)

    @Serializable
    data class LocationMessageRequest(val convId: String, val poiName: String, val label: String, val x: String, val y: String, val scale: String)

    @Serializable
    data class ShareCardMessageRequest(val convId: String, val cardWxId: String)

    @Serializable
    data class XmlMessageRequest(val convId: String, val xmlContent: String)

    @Serializable
    data class QuoteMessageRequest(val convId: String, val msgSvrId: Long, val content: String)

    @Serializable
    data class CipherMessageRequest(val convId: String, val title: String, val content: String)

    @Serializable
    data class NoteMessageRequest(val convId: String, val noteXml: String)

    @Serializable
    data class AppBrandMessageRequest(val convId: String, val title: String, val pagePath: String, val username: String)

    @Serializable
    data class SystemMessageRequest(val convId: String, val content: String, val timeMs: Long? = null)

    @Serializable
    data class ShareFileRequest(val convId: String, val title: String, val path: String, val appId: String)

    @Serializable
    data class ShareLinkRequest(
        val convId: String,
        val title: String,
        val description: String,
        val webpageUrl: String,
        val thumbDataBase64: String? = null,
        val appId: String
    )

    @Serializable
    data class ShareVideoRequest(
        val convId: String,
        val title: String,
        val description: String,
        val videoUrl: String,
        val thumbDataBase64: String? = null,
        val appId: String
    )

    @Serializable
    data class ShareTextRequest(val convId: String, val text: String, val appId: String)

    @Serializable
    data class ShareMusicRequest(
        val convId: String,
        val title: String,
        val description: String,
        val musicUrl: String,
        val musicDataUrl: String,
        val thumbDataBase64: String? = null,
        val appId: String
    )

    @Serializable
    data class ShareMusicVideoRequest(
        val convId: String,
        val title: String,
        val description: String,
        val musicUrl: String,
        val musicDataUrl: String,
        val singerName: String,
        val duration: Int,
        val songLyric: String,
        val thumbDataBase64: String? = null,
        val appId: String
    )

    @Serializable
    data class ShareMiniProgramRequest(
        val convId: String,
        val title: String,
        val description: String,
        val username: String,
        val path: String,
        val thumbDataBase64: String? = null,
        val appId: String
    )

    @Serializable
    data class MemberRequest(val memberWxid: String? = null, val memberWxids: List<String>? = null)

    @Serializable
    data class LabelsModifyRequest(val labels: List<String>)

    @Serializable
    data class MomentTextRequest(val content: String, val sdkId: String? = null, val sdkAppName: String? = null)

    @Serializable
    data class MomentPicsRequest(val content: String, val picPaths: List<String>, val sdkId: String? = null, val sdkAppName: String? = null)

    @Serializable
    data class ConfirmTransferRequest(val transId: String, val transSpanId: String, val invalidTime: Int)

    @Serializable
    data class RefuseTransferRequest(val transId: String, val transSpanId: String)

    @Serializable
    data class VerifyFriendRequest(val userId: String, val ticket: String, val scene: Int, val privacy: Int? = null)

    @Serializable
    data class DeviceStepRequest(val stepCount: Long)

    @Serializable
    data class AudioConvertRequest(val srcPath: String, val destPath: String)

    @Serializable
    data class JsLoginRequest(val appId: String)

    @Serializable
    data class CurrentTalkerRequest(val convId: String)

    @Serializable
    data class RevokeRequest(val msgSvrId: Long)

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

    @Serializable
    data class PathResponse(val path: String)

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

    private fun saveUploadedFile(part: PartData.FileItem): File {
        val ext = part.originalFileName?.substringAfterLast('.', "")?.let { if (it.isNotEmpty()) ".$it" else "" } ?: ""
        val file = (KnownPaths.moduleCache / "upload_${System.currentTimeMillis()}_${(0..10000).random()}$ext").toFile()
        part.provider().toInputStream().use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

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
            name = "send-text-message",
            description = "Send a text message to a specific conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("content", "Content of message")
                },
                required = listOf("conv-id", "content")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty")
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val content = args["content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid content", true)
            WeChatService.sendMessage("text", convId, content)
                .toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "get-contacts",
            description = "List all contacts by type",
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
                        "WxId='${c.wxId}',Nickname='${c.nickname}',CustomWxId='${c.customWxId}',RemarkName='${c.remarkName}'"
                    else
                        "WxId='${c.wxId}',Nickname='${c.nickname}'"
                })
            }
        }

        addTool(
            name = "get-chat-history",
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
            name = "cache-image",
            description = "Cache an image message into WeChat's own storage by its server ID (equivalent to tapping the image to download from CDN); " +
                    "does NOT decode or copy it to Download/WeKit/; returns the internal WeChat image path. May take a while if not cached yet.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("msg-svr-id", "Server ID (msgSvrId) of the image message to cache", "integer")
                },
                required = listOf("msg-svr-id")
            )
        ) { req ->
            val msgSvrId = req.arguments?.get("msg-svr-id")?.jsonPrimitive?.longOrNull
                ?: return@addTool textRes("Invalid msg-svr-id", true)
            WeChatService.cacheImage(msgSvrId).toCallToolResult { textRes(it) }
        }

        addTool(
            name = "download-image",
            description = "Download the image of an image message by its server ID: cache it from CDN if needed, then decode and save it to Download/WeKit/; " +
                    "returns the saved local file path. May take a while if not cached yet.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("msg-svr-id", "Server ID (msgSvrId) of the image message to download", "integer")
                },
                required = listOf("msg-svr-id")
            )
        ) { req ->
            val msgSvrId = req.arguments?.get("msg-svr-id")?.jsonPrimitive?.longOrNull
                ?: return@addTool textRes("Invalid msg-svr-id", true)
            WeChatService.downloadImage(msgSvrId).toCallToolResult { textRes(it) }
        }

        addTool(
            name = "download-sticker",
            description = "Decode the sticker of a sticker message by its server ID, convert it to a GIF and save it to Download/WeKit/; " +
                    "returns the saved local file path",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("msg-svr-id", "Server ID (msgSvrId) of the sticker message to download", "integer")
                },
                required = listOf("msg-svr-id")
            )
        ) { req ->
            val msgSvrId = req.arguments?.get("msg-svr-id")?.jsonPrimitive?.longOrNull
                ?: return@addTool textRes("Invalid msg-svr-id", true)
            WeChatService.downloadSticker(msgSvrId).toCallToolResult { textRes(it) }
        }

        addTool(
            name = "download-voice",
            description = "Decode the voice of a voice message by its server ID (silk → mp3) and save it to Download/WeKit/; " +
                    "returns the saved local mp3 file path",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("msg-svr-id", "Server ID (msgSvrId) of the voice message to download", "integer")
                },
                required = listOf("msg-svr-id")
            )
        ) { req ->
            val msgSvrId = req.arguments?.get("msg-svr-id")?.jsonPrimitive?.longOrNull
                ?: return@addTool textRes("Invalid msg-svr-id", true)
            WeChatService.downloadVoice(msgSvrId).toCallToolResult { textRes(it) }
        }

        addTool(
            name = "cache-file",
            description = "Cache a file message into WeChat's own storage by its server ID (equivalent to tapping the file bubble to download); " +
                    "does NOT copy it to Download/WeKit/; returns the internal WeChat file path. May take a while for large files.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("msg-svr-id", "Server ID (msgSvrId) of the file message to cache", "integer")
                    addField("conv-id", "Conversation ID (wxId / talker) of the conversation the message belongs to; can be empty, if empty, the module tries to auto-detect the convId.")
                },
                required = listOf("conv-id", "msg-svr-id")
            )
        ) { req ->
            val msgSvrId = req.arguments?.get("conv-id")?.jsonPrimitive?.longOrNull
                ?: return@addTool textRes("Invalid msg-svr-id", true)
            val convId = req.arguments?.get("conv-id")?.jsonPrimitive?.asStringOrNull
            WeChatService.cacheFile(msgSvrId, convId?.ifEmpty { null }).toCallToolResult { textRes(it) }
        }

        addTool(
            name = "download-file",
            description = "Download a file message by its server ID: first cache it into WeChat's storage if needed, then copy it to Download/WeKit/; " +
                    "returns the saved local file path. May take a while for large files.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("msg-svr-id", "Server ID (msgSvrId) of the file message to download", "integer")
                    addField("conv-id", "Conversation ID (wxId / talker) of the conversation the message belongs to; can be empty, if empty, the module tries to auto-detect the convId.")
                },
                required = listOf("conv-id", "msg-svr-id")
            )
        ) { req ->
            val msgSvrId = req.arguments?.get("msg-svr-id")?.jsonPrimitive?.longOrNull
                ?: return@addTool textRes("Invalid msg-svr-id", true)
            val convId = req.arguments?.get("conv-id")?.jsonPrimitive?.asStringOrNull
            WeChatService.downloadFile(msgSvrId, convId?.ifEmpty { null }).toCallToolResult { textRes(it) }
        }

        addTool(
            name = "get-group-members",
            description = "List all members of specific group",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("group-id", "Group ID; must end with '@chatroom'")
                },
                required = listOf("group-id")
            )
        ) { req ->
            val groupId = req.arguments?.get("group-id")?.jsonPrimitive?.content
                ?: return@addTool textRes("Invalid group ID", true)
            WeChatService.listGroupMembers(groupId).toCallToolResult { members ->
                textsRes(members.map {
                    "WxId='${it.wxId}',Nickname='${it.nickname}',CustomWxId='${it.customWxId}',RemarkName='${it.remarkName}'"
                })
            }
        }

        addTool(
            name = "lookup-contact-id",
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
            name = "get-contact-display-name",
            description = "Get display name by conversation/contact ID",
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

        addTool(
            name = "get-self-info",
            description = "Get details of the currently logged-in user profile",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) {
            WeChatService.getSelfInfo().toCallToolResult { self ->
                textRes("WxId='${self.wxId}',CustomWxId='${self.customWxId}'")
            }
        }

        addTool(
            name = "get-current-talker",
            description = "Get current conversation ID of the active chat window",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) {
            WeChatService.getCurrentTalker().toCallToolResult { textRes(it) }
        }

        addTool(
            name = "set-current-talker",
            description = "Manually set the current active conversation ID/talker",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID to set")
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val convId = req.arguments?.get("conv-id")?.jsonPrimitive?.content
                ?: return@addTool textRes("Invalid conversation ID", true)
            WeChatService.setCurrentTalker(convId).toCallToolResult { textRes("Talker set successfully") }
        }

        addTool(
            name = "get-contact-detail",
            description = "Get detailed information of a contact (friend or group) by conversation ID",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                },
                required = listOf("conv-id")
            )
        ) { req ->
            val convId = req.arguments?.get("conv-id")?.jsonPrimitive?.content
                ?: return@addTool textRes("Invalid conversation ID", true)
            WeChatService.getContactDetail(convId).toCallToolResult { c ->
                textRes("WxId='${c.wxId}',Nickname='${c.nickname}',CustomWxId='${c.customWxId}',RemarkName='${c.remarkName}',DisplayName='${c.displayName}',AvatarUrl='${c.avatarUrl}',Type=${c.type},IsGroup=${c.isGroup}")
            }
        }

        addTool(
            name = "send-image-message",
            description = "Send an image to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("path", "Local file path of image on device")
                },
                required = listOf("conv-id", "path")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val path = args["path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid path", true)
            WeChatService.sendImageMessage(convId, path).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-voice-message",
            description = "Send a voice recording (silk format) to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("path", "Local file path of voice file (silk/amr) on device")
                    addField("duration-ms", "Optional playback duration in milliseconds", "integer")
                },
                required = listOf("conv-id", "path")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val path = args["path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid path", true)
            val durationMs = args["duration-ms"]?.jsonPrimitive?.intOrNull ?: 0
            WeChatService.sendVoiceMessage(convId, path, durationMs).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-video-message",
            description = "Send a video file to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("path", "Local file path of video on device")
                },
                required = listOf("conv-id", "path")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val path = args["path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid path", true)
            WeChatService.sendVideoMessage(convId, path).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-file-message",
            description = "Send a document/file to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("path", "Local file path on device")
                    addField("title", "Filename/title to display")
                },
                required = listOf("conv-id", "path", "title")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val path = args["path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid path", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            WeChatService.sendFileMessage(convId, path, title).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-emoji-message",
            description = "Send an emoji (using MD5 or path) to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("emoji-path-or-md5", "MD5 or local path of emoji file")
                },
                required = listOf("conv-id", "emoji-path-or-md5")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val paramVal = args["emoji-path-or-md5"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid emoji param", true)
            WeChatService.sendEmojiMessage(convId, paramVal).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-pat-message",
            description = "Send a pat (nudge) to a contact in a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target (friend wxid or group chatroom)")
                    addField("pat-target", "WeChat wxid of the user you want to pat")
                },
                required = listOf("conv-id", "pat-target")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val patTarget = args["pat-target"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid pat-target", true)
            WeChatService.sendPatMessage(convId, patTarget).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-location-message",
            description = "Send a location card to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("poi-name", "POI Name (e.g. 'Peking University')")
                    addField("label", "Label / Address details")
                    addField("x", "Latitude coordinate string")
                    addField("y", "Longitude coordinate string")
                    addField("scale", "Map zoom scale string")
                },
                required = listOf("conv-id", "poi-name", "label", "x", "y", "scale")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val poiName = args["poi-name"]?.jsonPrimitive?.content ?: ""
            val label = args["label"]?.jsonPrimitive?.content ?: ""
            val x = args["x"]?.jsonPrimitive?.content ?: "0"
            val y = args["y"]?.jsonPrimitive?.content ?: "0"
            val scale = args["scale"]?.jsonPrimitive?.content ?: "0"
            WeChatService.sendLocationMessage(convId, poiName, label, x, y, scale).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-share-card-message",
            description = "Send a contact card to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("card-wxid", "WeChat ID of contact to share")
                },
                required = listOf("conv-id", "card-wxid")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val cardWxId = args["card-wxid"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid card-wxid", true)
            WeChatService.sendShareCardMessage(convId, cardWxId).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-xml-message",
            description = "Send an app message with raw XML content to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("xml-content", "Raw XML structure")
                },
                required = listOf("conv-id", "xml-content")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val xmlContent = args["xml-content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid xml-content", true)
            WeChatService.sendXmlMessage(convId, xmlContent).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-quote-message",
            description = "Send a quote (reply) message quoting a specific message by its server ID",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("msg-svr-id", "Server ID of the message to quote", "integer")
                    addField("content", "Reply message text content")
                },
                required = listOf("conv-id", "msg-svr-id", "content")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val msgSvrId = args["msg-svr-id"]?.jsonPrimitive?.intOrNull?.toLong() ?: return@addTool textRes("Invalid msg-svr-id", true)
            val content = args["content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid content", true)
            WeChatService.sendQuoteMessage(convId, msgSvrId, content).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-cipher-message",
            description = "Send a custom cipher HTML message to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Title description")
                    addField("content", "HTML or cipher content")
                },
                required = listOf("conv-id", "title", "content")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val content = args["content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid content", true)
            WeChatService.sendCipherMessage(convId, title, content).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-note-message",
            description = "Send a solitaire/note message with solitaire XML structure to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("note-xml", "Content inside the solitaire note XML")
                },
                required = listOf("conv-id", "note-xml")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val noteXml = args["note-xml"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid note-xml", true)
            WeChatService.sendNoteMessage(convId, noteXml).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "send-app-brand-message",
            description = "Send a WeChat Mini Program card to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Title of Mini Program card")
                    addField("page-path", "Mini Program page query path")
                    addField("username", "Mini Program original username (starts with 'gh_')")
                },
                required = listOf("conv-id", "title", "page-path", "username")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val pagePath = args["page-path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid page-path", true)
            val username = args["username"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid username", true)
            WeChatService.sendAppBrandMessage(convId, title, pagePath, username).toCallToolResult { textRes("Sent successfully") }
        }

        addTool(
            name = "revoke-message",
            description = "Revoke (unsend) a message by its ID (msgId)",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("msg-id", "Local ID of message to revoke", "integer")
                },
                required = listOf("msg-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val msgId = args["msg-id"]?.jsonPrimitive?.intOrNull?.toLong() ?: return@addTool textRes("Invalid msg-id", true)
            WeChatService.revokeMessage(msgId).toCallToolResult { textRes("Revoked successfully") }
        }

        addTool(
            name = "insert-system-message",
            description = "Insert a system/solitaire message into local database chat history",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("content", "System message content")
                    addField("time-ms", "Optional timestamp in milliseconds", "integer")
                },
                required = listOf("conv-id", "content")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val content = args["content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid content", true)
            val timeMs = args["time-ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: System.currentTimeMillis()
            WeChatService.insertSystemMessage(convId, content, timeMs).toCallToolResult { textRes("Inserted successfully") }
        }

        addTool(
            name = "share-file",
            description = "Share a file to a conversation with app ID",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Display title of file")
                    addField("path", "Local file path on device")
                    addField("app-id", "WeChat Open Platform App ID")
                },
                required = listOf("conv-id", "title", "path", "app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val path = args["path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid path", true)
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            WeChatService.shareFile(convId, title, path, appId).toCallToolResult { textRes("Shared successfully") }
        }

        addTool(
            name = "share-webpage",
            description = "Share a webpage link to a conversation with optional base64 thumb image",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Webpage title")
                    addField("description", "Webpage description")
                    addField("webpage-url", "Target webpage URL link")
                    addField("app-id", "WeChat Open Platform App ID")
                    addField("thumb-data-base64", "Optional base64 encoded thumbnail image data")
                },
                required = listOf("conv-id", "title", "webpage-url", "app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val description = args["description"]?.jsonPrimitive?.content ?: ""
            val webpageUrl = args["webpage-url"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid webpage-url", true)
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            val thumb = args["thumb-data-base64"]?.jsonPrimitive?.content
            WeChatService.shareWebpage(convId, title, description, webpageUrl, thumb, appId).toCallToolResult { textRes("Shared successfully") }
        }

        addTool(
            name = "share-video",
            description = "Share a video link to a conversation with optional base64 thumb image",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Video title")
                    addField("description", "Video description")
                    addField("video-url", "Target video URL link")
                    addField("app-id", "WeChat Open Platform App ID")
                    addField("thumb-data-base64", "Optional base64 encoded thumbnail image data")
                },
                required = listOf("conv-id", "title", "video-url", "app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val description = args["description"]?.jsonPrimitive?.content ?: ""
            val videoUrl = args["video-url"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid video-url", true)
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            val thumb = args["thumb-data-base64"]?.jsonPrimitive?.content
            WeChatService.shareVideo(convId, title, description, videoUrl, thumb, appId).toCallToolResult { textRes("Shared successfully") }
        }

        addTool(
            name = "share-text",
            description = "Share a text block using app ID to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("text", "Text content to share")
                    addField("app-id", "WeChat Open Platform App ID")
                },
                required = listOf("conv-id", "text", "app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val text = args["text"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid text", true)
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            WeChatService.shareText(convId, text, appId).toCallToolResult { textRes("Shared successfully") }
        }

        addTool(
            name = "share-music",
            description = "Share music to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Music title")
                    addField("description", "Music description")
                    addField("music-url", "Target webpage URL link")
                    addField("music-data-url", "Raw audio URL link")
                    addField("app-id", "WeChat Open Platform App ID")
                    addField("thumb-data-base64", "Optional base64 encoded thumbnail image data")
                },
                required = listOf("conv-id", "title", "music-url", "app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val description = args["description"]?.jsonPrimitive?.content ?: ""
            val musicUrl = args["music-url"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid music-url", true)
            val musicDataUrl = args["music-data-url"]?.jsonPrimitive?.content ?: ""
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            val thumb = args["thumb-data-base64"]?.jsonPrimitive?.content
            WeChatService.shareMusic(convId, title, description, musicUrl, musicDataUrl, thumb, appId).toCallToolResult { textRes("Shared successfully") }
        }

        addTool(
            name = "share-music-video",
            description = "Share a music video to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Music Video title")
                    addField("description", "Music Video description")
                    addField("music-url", "Target webpage URL link")
                    addField("music-data-url", "Raw audio URL link")
                    addField("singer-name", "Singer's name")
                    addField("duration", "Duration in seconds", "integer")
                    addField("song-lyric", "Song lyrics text")
                    addField("app-id", "WeChat Open Platform App ID")
                    addField("thumb-data-base64", "Optional base64 encoded thumbnail image data")
                },
                required = listOf("conv-id", "title", "music-url", "app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val description = args["description"]?.jsonPrimitive?.content ?: ""
            val musicUrl = args["music-url"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid music-url", true)
            val musicDataUrl = args["music-data-url"]?.jsonPrimitive?.content ?: ""
            val singerName = args["singer-name"]?.jsonPrimitive?.content ?: ""
            val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: 0
            val songLyric = args["song-lyric"]?.jsonPrimitive?.content ?: ""
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            val thumb = args["thumb-data-base64"]?.jsonPrimitive?.content
            WeChatService.shareMusicVideo(convId, title, description, musicUrl, musicDataUrl, singerName, duration, songLyric, thumb, appId)
                .toCallToolResult { textRes("Shared successfully") }
        }

        addTool(
            name = "share-mini-program",
            description = "Share a Mini Program app link card to a conversation",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID of target")
                    addField("title", "Mini Program card title")
                    addField("description", "Mini Program description")
                    addField("username", "Mini Program original username (starts with 'gh_')")
                    addField("path", "Mini Program page query path")
                    addField("app-id", "WeChat Open Platform App ID")
                    addField("thumb-data-base64", "Optional base64 encoded thumbnail image data")
                },
                required = listOf("conv-id", "title", "username", "path", "app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val title = args["title"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid title", true)
            val description = args["description"]?.jsonPrimitive?.content ?: ""
            val username = args["username"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid username", true)
            val path = args["path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid path", true)
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            val thumb = args["thumb-data-base64"]?.jsonPrimitive?.content
            WeChatService.shareMiniProgram(convId, title, description, username, path, thumb, appId).toCallToolResult { textRes("Shared successfully") }
        }

        addTool(
            name = "add-chatroom-member",
            description = "Add a member (or multiple members) to a chatroom",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("group-id", "Conversation ID of target chatroom; must end with '@chatroom'")
                    addField("member-wxid", "Optional single member wxid")
                    addField("member-wxids", "Optional array of member wxids; takes precedence if provided", "array")
                },
                required = listOf("group-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val groupId = args["group-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid group ID", true)
            val memberWxid = args["member-wxid"]?.jsonPrimitive?.content
            val memberWxids = args["member-wxids"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (memberWxids != null) {
                WeChatService.addChatroomMembers(groupId, memberWxids).toCallToolResult { textRes("Members added successfully") }
            } else if (memberWxid != null) {
                WeChatService.addChatroomMember(groupId, memberWxid).toCallToolResult { textRes("Member added successfully") }
            } else {
                textRes("Either member-wxid or member-wxids must be provided", true)
            }
        }

        addTool(
            name = "del-chatroom-member",
            description = "Remove a member (or multiple members) from a chatroom",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("group-id", "Conversation ID of target chatroom; must end with '@chatroom'")
                    addField("member-wxid", "Optional single member wxid")
                    addField("member-wxids", "Optional array of member wxids; takes precedence if provided", "array")
                },
                required = listOf("group-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val groupId = args["group-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid group ID", true)
            val memberWxid = args["member-wxid"]?.jsonPrimitive?.content
            val memberWxids = args["member-wxids"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (memberWxids != null) {
                WeChatService.delChatroomMembers(groupId, memberWxids).toCallToolResult { textRes("Members deleted successfully") }
            } else if (memberWxid != null) {
                WeChatService.delChatroomMember(groupId, memberWxid).toCallToolResult { textRes("Member deleted successfully") }
            } else {
                textRes("Either member-wxid or member-wxids must be provided", true)
            }
        }

        addTool(
            name = "invite-chatroom-member",
            description = "Invite a member (or multiple members) to join a chatroom",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("group-id", "Conversation ID of target chatroom; must end with '@chatroom'")
                    addField("member-wxid", "Optional single member wxid")
                    addField("member-wxids", "Optional array of member wxids; takes precedence if provided", "array")
                },
                required = listOf("group-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val groupId = args["group-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid group ID", true)
            val memberWxid = args["member-wxid"]?.jsonPrimitive?.content
            val memberWxids = args["member-wxids"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (memberWxids != null) {
                WeChatService.inviteChatroomMembers(groupId, memberWxids).toCallToolResult { textRes("Invitations sent successfully") }
            } else if (memberWxid != null) {
                WeChatService.inviteChatroomMember(groupId, memberWxid).toCallToolResult { textRes("Invitation sent successfully") }
            } else {
                textRes("Either member-wxid or member-wxids must be provided", true)
            }
        }

        addTool(
            name = "list-contact-labels",
            description = "List all custom contact labels/tags",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) {
            WeChatService.listContactLabels().toCallToolResult { labels ->
                textsRes(labels.map { "LabelID=${it.labelId},LabelName='${it.labelName}'" })
            }
        }

        addTool(
            name = "get-contacts-by-label",
            description = "List all contact wxids associated with a specific label ID or name",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("label-id-or-name", "ID (integer) or name (string) of label")
                },
                required = listOf("label-id-or-name")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val labelVal = args["label-id-or-name"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid label", true)
            WeChatService.getContactsByLabel(labelVal).toCallToolResult { textsRes(it) }
        }

        addTool(
            name = "modify-contact-labels",
            description = "Update/modify the list of labels associated with a contact",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("wxid", "WeChat wxid of contact")
                    addField("labels", "Array of label names to assign", "array")
                },
                required = listOf("wxid", "labels")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val wxId = args["wxid"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid wxid", true)
            val labels = args["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return@addTool textRes("Invalid labels", true)
            WeChatService.modifyContactLabels(wxId, labels).toCallToolResult { textRes("Labels modified successfully") }
        }

        addTool(
            name = "post-moment-text",
            description = "Post a text-only moment to WeChat Moments",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("content", "Moment text content")
                    addField("sdk-id", "Optional developer App ID")
                    addField("sdk-app-name", "Optional developer App name")
                },
                required = listOf("content")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val content = args["content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid content", true)
            val sdkId = args["sdk-id"]?.jsonPrimitive?.content
            val sdkAppName = args["sdk-app-name"]?.jsonPrimitive?.content
            WeChatService.postMomentText(content, sdkId, sdkAppName).toCallToolResult { textRes("Posted successfully") }
        }

        addTool(
            name = "post-moment-pics",
            description = "Post a moment with text and multiple images to WeChat Moments",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("content", "Moment text content")
                    addField("pic-paths", "Array of local picture file paths on device", "array")
                    addField("sdk-id", "Optional developer App ID")
                    addField("sdk-app-name", "Optional developer App name")
                },
                required = listOf("content", "pic-paths")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val content = args["content"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid content", true)
            val picPaths = args["pic-paths"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return@addTool textRes("Invalid pic-paths", true)
            val sdkId = args["sdk-id"]?.jsonPrimitive?.content
            val sdkAppName = args["sdk-app-name"]?.jsonPrimitive?.content
            WeChatService.postMomentPics(content, picPaths, sdkId, sdkAppName).toCallToolResult { textRes("Posted successfully") }
        }

        addTool(
            name = "confirm-transfer",
            description = "Accept/confirm an incoming transfer payment",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID where the transfer is located")
                    addField("trans-id", "Transfer ID")
                    addField("trans-span-id", "Transfer span ID")
                    addField("invalid-time", "Transfer validity window time value", "integer")
                },
                required = listOf("conv-id", "trans-id", "trans-span-id", "invalid-time")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val transId = args["trans-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid trans-id", true)
            val transSpanId = args["trans-span-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid trans-span-id", true)
            val invalidTime = args["invalid-time"]?.jsonPrimitive?.intOrNull ?: return@addTool textRes("Invalid invalid-time", true)
            WeChatService.confirmTransfer(convId, transId, transSpanId, invalidTime).toCallToolResult { textRes("Transfer confirmed successfully") }
        }

        addTool(
            name = "refuse-transfer",
            description = "Reject/refuse an incoming transfer payment",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Conversation ID where the transfer is located")
                    addField("trans-id", "Transfer ID")
                    addField("trans-span-id", "Transfer span ID")
                },
                required = listOf("conv-id", "trans-id", "trans-span-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val convId = args["conv-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid conversation ID", true)
            val transId = args["trans-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid trans-id", true)
            val transSpanId = args["trans-span-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid trans-span-id", true)
            WeChatService.refuseTransfer(convId, transId, transSpanId).toCallToolResult { textRes("Transfer refused successfully") }
        }

        addTool(
            name = "verify-friend",
            description = "Verify/approve an incoming friend request",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("user-id", "WeChat wxid of requester")
                    addField("ticket", "Verification ticket string")
                    addField("scene", "Verification scene indicator", "integer")
                    addField("privacy", "Optional privacy option settings", "integer")
                },
                required = listOf("user-id", "ticket", "scene")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val userId = args["user-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid user-id", true)
            val ticket = args["ticket"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid ticket", true)
            val scene = args["scene"]?.jsonPrimitive?.intOrNull ?: return@addTool textRes("Invalid scene", true)
            val privacy = args["privacy"]?.jsonPrimitive?.intOrNull
            WeChatService.verifyFriend(userId, ticket, scene, privacy).toCallToolResult { textRes("Friend verified successfully") }
        }

        addTool(
            name = "upload-device-step",
            description = "Manually report/upload a sport step count to WeChat",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("step-count", "Target sport step count", "integer")
                },
                required = listOf("step-count")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val stepCount = args["step-count"]?.jsonPrimitive?.intOrNull?.toLong() ?: return@addTool textRes("Invalid step-count", true)
            WeChatService.uploadDeviceStep(stepCount).toCallToolResult { textRes("Steps uploaded successfully") }
        }

        addTool(
            name = "audio-mp3-to-silk",
            description = "Convert a local MP3 file to WeChat Silk format",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("src-path", "Absolute source file path of MP3")
                    addField("dest-path", "Absolute target file path for Silk")
                },
                required = listOf("src-path", "dest-path")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val src = args["src-path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid src-path", true)
            val dest = args["dest-path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid dest-path", true)
            WeChatService.audioMp3ToSilk(src, dest).toCallToolResult { textRes("Converted successfully") }
        }

        addTool(
            name = "audio-silk-to-mp3",
            description = "Convert a local WeChat Silk file to MP3 format",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("src-path", "Absolute source file path of Silk")
                    addField("dest-path", "Absolute target file path for MP3")
                },
                required = listOf("src-path", "dest-path")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val src = args["src-path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid src-path", true)
            val dest = args["dest-path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid dest-path", true)
            WeChatService.audioSilkToMp3(src, dest).toCallToolResult { textRes("Converted successfully") }
        }

        addTool(
            name = "audio-get-duration",
            description = "Get the playback duration (in milliseconds) of a local audio file",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("path", "Absolute local audio file path")
                },
                required = listOf("path")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val path = args["path"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid path", true)
            WeChatService.audioGetDuration(path).toCallToolResult { textRes("DurationMs=$it") }
        }

        addTool(
            name = "js-login",
            description = "Request a web application authorization login code (js-login) for an App ID",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("app-id", "Developer WeChat App ID")
                },
                required = listOf("app-id")
            )
        ) { req ->
            val args = req.arguments ?: return@addTool textRes("Arguments are empty", true)
            val appId = args["app-id"]?.jsonPrimitive?.content ?: return@addTool textRes("Invalid app-id", true)
            WeChatService.jsLogin(appId).toCallToolResult { textRes(it) }
        }

        addTool(
            name = "wait-for-new-message",
            description = "Block until a new incoming message arrives, then return it. " +
                    "If conv-id is given, only messages from that conversation are awaited; " +
                    "otherwise the first incoming message from any conversation is returned. " +
                    "Self-sent messages are ignored. Returns nothing if no message arrives before the timeout.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    addField("conv-id", "Optional conversation ID to wait on; waits on any conversation if omitted")
                    addField("timeout-ms", "Optional max time to wait in milliseconds; defaults to 60000", "integer")
                },
                required = emptyList()
            )
        ) { req ->
            val args = req.arguments
            val convId = args?.get("conv-id")?.jsonPrimitive?.content
            val timeoutMs = args?.get("timeout-ms")?.jsonPrimitive?.intOrNull?.toLong() ?: 60_000L

            val deferred = CompletableDeferred<ContentValues>()
            val listener = WeDatabaseListenerApi.IInsertListener { table, values ->
                if (deferred.isCompleted) return@IInsertListener
                if (table != "message") return@IInsertListener
                // ignore self-sent messages
                if (values.getAsInteger("isSend") ?: 0 == 1) return@IInsertListener
                val talker = values.getAsString("talker") ?: return@IInsertListener
                if (convId != null && talker != convId) return@IInsertListener
                deferred.complete(values)
            }

            WeDatabaseListenerApi.addListener(listener)
            val values = try {
                withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
            } finally {
                WeDatabaseListenerApi.removeListener(listener)
            } ?: return@addTool textRes("No new message arrived within ${timeoutMs}ms")

            val talker = values.getAsString("talker") ?: ""
            val type = values.getAsInteger("type") ?: 0
            val rawContent = values.getAsString("content") ?: ""
            val sender: String
            val content: String
            if (talker.isGroupChatWxId) {
                sender = rawContent.substringBefore(':', "").ifEmpty { talker }
                content = rawContent.stripWxId()
            } else {
                sender = talker
                content = rawContent
            }
            textRes("ConvId='$talker',Sender='$sender',Type=$type,Content='$content'")
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
                    if (credential.token == authToken)
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
    // -------------------------------------------------------------------------

    private fun Route.restRoutes() {
        route("/api") {

            // GET /api/self/info
            get("self/info") {
                call.respondResult(WeChatService.getSelfInfo()) { respond(HttpStatusCode.OK, it) }
            }

            // POST /api/media/upload
            post("media/upload") {
                val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                if (!isMultipart) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request must be multipart/form-data"))
                }
                var uploadedFile: File? = null
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        uploadedFile = saveUploadedFile(part)
                    }
                    part.release()
                }
                if (uploadedFile == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No file was uploaded"))
                } else {
                    call.respond(HttpStatusCode.OK, mapOf("path" to uploadedFile.absolutePath))
                }
            }

            // GET /api/conversations/current
            get("conversations/current") {
                call.respondResult(WeChatService.getCurrentTalker()) { respond(HttpStatusCode.OK, WxIdResponse(it)) }
            }

            // POST /api/conversations/current
            post("conversations/current") {
                val req = runCatching { call.receive<CurrentTalkerRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.setCurrentTalker(req.convId)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            route("contacts/{wxId}") {
                // GET /api/contacts/{wxId}
                get {
                    val wxId = call.parameters["wxId"]!!
                    call.respondResult(WeChatService.getContactDetail(wxId)) { respond(HttpStatusCode.OK, it) }
                }

                // GET /api/contacts/{wxId}/display-name
                get("display-name") {
                    val wxId = call.parameters["wxId"]!!
                    call.respondResult(WeChatService.getDisplayNameByConvId(wxId)) { name ->
                        respond(HttpStatusCode.OK, DisplayNameResponse(name))
                    }
                }
            }

            // POST /api/messages/text
            post("messages/text") {
                val req = runCatching { call.receive<WeChatService.SendMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendMessage(req)) {
                    respond(HttpStatusCode.OK, SuccessResponse())
                }
            }

            // POST /api/messages/image
            post("messages/image") {
                val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                if (isMultipart) {
                    var convId: String? = null
                    var tempFile: File? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "convId") convId = part.value
                            }

                            is PartData.FileItem -> {
                                tempFile = saveUploadedFile(part)
                            }

                            else -> {}
                        }
                        part.release()
                    }
                    if (convId == null || tempFile == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing convId or file"))
                    }
                    call.respondResult(WeChatService.sendImageMessage(convId, tempFile.absolutePath)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                } else {
                    val req = runCatching { call.receive<ImageMessageRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.sendImageMessage(req.convId, req.path)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }
            }

            // POST /api/messages/voice
            post("messages/voice") {
                val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                if (isMultipart) {
                    var convId: String? = null
                    var durationMs = 0
                    var tempFile: File? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "convId") convId = part.value
                                else if (part.name == "durationMs") durationMs = part.value.toIntOrNull() ?: 0
                            }

                            is PartData.FileItem -> {
                                tempFile = saveUploadedFile(part)
                            }

                            else -> {}
                        }
                        part.release()
                    }
                    if (convId == null || tempFile == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing convId or file"))
                    }
                    call.respondResult(WeChatService.sendVoiceMessage(convId, tempFile.absolutePath, durationMs)) {
                        respond(
                            HttpStatusCode.OK,
                            SuccessResponse()
                        )
                    }
                } else {
                    val req = runCatching { call.receive<VoiceMessageRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.sendVoiceMessage(req.convId, req.path, req.durationMs)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }
            }

            // POST /api/messages/video
            post("messages/video") {
                val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                if (isMultipart) {
                    var convId: String? = null
                    var tempFile: File? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "convId") convId = part.value
                            }

                            is PartData.FileItem -> {
                                tempFile = saveUploadedFile(part)
                            }

                            else -> {}
                        }
                        part.release()
                    }
                    if (convId == null || tempFile == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing convId or file"))
                    }
                    call.respondResult(WeChatService.sendVideoMessage(convId, tempFile.absolutePath)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                } else {
                    val req = runCatching { call.receive<ImageMessageRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.sendVideoMessage(req.convId, req.path)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }
            }

            // POST /api/messages/file
            post("messages/file") {
                val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                if (isMultipart) {
                    var convId: String? = null
                    var title: String? = null
                    var tempFile: File? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "convId") convId = part.value
                                else if (part.name == "title") title = part.value
                            }

                            is PartData.FileItem -> {
                                if (title == null) title = part.originalFileName
                                tempFile = saveUploadedFile(part)
                            }

                            else -> {}
                        }
                        part.release()
                    }
                    if (convId == null || tempFile == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing convId or file"))
                    }
                    call.respondResult(WeChatService.sendFileMessage(convId, tempFile.absolutePath, title ?: tempFile.name)) {
                        respond(
                            HttpStatusCode.OK,
                            SuccessResponse()
                        )
                    }
                } else {
                    val req = runCatching { call.receive<FileMessageRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.sendFileMessage(req.convId, req.path, req.title)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }
            }

            // POST /api/messages/emoji
            post("messages/emoji") {
                val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                if (isMultipart) {
                    var convId: String? = null
                    var tempFile: File? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "convId") convId = part.value
                            }

                            is PartData.FileItem -> {
                                tempFile = saveUploadedFile(part)
                            }

                            else -> {}
                        }
                        part.release()
                    }
                    if (convId == null || tempFile == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing convId or file"))
                    }
                    call.respondResult(WeChatService.sendEmojiMessage(convId, tempFile.absolutePath)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                } else {
                    val req = runCatching { call.receive<EmojiMessageRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.sendEmojiMessage(req.convId, req.emojiPathOrMd5)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }
            }

            // POST /api/messages/pat
            post("messages/pat") {
                val req = runCatching { call.receive<PatMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendPatMessage(req.convId, req.patTarget)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/messages/location
            post("messages/location") {
                val req = runCatching { call.receive<LocationMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendLocationMessage(req.convId, req.poiName, req.label, req.x, req.y, req.scale)) {
                    respond(
                        HttpStatusCode.OK,
                        SuccessResponse()
                    )
                }
            }

            // POST /api/messages/share-card
            post("messages/share-card") {
                val req = runCatching { call.receive<ShareCardMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendShareCardMessage(req.convId, req.cardWxId)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/messages/xml
            post("messages/xml") {
                val req = runCatching { call.receive<XmlMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendXmlMessage(req.convId, req.xmlContent)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/messages/quote
            post("messages/quote") {
                val req = runCatching { call.receive<QuoteMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendQuoteMessage(req.convId, req.msgSvrId, req.content)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/messages/cipher
            post("messages/cipher") {
                val req = runCatching { call.receive<CipherMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendCipherMessage(req.convId, req.title, req.content)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/messages/note
            post("messages/note") {
                val req = runCatching { call.receive<NoteMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendNoteMessage(req.convId, req.noteXml)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/messages/app-brand
            post("messages/app-brand") {
                val req = runCatching { call.receive<AppBrandMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.sendAppBrandMessage(req.convId, req.title, req.pagePath, req.username)) {
                    respond(
                        HttpStatusCode.OK,
                        SuccessResponse()
                    )
                }
            }

            // POST /api/messages/revoke
            post("messages/revoke") {
                val req = runCatching { call.receive<RevokeRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.revokeMessage(req.msgSvrId)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/messages/system
            post("messages/system") {
                val req = runCatching { call.receive<SystemMessageRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.insertSystemMessage(req.convId, req.content, req.timeMs ?: System.currentTimeMillis())) {
                    respond(
                        HttpStatusCode.OK,
                        SuccessResponse()
                    )
                }
            }

            // POST /api/share/file
            post("share/file") {
                val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                if (isMultipart) {
                    var convId: String? = null
                    var title: String? = null
                    var appId: String? = null
                    var tempFile: File? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "convId" -> convId = part.value
                                    "title" -> title = part.value
                                    "appId" -> appId = part.value
                                }
                            }

                            is PartData.FileItem -> {
                                if (title == null) title = part.originalFileName
                                tempFile = saveUploadedFile(part)
                            }

                            else -> {}
                        }
                        part.release()
                    }
                    if (convId == null || tempFile == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing convId or file"))
                    }
                    call.respondResult(WeChatService.shareFile(convId, title ?: tempFile.name, tempFile.absolutePath, appId ?: "")) {
                        respond(
                            HttpStatusCode.OK,
                            SuccessResponse()
                        )
                    }
                } else {
                    val req = runCatching { call.receive<ShareFileRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.shareFile(req.convId, req.title, req.path, req.appId)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }
            }

            // POST /api/share/webpage
            post("share/webpage") {
                val req = runCatching { call.receive<ShareLinkRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(
                    WeChatService.shareWebpage(
                        req.convId,
                        req.title,
                        req.description,
                        req.webpageUrl,
                        req.thumbDataBase64,
                        req.appId
                    )
                ) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/share/video
            post("share/video") {
                val req = runCatching { call.receive<ShareVideoRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.shareVideo(req.convId, req.title, req.description, req.videoUrl, req.thumbDataBase64, req.appId)) {
                    respond(
                        HttpStatusCode.OK,
                        SuccessResponse()
                    )
                }
            }

            // POST /api/share/text
            post("share/text") {
                val req = runCatching { call.receive<ShareTextRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.shareText(req.convId, req.text, req.appId)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/share/music
            post("share/music") {
                val req = runCatching { call.receive<ShareMusicRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(
                    WeChatService.shareMusic(
                        req.convId,
                        req.title,
                        req.description,
                        req.musicUrl,
                        req.musicDataUrl,
                        req.thumbDataBase64,
                        req.appId
                    )
                ) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/share/music-video
            post("share/music-video") {
                val req = runCatching { call.receive<ShareMusicVideoRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(
                    WeChatService.shareMusicVideo(
                        req.convId,
                        req.title,
                        req.description,
                        req.musicUrl,
                        req.musicDataUrl,
                        req.singerName,
                        req.duration,
                        req.songLyric,
                        req.thumbDataBase64,
                        req.appId
                    )
                ) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            // POST /api/share/mini-program
            post("share/mini-program") {
                val req = runCatching { call.receive<ShareMiniProgramRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(
                    WeChatService.shareMiniProgram(
                        req.convId,
                        req.title,
                        req.description,
                        req.username,
                        req.path,
                        req.thumbDataBase64,
                        req.appId
                    )
                ) { respond(HttpStatusCode.OK, SuccessResponse()) }
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

                // GET /api/contacts/search?display-name=...&group-id=...
                get("search") {
                    val displayName = call.request.queryParameters["display-name"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'display-name' query parameter"))
                    val groupId = call.request.queryParameters["group-id"]
                    call.respondResult(WeChatService.getConvIdByDisplayName(displayName, groupId)) { wxId ->
                        respond(HttpStatusCode.OK, WxIdResponse(wxId))
                    }
                }
            }

            route("conversations/{convId}") {
                // GET /api/conversations/{convId}/history?page-index=1&page-size=20
                get("history") {
                    val convId = call.parameters["convId"]!!
                    val pageIndex = call.request.queryParameters["page-index"]?.toIntOrNull() ?: 1
                    val pageSize = call.request.queryParameters["page-size"]?.toIntOrNull() ?: 20
                    call.respondResult(WeChatService.listMessages(convId, pageIndex, pageSize)) { messages ->
                        respond(HttpStatusCode.OK, messages)
                    }
                }
            }

            // POST /api/messages/{msgSvrId}/image/cache — cache into WeChat's own storage only
            post("messages/{msgSvrId}/image/cache") {
                val msgSvrId = call.parameters["msgSvrId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid msgSvrId"))
                call.respondResult(WeChatService.cacheImage(msgSvrId)) { path ->
                    respond(HttpStatusCode.OK, PathResponse(path))
                }
            }

            // GET /api/messages/{msgSvrId}/image — cache if needed, then decode & save to Download/WeKit/
            get("messages/{msgSvrId}/image") {
                val msgSvrId = call.parameters["msgSvrId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid msgSvrId"))
                call.respondResult(WeChatService.downloadImage(msgSvrId)) { path ->
                    respond(HttpStatusCode.OK, PathResponse(path))
                }
            }

            // GET /api/messages/{msgSvrId}/sticker
            get("messages/{msgSvrId}/sticker") {
                val msgSvrId = call.parameters["msgSvrId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid msgSvrId"))
                call.respondResult(WeChatService.downloadSticker(msgSvrId)) { path ->
                    respond(HttpStatusCode.OK, PathResponse(path))
                }
            }

            // GET /api/messages/{msgSvrId}/voice
            get("messages/{msgSvrId}/voice") {
                val msgSvrId = call.parameters["msgSvrId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid msgSvrId"))
                call.respondResult(WeChatService.downloadVoice(msgSvrId)) { path ->
                    respond(HttpStatusCode.OK, PathResponse(path))
                }
            }

            // POST /api/messages/{msgSvrId}/file/cache?talker=... — cache into WeChat's own storage only
            post("messages/{msgSvrId}/file/cache") {
                val msgSvrId = call.parameters["msgSvrId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid msgSvrId"))
                val talker = call.request.queryParameters["talker"]
                call.respondResult(WeChatService.cacheFile(msgSvrId, talker?.ifEmpty { null })) { path ->
                    respond(HttpStatusCode.OK, PathResponse(path))
                }
            }

            // GET /api/messages/{msgSvrId}/file?talker=... — cache if needed, then copy to Download/WeKit/
            get("messages/{msgSvrId}/file") {
                val msgSvrId = call.parameters["msgSvrId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid msgSvrId"))
                val talker = call.request.queryParameters["talker"]
                call.respondResult(WeChatService.downloadFile(msgSvrId, talker?.ifEmpty { null })) { path ->
                    respond(HttpStatusCode.OK, PathResponse(path))
                }
            }

            route("groups/{groupId}") {
                // GET /api/groups/{groupId}/members
                get("members") {
                    val groupId = call.parameters["groupId"]!!
                    call.respondResult(WeChatService.listGroupMembers(groupId)) { members ->
                        respond(HttpStatusCode.OK, members)
                    }
                }

                // POST /api/groups/{groupId}/members/add
                post("members/add") {
                    val groupId = call.parameters["groupId"]!!
                    val req = runCatching { call.receive<MemberRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    if (req.memberWxids != null) {
                        call.respondResult(WeChatService.addChatroomMembers(groupId, req.memberWxids)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                    } else if (req.memberWxid != null) {
                        call.respondResult(WeChatService.addChatroomMember(groupId, req.memberWxid)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                    } else {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing member parameter"))
                    }
                }

                // POST /api/groups/{groupId}/members/delete
                post("members/delete") {
                    val groupId = call.parameters["groupId"]!!
                    val req = runCatching { call.receive<MemberRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    if (req.memberWxids != null) {
                        call.respondResult(WeChatService.delChatroomMembers(groupId, req.memberWxids)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                    } else if (req.memberWxid != null) {
                        call.respondResult(WeChatService.delChatroomMember(groupId, req.memberWxid)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                    } else {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing member parameter"))
                    }
                }

                // POST /api/groups/{groupId}/members/invite
                post("members/invite") {
                    val groupId = call.parameters["groupId"]!!
                    val req = runCatching { call.receive<MemberRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    if (req.memberWxids != null) {
                        call.respondResult(WeChatService.inviteChatroomMembers(groupId, req.memberWxids)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                    } else if (req.memberWxid != null) {
                        call.respondResult(WeChatService.inviteChatroomMember(groupId, req.memberWxid)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                    } else {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing member parameter"))
                    }
                }
            }

            route("labels") {
                // GET /api/labels
                get {
                    call.respondResult(WeChatService.listContactLabels()) { respond(HttpStatusCode.OK, it) }
                }

                // GET /api/labels/{labelIdOrName}/contacts
                get("{labelIdOrName}/contacts") {
                    val labelIdOrName = call.parameters["labelIdOrName"]!!
                    call.respondResult(WeChatService.getContactsByLabel(labelIdOrName)) { respond(HttpStatusCode.OK, it) }
                }
            }

            // POST /api/contacts/{wxId}/labels
            post("contacts/{wxId}/labels") {
                val wxId = call.parameters["wxId"]!!
                val req = runCatching { call.receive<LabelsModifyRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.modifyContactLabels(wxId, req.labels)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            route("moments") {
                // POST /api/moments/text
                post("text") {
                    val req = runCatching { call.receive<MomentTextRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.postMomentText(req.content, req.sdkId, req.sdkAppName)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }

                // POST /api/moments/pics
                post("pics") {
                    val isMultipart = call.request.contentType().match(ContentType.MultiPart.Any)
                    if (isMultipart) {
                        var content = ""
                        var sdkId: String? = null
                        var sdkAppName: String? = null
                        val tempFiles = mutableListOf<File>()
                        val multipart = call.receiveMultipart()
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FormItem -> {
                                    when (part.name) {
                                        "content" -> content = part.value
                                        "sdkId" -> sdkId = part.value
                                        "sdkAppName" -> sdkAppName = part.value
                                    }
                                }

                                is PartData.FileItem -> {
                                    tempFiles.add(saveUploadedFile(part))
                                }

                                else -> {}
                            }
                            part.release()
                        }
                        if (tempFiles.isEmpty()) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing uploaded pictures"))
                        }
                        call.respondResult(WeChatService.postMomentPics(content, tempFiles.map { it.absolutePath }, sdkId, sdkAppName)) {
                            respond(HttpStatusCode.OK, SuccessResponse())
                        }
                    } else {
                        val req = runCatching { call.receive<MomentPicsRequest>() }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                        call.respondResult(WeChatService.postMomentPics(req.content, req.picPaths, req.sdkId, req.sdkAppName)) {
                            respond(HttpStatusCode.OK, SuccessResponse())
                        }
                    }
                }
            }

            route("payments/transfer") {
                // POST /api/payments/transfer/confirm
                post("confirm") {
                    val req = runCatching { call.receive<ConfirmTransferRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.confirmTransfer(req.transId, req.transId, req.transSpanId, req.invalidTime)) {
                        respond(
                            HttpStatusCode.OK,
                            SuccessResponse()
                        )
                    }
                }

                // POST /api/payments/transfer/refuse
                post("refuse") {
                    val req = runCatching { call.receive<RefuseTransferRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.refuseTransfer(req.transId, req.transId, req.transSpanId)) {
                        respond(
                            HttpStatusCode.OK,
                            SuccessResponse()
                        )
                    }
                }
            }

            // POST /api/contacts/verify
            post("contacts/verify") {
                val req = runCatching { call.receive<VerifyFriendRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                call.respondResult(WeChatService.verifyFriend(req.userId, req.ticket, req.scene, req.privacy)) { respond(HttpStatusCode.OK, SuccessResponse()) }
            }

            route("utils") {
                post("device/step") {
                    val req = runCatching { call.receive<DeviceStepRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.uploadDeviceStep(req.stepCount)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }

                post("audio/mp3-to-silk") {
                    val req = runCatching { call.receive<AudioConvertRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.audioMp3ToSilk(req.srcPath, req.destPath)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }

                post("audio/silk-to-mp3") {
                    val req = runCatching { call.receive<AudioConvertRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.audioSilkToMp3(req.srcPath, req.destPath)) { respond(HttpStatusCode.OK, SuccessResponse()) }
                }

                post("audio/duration") {
                    val req = runCatching { call.receive<PathRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.audioGetDuration(req.path)) { respond(HttpStatusCode.OK, it) }
                }
            }

            route("auth") {
                post("js-login") {
                    val req = runCatching { call.receive<JsLoginRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    call.respondResult(WeChatService.jsLogin(req.appId)) { respond(HttpStatusCode.OK, WxIdResponse(it)) }
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

    private lateinit var netServer: EmbeddedServer<*, *>

    override fun onEnable() {
        netServer = embeddedServer(CIO, host = "0.0.0.0", port = serverPort) {
            configureServer()
        }.start(wait = false)
        showToast("MCP 服务器启动于 http://0.0.0.0:$serverPort/mcp")
        showToast("REST API 服务器启动于 http://0.0.0.0:$serverPort/api")
    }

    override fun onDisable() {
        netServer.stop(1000, 2000)
        showToast("服务器已停止")
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var authToken by remember { mutableStateOf(authToken) }
            var serverPortInput by remember { mutableStateOf(serverPort.toString()) }

            AlertDialogContent(
                title = { Text("API + MCP 服务器") },
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

                        ApiServer.serverPort = serverPort
                        ApiServer.authToken = authToken
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}

