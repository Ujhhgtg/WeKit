package dev.ujhhgtg.wekit.agent.model.local

import dev.ujhhgtg.wekit.extensions.LlamaPackNotInstalledException
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Lifecycle of the local llama inference server (1:1 with the JNI status JSON). */
sealed interface LlamaState {
    data object Stopped : LlamaState
    data object Starting : LlamaState
    data class Running(val port: Int, val pid: Int, val backend: String) : LlamaState
    data class Failed(val reason: String) : LlamaState
}

@Serializable
data class HealthBackend(
    val requested: String,
    val active: String,
    val devices: List<String> = emptyList(),
    val available: List<String> = emptyList(),
    val gpuLayers: Int,
    val totalLayers: Int,
    val fallbackReason: String? = null,
)

/** `GET /health` payload of the inference server. */
@Serializable
data class LlamaHealth(
    val state: String,
    val model: String,
    val port: Int,
    val uptimeSec: Long,
    val rssBytes: Long,
    val ctxUsed: Long,
    val ctxTotal: Long,
    val tokensPerSec: Double,
    val backend: HealthBackend,
)

/**
 * Device-side controller of the local llama inference server: single-flight
 * start/restart via the JNI bridge, lifecycle state mirrored from the native
 * controller (authoritative), and 2s `/health` polling while Running.
 */
object LocalLlamaController {

    private const val TAG = "LocalLlamaController"
    private const val HEALTH_POLL_INTERVAL_MS = 2_000L

