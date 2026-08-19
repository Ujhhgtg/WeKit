package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity

enum class LinuxEnvironmentType { NATIVE, PROOT, CHROOT, SSH }

data class EnvironmentSnapshot(
    val id: String,
    val displayName: String,
    val type: LinuxEnvironmentType,
    val operatingSystem: String,
    val architecture: String,
    val shell: String,
    val workingDirectory: String,
    val bridgeLocation: String?,
    val privilegesAndCapabilities: String,
    val rootfsPath: String? = null,
)

enum class EnvironmentHealthState { UNKNOWN, CHECKING, HEALTHY, DEGRADED, UNAVAILABLE }

data class EnvironmentHealth(
    val state: EnvironmentHealthState,
    val detail: String? = null,
)

const val NATIVE_ENVIRONMENT_ID = "native"

fun LinuxEnvironmentEntity.toSnapshot(): EnvironmentSnapshot = EnvironmentSnapshot(
    id = id,
    displayName = name,
    type = type,
    operatingSystem = if (type == LinuxEnvironmentType.SSH) "Remote Linux" else "Arch Linux ARM64",
    architecture = if (type == LinuxEnvironmentType.SSH) "Remote host architecture" else "arm64",
    shell = "/bin/bash",
    workingDirectory = workingDirectory,
    bridgeLocation = bridgePath,
    privilegesAndCapabilities = when (type) {
        LinuxEnvironmentType.NATIVE -> error("native environment is not stored in Room")
        LinuxEnvironmentType.PROOT -> "Rootless PRoot; shares the Android kernel and is not a sandbox"
        LinuxEnvironmentType.CHROOT -> ChrootConfiguration.CAPABILITIES
        LinuxEnvironmentType.SSH -> "Remote account privileges and server capabilities"
    },
    rootfsPath = rootfsPath,
)
