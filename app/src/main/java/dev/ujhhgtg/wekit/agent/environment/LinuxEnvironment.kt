package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class LinuxEnvironmentType {
    NATIVE,
    SSH,
}

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
    val environmentVariables: Map<String, String> = emptyMap(),
)

data class LinuxEnvironmentSessionState(
    val sessionId: String,
    val environmentId: String?,
    val lastEffectiveEnvironmentId: String?,
)

data class LinuxEnvironmentSessionTransition(
    val sessionId: String,
    val environmentId: String?,
    val previousEnvironment: EnvironmentSnapshot,
    val environment: EnvironmentSnapshot,
)

data class LinuxEnvironmentDeletionPlan(
    val defaultEnvironmentId: String,
    val transitions: List<LinuxEnvironmentSessionTransition>,
)

enum class EnvironmentHealthState {
    UNKNOWN,
    CHECKING,
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
}

data class EnvironmentHealth(
    val state: EnvironmentHealthState,
    val detail: String? = null,
)

const val NATIVE_ENVIRONMENT_ID = "native"

fun LinuxEnvironmentEntity.toSnapshot(): EnvironmentSnapshot =
    EnvironmentSnapshot(
        id = id,
        displayName = name,
        type = type,
        operatingSystem = "Remote Linux",
        architecture = "Remote host architecture",
        shell = "/bin/bash",
        workingDirectory = workingDirectory,
        bridgeLocation = bridgePath,
        privilegesAndCapabilities =
            when (type) {
                LinuxEnvironmentType.NATIVE -> error("native environment is not stored in Room")
                LinuxEnvironmentType.SSH -> "Remote account privileges and server capabilities"
            },
        environmentVariables =
            Json.parseToJsonElement(environmentVariablesJson).jsonObject.mapValues {
                it.value.jsonPrimitive.content
            },
    )
