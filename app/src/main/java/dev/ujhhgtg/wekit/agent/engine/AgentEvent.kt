package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus

/**
 * Live events emitted by [AgentSessionEngine.runTurn] for the UI to render (§1.2 / §2.1). These are
 * distinct from persisted Room rows — they drive the streaming chat bubbles, tool cards, and the
 * floating ball state machine.
 */
sealed interface AgentEvent {
    /** The assistant started a new model request (round). [requestIndex] is 1-based. */
    data class RequestStarted(val requestIndex: Int) : AgentEvent

    /** Incremental assistant text. */
    data class TextDelta(val text: String) : AgentEvent

    /** Incremental reasoning/thinking text. */
    data class ReasoningDelta(val text: String) : AgentEvent

    /** A tool call is about to be evaluated for approval. */
    data class ToolCallStarted(val callId: String, val toolName: String, val argumentsJson: String) : AgentEvent

    /** A tool call is waiting on human approval (drives the PendingApproval ball state). */
    data class ToolAwaitingApproval(val callId: String, val toolName: String) : AgentEvent

    /** A tool call finished (executed or denied). */
    data class ToolCallFinished(
        val callId: String,
        val toolName: String,
        val status: ApprovalStatus,
        val resultText: String,
    ) : AgentEvent

    /** Token usage reported for the just-completed model request (per round; may arrive each round). */
    data class UsageUpdated(val usage: dev.ujhhgtg.wekit.agent.model.LlmUsage) : AgentEvent

    /** The turn ended normally: the model returned no further tool calls. */
    data class TurnCompleted(val finalText: String?) : AgentEvent

    /** The turn ended with an uncaught error (§1.2 Error ball state). */
    data class TurnFailed(val error: Throwable) : AgentEvent
}
