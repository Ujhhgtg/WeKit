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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LocalLlamaSync {

    private const val TAG = "LocalLlamaSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val passMutex = Mutex()

    private var workerRunning = false
    private var dirty = false

    fun schedule() {
        val launchWorker = synchronized(stateLock) {
            dirty = true
            if (workerRunning) {
                false
            } else {
                workerRunning = true
                true
            }
        }
        if (launchWorker) scope.launch { runScheduledWorker(debounce = true) }
    }

    /** Runs one direct pass, serialized with scheduled work and other direct callers. */
    suspend fun syncOnce() = passMutex.withLock { syncPass() }

    private suspend fun runScheduledWorker(debounce: Boolean) {
        if (debounce) delay(500)
        try {
            while (synchronized(stateLock) {
                    if (dirty) {
                        dirty = false
                        true
                    } else {
                        false
                    }
                }) {
                runCatching { passMutex.withLock { syncPass() } }
                    .onFailure { WeLogger.e(TAG, "sync failed", it) }
            }
        } finally {
            val relaunch = synchronized(stateLock) {
                workerRunning = false
                if (dirty) {
                    workerRunning = true
                    true
                } else {
                    false
                }
            }
            if (relaunch) scope.launch { runScheduledWorker(debounce = false) }
        }
    }

    private suspend fun syncPass() {
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
