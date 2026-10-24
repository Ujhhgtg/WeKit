package dev.ujhhgtg.wekit.agent.model

import kotlinx.coroutines.flow.Flow

/**
 * Talks to one model-provider wire format. Implementations: [OpenAiChatCompletionsClient] and
 * [OpenAiResponsesClient] (§5.1). Each instance is bound to a base URL + API key (built by
 * [ModelProviderManager]); all requests stream. A non-streaming caller can collect the flow and
 * take the terminal [LlmStreamEvent.Completed].
 */
interface LlmClient {
    /**
     * Issues [request], emitting [LlmStreamEvent]s as the response streams in. The flow completes
     * after emitting exactly one terminal event ([LlmStreamEvent.Completed] or
     * [LlmStreamEvent.Failed]). Collect on `Dispatchers.IO`; the flow does not switch dispatchers.
     */
    fun stream(request: LlmRequest): Flow<LlmStreamEvent>
}

/** Thrown/wrapped for provider HTTP or protocol errors, surfaced via [LlmStreamEvent.Failed]. */
class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
