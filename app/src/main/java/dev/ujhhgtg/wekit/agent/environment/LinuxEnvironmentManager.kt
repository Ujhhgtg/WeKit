package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.agent.ssh.EncryptedSshCredentials
import dev.ujhhgtg.wekit.agent.ssh.SshCredentials
import dev.ujhhgtg.wekit.agent.ssh.SshCredentialStore
import dev.ujhhgtg.wekit.agent.ssh.SshEndpoint
import dev.ujhhgtg.wekit.agent.ssh.SshHostKey
import dev.ujhhgtg.wekit.agent.ssh.SshHostKeyException
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LinuxEnvironmentManager(
    nativeSnapshot: EnvironmentSnapshot = defaultNativeSnapshot(),
    private val backendFactory: ((EnvironmentSnapshot) -> LinuxEnvironmentBackend)? = null,
    private val persistEnvironment: suspend (LinuxEnvironmentEntity) -> Unit = WeAgentRepository::upsertLinuxEnvironment,
    private val getEnvironment: suspend (String) -> LinuxEnvironmentEntity? = WeAgentRepository::getLinuxEnvironment,
    private val deleteEnvironment: suspend (String, EnvironmentSnapshot, EnvironmentSnapshot) -> Boolean =
        WeAgentRepository::deleteLinuxEnvironment,
    private val notifyEnvironmentDeleted: suspend (String) -> Unit = { id ->
        dev.ujhhgtg.wekit.features.api.agent.WeAgentService.onLinuxEnvironmentDeleted(id)
    },
    private val loadNativeConfiguration: suspend () -> Pair<String?, String> = {
        WeAgentSettings.nativeLinuxWorkingDirectory() to WeAgentSettings.nativeLinuxEnvironmentVariables()
    },
) {
    var nativeSnapshot: EnvironmentSnapshot = nativeSnapshot
        private set
    private val stateMutex = Mutex()
    private val executionMutexes = ConcurrentHashMap<String, Mutex>()
    private val leaseCounts = HashMap<String, Int>()
    private val deleting = HashSet<String>()
    private val backends = ConcurrentHashMap<String, LinuxEnvironmentBackend>()
    private val staleBackends = HashSet<String>()
    private val mutableHealth = MutableStateFlow<Map<String, EnvironmentHealth>>(
        mapOf(NATIVE_ENVIRONMENT_ID to EnvironmentHealth(EnvironmentHealthState.UNKNOWN))
    )
    private val mutableNativeSnapshot = MutableStateFlow(nativeSnapshot)

    val health: Flow<Map<String, EnvironmentHealth>> = mutableHealth

    suspend fun initialize() {
        val (nativeWorkingDirectory, nativeEnvironmentVariablesJson) = loadNativeConfiguration()
        val nativeEnvironmentVariables = parseEnvironmentVariables(nativeEnvironmentVariablesJson)
        if (nativeWorkingDirectory != null || nativeEnvironmentVariables.isNotEmpty()) {
            val workingDirectory = File(nativeWorkingDirectory ?: nativeSnapshot.workingDirectory).apply { mkdirs() }
            nativeSnapshot = nativeSnapshot.copy(
                workingDirectory = workingDirectory.absolutePath,
                bridgeLocation = null,
                environmentVariables = nativeEnvironmentVariables,
            )
            mutableNativeSnapshot.value = nativeSnapshot
        }
    }

    fun observeEnvironments(): Flow<List<EnvironmentSnapshot>> =
        kotlinx.coroutines.flow.combine(
            mutableNativeSnapshot,
            WeAgentRepository.observeLinuxEnvironments(),
        ) { native, stored -> listOf(native) + stored.map(LinuxEnvironmentEntity::toSnapshot) }

    suspend fun updateNativeConfiguration(workingDirectory: String, environmentVariablesJson: String) {
        val directory = File(workingDirectory).absoluteFile
        require(directory.isDirectory || directory.mkdirs()) { "native working directory cannot be created" }
        require(directory.canWrite()) { "native working directory is not writable" }
        val variables = parseEnvironmentVariables(environmentVariablesJson)
        WeAgentSettings.set(WeAgentSettings.KEY_NATIVE_LINUX_WORKING_DIRECTORY, directory.path)
        WeAgentSettings.set(
            WeAgentSettings.KEY_NATIVE_LINUX_ENVIRONMENT_VARIABLES,
            kotlinx.serialization.json.JsonObject(variables.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString(),
        )
        nativeSnapshot = nativeSnapshot.copy(
            workingDirectory = directory.path,
            bridgeLocation = null,
            environmentVariables = variables,
        )
        mutableNativeSnapshot.value = nativeSnapshot
        stateMutex.withLock {
            staleBackends.add(NATIVE_ENVIRONMENT_ID)
            if ((leaseCounts[NATIVE_ENVIRONMENT_ID] ?: 0) == 0) {
                backends.remove(NATIVE_ENVIRONMENT_ID)?.close()
                staleBackends.remove(NATIVE_ENVIRONMENT_ID)
            }
        }
    }

    fun observeEffectiveEnvironmentId(sessionId: String): Flow<String> =
        WeAgentRepository.observeSessionEffectiveLinuxEnvironmentId(sessionId)

    suspend fun effectiveEnvironmentId(sessionId: String): String =
        WeAgentRepository.getEffectiveLinuxEnvironmentId(sessionId)

    suspend fun upsert(environment: LinuxEnvironmentEntity) {
        val existing = getEnvironment(environment.id)
        val identityChanged = existing?.type == LinuxEnvironmentType.SSH &&
            environment.type == LinuxEnvironmentType.SSH &&
            (existing.sshHost != environment.sshHost ||
                existing.sshPort != environment.sshPort ||
                existing.sshUsername != environment.sshUsername ||
                existing.sshAuthenticationType != environment.sshAuthenticationType)
        persistEnvironment(if (identityChanged) environment.copy(
            sshHostKeyAlgorithm = null,
            sshHostKeyFingerprint = null,
        ) else environment)
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
        val environment = getEnvironment(id)
        stateMutex.withLock {
            check((leaseCounts[id] ?: 0) == 0) { "environment is currently leased" }
            check(deleting.add(id)) { "environment deletion is already in progress" }
        }
        var deleted = false
        try {
            deleted = environment != null && deleteEnvironment(id, environment.toSnapshot(), nativeSnapshot)
            return deleted
        } finally {
            withContext(NonCancellable) {
                try {
                    if (deleted) {
                        runCatching { backends.remove(id)?.close() }
                            .onFailure { WeLogger.e("LinuxEnvironmentManager", "failed to close deleted environment backend $id", it) }
                        executionMutexes.remove(id)
                        stateMutex.withLock { mutableHealth.update { it - id } }
                        runCatching { notifyEnvironmentDeleted(id) }
                            .onFailure { WeLogger.e("LinuxEnvironmentManager", "failed to refresh foreground after deleting environment $id", it) }
                    }
                } finally {
                    stateMutex.withLock { deleting.remove(id) }
                }
            }
        }
    }

    suspend fun exec(
        environmentId: String,
        command: String,
        timeoutMillis: Long,
        environmentVariables: Map<String, String> = emptyMap(),
    ): ExecResult = withLease(environmentId) { it.exec(command, timeoutMillis, environmentVariables) }

    suspend fun ensureBridge(environmentId: String): BridgeInstallArtifact? =
        withLease(environmentId) { it.ensureBridge() }.also { artifact ->
            if (artifact != null && environmentId != NATIVE_ENVIRONMENT_ID) {
                getEnvironment(environmentId)?.takeIf { it.bridgePath != artifact.executablePath }?.let {
                    upsert(it.copy(bridgePath = artifact.executablePath))
                }
            }
        }

    suspend fun edit(environmentId: String, request: FileEditRequest) =
        withLease(environmentId) { it.edit(request) }

    suspend fun checkHealth(environmentId: String): EnvironmentHealth {
        publishHealth(environmentId, EnvironmentHealth(EnvironmentHealthState.CHECKING))
        return runCatching { withLease(environmentId) { it.checkHealth() } }
            .getOrElse {
                if (it is SshHostKeyException) throw it
                EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, it.message)
            }
            .also { result -> publishHealth(environmentId, result) }
    }

    suspend fun sshConnection(environmentId: String): SshConnectionManager {
        val backend = backend(environmentId)
        require(backend is SshBackend) { "environment is not SSH" }
        return backend.connection
    }

    /** Persists a host key only after the caller has explicitly approved [observed]. */
    suspend fun confirmSshHostKey(environmentId: String, endpoint: SshEndpoint, observed: SshHostKey) {
        val environment = requireNotNull(getEnvironment(environmentId)) { "environment does not exist" }
        require(environment.type == LinuxEnvironmentType.SSH) { "environment is not SSH" }
        check(
            endpoint == SshEndpoint(
                requireNotNull(environment.sshHost),
                requireNotNull(environment.sshPort),
                requireNotNull(environment.sshUsername),
            )
        ) { "SSH endpoint changed; test the connection again before trusting its host key" }
        SshConfiguration(
            endpoint.host,
            endpoint.port,
            endpoint.username,
            observed,
        )
        persistEnvironment(environment.copy(
            sshHostKeyAlgorithm = observed.algorithm,
            sshHostKeyFingerprint = observed.fingerprint,
        ))
        stateMutex.withLock {
            staleBackends.add(environmentId)
            if ((leaseCounts[environmentId] ?: 0) == 0) {
                backends.remove(environmentId)?.close()
                staleBackends.remove(environmentId)
            }
        }
    }

    private suspend fun publishHealth(environmentId: String, value: EnvironmentHealth) {
        stateMutex.withLock {
            if (environmentId == NATIVE_ENVIRONMENT_ID ||
                getEnvironment(environmentId) != null
            ) {
                mutableHealth.update { it + (environmentId to value) }
            }
        }
    }

    suspend fun snapshot(environmentId: String): EnvironmentSnapshot =
        if (environmentId == NATIVE_ENVIRONMENT_ID) nativeSnapshot
        else requireNotNull(getEnvironment(environmentId)).toSnapshot()

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
            withContext(NonCancellable) {
                when (val result = releaseLease(environmentId)) {
                    LeaseReleaseResult.Committed -> Unit
                    is LeaseReleaseResult.CommittedWithCloseFailure -> throw result.error
                }
            }
        }
    }

    suspend fun acquirePersistentLease(environmentId: String): EnvironmentLease {
        stateMutex.withLock {
            check(environmentId !in deleting) { "environment is being deleted" }
            if (environmentId != NATIVE_ENVIRONMENT_ID) {
                requireNotNull(getEnvironment(environmentId)) { "environment $environmentId does not exist" }
            }
            leaseCounts[environmentId] = (leaseCounts[environmentId] ?: 0) + 1
        }
        return EnvironmentLease {
            releaseLease(environmentId)
        }
    }

    private suspend fun releaseLease(environmentId: String): LeaseReleaseResult {
        var staleBackend: LinuxEnvironmentBackend? = null
        stateMutex.withLock {
            val remaining = leaseCounts.getValue(environmentId) - 1
            if (remaining == 0) leaseCounts.remove(environmentId) else leaseCounts[environmentId] = remaining
            if (remaining == 0 && staleBackends.remove(environmentId)) {
                staleBackend = backends.remove(environmentId)
            }
        }
        return try {
            staleBackend?.close()
            LeaseReleaseResult.Committed
        } catch (error: Throwable) {
            WeLogger.e("LinuxEnvironmentManager", "lease released but stale backend close failed for $environmentId", error)
            LeaseReleaseResult.CommittedWithCloseFailure(error)
        }
    }

    private suspend fun backend(environmentId: String): LinuxEnvironmentBackend {
        backends[environmentId]?.let { return it }
        val entity = if (environmentId == NATIVE_ENVIRONMENT_ID) null else getEnvironment(environmentId)
            ?: error("environment $environmentId does not exist")
        val snapshot = entity?.toSnapshot() ?: nativeSnapshot
        val created = backendFactory?.invoke(snapshot) ?: when (snapshot.type) {
            LinuxEnvironmentType.NATIVE -> NativeBackend(snapshot)
            LinuxEnvironmentType.SSH -> {
                val stored = requireNotNull(entity)
                val credentials = when {
                    stored.sshPassword != null -> SshCredentials.Password(stored.sshPassword)
                    stored.sshPrivateKey != null -> SshCredentials.PrivateKey(
                        stored.sshPrivateKey,
                        stored.sshPrivateKeyPassphrase,
                    )
                    else -> SshCredentialStore.decrypt(
                        EncryptedSshCredentials(
                            requireNotNull(stored.sshCredentialCiphertext) { "SSH credentials are missing" },
                            requireNotNull(stored.sshCredentialIv) { "SSH credential IV is missing" },
                        ),
                    )
                }
                val confirmed = stored.sshHostKeyFingerprint?.let { fingerprint ->
                    SshHostKey(requireNotNull(stored.sshHostKeyAlgorithm), fingerprint)
                }
                SshBackend(
                    snapshot,
                    SshConnectionManager(
                        SshConfiguration(
                            requireNotNull(stored.sshHost),
                            requireNotNull(stored.sshPort),
                            requireNotNull(stored.sshUsername),
                            confirmed,
                        ),
                        credentials,
                    ),
                )
            }
        }
        val existing = backends.putIfAbsent(environmentId, created)
        if (existing != null) {
            created.close()
            return existing
        }
        return created
    }

    companion object {
        fun parseEnvironmentVariables(json: String): Map<String, String> =
            kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.mapValues { (key, value) ->
                require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid environment variable name: $key" }
                require(value.jsonPrimitive.isString) { "environment variable $key must be a string" }
                value.jsonPrimitive.content
            }

        private fun defaultNativeSnapshot(): EnvironmentSnapshot {
            val workingDirectory = KnownPaths.moduleRoot.resolve("agent/environment/native").toFile()
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

sealed interface LeaseReleaseResult {
    data object Committed : LeaseReleaseResult
    data class CommittedWithCloseFailure(val error: Throwable) : LeaseReleaseResult
}

class EnvironmentLease constructor(private val releaseBlock: suspend () -> LeaseReleaseResult) {
    private enum class State { ACTIVE, RELEASING, RELEASED }
    private val state = java.util.concurrent.atomic.AtomicReference(State.ACTIVE)

    suspend fun release() {
        if (!state.compareAndSet(State.ACTIVE, State.RELEASING)) return
        withContext(NonCancellable) {
            val result = try {
                releaseBlock()
            } catch (error: Throwable) {
                state.set(State.ACTIVE)
                throw error
            }
            state.set(State.RELEASED)
            if (result is LeaseReleaseResult.CommittedWithCloseFailure) {
                throw result.error
            }
        }
    }
}
