package dev.ujhhgtg.wekit.agent.terminal

import kotlin.io.path.writeText
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.ChrootConfiguration
import dev.ujhhgtg.wekit.agent.environment.ChrootMountRegistry
import dev.ujhhgtg.wekit.agent.environment.ChrootRootHelper
import dev.ujhhgtg.wekit.agent.environment.ChrootRun
import dev.ujhhgtg.wekit.agent.environment.ArchLinuxInstanceLayout
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.EnvironmentLease
import dev.ujhhgtg.wekit.agent.environment.ProotCommand
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EnvironmentTerminalBackend internal constructor(
    private val native: TerminalBackend = NativeTerminalBackend(),
    private val ssh: TerminalBackend? = null,
    private val approveChrootStart: suspend (EnvironmentSnapshot) -> Boolean = { false },
    private val chrootInstancesRoot: Path = ArchLinuxInstanceLayout.canonicalInstancesRoot(),
    private val resolveProotLauncher: () -> Path = { NativeLoader.prootExecutable().toPath() },
    private val resolveProotLoader: () -> Path = { NativeLoader.prootLoaderExecutable().toPath() },
    private val resolveRootLauncher: suspend (ChrootRootHelper) -> Path = { helper ->
        check(helper.hasRoot()) { "root access denied" }
        helper.resolveSuExecutable()
    },
    private val cleanupChrootRun: suspend (ChrootRootHelper, ChrootRun) -> Unit = { helper, run ->
        helper.cleanupNamespace(run)
    },
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
        LinuxEnvironmentType.CHROOT -> {
            check(approveChrootStart(environment)) { "rooted chroot terminal start requires explicit high-risk approval" }
            val rootfs = ArchLinuxInstanceLayout.validatePublishedRootfs(
                requireNotNull(environment.rootfsPath).asPath, chrootInstancesRoot,
            )
            val configuration = ChrootConfiguration(rootfs, workingDirectory ?: environment.workingDirectory)
            val helper = ChrootRootHelper(configuration)
            helper.ensureReadyForLaunch()
            val launcher = resolveRootLauncher(helper)
            val hostEnvironment = environment.copy(
                type = LinuxEnvironmentType.NATIVE,
                workingDirectory = rootfs.parent.toString(),
                shell = launcher.toString(),
            )
            val nonce = java.util.UUID.randomUUID().toString()
            try {
                ChrootMountRegistry.begin(rootfs, nonce)
            } catch (error: Throwable) {
                throw error
            }
            val run = try { configuration.createRun(nonce) } catch (error: Throwable) {
                ChrootMountRegistry.end(rootfs, nonce)
                throw error
            }
            val hostArgv = configuration.hostLaunchArgv(run, launcher, argv, environmentVariables)
            try {
                run.stageFile.writeText("LAUNCHING", Charsets.UTF_8, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
                val started = native.start(hostEnvironment, hostArgv, hostEnvironment.workingDirectory, emptyMap(), cols, rows)
                TerminalBackendStart(ChrootTerminalSession(started.session, rootfs, helper, run, cleanupChrootRun), environment)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try {
                        cleanupChrootRun(helper, run)
                        ChrootMountRegistry.end(rootfs, run.nonce)
                    } catch (cleanupError: Throwable) {
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }
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

    private class ChrootTerminalSession(
        private val delegate: TerminalBackendSession,
        private val rootfs: Path,
        private val helper: ChrootRootHelper,
        private val run: ChrootRun,
        private val cleanupChrootRun: suspend (ChrootRootHelper, ChrootRun) -> Unit,
    ) : TerminalBackendSession by delegate {
        private val delegateClosed = AtomicBoolean()
        private val cleanupMutex = Mutex()
        private var cleaned = false
        override suspend fun kill() {
            try { delegate.kill() } finally { cleanup() }
        }
        override suspend fun close() {
            withContext(NonCancellable) {
                var failure: Throwable? = null
                if (delegateClosed.compareAndSet(false, true)) {
                    try { delegate.close() } catch (error: Throwable) { failure = error }
                }
                try { cleanup() } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: throw error
                }
                failure?.let { throw it }
            }
        }

        private suspend fun cleanup() = cleanupMutex.withLock {
            if (cleaned) return@withLock
            cleanupChrootRun(helper, run)
            ChrootMountRegistry.end(rootfs, run.nonce)
            cleaned = true
        }
    }
}
