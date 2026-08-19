package dev.ujhhgtg.wekit.agent.bridge

import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.engine.ToolCallExecutor
import dev.ujhhgtg.wekit.agent.engine.ToolExecutionContext
import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.tool.ToolCallOrigin
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import dev.ujhhgtg.wekit.agent.ui.UiImageSink
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicBoolean

class ToolBridgeSession(
    private val registry: ToolRegistry,
    private val executor: ToolCallExecutor,
    private val visibility: ToolVisibility,
    parentContext: CoroutineContext,
    val token: String,
    val owner: String,
    private val audit: suspend (AuditEntry) -> Unit = {},
    private val environmentId: String,
    private val parentToolCallId: String?,
) {
    private val active = AtomicBoolean(true)
    private val sessionJob = SupervisorJob()
    private val sessionContext = parentContext.minusKey(Job).minusKey(ToolExecutionContext)

    data class AuditEntry(
        val sessionId: String,
        val environmentId: String,
        val parentToolCallId: String?,
        val providerId: String,
        val tool: String,
        val argumentsJson: String,
        val approvalStatus: dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus?,
        val executionOutcome: String,
        val result: String,
    )

    fun revoke() {
        if (active.getAndSet(false)) sessionJob.cancel()
    }

    suspend fun handle(payload: String): String {
        if (!active.get()) return error("token_revoked", "bridge token has expired")
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
        ToolRegistry.isCallAllowed(
            it.provider.kind, it.exposedName, it.bareName, ToolCallOrigin.ENVIRONMENT_BRIDGE,
        )
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
        val tool = tools().firstOrNull { it.exposedName == name } ?: return error("unknown_tool", name)
        val arguments = args.toString()
        val result = try {
            withContext(sessionContext +
                (sessionContext[AgentSessionContext] ?: AgentSessionContext(owner)) +
                (sessionContext[UiImageSink] ?: UiImageSink())) {
                val requestJob = currentCoroutineContext()[Job]!!
                val revocation = sessionJob.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        requestJob.cancel(CancellationException("bridge capability was revoked", cause))
                    }
                }
                try {
                    sessionJob.ensureActive()
                    executor.execute(
                        LlmToolCall("bridge-${java.util.UUID.randomUUID()}", name, arguments),
                        ToolCallExecutor.Context(
                            visibility = visibility,
                            origin = ToolCallOrigin.ENVIRONMENT_BRIDGE,
                        ),
                    )
                } finally {
                    revocation.dispose()
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                audit(AuditEntry(owner, environmentId, parentToolCallId, tool.provider.id, name, arguments,
                    null, "CANCELLED", error.message ?: "bridge call was cancelled"))
            }
            throw error
        }
        audit(AuditEntry(owner, environmentId, parentToolCallId, result.providerId, name, arguments,
            result.status, when {
                result.status.name.endsWith("REJECTED") -> "DENIED"
                result.executionSucceeded -> "SUCCEEDED"
                else -> "FAILED"
            }, result.text))
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
