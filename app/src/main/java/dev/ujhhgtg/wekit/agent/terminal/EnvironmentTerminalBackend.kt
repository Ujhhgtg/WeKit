package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.ChrootConfiguration
import dev.ujhhgtg.wekit.agent.environment.ChrootMountRegistry
import dev.ujhhgtg.wekit.agent.environment.ChrootRootHelper
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.ProotCommand
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class EnvironmentTerminalBackend(
    private val native: NativeTerminalBackend = NativeTerminalBackend(),
    private val approveChrootStart: suspend (EnvironmentSnapshot) -> Boolean = { false },
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
        LinuxEnvironmentType.CHROOT -> {
            check(approveChrootStart(environment)) { "rooted chroot terminal start requires explicit high-risk approval" }
            val rootfs = Path.of(requireNotNull(environment.rootfsPath))
            val configuration = ChrootConfiguration(rootfs, workingDirectory ?: environment.workingDirectory)
            check(ChrootRootHelper(configuration).hasRoot()) { "root access denied" }
            val hostArgv = configuration.hostLaunchArgv(argv, environmentVariables)
            val hostEnvironment = environment.copy(
                type = LinuxEnvironmentType.NATIVE,
                workingDirectory = rootfs.parent.toString(),
                shell = hostArgv.first(),
            )
            ChrootMountRegistry.begin(rootfs)
            try {
                val started = native.start(hostEnvironment, hostArgv, hostEnvironment.workingDirectory, emptyMap(), cols, rows)
                TerminalBackendStart(ChrootTerminalSession(started.session, rootfs), environment)
            } catch (error: Throwable) {
                ChrootMountRegistry.end(rootfs)
                throw error
            }
        }
        else -> error("${environment.type} terminal backend is not implemented")
    }

    private class ChrootTerminalSession(
        private val delegate: TerminalBackendSession,
        private val rootfs: Path,
    ) : TerminalBackendSession by delegate {
        private val closed = AtomicBoolean()
        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { delegate.close() } finally {
                ChrootMountRegistry.end(rootfs)
            }
        }
    }
}
