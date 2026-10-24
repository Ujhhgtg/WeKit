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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [LlmClient] adapter for the **Anthropic Messages** wire format (`POST {baseUrl}/messages`), §5.1.
 *
 * Notable differences from the OpenAI formats, all handled here:
 *  - Auth is `x-api-key` + a required `anthropic-version` header (not a bearer token).
 *  - The system prompt is a top-level `system` string, not a message; we hoist all SYSTEM messages.
 *  - Tool results are `tool_result` content blocks inside a **user** message, and tool calls are
 *    `tool_use` blocks inside an **assistant** message. The API requires roles to alternate, so we
 *    coalesce consecutive same-wire-role turns (notably several TOOL results after a multi-call
 *    assistant turn) into a single message with multiple content blocks.
 *  - `max_tokens` is required; we default it to 4096 (overridable via custom JSON, §5.2).
 *  - Reasoning uses `thinking.budget_tokens`; we map the standard effort gears to a token budget.
 *
 * Streaming consumes the typed SSE events: `content_block_start/delta/stop` (text, thinking, and
 * `tool_use` with `input_json_delta` fragments), `message_delta` (stop reason), and `error`.
 *
 * Limitation: emitted `thinking` blocks are not replayed back on subsequent turns. This is fine when
 * thinking is off (the default); if a budget is set and the model interleaves thinking with tool
 * use, Anthropic may reject the follow-up. Leave reasoning effort unset for Anthropic tool loops.
 */
class AnthropicMessagesClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {
    private val endpoint = "${baseUrl.trimEnd('/')}/messages"

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val body = LlmJson.shallowMerge(buildBody(request), request.customJsonOverride)
        // The response is scoped to `execute { … }`: every early exit below (HTTP error, stream
        // error, break on message_stop) previously abandoned an open SSE body channel, leaking the
        // connection until GC. `execute` releases it on every path, including exceptions.
        http.preparePost(endpoint) {
            header("x-opencode-session", request.sessionId)
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            contentType(ContentType.Application.Json)
            setBody(LlmJson.json.encodeToString(JsonObject.serializer(), body))
        }.execute { resp ->
            if (!resp.status.isSuccess()) {
                emit(LlmStreamEvent.Failed(LlmException("HTTP ${resp.status.value}: ${readBodyText(resp)}")))
                return@execute
            }

            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            val signatureBuf = StringBuilder()
            val blocks = sortedMapOf<Int, ToolUseBlock>()
            var stopReason: String? = null
            var inputTokens: Int? = null
            var outputTokens: Int? = null

            val channel = resp.bodyAsChannel()
            while (true) {
                @Suppress("DEPRECATION")
                val line = channel.readUTF8Line() ?: break
                val data = SseParser.dataOrNull(line) ?: continue
                val event = runCatching { LlmJson.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

                when (event["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                    "message_start" ->
                        event["message"]?.jsonObject?.get("usage")?.jsonObject?.let { u ->
                            u["input_tokens"]?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
                            u["output_tokens"]?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                        }

                    "content_block_start" -> {
                        val index = event["index"]?.jsonPrimitive?.int ?: continue
                        val block = event["content_block"]?.jsonObject ?: continue
                        when (block["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                            "tool_use" -> blocks[index] = ToolUseBlock(
                                id = block["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "call_$index",
                                name = block["name"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
                            )
                            // Signal the UI that a thinking block has started so the "思考中..." card
                            // appears immediately — before any thinking_delta text arrives.
                            "thinking" -> emit(LlmStreamEvent.ReasoningDelta(""))
                        }
                    }

                    "content_block_delta" -> {
                        val index = event["index"]?.jsonPrimitive?.int ?: continue
                        val delta = event["delta"]?.jsonObject ?: continue
                        when (delta["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNullSafe()?.let {
                                textBuf.append(it); emit(LlmStreamEvent.TextDelta(it))
                            }

                            "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNullSafe()?.let {
                                reasoningBuf.append(it); emit(LlmStreamEvent.ReasoningDelta(it))
                            }
                            // signature_delta carries the encrypted thinking-block signature that Anthropic
                            // requires to be replayed verbatim when this turn appears in later history.
                            "signature_delta" -> delta["signature"]?.jsonPrimitive?.contentOrNullSafe()?.let {
                                signatureBuf.append(it)
                            }

                            "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNullSafe()?.let {
                                blocks[index]?.args?.append(it)
                            }
                        }
                    }

                    "message_delta" -> {
                        event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNullSafe()
                            ?.let { stopReason = it }
                        // Anthropic reports the running output token count on the top-level usage here.
                        event["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull
                            ?.let { outputTokens = it }
                    }

                    "message_stop" -> break

                    "error" -> {
                        emit(LlmStreamEvent.Failed(LlmException("Anthropic stream error: $data")))
                        return@execute
                    }
                }
            }

            val toolCalls = blocks.values
                .filter { it.name.isNotEmpty() }
                .map { LlmToolCall(it.id, it.name, it.args.toString().ifEmpty { "{}" }) }

            val usage = if (inputTokens != null || outputTokens != null) {
                LlmUsage(
                    promptTokens = inputTokens,
                    completionTokens = outputTokens,
                    totalTokens = (inputTokens ?: 0).let { i -> (outputTokens ?: 0).let { o -> i + o } }
                        .takeIf { inputTokens != null && outputTokens != null },
                )
            } else null

            emit(
                LlmStreamEvent.Completed(
                    LlmMessage(
                        role = LlmRole.ASSISTANT,
                        content = textBuf.toString().ifEmpty { null },
                        reasoning = reasoningBuf.toString().ifEmpty { null },
                        reasoningSignature = signatureBuf.toString().ifEmpty { null },
                        toolCalls = toolCalls,
                    ),
                    stopReason ?: if (toolCalls.isNotEmpty()) "tool_use" else null,
                    usage,
                )
            )
        }
    }

    // -- request body ---------------------------------------------------------

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.modelIdRemote)
        put("stream", true)
        // Anthropic requires `max_tokens`; use the per-model value or the default.
        val maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS
        put("max_tokens", maxTokens)

        // Hoist all system messages into the top-level `system` field. We emit it as an array of
        // text blocks (not a plain string) so the last block can carry a `cache_control` breakpoint:
        // this makes Anthropic cache the tools + system prefix (prompt caching is opt-in on this API,
        // unlike OpenAI's automatic prefix caching), so repeated turns re-read it instead of re-billing.
        val systemText = request.messages
            .filter { it.role == LlmRole.SYSTEM }
            .mapNotNull { it.content?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        if (systemText.isNotEmpty()) {
            putJsonArray("system") {
                addJsonObject {
                    put("type", "text")
                    put("text", systemText)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            }
        }

        request.reasoningEffort?.let { effort ->
            budgetTokensFor(effort)?.let { budget ->
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
                // max_tokens must exceed the thinking budget: honor the (per-model or default)
                // value, but floor it to budget + DEFAULT_MAX_TOKENS so the response has room.
                put("max_tokens", maxOf(maxTokens, budget + DEFAULT_MAX_TOKENS))
            }
        }

        put("messages", encodeMessages(request.messages))

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { spec ->
                    addJsonObject {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("input_schema", spec.parametersSchema)
                    }
                }
            }
        }
    }

