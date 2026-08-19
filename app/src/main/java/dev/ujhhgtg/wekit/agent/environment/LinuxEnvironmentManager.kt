package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.extensions.ArchLinuxPack
import dev.ujhhgtg.wekit.extensions.ExtensionPack
import dev.ujhhgtg.wekit.utils.HostInfo
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LinuxEnvironmentManager(
    val nativeSnapshot: EnvironmentSnapshot = defaultNativeSnapshot(),
    private val backendFactory: ((EnvironmentSnapshot) -> LinuxEnvironmentBackend)? = null,
    private val prootPackAvailable: () -> Boolean = { ArchLinuxPack.installedManifest() != null },
    private val installProot: suspend (String) -> ArchLinuxInstance = ArchLinuxPack::createInstance,
    private val persistEnvironment: suspend (LinuxEnvironmentEntity) -> Unit = WeAgentRepository::upsertLinuxEnvironment,
    private val highRiskApproval: suspend (String, EnvironmentSnapshot?) -> Boolean = { _, _ -> false },
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

    suspend fun createProotEnvironment(
        name: String,
        instanceId: String = UUID.randomUUID().toString(),
    ): ProotEnvironmentCreationResult {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty() && trimmedName.length <= 80) { "environment name must be 1-80 characters" }
        require(instanceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid instance id" }
        if (!prootPackAvailable()) return ProotEnvironmentCreationResult.MissingPack(ArchLinuxPack)

        val instance = installProot(instanceId)
        val entity = LinuxEnvironmentEntity(
            id = instanceId,
            name = trimmedName,
            type = LinuxEnvironmentType.PROOT,
            workingDirectory = instance.workingDirectory,
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
    ): ChrootEnvironmentCreationResult {
        check(highRiskApproval("create rooted chroot environment", null)) {
            "rooted chroot creation requires explicit high-risk approval"
        }
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty() && trimmedName.length <= 80) { "environment name must be 1-80 characters" }
        require(instanceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid instance id" }
        if (!prootPackAvailable()) return ChrootEnvironmentCreationResult.MissingPack(ArchLinuxPack)

        val instance = installProot(instanceId)
        val rootfs = ArchLinuxInstanceLayout.validatePublishedRootfs(instance.rootfs.toPath())
        val helper = ChrootRootHelper(ChrootConfiguration(rootfs, instance.workingDirectory))
        val entity = LinuxEnvironmentEntity(
            id = instanceId,
            name = trimmedName,
            type = LinuxEnvironmentType.CHROOT,
            workingDirectory = instance.workingDirectory,
            rootfsPath = instance.rootfs.absolutePath,
            rootfsContentVersion = instance.contentVersion,
            createdAt = System.currentTimeMillis(),
            bridgePath = instance.bridgePath,
        )
        try {
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
        val chrootRootfs = WeAgentRepository.getLinuxEnvironment(id)
            ?.takeIf { it.type == LinuxEnvironmentType.CHROOT }?.rootfsPath?.let(Path::of)
        chrootRootfs?.let(ChrootMountRegistry::beginDeletion)
        try {
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
        } finally {
            chrootRootfs?.let(ChrootMountRegistry::endDeletion)
        }
    }

    suspend fun exec(
        environmentId: String,
        command: String,
        timeoutMillis: Long,
        environmentVariables: Map<String, String> = emptyMap(),
    ): ExecResult {
        requireChrootStartApproval(environmentId)
        return withLease(environmentId) { it.exec(command, timeoutMillis, environmentVariables) }
    }

    suspend fun ensureBridge(environmentId: String): BridgeInstallArtifact? =
        withLease(environmentId) { it.ensureBridge() }

    suspend fun edit(environmentId: String, request: FileEditRequest) =
        withLease(environmentId) { it.edit(request) }

    suspend fun checkHealth(environmentId: String): EnvironmentHealth {
        if (isChroot(environmentId) && !highRiskApproval("check rooted chroot health", snapshot(environmentId))) {
            return EnvironmentHealth(EnvironmentHealthState.DEGRADED, "high-risk chroot start approval required")
                .also { publishHealth(environmentId, it) }
        }
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

    private suspend fun requireChrootStartApproval(environmentId: String) {
        check(!isChroot(environmentId) || highRiskApproval("execute in rooted chroot", snapshot(environmentId))) {
            "rooted chroot start requires explicit high-risk approval"
        }
    }

    private suspend fun isChroot(environmentId: String): Boolean =
        environmentId != NATIVE_ENVIRONMENT_ID &&
            WeAgentRepository.getLinuxEnvironment(environmentId)?.type == LinuxEnvironmentType.CHROOT

    private suspend fun snapshot(environmentId: String): EnvironmentSnapshot =
        requireNotNull(WeAgentRepository.getLinuxEnvironment(environmentId)).toSnapshot()

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
        val created = backendFactory?.invoke(snapshot) ?: when (snapshot.type) {
            LinuxEnvironmentType.NATIVE -> NativeBackend(snapshot)
            LinuxEnvironmentType.PROOT -> ProotBackend(snapshot)
            LinuxEnvironmentType.CHROOT -> ChrootBackend(snapshot)
            LinuxEnvironmentType.SSH -> error("SSH backend is not implemented")
        }
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

sealed interface ProotEnvironmentCreationResult {
    data class Created(val environment: LinuxEnvironmentEntity) : ProotEnvironmentCreationResult
    data class MissingPack(val pack: ExtensionPack) : ProotEnvironmentCreationResult
}

sealed interface ChrootEnvironmentCreationResult {
    data class Created(val environment: LinuxEnvironmentEntity) : ChrootEnvironmentCreationResult
    data class MissingPack(val pack: ExtensionPack) : ChrootEnvironmentCreationResult
}
