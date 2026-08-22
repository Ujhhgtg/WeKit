package dev.ujhhgtg.wekit.agent.model.local

import dev.ujhhgtg.wekit.agent.data.WeAgentDatabase
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object LocalLlamaSync {

    private const val TAG = "LocalLlamaSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pending = false

    fun schedule() {
        if (pending) return
        pending = true
        scope.launch {
            delay(500)
            pending = false
            runCatching { syncOnce() }
                .onFailure { WeLogger.e(TAG, "sync failed", it) }
        }
    }

    suspend fun syncOnce() {
        val db = WeAgentDatabase.instance
        val canonicalProvider = ModelProviderEntity(
            id = LocalLlama.PROVIDER_ID,
            type = ModelProviderType.LOCAL_LLAMA,
            name = "",
            baseUrl = "",
            apiKey = "",
        )
        if (db.modelProviderDao().getById(LocalLlama.PROVIDER_ID) != canonicalProvider) {
            db.modelProviderDao().upsert(canonicalProvider)
        }

        val desired = LocalLlamaModels.listInstalled()
        val existing = db.modelDao().getForProviderOnce(LocalLlama.PROVIDER_ID).associateBy { it.id }
        for (model in desired) {
            val previous = existing[model.id]
            db.modelDao().upsert(
                ModelEntity(
                    id = model.id,
                    providerId = LocalLlama.PROVIDER_ID,
                    modelIdRemote = model.id,
                    reasoningEffort = if (previous == null) {
                        model.defaultReasoningEffort
                    } else {
                        previous.reasoningEffort
                    },
                    customJsonOverride = null,
                    displayName = model.displayName,
                    contextWindow = if (previous == null) {
                        model.defaultContextWindow
                    } else {
                        previous.contextWindow
                    },
                    maxTokens = model.maxTokens,
                    supportsVision = false,
                )
            )
        }

        val desiredIds = desired.mapTo(HashSet()) { it.id }
        for (id in existing.keys - desiredIds) {
            WeAgentRepository.deleteLocalLlamaModelForSync(id)
        }
    }
}
