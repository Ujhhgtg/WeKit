package dev.ujhhgtg.wekit.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import java.time.Instant

// ---------------------------------------------------------------------------
// Conversation domain
// ---------------------------------------------------------------------------

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val systemPromptId: String?,
    val linuxEnvironmentId: String?,
    val lastEffectiveLinuxEnvironmentId: String?,
    /**
     * Bound model id, or null for "默认" — meaning follow [dev.ujhhgtg.wekit.agent.data.WeAgentSettings.defaultModelId] resolved
     * at turn time (like [systemPromptId]/[linuxEnvironmentId]). Null lets changing the global default apply
     * to existing sessions instead of snapshotting the model at creation.
     */
    val modelId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * Favorited (starred) sessions sort to the top of the drawer and cannot be deleted until
     * un-starred — this guards sessions that own triggers from accidental deletion.
     */
    val favorite: Boolean = false,
    /**
     * Last reported token usage + resolved context window for this session, persisted so the usage
     * strip survives a session switch / WeChat restart (usage is otherwise per-request in-memory).
     * All null until the first model response; [contextWindow] is the window of the model actually
     * used that turn (resolved "默认" included), null when the model declares none.
     */
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val contextWindow: Int? = null,
)

enum class MessageRole { USER, ASSISTANT, TOOL, SYSTEM }

@Entity(
    tableName = "messages",
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
    /** Assistant reasoning ("思考过程"), if the model produced any. Null for non-assistant rows. */
    val reasoning: String? = null,
    /**
     * Provider-specific signature that must be replayed on subsequent turns to keep the reasoning
     * chain intact. Null for providers that don't use signatures or when reasoning was off.
     *
     * - **Anthropic**: the base64 `signature` field from the `signature_delta` SSE event — must be
     *   sent back as a `{"type":"thinking","thinking":"...","signature":"..."}` content block.
     * - **Gemini Interactions**: the `thought_signature` value from the thought step — must be
     *   re-emitted as a `{"type":"thought","signature":"..."}` step before any `function_call` /
     *   `model_output` steps that followed it.
     */
    val reasoningSignature: String? = null,
)

enum class ApprovalStatus { AUTO_ALLOWED, USER_APPROVED, USER_REJECTED, AI_APPROVED, AI_REJECTED }

@Entity(
    tableName = "tool_calls",
    indices = [Index("messageId")],
)
data class ToolCallEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val provider: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String?,
    val approvalStatus: ApprovalStatus,
    val approvalReason: String?,
    val executedAt: Instant?,
    /**
     * Provider-specific opaque signature that must be replayed alongside this tool call on
     * subsequent turns. Null for providers that don't use per-call signatures.
     *
     * - **Gemini generateContent**: the `thoughtSignature` field from the `functionCall` part of
     *   the streaming response. Must be included on the **first** `functionCall` part in the model
     *   Content when replaying the assistant turn; Gemini 3 returns HTTP 400 if it is omitted.
     */
    val providerSignature: String? = null,
)

@Entity(
    tableName = "bridge_tool_audits",
    indices = [Index("sessionId"), Index("environmentId")],
)
data class BridgeToolAuditEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val environmentId: String,
    val parentToolCallId: String?,
    val providerId: String,
    val toolName: String,
    val argumentsJson: String,
    val approvalStatus: ApprovalStatus?,
    val executionOutcome: String,
    val result: String,
    val executedAt: Instant,
)

// ---------------------------------------------------------------------------
// Tool providers & permissions (§10)
// ---------------------------------------------------------------------------

enum class McpTransport { STREAMABLE_HTTP, SSE }

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val kind: ProviderKind,
    val name: String,
    val transport: McpTransport?,
    val endpointUrl: String?,
    val headersJson: String?,
    val enabled: Boolean,
)

@Entity(tableName = "tool_permissions", primaryKeys = ["providerId", "toolName"])
data class ToolPermissionEntity(
    val providerId: String,
    val toolName: String,
    val mode: ToolMode,
)
