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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * [LlmClient] adapter for the **Gemini Interactions** (modern) wire format:
 * `POST {baseUrl}/interactions?alt=sse`.
 *
 * This is Google's stateless-history style: the entire conversation is sent as a flat `input` array
 * of typed steps per request (`store: false`). Notable differences from generateContent:
 *  - Auth is `x-goog-api-key`.
 *  - Model id goes in the **request body** (`"model": "gemini-3.5-flash"`), not in the URL path.
 *  - System prompt is a plain string at `system_instruction` (not a Content object).
 *  - History is an `input` array of step objects (not `contents`). Each message maps to one or more
 *    steps: USER → `user_input`, ASSISTANT text → `model_output`, ASSISTANT tool call →
 *    `function_call`, TOOL result → `function_result`.
 *  - Tools are a flat array of `{"type":"function", "name", "description", "parameters"}`.
 *  - Thinking is configured via `generation_config.thinking_level` + `thinking_summaries:"auto"`.
 *    Thought steps in the response carry a signature that must be replayed in multi-turn history;
 *    since we don't persist thought steps, cross-turn thinking replay is not supported (same
 *    limitation as Anthropic thinking blocks and [GeminiGenerateContentClient] thought signatures).
 *
 * SSE events (typed, unlike generateContent):
 *  - `step.start` → step type + optional initial content (text, thought summary start, etc.)
 *  - `step.delta` → incremental `delta.type` payload (`text`, `thought_summary`, `arguments`)
 *  - `step.stop` → step index closed; finalises function-call argument accumulation
 *  - `interaction.completed` → terminal event carrying `interaction.usage`
 *  - `error` → terminal error
 *
 * Function-result steps require the original function **name** (not just the call id); we track
 * a `callId→name` map from the input history and from `function_call` steps received mid-stream.
 *
 * Usage fields: `total_input_tokens`, `total_output_tokens`, `total_thought_tokens`,
 * `total_tokens` — mapped to [LlmUsage] (`totalTokens` = output + thought, matching provider billing).
 */
class GeminiInteractionsClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val endpoint = "${baseUrl.trimEnd('/')}/interactions?alt=sse"
        val body = LlmJson.shallowMerge(buildBody(request), request.customJsonOverride)

        // The response is scoped to `execute { … }`: every early exit below (HTTP error, stream
        // error, break on interaction.completed) previously abandoned an open SSE body channel,
        // leaking the connection until GC. `execute` releases it on every path, incl. exceptions.
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
            // The last thought_signature delta seen this stream — stored so it can be replayed on
            // the next turn as a thought step preceding any function_call / model_output steps.
            var lastThoughtSignature: String? = null
            // Accumulates function_call argument fragments per step index.
            val pendingCalls = sortedMapOf<Int, PartialFunctionCall>()
            val completedCalls = mutableListOf<LlmToolCall>()
            var finishReason: String? = null
            var usage: LlmUsage? = null

            val channel = resp.bodyAsChannel()
            var eventType = ""

            while (true) {
                @Suppress("DEPRECATION")
                val rawLine = channel.readUTF8Line() ?: break
                val line = rawLine.trim()

                when {
                    line.startsWith("event:") -> {
                        eventType = line.removePrefix("event:").trim()
                    }

                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        val ev = runCatching { LlmJson.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

                        when (eventType) {
                            "step.start" -> {
                                val stepIndex = ev["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val step = ev["step"]?.jsonObject ?: continue
                                when (step["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                                    "thought" -> {
                                        // Emit the empty sentinel so the UI shows "思考中…" immediately.
                                        if (reasoningBuf.isEmpty()) emit(LlmStreamEvent.ReasoningDelta(""))
                                        // Thought summary text may be available immediately in step.start.
                                        step["summary"]?.jsonArray?.forEach { el ->
                                            el.jsonObject["text"]?.jsonPrimitive?.contentOrNullSafe()?.let { t ->
                                                if (t.isNotEmpty()) {
                                                    reasoningBuf.append(t); emit(LlmStreamEvent.ReasoningDelta(t))
                                                }
                                            }
                                        }
                                    }

                                    "function_call" -> {
                                        // Register the call; arguments may trickle in via step.delta.
                                        val id = step["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "call_$stepIndex"
                                        val name = step["name"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
                                        pendingCalls[stepIndex] = PartialFunctionCall(id = id, name = name)
                                        // Arguments may already be present in step.start.
                                        step["arguments"]?.let { args ->
                                            val pc = pendingCalls[stepIndex] ?: return@let
                                            if (args is JsonObject) {
                                                // Full args object up front — serialise directly.
                                                pc.argsBuf.append(LlmJson.json.encodeToString(JsonObject.serializer(), args))
                                                pc.argsFromObject = true
                                            } else {
                                                pc.argsBuf.append(args.jsonPrimitive.contentOrNullSafe() ?: "")
                                            }
                                        }
                                    }

                                    "model_output" -> {
                                        // Initial content may contain text blocks.
                                        step["content"]?.jsonArray?.forEach { el ->
                                            el.jsonObject.let { part ->
                                                if (part["type"]?.jsonPrimitive?.contentOrNullSafe() == "text") {
                                                    part["text"]?.jsonPrimitive?.contentOrNullSafe()?.let { t ->
                                                        if (t.isNotEmpty()) {
                                                            textBuf.append(t); emit(LlmStreamEvent.TextDelta(t))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "step.delta" -> {
                                val stepIndex = ev["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val delta = ev["delta"]?.jsonObject ?: continue
                                when (delta["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                                    "text" -> delta["text"]?.jsonPrimitive?.contentOrNullSafe()?.let { t ->
                                        if (t.isNotEmpty()) {
                                            textBuf.append(t); emit(LlmStreamEvent.TextDelta(t))
                                        }
                                    }

                                    "thought_summary" -> delta["text"]?.jsonPrimitive?.contentOrNullSafe()?.let { t ->
                                        if (t.isNotEmpty()) {
                                            if (reasoningBuf.isEmpty()) emit(LlmStreamEvent.ReasoningDelta(""))
                                            reasoningBuf.append(t); emit(LlmStreamEvent.ReasoningDelta(t))
                                        }
                                    }

                                    "thought_signature" -> delta["signature"]?.jsonPrimitive?.contentOrNullSafe()?.let { sig ->
                                        if (sig.isNotEmpty()) lastThoughtSignature = sig
                                    }

                                    "arguments" -> {
                                        val pc = pendingCalls[stepIndex]
                                        if (pc != null) {
                                            delta["partial_arguments"]?.jsonPrimitive?.contentOrNullSafe()?.let { frag ->
                                                pc.argsBuf.append(frag)
                                            }
                                        }
                                    }
                                }
                            }

                            "step.stop" -> {
                                val stepIndex = ev["index"]?.jsonPrimitive?.intOrNull ?: 0
                                pendingCalls.remove(stepIndex)?.let { pc ->
                                    if (pc.name.isNotEmpty()) {
                                        val argsJson = if (pc.argsFromObject) {
                                            pc.argsBuf.toString().ifEmpty { "{}" }
                                        } else {
                                            // argsBuf may be a JSON string fragment; validate and fall back.
                                            val raw = pc.argsBuf.toString()
                                            runCatching {
                                                LlmJson.json.parseToJsonElement(raw).jsonObject
                                                raw
                                            }.getOrElse { "{}" }
                                        }
                                        completedCalls.add(LlmToolCall(id = pc.id, name = pc.name, argumentsJson = argsJson))
                                    }
                                }
                            }

                            "interaction.completed" -> {
                                finishReason = "stop"
                                ev["interaction"]?.jsonObject?.get("usage")?.jsonObject?.let { u ->
                                    val inp = u["total_input_tokens"]?.jsonPrimitive?.intOrNull
                                    val out = u["total_output_tokens"]?.jsonPrimitive?.intOrNull
                                    val thought = u["total_thought_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    val total = u["total_tokens"]?.jsonPrimitive?.intOrNull
                                    usage = LlmUsage(
                                        promptTokens = inp,
                                        // Gemini bills output + thought tokens together; report the sum.
                                        completionTokens = out?.let { it + thought },
                                        totalTokens = total,
                                    )
                                }
                                break
                            }

                            "error" -> {
                                emit(LlmStreamEvent.Failed(LlmException("Interactions stream error: $data")))
                                return@execute
                            }
                        }
                    }
                    // Blank lines reset the event type (SSE spec).
                    line.isEmpty() -> eventType = ""
                }
            }

            // Flush any pending calls that didn't get a step.stop (defensive).
            pendingCalls.values.forEach { pc ->
                if (pc.name.isNotEmpty()) {
                    val argsJson = pc.argsBuf.toString().let { raw ->
                        runCatching { LlmJson.json.parseToJsonElement(raw).jsonObject; raw }.getOrElse { "{}" }
                    }
                    completedCalls.add(LlmToolCall(id = pc.id, name = pc.name, argumentsJson = argsJson))
                }
            }

            emit(
                LlmStreamEvent.Completed(
                    LlmMessage(
                        role = LlmRole.ASSISTANT,
                        content = textBuf.toString().ifEmpty { null },
                        reasoning = reasoningBuf.toString().ifEmpty { null },
                        reasoningSignature = lastThoughtSignature,
                        toolCalls = completedCalls,
                    ),
                    finishReason ?: if (completedCalls.isNotEmpty()) "stop" else null,
                    usage,
                )
            )
        }
    }

    // -- request body ---------------------------------------------------------

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.modelIdRemote)
        put("store", false)

        val systemText = request.messages
            .filter { it.role == LlmRole.SYSTEM }
            .mapNotNull { it.content?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        if (systemText.isNotEmpty()) put("system_instruction", systemText)

        put("input", buildInputSteps(request.messages))

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { spec ->
                    addJsonObject {
                        put("type", "function")
                        put("name", spec.name)
                        put("description", spec.description)
                        put("parameters", GeminiCommon.sanitizeSchema(spec.parametersSchema))
                    }
                }
            }
        }

        val thinkingLevel = GeminiCommon.effortToThinkingLevel(request.reasoningEffort)
        val genConfig = GeminiCommon.buildGenerationConfig(thinkingLevel, request.maxTokens, camelCase = false)
        if (genConfig.isNotEmpty()) put("generation_config", genConfig)
    }

    /**
     * Converts the [LlmMessage] history into an Interactions `input` array of typed steps.
     *
     * - SYSTEM: skipped (goes to `system_instruction`).
     * - USER: `user_input` step with either a string or a content-array (multimodal).
     * - ASSISTANT text: `model_output` step.
     * - ASSISTANT tool calls: one `function_call` step per call.
     * - TOOL result: `function_result` step (requires the tool name, looked up from ASSISTANT turns).
     * Converts the [LlmMessage] history into an Interactions `input` array of typed steps.
     *
     * - SYSTEM: skipped (goes to `system_instruction`).
     * - USER: `user_input` step with either a string or a content-array (multimodal).
     * - ASSISTANT text: `model_output` step.
     * - ASSISTANT tool calls: one `function_call` step per call.
     * - TOOL result: `function_result` step (requires the tool name, looked up from ASSISTANT turns).
     *
     * When an ASSISTANT message has [LlmMessage.reasoningSignature] set, a `thought` step is
     * injected before any following `function_call` or `model_output` steps. The Interactions API
     * requires these thought steps (with their encrypted signatures) to be replayed verbatim in
     * stateless mode; omitting them causes the API to fail or produce incorrect results when
     * thinking is enabled.  If [LlmMessage.reasoning] is also available it is included as the
     * thought summary so the model can refer to it; if only the signature is present the summary
     * is omitted (the API accepts a signature-only thought step).
     *
     * Note: when multiple thought steps occurred within a single turn (e.g., one before function
     * calls and another before the final answer), only one signature is stored per [LlmMessage].
     * The replay therefore collapses them into a single thought step — a known simplification for
     * multi-thought turns, which are uncommon in practice.
     */
    private fun buildInputSteps(messages: List<LlmMessage>): kotlinx.serialization.json.JsonArray {
        val toolNames = buildInputToolNames(messages) // callId -> name for function_result
        return kotlinx.serialization.json.buildJsonArray {
            for (msg in messages) {
                when (msg.role) {
                    LlmRole.SYSTEM -> Unit

                    LlmRole.USER -> {
                        addJsonObject {
                            put("type", "user_input")
                            if (msg.images.isEmpty()) {
                                // Simple string form for text-only turns.
                                put("content", msg.content ?: "")
                            } else {
                                // Array form for multimodal turns.
                                putJsonArray("content") {
                                    msg.content?.takeIf { it.isNotEmpty() }?.let { t ->
                                        addJsonObject { put("type", "text"); put("text", t) }
                                    }
                                    msg.images.forEach { img ->
                                        addJsonObject {
                                            put("type", "image")
                                            put("mime_type", img.mimeType)
                                            put("data", img.base64)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    LlmRole.ASSISTANT -> {
                        msg.toolCalls.forEach { tc -> toolNames[tc.id] = tc.name }

                        // Inject the thought step (with signature) before any function_call or
                        // model_output steps so the API can verify the reasoning chain.
                        msg.reasoningSignature?.takeIf { it.isNotEmpty() }?.let { sig ->
                            addJsonObject {
                                put("type", "thought")
                                put("signature", sig)
                                // Include the summary if we have reasoning text; helps the model
                                // refer to its prior thinking. Optional — API accepts sig-only.
                                msg.reasoning?.takeIf { it.isNotBlank() }?.let { text ->
                                    putJsonArray("summary") {
                                        addJsonObject { put("type", "text"); put("text", text) }
                                    }
                                }
                            }
                        }

                        // Emit text as a model_output step if present.
                        msg.content?.takeIf { it.isNotBlank() }?.let { text ->
                            addJsonObject {
                                put("type", "model_output")
                                putJsonArray("content") {
                                    addJsonObject { put("type", "text"); put("text", text) }
                                }
                            }
                        }
                        // Emit one function_call step per tool call.
                        msg.toolCalls.forEach { tc ->
                            addJsonObject {
                                put("type", "function_call")
                                put("id", tc.id)
                                put("name", tc.name)
                                put(
                                    "arguments",
                                    runCatching {
                                        LlmJson.json.parseToJsonElement(tc.argumentsJson).jsonObject
                                    }.getOrElse { JsonObject(emptyMap()) }
                                )
                            }
                        }
                    }

                    LlmRole.TOOL -> {
                        val name = toolNames[msg.toolCallId ?: ""] ?: ""
                        addJsonObject {
                            put("type", "function_result")
                            put("call_id", msg.toolCallId ?: "")
                            put("name", name)
                            putJsonArray("result") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", msg.content ?: "")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds the initial `callId → toolName` map from the existing [messages] history. This is
     * used both for encoding the `input` array and for resolving names while streaming (function
     * results injected mid-turn by the engine also need to look up names from earlier stream steps).
     */
    private fun buildInputToolNames(messages: List<LlmMessage>): HashMap<String, String> {
        val map = HashMap<String, String>()
        messages.filter { it.role == LlmRole.ASSISTANT }
            .forEach { m -> m.toolCalls.forEach { tc -> map[tc.id] = tc.name } }
        return map
    }

    private suspend fun readBodyText(resp: HttpResponse): String =
        runCatching { resp.bodyAsChannel().readRemainingText() }.getOrElse { "<unreadable>" }
            .also { WeLogger.w(TAG, "error response: $it") }

    /** Accumulates a streaming function_call step's argument fragments until step.stop. */
    private class PartialFunctionCall(
        val id: String,
        val name: String,
        val argsBuf: StringBuilder = StringBuilder(),
        /** True when the full args JsonObject was received in step.start rather than as fragments. */
        var argsFromObject: Boolean = false,
    )

    private companion object {
        const val TAG = "GeminiInteractions"
    }
}
