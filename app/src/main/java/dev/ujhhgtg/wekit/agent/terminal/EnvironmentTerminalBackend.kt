package dev.ujhhgtg.wekit.agent.terminal

import kotlin.io.path.writeText
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.EnvironmentLease
import dev.ujhhgtg.wekit.agent.environment.ProotCommand
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class EnvironmentTerminalBackend constructor(
    private val native: TerminalBackend = NativeTerminalBackend(),
    private val ssh: TerminalBackend? = null,
    private val resolveProotLauncher: () -> Path = { NativeLoader.prootExecutable().toPath() },
    private val resolveProotLoader: () -> Path = { NativeLoader.prootLoaderExecutable().toPath() },
    private val acquireEnvironmentLease: suspend (String) -> EnvironmentLease? = { null },
) : TerminalBackend {
    override suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart {
        val lease = acquireEnvironmentLease(environment.id)
        return try {
            val started = startUnleased(environment, argv, workingDirectory, environmentVariables, cols, rows)
            if (lease == null) started else TerminalBackendStart(
                LeasedTerminalSession(started.session, lease),
                started.environment,
            )
        } catch (error: Throwable) {
            lease?.release()
            throw error
        }
    }

    private suspend fun startUnleased(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart = when (environment.type) {
        LinuxEnvironmentType.NATIVE -> native.start(environment, argv, workingDirectory, environmentVariables, cols, rows)
        LinuxEnvironmentType.PROOT -> {
            val rootfs = requireNotNull(environment.rootfsPath).asPath
            val launcher = resolveProotLauncher()
            val loader = resolveProotLoader()
            val prootTmp = rootfs.parent.resolve("tmp").toFile().apply { mkdirs() }.toPath()
            val fipsEnabled = prootTmp.resolve("fips_enabled").also { it.writeText("0\n") }
            val hostArgv = ProotCommand.launchArgv(
                launcher, rootfs, workingDirectory ?: environment.workingDirectory,
                argv, environmentVariables,
                storageBinds = listOf(ProotCommand.Bind(fipsEnabled, "/proc/sys/crypto/fips_enabled")),
            )
            val hostEnvironment = environment.copy(
                type = LinuxEnvironmentType.NATIVE,
                workingDirectory = rootfs.parent.toString(),
                shell = hostArgv.first(),
            )
            val hostProcessEnvironment = mapOf(
                "PROOT_LOADER" to loader.toString(),
                "PROOT_NO_SECCOMP" to "1",
                "PROOT_TMP_DIR" to prootTmp.toString(),
            )
            val started = native.start(hostEnvironment, hostArgv, hostEnvironment.workingDirectory, hostProcessEnvironment, cols, rows)
            TerminalBackendStart(started.session, environment)
        }
        LinuxEnvironmentType.SSH -> requireNotNull(ssh) { "SSH terminal backend is not configured" }
            .start(environment, argv, workingDirectory, environmentVariables, cols, rows)
    }

    private class LeasedTerminalSession(
        private val delegate: TerminalBackendSession,
        private val lease: EnvironmentLease,
    ) : TerminalBackendSession by delegate {
        private val closed = AtomicBoolean()
        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                delegate.close()
            } finally {
                lease.release()
            }
        }
    }
}
