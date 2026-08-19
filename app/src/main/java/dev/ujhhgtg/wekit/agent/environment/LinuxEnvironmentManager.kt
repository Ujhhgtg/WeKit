package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.utils.HostInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LinuxEnvironmentManager(
    val nativeSnapshot: EnvironmentSnapshot = defaultNativeSnapshot(),
    private val backendFactory: (EnvironmentSnapshot) -> LinuxEnvironmentBackend = { snapshot ->
        when (snapshot.type) {
            LinuxEnvironmentType.NATIVE -> NativeBackend(snapshot)
            LinuxEnvironmentType.PROOT -> ProotBackend(snapshot)
            else -> error("${snapshot.type} backend is not implemented")
        }
    },
) {
    private val stateMutex = Mutex()
    private val executionMutexes = ConcurrentHashMap<String, Mutex>()
    private val leaseCounts = HashMap<String, Int>()
    private val deleting = HashSet<String>()
    private val backends = ConcurrentHashMap<String, LinuxEnvironmentBackend>()
    private val staleBackends = HashSet<String>()
    private val mutableHealth = MutableStateFlow<Map<String, EnvironmentHealth>>(
        mapOf(NATIVE_ENVIRONMENT_ID to EnvironmentHealth(EnvironmentHealthState.UNKNOWN))
    )

    val health: Flow<Map<String, EnvironmentHealth>> = mutableHealth

    fun observeEnvironments(): Flow<List<EnvironmentSnapshot>> =
        WeAgentRepository.observeLinuxEnvironments().map { stored ->
            listOf(nativeSnapshot) + stored.map(LinuxEnvironmentEntity::toSnapshot)
        }

    fun observeEffectiveEnvironmentId(sessionId: String): Flow<String> =
        WeAgentRepository.observeSessionEffectiveLinuxEnvironmentId(sessionId)

    suspend fun effectiveEnvironmentId(sessionId: String): String =
        WeAgentRepository.getEffectiveLinuxEnvironmentId(sessionId)

    suspend fun upsert(environment: LinuxEnvironmentEntity) {
        WeAgentRepository.upsertLinuxEnvironment(environment)
        stateMutex.withLock {
            staleBackends.add(environment.id)
            if ((leaseCounts[environment.id] ?: 0) == 0) {
                backends.remove(environment.id)?.close()
                staleBackends.remove(environment.id)
            }
        }
    }

    suspend fun delete(id: String): Boolean {
        require(id != NATIVE_ENVIRONMENT_ID) { "native environment cannot be deleted" }
        stateMutex.withLock {
            check((leaseCounts[id] ?: 0) == 0) { "environment is currently leased" }
            check(deleting.add(id)) { "environment deletion is already in progress" }
        }
        try {
            val deleted = WeAgentRepository.deleteLinuxEnvironment(id)
            if (deleted) {
                backends.remove(id)?.close()
                executionMutexes.remove(id)
                stateMutex.withLock { mutableHealth.update { it - id } }
            }
            return deleted
        } finally {
            stateMutex.withLock { deleting.remove(id) }
        }
    }

    suspend fun exec(
        environmentId: String,
        command: String,
        timeoutMillis: Long,
        environmentVariables: Map<String, String> = emptyMap(),
    ): ExecResult = withLease(environmentId) { it.exec(command, timeoutMillis, environmentVariables) }

    suspend fun ensureBridge(environmentId: String): BridgeInstallArtifact? =
        withLease(environmentId) { it.ensureBridge() }

    suspend fun edit(environmentId: String, request: FileEditRequest) =
        withLease(environmentId) { it.edit(request) }

    suspend fun checkHealth(environmentId: String): EnvironmentHealth {
        publishHealth(environmentId, EnvironmentHealth(EnvironmentHealthState.CHECKING))
        return runCatching { withLease(environmentId) { it.checkHealth() } }
            .getOrElse { EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, it.message) }
            .also { result -> publishHealth(environmentId, result) }
    }

    private suspend fun publishHealth(environmentId: String, value: EnvironmentHealth) {
        stateMutex.withLock {
            if (environmentId == NATIVE_ENVIRONMENT_ID ||
                WeAgentRepository.getLinuxEnvironment(environmentId) != null
            ) {
                mutableHealth.update { it + (environmentId to value) }
            }
        }
    }

    private suspend fun <T> withLease(
        environmentId: String,
        action: suspend (LinuxEnvironmentBackend) -> T,
    ): T {
        stateMutex.withLock {
            check(environmentId !in deleting) { "environment is being deleted" }
            leaseCounts[environmentId] = (leaseCounts[environmentId] ?: 0) + 1
        }
        try {
            val backend = backend(environmentId)
            return executionMutexes.computeIfAbsent(environmentId) { Mutex() }.withLock {
                action(backend)
            }
        } finally {
            stateMutex.withLock {
                val remaining = leaseCounts.getValue(environmentId) - 1
                if (remaining == 0) leaseCounts.remove(environmentId) else leaseCounts[environmentId] = remaining
                if (remaining == 0 && staleBackends.remove(environmentId)) {
                    backends.remove(environmentId)?.close()
                }
            }
        }
    }

    private suspend fun backend(environmentId: String): LinuxEnvironmentBackend {
        backends[environmentId]?.let { return it }
        val snapshot = if (environmentId == NATIVE_ENVIRONMENT_ID) nativeSnapshot else {
            WeAgentRepository.getLinuxEnvironment(environmentId)?.toSnapshot()
                ?: error("environment $environmentId does not exist")
        }
        val created = backendFactory(snapshot)
        val existing = backends.putIfAbsent(environmentId, created)
        if (existing != null) {
            created.close()
            return existing
        }
        return created
    }

    companion object {
        private fun defaultNativeSnapshot(): EnvironmentSnapshot {
            val workingDirectory = File(HostInfo.application.filesDir, "wekit-agent/environment/native")
                .apply { mkdirs() }
            return EnvironmentSnapshot(
                id = NATIVE_ENVIRONMENT_ID,
                displayName = "Native Android",
                type = LinuxEnvironmentType.NATIVE,
                operatingSystem = "Android/Toybox",
                architecture = System.getProperty("os.arch") ?: "unknown",
                shell = "/system/bin/sh",
                workingDirectory = workingDirectory.absolutePath,
                bridgeLocation = null,
                privilegesAndCapabilities = "WeChat UID and SELinux domain; no additional root privileges",
            )
        }
    }
}
