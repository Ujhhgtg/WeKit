package dev.ujhhgtg.wekit.agent.model.local

import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.extensions.InstalledLocalModel
import dev.ujhhgtg.wekit.extensions.ModelExtensionPack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Registry of locally installed LLM models (GGUF weights shipped in model
 * extension packs). All lookups re-scan disk so installs/deletes are visible
 * immediately.
 */
object LocalLlamaModels {
    fun listInstalled(): List<InstalledLocalModel> =
        ExtensionPacks.packs.filterIsInstance<ModelExtensionPack>().mapNotNull { it.installedModel() }

    fun resolveModelFile(modelId: String): File? =
        listInstalled().firstOrNull { it.id == modelId }?.gguf

    fun defaultContextWindow(modelId: String): Int? =
        listInstalled().firstOrNull { it.id == modelId }?.defaultContextWindow
}

/** Meta block carried by model-pack index entries and install manifests. */
@Serializable
data class LocalModelMeta(
    val schemaVersion: Int,
    val models: List<LocalModelMetaEntry>,
)

@Serializable
data class LocalModelMetaEntry(
    val id: String,
    val displayName: String,
    val file: String,
    val quant: String,
    val defaultContextWindow: Int,
    val maxContextWindow: Int,
    val maxTokens: Int,
    val defaultReasoningEffort: String,
    val supportsThinking: Boolean = true,
    val sampling: LocalSampling = LocalSampling(),
)

@Serializable
data class LocalSampling(
    val temperature: Double = 0.6,
    val topP: Double = 0.95,
    val topK: Int = 20,
)

private val json = Json { ignoreUnknownKeys = true }

/** Parses a meta block into its first (primary) model, anchored at [gguf]. */
fun parseLocalModelMeta(metaJson: String, gguf: File): InstalledLocalModel {
    val entry = json.decodeFromString(LocalModelMeta.serializer(), metaJson).models.first()
    return InstalledLocalModel(
        id = entry.id,
        displayName = entry.displayName,
        gguf = gguf,
        quant = entry.quant,
        defaultContextWindow = entry.defaultContextWindow,
        maxContextWindow = entry.maxContextWindow,
        maxTokens = entry.maxTokens,
        defaultReasoningEffort = entry.defaultReasoningEffort,
        temperature = entry.sampling.temperature,
        topP = entry.sampling.topP,
        topK = entry.sampling.topK,
    )
}
