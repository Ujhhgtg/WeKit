package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.ChrootConfiguration
import dev.ujhhgtg.wekit.agent.environment.ChrootMountRegistry
import dev.ujhhgtg.wekit.agent.environment.ChrootRootHelper
import dev.ujhhgtg.wekit.agent.environment.ArchLinuxInstanceLayout
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.ProotCommand
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EnvironmentTerminalBackend internal constructor(
    private val native: TerminalBackend = NativeTerminalBackend(),
    private val approveChrootStart: suspend (EnvironmentSnapshot) -> Boolean = { false },
    private val chrootInstancesRoot: Path = ArchLinuxInstanceLayout.canonicalInstancesRoot(),
    private val resolveRootLauncher: suspend (ChrootRootHelper) -> Path = { helper ->
        check(helper.hasRoot()) { "root access denied" }
        helper.resolveSuExecutable()
    },
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
            val rootfs = ArchLinuxInstanceLayout.validatePublishedRootfs(
                Path.of(requireNotNull(environment.rootfsPath)), chrootInstancesRoot,
            )
            val configuration = ChrootConfiguration(rootfs, workingDirectory ?: environment.workingDirectory)
            val helper = ChrootRootHelper(configuration)
            val hostArgv = configuration.hostLaunchArgv(resolveRootLauncher(helper), argv, environmentVariables)
            val hostEnvironment = environment.copy(
                type = LinuxEnvironmentType.NATIVE,
                workingDirectory = rootfs.parent.toString(),
                shell = hostArgv.first(),
            )
            ChrootMountRegistry.begin(rootfs)
            try {
                val started = native.start(hostEnvironment, hostArgv, hostEnvironment.workingDirectory, emptyMap(), cols, rows)
                TerminalBackendStart(ChrootTerminalSession(started.session, rootfs, helper), environment)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    helper.cleanupNamespace()
                    ChrootMountRegistry.end(rootfs)
                }
                throw error
            }
        }
        else -> error("${environment.type} terminal backend is not implemented")
    }

    private class ChrootTerminalSession(
        private val delegate: TerminalBackendSession,
        private val rootfs: Path,
        private val helper: ChrootRootHelper,
    ) : TerminalBackendSession by delegate {
        private val delegateClosed = AtomicBoolean()
        private val cleanupMutex = Mutex()
        private var cleaned = false
        override suspend fun kill() {
            try { delegate.kill() } finally { cleanup() }
        }
        override suspend fun close() {
            if (!delegateClosed.compareAndSet(false, true)) return
            withContext(NonCancellable) {
                var failure: Throwable? = null
                try { delegate.close() } catch (error: Throwable) { failure = error }
                try { cleanup() } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: throw error
                }
                failure?.let { throw it }
            }
        }

        private suspend fun cleanup() = cleanupMutex.withLock {
            if (cleaned) return@withLock
            helper.cleanupNamespace()
            ChrootMountRegistry.end(rootfs)
            cleaned = true
        }
    }
}