    /**
     * Builds the alternating user/assistant message array. SYSTEM messages are skipped (hoisted).
     * Consecutive turns of the same wire role are merged into one message with concatenated content
     * blocks — required because TOOL results map to `user` blocks and a multi-call assistant turn
     * produces several of them in a row.
     */
    private fun encodeMessages(messages: List<LlmMessage>): JsonArray {
        // (wireRole -> mutable list of content blocks), preserving order with coalescing.
        val turns = ArrayList<Pair<String, MutableList<JsonObject>>>()

        fun appendBlocks(role: String, newBlocks: List<JsonObject>) {
            if (newBlocks.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) {
                last.second.addAll(newBlocks)
            } else {
                turns.add(role to newBlocks.toMutableList())
            }
        }

        for (msg in messages) {
            when (msg.role) {
                LlmRole.SYSTEM -> Unit
                LlmRole.USER -> {
                    // Text first, then any inline images as base64 `image` blocks (vision).
                    val blocks = ArrayList<JsonObject>()
                    msg.content?.takeIf { it.isNotEmpty() }?.let { blocks.add(textBlock(it)) }
                    msg.images.forEach { img -> blocks.add(imageBlock(img)) }
                    if (blocks.isEmpty()) blocks.add(textBlock(""))
                    appendBlocks("user", blocks)
                }

                LlmRole.TOOL -> appendBlocks(
                    "user",
                    listOf(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", msg.toolCallId ?: "")
                        put("content", msg.content ?: "")
                    }),
                )

                LlmRole.ASSISTANT -> {
                    val blocks = ArrayList<JsonObject>()
                    // Thinking block must precede text and tool_use blocks. Include it when we have
                    // the signature — Anthropic requires the exact signature from the original turn;
                    // omitting the block (no signature stored) degrades silently rather than failing.
                    if (msg.reasoning != null && msg.reasoningSignature != null) {
                        blocks.add(buildJsonObject {
                            put("type", "thinking")
                            put("thinking", msg.reasoning)
                            put("signature", msg.reasoningSignature)
                        })
                    }
                    msg.content?.takeIf { it.isNotBlank() }?.let { blocks.add(textBlock(it)) }
                    msg.toolCalls.forEach { tc ->
                        blocks.add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            put("input", parseInputOrEmpty(tc.argumentsJson))
                        })
                    }
                    appendBlocks("assistant", blocks)
                }
            }
        }

        // Mark the final content block of the last turn with a cache breakpoint too, so the growing
        // conversation prefix (everything before this turn's tail) is cached and re-read on the next
        // turn — not just the static system+tools prefix. Anthropic caches up to the longest matching
        // prefix; the previous request's breakpoint sits mid-history on the next request and still hits.
        val lastTurnIndex = turns.lastIndex
        val lastBlockIndex = turns.lastOrNull()?.second?.lastIndex ?: -1
        return buildJsonArray {
            turns.forEachIndexed { turnIndex, (role, blocks) ->
                addJsonObject {
                    put("role", role)
                    putJsonArray("content") {
                        blocks.forEachIndexed { blockIndex, block ->
                            if (turnIndex == lastTurnIndex && blockIndex == lastBlockIndex) {
                                // Re-emit the block with an ephemeral cache_control breakpoint appended.
                                add(buildJsonObject {
                                    block.forEach { (k, v) -> put(k, v) }
                                    putJsonObject("cache_control") { put("type", "ephemeral") }
                                })
                            } else {
                                add(block)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** An inline base64 image block (Anthropic `source.type = base64`). */
    private fun imageBlock(img: LlmImage): JsonObject = buildJsonObject {
        put("type", "image")
        putJsonObject("source") {
            put("type", "base64")
            put("media_type", img.mimeType)
            put("data", img.base64)
        }
    }

    /** Tool-call arguments arrive as a JSON string; Anthropic expects an object under `input`. */
    private fun parseInputOrEmpty(argumentsJson: String): JsonObject =
        runCatching { LlmJson.json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    /** Maps the standard reasoning-effort gears to an Anthropic thinking budget, or null to disable. */
    private fun budgetTokensFor(effort: String): Int? = when (effort.lowercase()) {
        "minimal" -> 1024
        "low" -> 2048
        "medium" -> 8192
        "high" -> 16384
        "xhigh", "max" -> 32000
        else -> effort.toIntOrNull()?.takeIf { it > 0 }
    }

    private suspend fun readBodyText(resp: HttpResponse): String =
        runCatching { resp.bodyAsChannel().readRemainingText() }.getOrElse { "<unreadable>" }
            .also { WeLogger.w(TAG, "error response: $it") }

    private class ToolUseBlock(val id: String, val name: String, val args: StringBuilder = StringBuilder())

    private companion object {
        const val TAG = "AnthropicMessages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 4096
    }
}
