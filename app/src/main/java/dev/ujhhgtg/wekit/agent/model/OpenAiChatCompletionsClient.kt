package dev.ujhhgtg.wekit.agent.model

import dev.ujhhgtg.wekit.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [LlmClient] adapter for the OpenAI **Chat Completions** wire format (`POST {baseUrl}/chat/completions`).
 * Compatible with OpenAI, DeepSeek, Ollama, OpenRouter, and most local servers. Reasoning effort is
 * placed at the top-level `reasoning_effort` field (verified against the current OpenAI docs).
 */
class OpenAiChatCompletionsClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {
    private val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val body = LlmJson.shallowMerge(buildBody(request, stream = true), request.customJsonOverride)
        // The response is scoped to `execute { … }`: every early exit below (HTTP error, break on
        // [DONE]) previously abandoned an open SSE body channel, leaking the connection until GC.
        // `execute` releases it on every path, including exceptions.
        http.preparePost(endpoint) {
            header("x-opencode-session", request.sessionId)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(LlmJson.json.encodeToString(JsonObject.serializer(), body))
        }.execute { resp ->
            if (!resp.status.isSuccess()) {
                emit(LlmStreamEvent.Failed(LlmException("HTTP ${resp.status.value}: ${readBodyText(resp)}")))
                return@execute
            }
            val acc = ToolCallAccumulator()
            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            var finishReason: String? = null
            var usage: LlmUsage? = null

            val channel = resp.bodyAsChannel()
            while (true) {
                @Suppress("DEPRECATION")
                val line = channel.readUTF8Line() ?: break
                val data = SseParser.dataOrNull(line) ?: continue
                if (data == "[DONE]") break

                val chunk = runCatching { LlmJson.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                // The final chunk (with stream_options.include_usage) carries `usage` and an empty
                // `choices` array, so parse usage before the empty-choices `continue` below.
                chunk["usage"]?.let { if (it is JsonObject) usage = parseUsage(it) }
                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                choice["finish_reason"]?.jsonPrimitive?.contentOrNullSafe()?.let { finishReason = it }

                val delta = choice["delta"]?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNullSafe()?.let {
                    if (it.isNotEmpty()) {
                        textBuf.append(it); emit(LlmStreamEvent.TextDelta(it))
                    }
                }
                // Some providers expose reasoning under `reasoning` or `reasoning_content`.
                (delta["reasoning"] ?: delta["reasoning_content"])?.jsonPrimitive?.contentOrNullSafe()?.let {
                    if (it.isNotEmpty()) {
                        // On the very first reasoning chunk, emit an empty sentinel so the UI shows the
                        // "思考中..." card immediately — before the first real token is appended.
                        if (reasoningBuf.isEmpty()) emit(LlmStreamEvent.ReasoningDelta(""))
                        reasoningBuf.append(it); emit(LlmStreamEvent.ReasoningDelta(it))
                    }
                }
                delta["tool_calls"]?.jsonArray?.forEach { acc.accept(it.jsonObject) }
            }

            emit(
                LlmStreamEvent.Completed(
                    LlmMessage(
                        role = LlmRole.ASSISTANT,
                        content = textBuf.toString().ifEmpty { null },
                        reasoning = reasoningBuf.toString().ifEmpty { null },
                        toolCalls = acc.build(),
                    ),
                    finishReason,
                    usage,
                )
            )
        }
    }

    // -- request body ---------------------------------------------------------

    private fun buildBody(request: LlmRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.modelIdRemote)
        put("stream", stream)
        // Ask OpenAI-compatible servers to emit a final usage-only chunk (choices: []).
        if (stream) putJsonObject("stream_options") { put("include_usage", true) }
        request.reasoningEffort?.let { put("reasoning_effort", it) }
        // Output-token cap. The official OpenAI field is `max_completion_tokens` (`max_tokens` is
        // deprecated there), but many domestic OpenAI-compatible servers only honor the legacy
        // `max_tokens`. Send both so the limit is respected regardless of which the server reads.
        request.maxTokens?.let {
            put("max_tokens", it)
            put("max_completion_tokens", it)
        }
        putJsonArray("messages") { request.messages.forEach { add(encodeMessage(it)) } }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { spec ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", spec.parametersSchema)
                        }
                    }
                }
            }
        }
    }

    private fun encodeMessage(msg: LlmMessage): JsonObject = buildJsonObject {
        put("role", msg.role.wire)
        // With images, `content` becomes an array of typed parts (text + image_url data URIs);
        // without, it stays a plain string for maximum server compatibility.
        if (msg.images.isNotEmpty()) {
            putJsonArray("content") {
                msg.content?.takeIf { it.isNotEmpty() }?.let {
                    addJsonObject { put("type", "text"); put("text", it) }
                }
                msg.images.forEach { img ->
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", img.dataUri) }
                    }
                }
            }
        } else {
            msg.content?.let { put("content", it) }
        }
        msg.toolCallId?.let { put("tool_call_id", it) }
        if (msg.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                msg.toolCalls.forEach { tc ->
                    addJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tc.name)
                            put("arguments", tc.argumentsJson)
                        }
                    }
                }
            }
        }
    }

    private suspend fun readBodyText(resp: io.ktor.client.statement.HttpResponse): String =
        runCatching { resp.bodyAsChannel().readRemainingText() }.getOrElse { "<unreadable>" }
            .also { WeLogger.w(TAG, "error response: $it") }

    private val LlmRole.wire: String
        get() = when (this) {
            LlmRole.SYSTEM -> "system"
            LlmRole.USER -> "user"
            LlmRole.ASSISTANT -> "assistant"
            LlmRole.TOOL -> "tool"
        }

    /**
     * Chat Completions streams tool calls as fragments keyed by array `index`; the id/name arrive in
     * the first fragment and `arguments` accumulate across fragments. This reassembles them.
     */
    private class ToolCallAccumulator {
        private data class Partial(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

        private val byIndex = sortedMapOf<Int, Partial>()

        fun accept(obj: JsonObject) {
            val index = obj["index"]?.jsonPrimitive?.int ?: 0
            val p = byIndex.getOrPut(index) { Partial() }
            obj["id"]?.jsonPrimitive?.contentOrNullSafe()?.let { if (it.isNotEmpty()) p.id = it }
            val fn = obj["function"]?.jsonObject
            fn?.get("name")?.jsonPrimitive?.contentOrNullSafe()?.let { if (it.isNotEmpty()) p.name = it }
            fn?.get("arguments")?.jsonPrimitive?.contentOrNullSafe()?.let { p.args.append(it) }
        }

        fun build(): List<LlmToolCall> = byIndex.values
            .filter { it.name.isNotEmpty() }
            .map { LlmToolCall(it.id.ifEmpty { "call_${it.name}" }, it.name, it.args.toString().ifEmpty { "{}" }) }
    }

    private companion object {
        const val TAG = "OpenAiChatCompletions"
    }
}
