package dev.ujhhgtg.wekit.extensions

import java.io.File

/**
 * A pack that installs local-LLM model weights (GGUF). Exposes the flattened
 * [InstalledLocalModel] view that the local model provider consumes; the raw
 * meta schema lives in `agent.model.local` ([parseLocalModelMeta]).
 */
interface ModelExtensionPack : ExtensionPack {
    /** The model this pack currently provides, or null when not installed. */
    fun installedModel(): InstalledLocalModel?
}

/** One locally installed model, flattened from the pack manifest's meta block. */
data class InstalledLocalModel(
    val id: String,
    val displayName: String,
    val gguf: File,
    val quant: String,
    val defaultContextWindow: Int,
    val maxContextWindow: Int,
    val maxTokens: Int,
    val defaultReasoningEffort: String,
    val temperature: Double,
    val topP: Double,
    val topK: Int,
)
