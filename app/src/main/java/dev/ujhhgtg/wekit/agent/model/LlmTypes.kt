package dev.ujhhgtg.wekit.agent.model

import kotlinx.serialization.json.JsonObject

/**
 * API-agnostic conversation types consumed by the [dev.ujhhgtg.wekit.agent.engine] loop. The
 * [LlmClient] adapters translate these to/from a specific provider wire format (OpenAI Chat
 * Completions, OpenAI Responses, …).
 */

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * A single tool invocation requested by the model. [id] correlates the later [LlmMessage] with
 * role [LlmRole.TOOL] carrying the result. [argumentsJson] is the raw JSON object string the model
 * produced (may need re-parsing/validation before use).
 *
 * [providerSignature] is opaque provider-specific metadata that must be replayed verbatim on the
 * next turn. Gemini 3 uses it for the `thoughtSignature` that guards `functionCall` parts: omitting
 * it on a replayed tool-call turn fails with HTTP 400. It is carried in-memory only within a single
 * [dev.ujhhgtg.wekit.agent.engine.AgentSessionEngine.runTurn] tool loop and is **not** persisted —
 * so across a session reload it is lost, exactly like Anthropic's thinking blocks (see
 * [AnthropicMessagesClient]). This is fine for the normal case where a turn ends on plain text.
 */
data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val providerSignature: String? = null,
)

/**
 * An inline image attached to a [LlmMessage]. Carries raw base64 (no data-URI prefix) so each
 * [LlmClient] adapter can wrap it in its own wire shape (OpenAI `image_url` data-URI, Anthropic
 * `source.base64`). Produced by the `ui-screenshot` tool and injected as a transient USER message.
 */
data class LlmImage(
    val base64: String,
    val mimeType: String = "image/png",
) {
    /** `data:<mime>;base64,<payload>` form used by the OpenAI `image_url` shape. */
    val dataUri: String get() = "data:$mimeType;base64,$base64"
}

/**
 * One message in the conversation sent to / received from the model.
 * - user/system: [content] holds text; [images] optionally carries inline images (vision models).
 * - assistant: [content] optional text plus zero or more [toolCalls]; [reasoning] holds any
 *   thinking text the model emitted; [reasoningSignature] is the provider signature that must be
 *   replayed on subsequent turns (see [LlmToolCall.providerSignature] for per-call signatures).
 * - tool: [content] holds the tool result, [toolCallId] correlates it with the request.
 */
data class LlmMessage(
    val role: LlmRole,
    val content: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val reasoning: String? = null,
    val images: List<LlmImage> = emptyList(),
    /**
     * Provider-specific opaque signature attached to the reasoning / thinking block of this
     * assistant message. Must be echoed back verbatim on subsequent turns:
     *
     * - **Anthropic**: base64 signature from the `signature_delta` SSE event; required in the
     *   `{"type":"thinking","thinking":"…","signature":"…"}` content block.
     * - **Gemini Interactions**: `thought_signature` from the thought step; required when
     *   re-emitting the `thought` step that preceded any function calls or the final model output.
     *
     * Null when reasoning was off, the provider doesn't use signatures, or the data predates v11.
     */
    val reasoningSignature: String? = null,
)

/** A tool advertised to the model, in provider-neutral form. */
data class LlmToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
)

/**
 * Token usage reported by the provider for one model response. All fields are nullable since not
 * every provider/endpoint returns them. [promptTokens] is the input (context) size, which the UI
 * uses to compute the context-window percentage.
 */
data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

/**
 * A fully-resolved request to the model: the effective model id, the message history, the tool
 * specs, and provider-neutral knobs. [reasoningEffort] is a standard gear string (or null to omit);
 * [customJsonOverride] is shallow-merged onto the top-level request body with higher precedence
 * (§5.2).
 */
data class LlmRequest(
    val modelIdRemote: String,
    val messages: List<LlmMessage>,
    /** Persisted conversation UUID, reused across turns and auxiliary requests for routing/cache affinity. */
    val sessionId: String,
    val tools: List<LlmToolSpec> = emptyList(),
    val reasoningEffort: String? = null,
    val customJsonOverride: JsonObject? = null,
    val stream: Boolean = true,
    /**
     * Per-model output-token limit (null = omit / use provider default). Each [LlmClient] adapter
     * maps this to its provider's field(s): Chat Completions sets both `max_tokens` and the official
     * `max_completion_tokens` (some non-standard OpenAI-compatible servers only read the former);
     * Responses uses `max_output_tokens`; Anthropic uses the required `max_tokens`.
     */
    val maxTokens: Int? = null,
)

/**
 * Streaming event emitted by [LlmClient.stream]. The loop renders text/reasoning deltas live and
 * accumulates tool-call argument fragments until [Completed] delivers the assembled message.
 */
sealed interface LlmStreamEvent {
    /** Incremental assistant text. */
    data class TextDelta(val text: String) : LlmStreamEvent

    /** Incremental reasoning/thinking text (models that expose it). */
    data class ReasoningDelta(val text: String) : LlmStreamEvent

    /** Terminal event: the fully-assembled assistant message, why generation stopped, and usage. */
    data class Completed(
        val message: LlmMessage,
        val finishReason: String?,
        val usage: LlmUsage? = null,
    ) : LlmStreamEvent

    /** Terminal error event. */
    data class Failed(val error: Throwable) : LlmStreamEvent
}
