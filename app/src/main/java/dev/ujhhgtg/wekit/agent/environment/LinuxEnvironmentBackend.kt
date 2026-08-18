package dev.ujhhgtg.wekit.agent.environment

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val elapsedMillis: Long,
    val spillPath: String? = null,
)

data class FileEditRequest(
    val path: String,
    val oldString: String?,
    val newString: String,
    val replaceAll: Boolean = false,
)

interface LinuxEnvironmentBackend {
    val snapshot: EnvironmentSnapshot

    suspend fun exec(command: String, timeoutMillis: Long): ExecResult
    suspend fun readUtf8(path: String, maxBytes: Long): String
    suspend fun edit(request: FileEditRequest)
    fun resolvePath(path: String): String
    suspend fun ensureBridge(): String?
    suspend fun checkHealth(): EnvironmentHealth
    suspend fun close() = Unit
}
