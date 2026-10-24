package dev.ujhhgtg.wekit.agent.model

import dev.ujhhgtg.wekit.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [LlmClient] adapter for the **Gemini generateContent** (legacy) wire format:
 * `POST {baseUrl}/models/{model}:streamGenerateContent?alt=sse`.
 *
 * Notable differences from the OpenAI / Anthropic formats:
 *  - Auth is `x-goog-api-key` — no bearer token.
 *  - The model id is embedded in the **URL path** (not in the request body). [request.modelIdRemote]
 *    may be a bare id (e.g. `"gemini-3.5-flash"`) or already prefixed with `"models/"` — both forms
 *    are accepted; we normalise to `models/{id}`.
 *  - System prompt goes in a top-level `systemInstruction.parts[].text` object (not a message).
 *  - Roles are `"user"` and `"model"` (not `"assistant"`).
 *  - Tool results (`functionResponse`) are sent as **user**-role parts (not a separate `tool` role).
 *  - `functionResponse` requires the function **name** — we build a `callId→name` map from preceding
 *    ASSISTANT turns during encoding.
 *  - Consecutive user-role turns are coalesced into one `Content` (alternation requirement), just
 *    like Anthropic's role-merging for tool results.
 *  - Thinking: `generationConfig.thinkingConfig.thinkingLevel` + `includeThoughts = true` exposes
 *    reasoning as parts flagged with `"thought": true` in the response.
 *  - `thoughtSignature`: Gemini 3 attaches a `thoughtSignature` field to `functionCall` (and text)
 *    parts. The signature **must** be replayed on the next turn; we carry it in-memory via
 *    [LlmToolCall.providerSignature] (not persisted — same limitation as Anthropic thinking blocks).
 *  - Token usage is in `usageMetadata` (`promptTokenCount`, `candidatesTokenCount`,
 *    `thoughtsTokenCount`, `totalTokenCount`).
 *
 * SSE events: each `data:` line is a full `GenerateContentResponse` JSON object. Streaming ends
 * when the channel closes (there is no `[DONE]` sentinel).
 */
class GeminiGenerateContentClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val modelPath = request.modelIdRemote.let {
            if (it.startsWith("models/")) it else "models/$it"
        }
        val endpoint = "${baseUrl.trimEnd('/')}/$modelPath:streamGenerateContent?alt=sse"

        val body = LlmJson.shallowMerge(buildBody(request), request.customJsonOverride)
        // The response is scoped to `execute { … }`: every early exit below (HTTP error, stream
        // error, end of stream) previously abandoned an open SSE body channel, leaking the
        // connection until GC. `execute` releases it on every path, including exceptions.
        http.preparePost(endpoint) {
            header("x-opencode-session", request.sessionId)
            header(GeminiCommon.API_KEY_HEADER, apiKey)
            contentType(ContentType.Application.Json)
            setBody(LlmJson.json.encodeToString(JsonObject.serializer(), body))
        }.execute { resp ->
            if (!resp.status.isSuccess()) {
                emit(LlmStreamEvent.Failed(LlmException("HTTP ${resp.status.value}: ${readBodyText(resp)}")))
                return@execute
            }

            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            val toolCalls = mutableListOf<LlmToolCall>()
            var finishReason: String? = null
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var totalTokens: Int? = null

            val channel = resp.bodyAsChannel()
            while (true) {
                @Suppress("DEPRECATION")
                val line = channel.readUTF8Line() ?: break
                val data = SseParser.dataOrNull(line) ?: continue
                // generateContent SSE has no [DONE] marker — the channel just closes.
                val chunk = runCatching { LlmJson.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

                // Usage metadata (present on every chunk, most complete on the last).
                chunk["usageMetadata"]?.jsonObject?.let { u ->
                    u["promptTokenCount"]?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
                    u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                    u["totalTokenCount"]?.jsonPrimitive?.intOrNull?.let { totalTokens = it }
                }

                val candidate = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                candidate["finishReason"]?.jsonPrimitive?.contentOrNullSafe()
                    ?.takeIf { it.isNotEmpty() && it != "FINISH_REASON_UNSPECIFIED" }
                    ?.let { finishReason = it }

                val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: continue

                for (part in parts) {
                    val p = part.jsonObject
                    val isThought = p["thought"]?.jsonPrimitive?.contentOrNullSafe() == "true"
                            || p["thought"]?.let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "true" } == true

                    val fcObj = p["functionCall"]?.jsonObject
                    if (fcObj != null) {
                        // Completed function call part — tool calls in generateContent arrive fully
                        // formed (not streamed piecemeal like Chat Completions deltas).
                        val id = fcObj["id"]?.jsonPrimitive?.contentOrNullSafe()
                            ?: "call_${toolCalls.size}"
                        val name = fcObj["name"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
                        val argsJson = fcObj["args"]?.let {
                            runCatching { LlmJson.json.encodeToString(JsonObject.serializer(), it.jsonObject) }.getOrElse { "{}" }
                        } ?: "{}"
                        // thoughtSignature lives alongside the functionCall part (Gemini 3).
                        val sig = (p["thoughtSignature"] ?: p["thought_signature"])
                            ?.jsonPrimitive?.contentOrNullSafe()
                        toolCalls.add(LlmToolCall(id = id, name = name, argumentsJson = argsJson, providerSignature = sig))
                        continue
                    }

                    val text = p["text"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
                    if (text.isEmpty()) continue

                    if (isThought) {
                        // Emit empty sentinel on first reasoning chunk so the UI shows "思考中…" immediately.
                        if (reasoningBuf.isEmpty()) emit(LlmStreamEvent.ReasoningDelta(""))
                        reasoningBuf.append(text)
                        emit(LlmStreamEvent.ReasoningDelta(text))
                    } else {
                        textBuf.append(text)
                        emit(LlmStreamEvent.TextDelta(text))
                    }
                }

                // Error parts embedded in the candidates structure.
                chunk["error"]?.let {
                    emit(LlmStreamEvent.Failed(LlmException("Gemini error: $data")))
                    return@execute
                }
            }

            val usage = if (inputTokens != null || outputTokens != null) {
                LlmUsage(
                    promptTokens = inputTokens,
                    completionTokens = outputTokens,
                    totalTokens = totalTokens,
                )
            } else null

            emit(
                LlmStreamEvent.Completed(
                    LlmMessage(
                        role = LlmRole.ASSISTANT,
                        content = textBuf.toString().ifEmpty { null },
                        reasoning = reasoningBuf.toString().ifEmpty { null },
                        toolCalls = toolCalls,
                    ),
                    finishReason ?: if (toolCalls.isNotEmpty()) "STOP" else null,
                    usage,
                )
            )
        }
    }

    // -- request body ---------------------------------------------------------

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        // System instruction: hoisted from SYSTEM messages, encoded as parts array.
        val systemText = request.messages
            .filter { it.role == LlmRole.SYSTEM }
            .mapNotNull { it.content?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        if (systemText.isNotEmpty()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemText) } }
            }
        }

        put("contents", encodeContents(request.messages))

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("function_declarations") {
                        request.tools.forEach { spec ->
                            addJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", GeminiCommon.sanitizeSchema(spec.parametersSchema))
                            }
                        }
                    }
                }
            }
        }

        val thinkingLevel = GeminiCommon.effortToThinkingLevel(request.reasoningEffort)
        val genConfig = GeminiCommon.buildGenerationConfig(thinkingLevel, request.maxTokens, camelCase = true)
        if (genConfig.isNotEmpty()) put("generationConfig", genConfig)
    }

    /**
     * Encodes the conversation history (excluding SYSTEM) into a Gemini `contents` array.
     *
     * Role mapping: USER → "user", ASSISTANT → "model", TOOL → "user" (functionResponse part).
     * Consecutive turns with the same wire role are coalesced into one Content to satisfy Gemini's
     * alternating-role requirement — this handles multi-tool-call assistant turns where several
     * TOOL results arrive consecutively and must merge into one user Content.
     *
     * Function responses require the original function **name** (not just the call id); we build
     * a `callId → name` map from the ASSISTANT messages as we go.
     */
    private fun encodeContents(messages: List<LlmMessage>): JsonArray {
        val toolNames = HashMap<String, String>() // callId -> toolName for functionResponse lookup
        val turns = ArrayList<Pair<String, MutableList<JsonObject>>>()

        fun appendParts(role: String, parts: List<JsonObject>) {
            if (parts.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second.addAll(parts)
            else turns.add(role to parts.toMutableList())
        }

        for (msg in messages) {
            when (msg.role) {
                LlmRole.SYSTEM -> Unit // hoisted to systemInstruction

                LlmRole.USER -> {
                    val parts = ArrayList<JsonObject>()
                    msg.content?.takeIf { it.isNotEmpty() }?.let { parts.add(textPart(it)) }
                    msg.images.forEach { parts.add(inlineImagePart(it)) }
                    if (parts.isEmpty()) parts.add(textPart(""))
                    appendParts("user", parts)
                }

                LlmRole.ASSISTANT -> {
                    // Record names for any tool calls this turn (needed when TOOL results follow).
                    msg.toolCalls.forEach { tc -> toolNames[tc.id] = tc.name }
                    val parts = ArrayList<JsonObject>()
                    msg.content?.takeIf { it.isNotBlank() }?.let { parts.add(textPart(it)) }
                    msg.toolCalls.forEachIndexed { i, tc ->
                        parts.add(functionCallPart(tc, attachSignature = i == 0))
                    }
                    if (parts.isEmpty()) parts.add(textPart("")) // API requires at least one part
                    appendParts("model", parts)
                }

                LlmRole.TOOL -> {
                    val name = toolNames[msg.toolCallId ?: ""] ?: ""
                    appendParts(
                        "user",
                        listOf(functionResponsePart(msg.toolCallId ?: "", name, msg.content ?: ""))
                    )
                }
            }
        }

        return buildJsonArray {
            turns.forEach { (role, parts) ->
                addJsonObject {
                    put("role", role)
                    putJsonArray("parts") { parts.forEach { add(it) } }
                }
            }
        }
    }

    private fun textPart(text: String): JsonObject = buildJsonObject { put("text", text) }

    /**
     * Encodes an inline-data image part (`inline_data.mime_type` + `inline_data.data`).
     * The Gemini generateContent API uses `inline_data` (not `image_url` as OpenAI does).
     */
    private fun inlineImagePart(img: LlmImage): JsonObject = buildJsonObject {
        putJsonObject("inline_data") {
            put("mime_type", img.mimeType)
            put("data", img.base64)
        }
    }

    /**
     * Encodes a `functionCall` part for an ASSISTANT turn. [attachSignature] controls whether
     * [LlmToolCall.providerSignature] is emitted as `thoughtSignature` — Gemini 3 requires the
     * signature on the **first** function call part of a model Content when thinking is enabled;
     * subsequent calls in the same Content must not carry it.
     */
    private fun functionCallPart(tc: LlmToolCall, attachSignature: Boolean): JsonObject =
        buildJsonObject {
            putJsonObject("functionCall") {
                put("id", tc.id)
                put("name", tc.name)
                put("args", parseArgsOrEmpty(tc.argumentsJson))
            }
            if (attachSignature) {
                tc.providerSignature?.takeIf { it.isNotEmpty() }?.let {
                    put("thoughtSignature", it)
                }
            }
        }

    /**
     * Encodes a `functionResponse` part (sent as a user-role Content after tool execution).
     * The response payload is wrapped in `{"result": "<text>"}` — Gemini expects an object.
     */
    private fun functionResponsePart(callId: String, name: String, result: String): JsonObject =
        buildJsonObject {
            putJsonObject("functionResponse") {
                put("id", callId)
                put("name", name)
                putJsonObject("response") { put("result", result) }
            }
        }

    private fun parseArgsOrEmpty(argumentsJson: String): JsonObject =
        runCatching { LlmJson.json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private suspend fun readBodyText(resp: HttpResponse): String =
        runCatching { resp.bodyAsChannel().readRemainingText() }.getOrElse { "<unreadable>" }
            .also { WeLogger.w(TAG, "error response: $it") }

    private companion object {
        const val TAG = "GeminiGenerateContent"
    }
}