    private data class ActiveTuple(val modelPath: String, val nCtx: Int, val backend: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 3_000
        }
    }

    private val stateFlow = MutableStateFlow<LlamaState>(LlamaState.Stopped)

    /** Lifecycle; always freshly reconciled from the native controller on change. */
    val state: StateFlow<LlamaState> = stateFlow

    private val healthFlow = MutableStateFlow<LlamaHealth?>(null)

    /** Last successful `/health` probe; null while not Running or unreachable. */
    val health: StateFlow<LlamaHealth?> = healthFlow

    private val pollLock = Any()
    private var pollJob: Job? = null
    private val pollGeneration = AtomicLong()
    private var pollPort: Int? = null

    /** The `(model, nCtx, backend)` tuple the native controller's child belongs to. */
    @Volatile
    private var active: ActiveTuple? = null

    fun isRunning(): Boolean {
        if (!NativeLoader.isLlamaLoaded()) return false
        return parseStatus(LlamaServerNative.serverStatus()).state == "running"
    }

    /** Native payload deletion is blocked only while a child is starting or running. */
    fun isLifecycleActive(): Boolean =
        stateFlow.value is LlamaState.Starting || stateFlow.value is LlamaState.Running

    /** Absolute path of the model the running child serves, or null. */
    fun loadedModelPath(): String? = active?.modelPath

    /** `http://127.0.0.1:<port>/v1` while Running, else null. */
    fun baseUrlOrNull(): String? {
        val running = stateFlow.value as? LlamaState.Running ?: return null
        return "http://127.0.0.1:${running.port}/v1"
    }

    /** Fire-and-forget start for the UI; failures land in [state] and the log. */
    fun start(gguf: File, nCtx: Int, backend: String) {
        scope.launch {
            try {
                ensureReady(gguf, nCtx, backend)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "local llama start failed", e)
            }
        }
    }

    /** Starts a controller-owned stop and returns its job so UI callers can await completion. */
    fun stop(): Job = scope.launch { mutex.withLock { stopInternal() } }

    /**
     * Single-flight: returns the OpenAI-compatible base URL with the server
     * running exactly `(gguf, nCtx, backend)` — an identical running tuple
     * short-circuits, anything else is stopped and restarted. Throws
     * [LlamaPackNotInstalledException] when the llama-native pack is missing and
     * [IllegalStateException] carrying the native controller's failure reason.
     */
    suspend fun ensureReady(gguf: File, nCtx: Int, backend: String): String = mutex.withLock {
        ensureReadyLocked(gguf, nCtx, backend)
    }

    /**
     * Exclusively leases the singleton server for one collected request stream. The lifecycle
     * mutex remains owned by the lease, so start, stop, and an incompatible request cannot change
     * the model tuple or random port until [LocalLlamaServerLease.release].
     */
    internal suspend fun acquireServerLease(
        gguf: File,
        nCtx: Int,
        backend: String,
    ): LocalLlamaServerLease {
        val owner = Any()
        mutex.lock(owner)
        return try {
            ensureReadyLocked(gguf, nCtx, backend)
            LocalLlamaServerLease { mutex.unlock(owner) }
        } catch (error: Throwable) {
            mutex.unlock(owner)
            throw error
        }
    }

    private fun ensureReadyLocked(gguf: File, nCtx: Int, backend: String): String {
        val desired = ActiveTuple(gguf.absolutePath, nCtx, backend)
        syncState()
        val current = stateFlow.value
        if (current is LlamaState.Running && active == desired) {
            return "http://127.0.0.1:${current.port}/v1"
        }
        stopPolling()
        if (current is LlamaState.Starting || current is LlamaState.Running) stopInternal()

        stateFlow.value = LlamaState.Starting
        try {
            // The OpenCL library variant is only needed for the explicit OpenCL backend;
            // auto/cpu/vulkan run on the base (CPU/Vulkan) build.
            NativeLoader.ensureLlamaLoaded(preferOpencl = backend == "opencl")
            val result = LlamaServerNative.startServer(
                desired.modelPath,
                nCtx,
                backend,
                configJsonFor(gguf),
            )
            val status = parseStatus(result)
            if (status.state != "running") {
                val reason = status.error?.takeIf(String::isNotBlank)
                    ?: "start failed with state ${status.state}"
                throw IllegalStateException(reason)
            }
            active = desired
            stateFlow.value = LlamaState.Running(status.port!!, status.pid ?: -1, backend)
            startPolling(stateFlow.value as LlamaState.Running)
            return "http://127.0.0.1:${status.port}/v1"
        } catch (failure: Throwable) {
            stopPolling()
            active = null
            val reason = failure.message?.takeIf(String::isNotBlank)
                ?: failure.javaClass.simpleName
            stateFlow.value = LlamaState.Failed(reason)
            throw failure
        }
    }

    private fun stopInternal() {
        stopPolling()
        if (NativeLoader.isLlamaLoaded()) LlamaServerNative.stopServer()
        active = null
        syncState()
    }

    /**
     * Re-reads the authoritative native lifecycle status into [state]. The status
     * JSON carries no backend, so the remembered [active] tuple supplies it.
     */
    private fun syncState() {
        val mapped = if (NativeLoader.isLlamaLoaded()) {
            val status = parseStatus(LlamaServerNative.serverStatus())
            when (status.state) {
                "starting" -> LlamaState.Starting
                "running" -> LlamaState.Running(
                    port = status.port!!,
                    pid = status.pid ?: -1,
                    backend = active?.backend ?: "auto",
                )
                "failed" -> LlamaState.Failed(status.error ?: "unknown failure")
                else -> LlamaState.Stopped
            }
        } else {
            LlamaState.Stopped
        }
        if (mapped !is LlamaState.Running) active = null
        stateFlow.value = mapped
        if (mapped is LlamaState.Running) startPolling(mapped) else stopPolling()
    }

    /** Polls `/health` every 2s while Running; a failed probe reconciles against the native status. */
    private fun startPolling(running: LlamaState.Running) {
        synchronized(pollLock) {
            if (pollJob?.isActive == true && pollPort == running.port) return
            stopPollingLocked()
            healthFlow.value = null
            val generation = pollGeneration.get()
            pollPort = running.port
            pollJob = scope.launch {
                try {
                    while (pollGeneration.get() == generation) {
                        val current = stateFlow.value as? LlamaState.Running ?: break
                        if (current.port != running.port) break
                        val polledHealth = try {
                            val response = httpClient.get("http://127.0.0.1:${running.port}/health")
                            if (response.status.isSuccess()) {
                                json.decodeFromString(LlamaHealth.serializer(), response.bodyAsText())
                            } else {
                                null
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        val published = synchronized(pollLock) {
                            val stillCurrent = stateFlow.value as? LlamaState.Running
                            if (pollGeneration.get() != generation || stillCurrent?.port != running.port) {
                                false
                            } else {
                                healthFlow.value = polledHealth
                                true
                            }
                        }
                        if (!published) break
                        if (polledHealth == null) {
                            // The HTTP side died or never came up; native status remains authoritative.
                            syncState()
                            if (pollGeneration.get() != generation || stateFlow.value !is LlamaState.Running) break
                        }
                        delay(HEALTH_POLL_INTERVAL_MS)
                    }
                } finally {
                    synchronized(pollLock) {
                        if (pollGeneration.get() == generation) {
                            pollJob = null
                            pollPort = null
                        }
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        synchronized(pollLock) {
            stopPollingLocked()
            healthFlow.value = null
        }
    }

    private fun stopPollingLocked() {
        pollGeneration.incrementAndGet()
        pollJob?.cancel()
        pollJob = null
        pollPort = null
    }

    /**
     * configJson for startServer: idle timeout plus the sampling preset of the
     * installed model being served (defaults 0.6/0.95/20 when the model pack's
     * meta is unknown).
     */
    private fun configJsonFor(gguf: File): String {
        val installed = LocalLlamaModels.listInstalled()
            .firstOrNull { it.gguf.absolutePath == gguf.absolutePath }
        return buildJsonObject {
            put("idleTimeoutSec", 600)
            put("temperature", installed?.temperature ?: 0.6)
            put("topP", installed?.topP ?: 0.95)
            put("topK", installed?.topK ?: 20)
        }.toString()
    }

    private data class StatusJson(val state: String, val port: Int?, val pid: Int?, val error: String?)

    private fun parseStatus(raw: String): StatusJson {
        val obj: JsonObject = json.parseToJsonElement(raw).jsonObject
        return StatusJson(
            state = obj.getValue("state").jsonPrimitive.content,
            port = obj["port"]?.jsonPrimitive?.intOrNull,
            pid = obj["pid"]?.jsonPrimitive?.intOrNull,
            error = obj["error"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        )
    }
}

/** Idempotent handle for the controller's exclusive request-stream lease. */
internal class LocalLlamaServerLease internal constructor(private val releaseBlock: () -> Unit) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) releaseBlock()
    }
}
