package dev.ujhhgtg.wekit.agent.environment

import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getOwner
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import dev.ujhhgtg.wekit.utils.fs.asPath
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import dev.ujhhgtg.wekit.utils.HostInfo

data class ChrootBind(val host: Path, val guest: String)

class ChrootConfiguration(
    val rootfs: Path,
    val workingDirectory: String,
    val binds: List<ChrootBind> = emptyList(),
    instancesRoot: Path? = null,
) {
    val instance: Path = rootfs.parent
    val runsDirectory: Path = instance.resolve("chroot-runs")

    init {
        require(rootfs.isAbsolute && rootfs.normalize() == rootfs) { "chroot rootfs must be an absolute normalized path" }
        instancesRoot?.let { ArchLinuxInstanceLayout.validatePublishedRootfs(rootfs, it) }
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

    fun createRun(nonce: String = UUID.randomUUID().toString()): ChrootRun {
        require(nonce.matches(RUN_NONCE)) { "invalid chroot run nonce" }
        runsDirectory.createDirectories()
        val directory = runsDirectory.resolve(nonce)
        directory.createDirectory()
        try {
            directory.resolve("nonce").writeText(nonce, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)
            directory.resolve("stage").writeText("CREATED", Charsets.UTF_8, StandardOpenOption.CREATE_NEW)
            return ChrootRun(nonce, directory)
        } catch (error: Throwable) {
            directory.listDirectoryEntries().forEach(Path::deleteIfExists)
            directory.deleteIfExists()
            throw error
        }
    }

    fun pendingRuns(): List<ChrootRun> {
        if (!runsDirectory.isDirectory()) return emptyList()
        return runsDirectory.listDirectoryEntries()
            .filter { it.isDirectory() }
            .map { directory -> ChrootRun(directory.fileName.toString(), directory) }
    }

    fun execScript(run: ChrootRun, command: String, environment: Map<String, String>): String =
        launchScript(run, listOf("/bin/bash", "-lc", command), environment)

    fun launchScript(run: ChrootRun, argv: List<String>, environment: Map<String, String>): String {
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
            printf '%s' "${'$'}${'$'}" > ${shell(run.pidFile.toString())}
            sed 's/.*) //' /proc/${'$'}${'$'}/stat | cut -d ' ' -f 20 > ${shell(run.startTimeFile.toString())} || exit 70
            cat /proc/sys/kernel/random/boot_id > ${shell(run.bootIdFile.toString())} || exit 70
            stat -Lc %i /proc/${'$'}${'$'}/ns/mnt > ${shell(run.mountNamespaceFile.toString())} || exit 70
            printf '%s' NAMESPACE > ${shell(run.stageFile.toString())}
            mount --make-rprivate / || exit 70
            cleanup_failed=0
            ${mounts.indices.joinToString("\n") { "mounted_$it=0" }}
            cleanup() {
              trap - EXIT HUP INT TERM
            $cleanup
              if [ "${'$'}cleanup_failed" -ne 0 ]; then
                printf '%s' CLEANUP > ${shell(run.stageFile.toString())}
                exit 74
              fi
            }
            trap cleanup EXIT HUP INT TERM
            printf '%s' MOUNT > ${shell(run.stageFile.toString())}
            $prepareMounts
            test -r ${shell(rootfs.resolve("etc/resolv.conf").toString())} || exit 71
            printf '%s' EXEC > ${shell(run.stageFile.toString())}
            $command
        """.trimIndent()
    }

    internal fun hostLaunchArgv(run: ChrootRun, suExecutable: Path, argv: List<String>, environment: Map<String, String>): List<String> {
        require(suExecutable.isAbsolute) { "root helper executable must be absolute" }
        return listOf(
            suExecutable.toString(), "-c",
            "exec unshare -m -- /system/bin/sh -c ${shell(launchScript(run, argv, environment))} ${shell(run.cmdlineMarker)}",
        )
    }

    fun mountArguments(): List<List<String>> = mountCommands().map(Mount::arguments)

    private fun mountCommands(): List<Mount> = buildList {
        add(Mount("/proc", listOf("mount", "-t", "proc", "proc")))
        add(Mount("/sys", listOf("mount", "-t", "sysfs", "sysfs")))
        add(Mount("/dev", listOf("mount", "--rbind", "/dev"), makeSlave = true))
        binds.forEach { add(Mount(it.guest, listOf("mount", "--bind", it.host.toString()))) }
    }

    private fun validateGuestPath(path: String) {
        val normalized = path.asPath.normalize()
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
            "/storage/emulated".asPath, "/storage/self/primary".asPath, "/sdcard".asPath,
        )

        internal fun shell(value: String): String = "'${value.replace("'", "'\\''")}'"
        private val RUN_NONCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

class ChrootRun internal constructor(val nonce: String, val directory: Path) {
    val cmdlineMarker: String = "wekit-chroot-run-$nonce"
    val pidFile: Path = directory.resolve("pid")
    val startTimeFile: Path = directory.resolve("starttime")
    val bootIdFile: Path = directory.resolve("boot-id")
    val mountNamespaceFile: Path = directory.resolve("mnt-ns")
    val stageFile: Path = directory.resolve("stage")
}

internal object ArchLinuxInstanceLayout {
    fun canonicalInstancesRoot(): Path =
        HostInfo.application.filesDir.path.asPath.resolve("wekit-agent/environment/instances")

    fun validatePublishedRootfs(rootfs: Path, instancesRoot: Path = canonicalInstancesRoot()): Path {
        require(rootfs.isAbsolute && rootfs.normalize() == rootfs && rootfs.fileName?.toString() == "rootfs") {
            "invalid Arch rootfs path"
        }
        val canonicalRoot = instancesRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val instance = rootfs.parent
        require(instance.parent == instancesRoot.normalize() && !instance.fileName.toString().startsWith('.')) {
            "chroot rootfs is outside the canonical instance root"
        }
        val realInstance = instance.toRealPath()
        val realRootfs = rootfs.toRealPath()
        require(realInstance.parent == canonicalRoot && realRootfs.parent == realInstance) {
            "chroot rootfs escapes the canonical instance root"
        }
        require(realInstance.getOwner() == canonicalRoot.getOwner()) {
            "Arch instance is not app-owned"
        }
        require(realInstance.resolve(ArchLinuxInstanceInstaller.PUBLISHED_MARKER).isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            "Arch instance is not published"
        }
        require(
            realRootfs.resolve("bin/bash").isExecutable() &&
                realRootfs.resolve("usr/bin/invoke_tool").isExecutable() &&
                realRootfs.resolve("etc/resolv.conf").isRegularFile(LinkOption.NOFOLLOW_LINKS)
        ) { "published Arch instance is incomplete" }
        return realRootfs
    }
}

internal object ChrootMountRegistry {
    private val active = HashMap<String, MutableSet<String>>()
    private val deleting = HashSet<String>()
    @Synchronized fun begin(rootfs: Path, token: String) {
        val key = rootfs.toString()
        check(key !in deleting) { "chroot environment is being deleted" }
        check(key !in active && !hasPersistedRuns(rootfs)) { "chroot environment has an active or unresolved run" }
        check(active.getOrPut(key, ::HashSet).add(token)) { "chroot run token is already active" }
    }
    @Synchronized fun end(rootfs: Path, token: String) {
        val key = rootfs.toString()
        val tokens = active[key] ?: return
        tokens.remove(token)
        if (tokens.isEmpty()) active.remove(key)
    }
    @Synchronized fun beginDeletion(rootfs: Path) {
        val key = rootfs.toString()
        check(!isBusy(rootfs)) { "chroot environment has an active or unresolved run" }
        check(deleting.add(key)) { "chroot environment deletion is already in progress" }
    }
    @Synchronized fun endDeletion(rootfs: Path) { deleting.remove(rootfs.toString()) }
    @Synchronized internal fun hasActiveRuns(rootfs: Path): Boolean = rootfs.toString() in active
    @Synchronized internal fun isBusy(rootfs: Path): Boolean =
        hasActiveRuns(rootfs) || hasPersistedRuns(rootfs)

    private fun hasPersistedRuns(rootfs: Path): Boolean = rootfs.parent.resolve("chroot-runs").let { runs ->
        runs.isDirectory() && runs.listDirectoryEntries().isNotEmpty()
    }
}

class ChrootBackend internal constructor(
    override val snapshot: EnvironmentSnapshot,
    private val configuration: ChrootConfiguration = ChrootConfiguration(
        ArchLinuxInstanceLayout.validatePublishedRootfs(requireNotNull(snapshot.rootfsPath).asPath),
        snapshot.workingDirectory,
    ),
    private val rootHelper: ChrootRootHelper = ChrootRootHelper(configuration),
) : LinuxEnvironmentBackend {
    init { require(snapshot.type == LinuxEnvironmentType.CHROOT) }

    override suspend fun exec(command: String, timeoutMillis: Long, environmentVariables: Map<String, String>): ExecResult {
        return rootHelper.exec(command, timeoutMillis, environmentVariables)
    }

    override suspend fun readUtf8(path: String, maxBytes: Long): String = rootHelper.readUtf8(resolvePath(path), maxBytes)
    override suspend fun edit(request: FileEditRequest) = rootHelper.edit(request.copy(path = resolvePath(request.path)))
    override fun resolvePath(path: String): String {
        val requested = path.asPath
        val guest = (if (requested.isAbsolute) requested else snapshot.workingDirectory.asPath.resolve(requested)).normalize()
        require(guest.isAbsolute && !guest.startsWith("/..")) { "path escapes guest root" }
        require(listOf("/proc", "/sys", "/dev").none { guest.startsWith(it) }) { "virtual and device files are not supported" }
        return guest.toString()
    }

    override suspend fun ensureBridge(): BridgeInstallArtifact {
        return BridgeInstallArtifact("/usr/bin/invoke_tool", "/usr/bin")
    }

    override suspend fun checkHealth(): EnvironmentHealth {
        if (!configuration.rootfs.resolve("bin/bash").isRegularFile()) {
            return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "Arch rootfs is corrupt")
        }
        if (!rootHelper.hasRoot()) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "root access denied")
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
