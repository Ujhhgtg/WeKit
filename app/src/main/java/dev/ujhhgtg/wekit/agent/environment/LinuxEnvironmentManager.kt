package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.agent.ssh.EncryptedSshCredentials
import dev.ujhhgtg.wekit.agent.ssh.SshCredentialStore
import dev.ujhhgtg.wekit.agent.ssh.SshEndpoint
import dev.ujhhgtg.wekit.agent.ssh.SshHostKey
import dev.ujhhgtg.wekit.agent.ssh.SshHostKeyException
import dev.ujhhgtg.wekit.extensions.ArchLinuxPack
import dev.ujhhgtg.wekit.extensions.ExtensionPack
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
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
    private val prootPackAvailable: () -> Boolean = { ArchLinuxPack.installedManifest() != null },
    private val installProot: suspend (String) -> ArchLinuxInstance = ArchLinuxPack::createInstance,
    private val persistEnvironment: suspend (LinuxEnvironmentEntity) -> Unit = WeAgentRepository::upsertLinuxEnvironment,
    private val highRiskApproval: suspend (String, EnvironmentSnapshot?) -> Boolean = { _, _ -> false },
    private val storedEnvironments: suspend () -> List<LinuxEnvironmentEntity> = WeAgentRepository::getAllLinuxEnvironments,
    private val getEnvironment: suspend (String) -> LinuxEnvironmentEntity? = WeAgentRepository::getLinuxEnvironment,
    private val deleteEnvironment: suspend (String, EnvironmentSnapshot, EnvironmentSnapshot) -> Boolean =
        WeAgentRepository::deleteLinuxEnvironment,
    private val notifyEnvironmentDeleted: suspend (String) -> Unit = { id ->
        dev.ujhhgtg.wekit.features.api.agent.WeAgentService.onLinuxEnvironmentDeleted(id)
    },
    private val recoverChroot: suspend (Path, String) -> ChrootRecoveryResult = { rootfs, workingDirectory ->
        ChrootRootHelper(ChrootConfiguration(rootfs, workingDirectory)).recoverPendingRuns()
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
                bridgeLocation = workingDirectory.resolve(".weagent/bin/invoke_tool").absolutePath,
                environmentVariables = nativeEnvironmentVariables,
            )
            mutableNativeSnapshot.value = nativeSnapshot
        }
        storedEnvironments().filter { it.type == LinuxEnvironmentType.CHROOT }.forEach { environment ->
            val result = runCatching {
                recoverChroot(Path.of(requireNotNull(environment.rootfsPath)), environment.workingDirectory)
            }.getOrElse { error -> ChrootRecoveryResult(0, mapOf("recovery" to (error.message ?: error::class.java.simpleName))) }
            result.healthError?.let {
                publishHealth(environment.id, EnvironmentHealth(EnvironmentHealthState.DEGRADED, it))
            }
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
            bridgeLocation = directory.resolve(".weagent/bin/invoke_tool").path,
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

    suspend fun createProotEnvironment(
        name: String,
        instanceId: String = UUID.randomUUID().toString(),
        workingDirectory: String = "/root",
        environmentVariablesJson: String = "{}",
    ): ProotEnvironmentCreationResult {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty() && trimmedName.length <= 80) { "environment name must be 1-80 characters" }
        require(instanceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid instance id" }
        validateGuestWorkingDirectory(workingDirectory)
        parseEnvironmentVariables(environmentVariablesJson)
        if (!prootPackAvailable()) return ProotEnvironmentCreationResult.MissingPack(ArchLinuxPack)

        val instance = installProot(instanceId)
        val entity = LinuxEnvironmentEntity(
            id = instanceId,
            name = trimmedName,
            type = LinuxEnvironmentType.PROOT,
            workingDirectory = workingDirectory,
            environmentVariablesJson = environmentVariablesJson,
            rootfsPath = instance.rootfs.absolutePath,
            rootfsContentVersion = instance.contentVersion,
            createdAt = System.currentTimeMillis(),
            bridgePath = instance.bridgePath,
        )
        try {
            persistEnvironment(entity)
        } catch (error: Throwable) {
            instance.rootfs.parentFile?.deleteRecursively()
            throw error
        }
        return ProotEnvironmentCreationResult.Created(entity)
    }

    suspend fun createChrootEnvironment(
        name: String,
        instanceId: String = UUID.randomUUID().toString(),
        workingDirectory: String = "/root",
        environmentVariablesJson: String = "{}",
        highRiskApproved: Boolean = false,
    ): ChrootEnvironmentCreationResult {
        check(highRiskApproved || highRiskApproval("create rooted chroot environment", null)) {
            "rooted chroot creation requires explicit high-risk approval"
        }
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty() && trimmedName.length <= 80) { "environment name must be 1-80 characters" }
        require(instanceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid instance id" }
        validateGuestWorkingDirectory(workingDirectory)
        parseEnvironmentVariables(environmentVariablesJson)
        if (!prootPackAvailable()) return ChrootEnvironmentCreationResult.MissingPack(ArchLinuxPack)

        val instance = installProot(instanceId)
        val rootfs = ArchLinuxInstanceLayout.validatePublishedRootfs(instance.rootfs.toPath())
        val helper = ChrootRootHelper(ChrootConfiguration(rootfs, workingDirectory))
        val entity = LinuxEnvironmentEntity(
            id = instanceId,
            name = trimmedName,
            type = LinuxEnvironmentType.CHROOT,
            workingDirectory = workingDirectory,
            environmentVariablesJson = environmentVariablesJson,
            rootfsPath = instance.rootfs.absolutePath,
            rootfsContentVersion = instance.contentVersion,
            createdAt = System.currentTimeMillis(),
            bridgePath = instance.bridgePath,
        )
        try {
            check(helper.hasRoot()) { "root access denied" }
            helper.prepareInstance()
            persistEnvironment(entity)
        } catch (error: Throwable) {
            runCatching { helper.removeInstance() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        return ChrootEnvironmentCreationResult.Created(entity)
    }

    suspend fun delete(id: String): Boolean {
        require(id != NATIVE_ENVIRONMENT_ID) { "native environment cannot be deleted" }
        val environment = getEnvironment(id)
        val chrootRootfs = environment?.takeIf { it.type == LinuxEnvironmentType.CHROOT }?.rootfsPath?.let(Path::of)
        stateMutex.withLock {
            check((leaseCounts[id] ?: 0) == 0) { "environment is currently leased" }
            check(deleting.add(id)) { "environment deletion is already in progress" }
        }
        var registryLocked = false
        var deleted = false
        var quarantinedInstance: Path? = null
        var originalInstance: Path? = null
        try {
            if (chrootRootfs != null) {
                check(!ChrootMountRegistry.hasActiveRuns(chrootRootfs)) { "chroot environment has an active run" }
                val recovery = recoverChroot(chrootRootfs, environment.workingDirectory)
                check(recovery.isHealthy) { recovery.healthError!! }
                ChrootMountRegistry.beginDeletion(chrootRootfs)
                registryLocked = true
            }
            if (environment != null && environment.type != LinuxEnvironmentType.SSH) {
                val rootfs = Path.of(requireNotNull(environment.rootfsPath))
                val instance = rootfs.parent
                check(instance.fileName.toString() == id) { "invalid local environment layout" }
                if (Files.exists(instance)) {
                    val quarantine = instance.resolveSibling(".${instance.fileName}.deleting-${UUID.randomUUID()}")
                    Files.move(instance, quarantine, StandardCopyOption.ATOMIC_MOVE)
                    originalInstance = instance
                    quarantinedInstance = quarantine
                }
            }
            try {
                deleted = environment != null && deleteEnvironment(id, environment.toSnapshot(), nativeSnapshot)
                if (!deleted && quarantinedInstance != null) {
                    Files.move(quarantinedInstance, originalInstance, StandardCopyOption.ATOMIC_MOVE)
                    quarantinedInstance = null
                }
            } catch (error: Throwable) {
                quarantinedInstance?.let { quarantine ->
                    runCatching { Files.move(quarantine, originalInstance, StandardCopyOption.ATOMIC_MOVE) }
                        .exceptionOrNull()?.let(error::addSuppressed)
                }
                quarantinedInstance = null
                throw error
            }
            quarantinedInstance?.let { quarantine ->
                check(quarantine.toFile().deleteRecursively()) {
                    "environment was deleted but quarantined files could not be removed: $quarantine"
                }
                quarantinedInstance = null
            }
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
                    if (registryLocked) ChrootMountRegistry.endDeletion(chrootRootfs!!)
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
    ): ExecResult {
        requireChrootStartApproval(environmentId)
        ensureChrootReady(environmentId)
        return withLease(environmentId) { it.exec(command, timeoutMillis, environmentVariables) }
    }

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

    suspend fun checkHealth(environmentId: String, highRiskApproved: Boolean = false): EnvironmentHealth {
        if (isChroot(environmentId) && !highRiskApproved && !highRiskApproval("check rooted chroot health", snapshot(environmentId))) {
            return EnvironmentHealth(EnvironmentHealthState.DEGRADED, "high-risk chroot start approval required")
                .also { publishHealth(environmentId, it) }
        }
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

    private suspend fun requireChrootStartApproval(environmentId: String) {
        check(!isChroot(environmentId) || highRiskApproval("execute in rooted chroot", snapshot(environmentId))) {
            "rooted chroot start requires explicit high-risk approval"
        }
    }

    private suspend fun ensureChrootReady(environmentId: String) {
        if (environmentId == NATIVE_ENVIRONMENT_ID) return
        val environment = getEnvironment(environmentId)?.takeIf { it.type == LinuxEnvironmentType.CHROOT } ?: return
        val rootfs = Path.of(requireNotNull(environment.rootfsPath))
        check(!ChrootMountRegistry.hasActiveRuns(rootfs)) { "chroot environment has an active run" }
        val recovery = recoverChroot(rootfs, environment.workingDirectory)
        if (!recovery.isHealthy) {
            val health = EnvironmentHealth(EnvironmentHealthState.DEGRADED, recovery.healthError)
            publishHealth(environmentId, health)
            error(recovery.healthError!!)
        }
        check(!ChrootMountRegistry.isBusy(rootfs)) { "chroot environment has an active or unresolved run" }
    }

    private suspend fun isChroot(environmentId: String): Boolean =
        environmentId != NATIVE_ENVIRONMENT_ID &&
            getEnvironment(environmentId)?.type == LinuxEnvironmentType.CHROOT

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
            LinuxEnvironmentType.PROOT -> ProotBackend(snapshot)
            LinuxEnvironmentType.CHROOT -> ChrootBackend(snapshot)
            LinuxEnvironmentType.SSH -> {
                val stored = requireNotNull(entity)
                val encrypted = EncryptedSshCredentials(
                    requireNotNull(stored.sshCredentialCiphertext) { "SSH credentials are missing" },
                    requireNotNull(stored.sshCredentialIv) { "SSH credential IV is missing" },
                )
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
                        SshCredentialStore.decrypt(encrypted),
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
        private fun validateGuestWorkingDirectory(path: String) {
            require(path.startsWith('/')) { "local environment working directory must be absolute" }
            require(path.split('/').none { it == ".." }) { "local environment working directory cannot traverse upward" }
        }

        internal fun parseEnvironmentVariables(json: String): Map<String, String> =
            kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.mapValues { (key, value) ->
                require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid environment variable name: $key" }
                require(value.jsonPrimitive.isString) { "environment variable $key must be a string" }
                value.jsonPrimitive.content
            }

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
                bridgeLocation = workingDirectory.resolve(".weagent/bin/invoke_tool").absolutePath,
                privilegesAndCapabilities = "WeChat UID and SELinux domain; no additional root privileges",
            )
        }
    }
}

internal sealed interface LeaseReleaseResult {
    data object Committed : LeaseReleaseResult
    data class CommittedWithCloseFailure(val error: Throwable) : LeaseReleaseResult
}

class EnvironmentLease internal constructor(private val releaseBlock: suspend () -> LeaseReleaseResult) {
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

sealed interface ProotEnvironmentCreationResult {
    data class Created(val environment: LinuxEnvironmentEntity) : ProotEnvironmentCreationResult
    data class MissingPack(val pack: ExtensionPack) : ProotEnvironmentCreationResult
}

sealed interface ChrootEnvironmentCreationResult {
    data class Created(val environment: LinuxEnvironmentEntity) : ChrootEnvironmentCreationResult
    data class MissingPack(val pack: ExtensionPack) : ChrootEnvironmentCreationResult
}
