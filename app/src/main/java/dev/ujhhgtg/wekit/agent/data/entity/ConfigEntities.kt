package dev.ujhhgtg.wekit.agent.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType

// ---------------------------------------------------------------------------
// Model providers & models
// ---------------------------------------------------------------------------

enum class ModelProviderType {
    OPENAI_CHAT_COMPLETION,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
    GEMINI_INTERACTIONS
}

/**
 * A configured LLM endpoint. [apiKey] is stored as-is (unencrypted) — see
 * [dev.ujhhgtg.wekit.agent.data.WeAgentRepository.upsertModelProvider] for why.
 */
@Entity(tableName = "model_providers")
data class ModelProviderEntity(
    @PrimaryKey val id: String,
    val type: ModelProviderType,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
)

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val modelIdRemote: String,
    val reasoningEffort: String?,
    val customJsonOverride: String?,
    val displayName: String,
    /** Custom context window (tokens) for usage %; null = unknown (percentage hidden). */
    val contextWindow: Int? = null,
    /**
     * Max output/completion tokens per response; null = omit the field (server default). Mapped
     * per wire format: OpenAI Chat Completions sends BOTH `max_tokens` and `max_completion_tokens`
     * (some Chinese providers use the non-standard `max_tokens`); OpenAI Responses uses
     * `max_output_tokens`; Anthropic uses `max_tokens` (required — overrides its 4096 default).
     */
    val maxTokens: Int? = null,
    /**
     * Whether this model accepts image input. Gates the `ui-screenshot` vision tool: it is only
     * advertised to the model when the session's bound model has this set (the turn snapshots it
     * into [dev.ujhhgtg.wekit.agent.tool.ToolVisibility.visionTools], so concurrent sessions on
     * different models can't clobber each other). Sending images to a non-vision model would error
     * at the provider, so the tool is hidden rather than failing.
     */
    val supportsVision: Boolean = false,
)

// ---------------------------------------------------------------------------
// Prompts. Prompts are four flat, independent lists. System prompts bind
// per-session; per-turn & conditional prompts each have a global on/off switch;
// presets are reusable snippets.
// ---------------------------------------------------------------------------

/** A named system prompt. A session may bind one (SessionEntity.systemPromptId). */
@Entity(tableName = "system_prompts")
data class SystemPromptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
)

/** A per-turn prompt prefixed to every user message while [enabled] (global). */
@Entity(tableName = "per_turn_prompts")
data class PerTurnPromptEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean,
)

/** A conditional prompt: when [enabled], [regex] is matched against each model reply and [content] is injected on a hit. */
@Entity(tableName = "conditional_prompts")
data class ConditionalPromptEntity(
    @PrimaryKey val id: String,
    val regex: String,
    val content: String,
    val enabled: Boolean,
)

/** A reusable preset snippet the user can insert into the input; no switch. */
@Entity(tableName = "preset_prompts")
data class PresetPromptEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
)

// ---------------------------------------------------------------------------
// Linux environments & global settings
// ---------------------------------------------------------------------------

@Entity(tableName = "linux_environments")
data class LinuxEnvironmentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: LinuxEnvironmentType,
    val workingDirectory: String,
    val environmentVariablesJson: String = "{}",
    val rootfsPath: String? = null,
    val rootfsContentVersion: String? = null,
    val createdAt: Long? = null,
    val sshHost: String? = null,
    val sshPort: Int? = null,
    val sshUsername: String? = null,
    val sshAuthenticationType: String? = null,
    val sshCredentialCiphertext: ByteArray? = null,
    val sshCredentialIv: ByteArray? = null,
    val sshCredentialReference: String? = null,
    val sshHostKeyAlgorithm: String? = null,
    val sshHostKeyFingerprint: String? = null,
    val bridgePath: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is LinuxEnvironmentEntity &&
            id == other.id && name == other.name && type == other.type &&
            workingDirectory == other.workingDirectory &&
            environmentVariablesJson == other.environmentVariablesJson &&
            rootfsPath == other.rootfsPath && rootfsContentVersion == other.rootfsContentVersion &&
            createdAt == other.createdAt && sshHost == other.sshHost && sshPort == other.sshPort &&
            sshUsername == other.sshUsername && sshAuthenticationType == other.sshAuthenticationType &&
            sshCredentialCiphertext.contentEqualsNullable(other.sshCredentialCiphertext) &&
            sshCredentialIv.contentEqualsNullable(other.sshCredentialIv) &&
            sshCredentialReference == other.sshCredentialReference &&
            sshHostKeyAlgorithm == other.sshHostKeyAlgorithm &&
            sshHostKeyFingerprint == other.sshHostKeyFingerprint && bridgePath == other.bridgePath

    override fun hashCode(): Int = id.hashCode()
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Per-service API key storage for external network integrations (Exa, Brave, etc.).
 * Keys are stored as-is; see [dev.ujhhgtg.wekit.agent.net.ExternalServiceId] for known ids.
 */
@Entity(tableName = "external_services")
data class ExternalServiceEntity(
    @PrimaryKey val serviceId: String,
    val apiKey: String?,
)
