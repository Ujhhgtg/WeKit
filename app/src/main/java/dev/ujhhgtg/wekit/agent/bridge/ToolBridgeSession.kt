package dev.ujhhgtg.wekit.agent.bridge

import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.engine.ToolCallExecutor
import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.tool.ToolCallOrigin
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import dev.ujhhgtg.wekit.agent.ui.UiImageSink
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext

class ToolBridgeSession(
    private val registry: ToolRegistry,
    private val executor: ToolCallExecutor,
    private val visibility: ToolVisibility,
    private val parentContext: CoroutineContext,
    val token: String,
    val owner: String,
    private val audit: (AuditEntry) -> Unit = {},
) {
    @Volatile private var active = true

    data class AuditEntry(val owner: String, val tool: String, val argumentsJson: String, val result: String)

    fun revoke() { active = false }

    suspend fun handle(payload: String): String {
        if (!active) return error("token_revoked", "bridge token has expired")
        val request = runCatching { Json.parseToJsonElement(payload).jsonObject }
            .getOrElse { return error("invalid_json", "request must be a JSON object") }
        val response = when ((request["op"] as? JsonPrimitive)?.content) {
            "list" -> list(request)
            "search" -> search(request)
            "schema" -> schema(request)
            "call" -> call(request)
            else -> error("invalid_operation", "op must be list, search, schema, or call")
        }
        return if (response.toByteArray().size <= ToolBridgeProtocol.MAX_PAYLOAD_BYTES) response
            else error("response_too_large", "tool response exceeds the bridge limit")
    }

    private fun tools() = registry.resolveVisibleTools(visibility).filter {
        ToolRegistry.isCallAllowed(it.exposedName, dev.ujhhgtg.wekit.agent.tool.ToolCallOrigin.ENVIRONMENT_BRIDGE)
    }

    private fun list(request: JsonObject): String {
        val provider = (request["provider"] as? JsonPrimitive)?.contentOrNull
        val result = tools().filter { provider == null || it.provider.id == provider }.map {
            buildJsonObject { put("name", it.exposedName); put("provider", it.provider.id); put("description", it.description); put("schema", it.jsonSchema) }
        }
        return Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(result))
    }

    private fun search(request: JsonObject): String {
        val keyword = (request["keyword"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(tools().filter { keyword.isEmpty() || it.exposedName.contains(keyword, true) || it.description.contains(keyword, true) }.map {
                buildJsonObject { put("name", it.exposedName); put("provider", it.provider.id); put("description", it.description) }
            }))
    }

    private fun schema(request: JsonObject): String {
        val name = (request["name"] as? JsonPrimitive)?.contentOrNull ?: return error("invalid_arguments", "name is required")
        val tool = tools().firstOrNull { it.exposedName == name } ?: return error("unknown_tool", name)
        return buildJsonObject { put("name", tool.exposedName); put("schema", tool.jsonSchema) }.toString()
    }

    private suspend fun call(request: JsonObject): String {
        val name = (request["name"] as? JsonPrimitive)?.contentOrNull ?: return error("invalid_arguments", "name is required")
        val args = request["arguments"]?.let { it as? JsonObject ?: return error("invalid_arguments", "arguments must be an object") }
            ?: JsonObject(emptyMap())
        if (tools().none { it.exposedName == name }) return error("unknown_tool", name)
        val arguments = args.toString()
        val result = withContext(parentContext + (parentContext[AgentSessionContext] ?: AgentSessionContext(owner)) +
            (parentContext[UiImageSink] ?: UiImageSink())) {
            executor.execute(LlmToolCall("bridge-$owner", name, arguments), ToolCallExecutor.Context(
                visibility = visibility, origin = ToolCallOrigin.ENVIRONMENT_BRIDGE,
            ))
        }
        audit(AuditEntry(owner, name, arguments, result.text))
        val denied = result.status.name.endsWith("REJECTED")
        return buildJsonObject {
            put("ok", !denied)
            if (denied) put("error", "approval_denied")
            put("status", result.status.name)
            put("result", result.text)
        }.toString()
    }

    private fun error(code: String, message: String) = buildJsonObject { put("ok", false); put("error", code); put("message", message) }.toString()
}
