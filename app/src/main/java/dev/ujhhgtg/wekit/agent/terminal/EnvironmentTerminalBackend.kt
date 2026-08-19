package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.ProotCommand
import java.nio.file.Path

class EnvironmentTerminalBackend(
    private val native: NativeTerminalBackend = NativeTerminalBackend(),
) : TerminalBackend {
    override suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart = when (environment.type) {
        LinuxEnvironmentType.NATIVE -> native.start(environment, argv, workingDirectory, environmentVariables, cols, rows)
        LinuxEnvironmentType.PROOT -> {
            val rootfs = Path.of(requireNotNull(environment.rootfsPath))
            val hostArgv = ProotCommand.launchArgv(
                rootfs.parent.resolve("bin/proot"), rootfs, workingDirectory ?: environment.workingDirectory,
                argv, environmentVariables,
            )
            val hostEnvironment = environment.copy(
                type = LinuxEnvironmentType.NATIVE,
                workingDirectory = rootfs.parent.toString(),
                shell = hostArgv.first(),
            )
            val hostProcessEnvironment = mapOf(
                "PROOT_LOADER" to rootfs.parent.resolve("bin/loader").toString(),
                "PROOT_TMP_DIR" to rootfs.parent.resolve("tmp").toFile().apply { mkdirs() }.absolutePath,
            )
            val started = native.start(hostEnvironment, hostArgv, hostEnvironment.workingDirectory, hostProcessEnvironment, cols, rows)
            TerminalBackendStart(started.session, environment)
        }
        else -> error("${environment.type} terminal backend is not implemented")
    }
}
