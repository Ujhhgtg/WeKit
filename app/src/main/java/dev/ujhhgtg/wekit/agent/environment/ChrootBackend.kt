package dev.ujhhgtg.wekit.agent.environment

import java.nio.file.Files
import java.nio.file.Path

data class ChrootBind(val host: Path, val guest: String)

class ChrootConfiguration(
    val rootfs: Path,
    val workingDirectory: String,
    val binds: List<ChrootBind> = emptyList(),
) {
    val instance: Path = rootfs.parent
    val pidFile: Path = instance.resolve("chroot.pid")
    val stageFile: Path = instance.resolve("chroot.stage")

    init {
        require(rootfs.isAbsolute && rootfs.normalize() == rootfs) { "chroot rootfs must be an absolute normalized path" }
        validateGuestPath(workingDirectory)
        binds.forEach { bind ->
            val host = bind.host.toAbsolutePath().normalize()
            require(bind.host.isAbsolute && host == bind.host.normalize()) { "bind host must be an absolute normalized path" }
            require(bind.guest.startsWith("/storage/") && APPROVED_STORAGE_ROOTS.any(host::startsWith)) {
                "chroot binds must use approved Android shared-storage paths"
            }
            validateGuestPath(bind.guest)
        }
    }

    fun execScript(command: String, environment: Map<String, String>): String =
        launchScript(listOf("/bin/bash", "-lc", command), environment)

    fun launchScript(argv: List<String>, environment: Map<String, String>): String {
        require(argv.isNotEmpty() && argv.none(String::isEmpty)) { "chroot argv cannot be empty" }
        val mounts = mountCommands()
        val cleanup = mounts.indices.reversed().joinToString("\n") { index ->
            "if [ \"\$mounted_$index\" -eq 1 ]; then umount -l ${shell(rootfs.resolveGuest(mounts[index].guest).toString())} || cleanup_failed=1; fi"
        }
        val prepareMounts = mounts.mapIndexed { index, mount ->
            "mkdir -p ${shell(rootfs.resolveGuest(mount.guest).toString())} || exit 71\n" +
                mount.command(rootfs) + " || exit 71\nmounted_$index=1"
        }.joinToString("\n")
        val guestEnvironment = buildList {
            add("HOME=/root"); add("USER=root"); add("LOGNAME=root"); add("SHELL=/bin/bash")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin")
            environment.filterKeys(ENVIRONMENT_NAME::matches).filterKeys { it != "PATH" }
                .forEach { (key, value) -> add("$key=$value") }
        }
        val command = listOf(
            "chroot", rootfs.toString(), "/usr/bin/env", "-i",
            *guestEnvironment.toTypedArray(), "/bin/sh", "-c",
            "cd \"\$1\" && shift && exec \"\$@\"", "wekit-chroot", workingDirectory,
            *argv.toTypedArray(),
        ).joinToString(" ", transform = ::shell)
        return """
            set -u
            printf '%s' NAMESPACE > ${shell(stageFile.toString())}
            echo $$ > ${shell(pidFile.toString())}
            mount --make-rprivate / || exit 70
            cleanup_failed=0
            ${mounts.indices.joinToString("\n") { "mounted_$it=0" }}
            cleanup() {
              trap - EXIT HUP INT TERM
            $cleanup
              rm -f ${shell(pidFile.toString())}
              if [ "${'$'}cleanup_failed" -ne 0 ]; then
                printf '%s' CLEANUP > ${shell(stageFile.toString())}
                exit 74
              fi
            }
            trap cleanup EXIT HUP INT TERM
            printf '%s' MOUNT > ${shell(stageFile.toString())}
            $prepareMounts
            test -r ${shell(rootfs.resolve("etc/resolv.conf").toString())} || exit 71
            printf '%s' EXEC > ${shell(stageFile.toString())}
            $command
        """.trimIndent()
    }

    internal fun hostLaunchArgv(argv: List<String>, environment: Map<String, String>): List<String> =
        listOf("su", "-c", "exec setsid unshare -m -- /system/bin/sh -c ${shell(launchScript(argv, environment))}")

    fun mountArguments(): List<List<String>> = mountCommands().map(Mount::arguments)

    private fun mountCommands(): List<Mount> = buildList {
        add(Mount("/proc", listOf("mount", "-t", "proc", "proc")))
        add(Mount("/sys", listOf("mount", "-t", "sysfs", "sysfs")))
        add(Mount("/dev", listOf("mount", "--rbind", "/dev"), makeSlave = true))
        binds.forEach { add(Mount(it.guest, listOf("mount", "--bind", it.host.toString()))) }
    }

    private fun validateGuestPath(path: String) {
        val normalized = Path.of(path).normalize()
        require(path.startsWith('/') && normalized.toString() == path && !normalized.startsWith("/..")) {
            "chroot guest path must be absolute and normalized"
        }
    }

    private data class Mount(val guest: String, val prefix: List<String>, val makeSlave: Boolean = false) {
        fun arguments(): List<String> = prefix + guest
        fun command(rootfs: Path): String {
            val target = rootfs.resolveGuest(guest).toString()
            val mount = (prefix + target).joinToString(" ", transform = ::shell)
            return if (makeSlave) "$mount && mount --make-rslave ${shell(target)}" else mount
        }
    }

    companion object {
        const val CAPABILITIES = "HIGH RISK: device root and approved host-filesystem binds; shares the Android kernel and is not a sandbox"
        private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val APPROVED_STORAGE_ROOTS = listOf(
            Path.of("/storage/emulated"), Path.of("/storage/self/primary"), Path.of("/sdcard"),
        )

        internal fun shell(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}

internal object ChrootMountRegistry {
    private val active = HashMap<String, Int>()
    private val deleting = HashSet<String>()
    @Synchronized fun begin(rootfs: Path) {
        val key = rootfs.toString()
        check(key !in deleting) { "chroot environment is being deleted" }
        active[key] = (active[key] ?: 0) + 1
    }
    @Synchronized fun end(rootfs: Path) {
        val key = rootfs.toString()
        val count = active.getValue(key)
        if (count == 1) active.remove(key) else active[key] = count - 1
    }
    @Synchronized fun beginDeletion(rootfs: Path) {
        val key = rootfs.toString()
        check(key !in active) { "chroot environment is mounted or running" }
        check(deleting.add(key)) { "chroot environment deletion is already in progress" }
    }
    @Synchronized fun endDeletion(rootfs: Path) { deleting.remove(rootfs.toString()) }
}

class ChrootBackend internal constructor(
    override val snapshot: EnvironmentSnapshot,
    private val configuration: ChrootConfiguration = ChrootConfiguration(
        Path.of(requireNotNull(snapshot.rootfsPath)), snapshot.workingDirectory,
    ),
    private val approveStart: suspend () -> Boolean = { false },
    private val rootHelper: ChrootRootHelper = ChrootRootHelper(configuration),
) : LinuxEnvironmentBackend {
    private val files = ProotBackend(snapshot.copy(type = LinuxEnvironmentType.PROOT))

    init { require(snapshot.type == LinuxEnvironmentType.CHROOT) }

    override suspend fun exec(command: String, timeoutMillis: Long, environmentVariables: Map<String, String>): ExecResult {
        check(approveStart()) { "rooted chroot start requires explicit high-risk approval" }
        return rootHelper.exec(command, timeoutMillis, environmentVariables)
    }

    override suspend fun readUtf8(path: String, maxBytes: Long): String = files.readUtf8(path, maxBytes)
    override suspend fun edit(request: FileEditRequest) = files.edit(request)
    override fun resolvePath(path: String): String = files.resolvePath(path)

    override suspend fun ensureBridge(): BridgeInstallArtifact {
        val bridge = configuration.rootfs.resolve("usr/bin/invoke_tool")
        require(Files.isRegularFile(bridge) && Files.isExecutable(bridge)) { "invoke_tool is missing from chroot instance" }
        return BridgeInstallArtifact("/usr/bin/invoke_tool", "/usr/bin")
    }

    override suspend fun checkHealth(): EnvironmentHealth {
        if (!Files.isRegularFile(configuration.rootfs.resolve("bin/bash"))) {
            return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "Arch rootfs is corrupt")
        }
        if (!rootHelper.hasRoot()) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "root access denied")
        if (!approveStart()) return EnvironmentHealth(EnvironmentHealthState.DEGRADED, "high-risk chroot start approval required")
        return try {
            val result = rootHelper.exec("test -x /usr/bin/invoke_tool && test -w /root", 15_000, emptyMap())
            if (result.exitCode == 0) EnvironmentHealth(EnvironmentHealthState.HEALTHY)
            else EnvironmentHealth(EnvironmentHealthState.DEGRADED, result.stderr.ifBlank { "chroot health command failed" })
        } catch (error: ChrootFailure) {
            EnvironmentHealth(EnvironmentHealthState.DEGRADED, error.message)
        }
    }
}

private fun Path.resolveGuest(guest: String): Path = resolve(guest.removePrefix("/"))
