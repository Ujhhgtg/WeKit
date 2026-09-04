package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.utils.fs.copyTo
import dev.ujhhgtg.wekit.utils.fs.copyFrom
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.asPath
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ProotBackend constructor(
    override val snapshot: EnvironmentSnapshot,
    private val rootfs: Path = requireNotNull(snapshot.rootfsPath).asPath,
    private val storageBinds: List<ProotCommand.Bind> = emptyList(),
    private val launcher: Path = NativeLoader.prootExecutable().toPath(),
    private val loader: Path = NativeLoader.prootLoaderExecutable().toPath(),
    private val startProcess: OwnedProcessStarter = OwnedProcess::start,
) : LinuxEnvironmentBackend {
    private val instance = rootfs.parent

    init {
        require(snapshot.type == LinuxEnvironmentType.PROOT)
        ArchLinuxInstanceInstaller.ensurePacmanSandboxDisabled(rootfs)
        storageBinds.forEach {
            val host = it.host.toAbsolutePath().normalize()
            require(it.guest.startsWith("/storage/") && APPROVED_STORAGE_ROOTS.any(host::startsWith)) {
                "storage binds must use approved Android shared-storage paths"
            }
        }
    }

    override suspend fun exec(command: String, timeoutMillis: Long, environmentVariables: Map<String, String>): ExecResult =
        withContext(Dispatchers.IO) {
            require(timeoutMillis in 1..NativeBackend.MAX_TIMEOUT_MILLIS)
            val outputs = rootfs.resolve("root/.weagent/outputs").also { it.createDirectories() }
            val stdout = createTempFile(outputs, "exec-", ".stdout")
            val stderr = createTempFile(outputs, "exec-", ".stderr")
            val startedAt = System.nanoTime()
            val prootTmp = instance.resolve("tmp").also { it.createDirectories() }
            val fipsEnabled = prootTmp.resolve("fips_enabled").also { it.writeText("0\n") }
            val argv = ProotCommand.execArgv(
                launcher,
                rootfs,
                snapshot.workingDirectory,
                ArchLinuxInstanceInstaller.withPacmanKeyringInitialization(command),
                environmentVariables,
                storageBinds = storageBinds + ProotCommand.Bind(fipsEnabled, "/proc/sys/crypto/fips_enabled"),
            )
            val processEnvironment = System.getenv().toMutableMap().apply {
                this["PROOT_LOADER"] = loader.toString()
                this["PROOT_NO_SECCOMP"] = "1"
                this["PROOT_TMP_DIR"] = prootTmp.toString()
            }
            WeLogger.d(
                TAG,
                "starting PRoot hostCwd=$instance guestCwd=${snapshot.workingDirectory} " +
                        "rootfs=$rootfs launcher=$launcher loader=$loader " +
                        "rootfsExists=${rootfs.isDirectory()} " +
                        "tmpExists=${instance.resolve("tmp").isDirectory()} " +
                        "prootTmp=${processEnvironment["PROOT_TMP_DIR"]} " +
                        "tmpdir=${processEnvironment["TMPDIR"]} " +
                        "pwd=${processEnvironment["PWD"]} " +
                        "envKeys=${processEnvironment.keys.sorted().joinToString(",")}",
            )
            val process = startProcess(argv, processEnvironment, instance.toString())
            val streamFailure = AtomicReference<Throwable?>()
            var stdoutReader: Thread? = null
            var stderrReader: Thread? = null
            var timedOut = false
            try {
                process.outputStream.close()
                stdoutReader = drain(process.inputStream, stdout, streamFailure)
                stderrReader = drain(process.errorStream, stderr, streamFailure)
                val deadline = System.nanoTime() + timeoutMillis * 1_000_000
                var exitCode = process.pollExit()
                while (exitCode == null) {
                    coroutineContext.ensureActive()
                    streamFailure.get()?.let { throw it }
                    if (System.nanoTime() >= deadline) {
                        timedOut = true
                        break
                    }
                    Thread.sleep(25)
                    exitCode = process.pollExit()
                }
                withContext(NonCancellable) {
                    ProcessTermination.drain(process)
                    while (exitCode == null) {
                        Thread.sleep(25)
                        exitCode = process.pollExit()
                    }
                    stdoutReader.join()
                    stderrReader.join()
                }
                streamFailure.get()?.let { throw it }
                val stdoutBytes = stdout.fileSize()
                val stderrBytes = stderr.fileSize()
                val spill = stdoutBytes + stderrBytes > NativeBackend.DEFAULT_MAX_OUTPUT_BYTES
                val outLimit = minOf(stdoutBytes, NativeBackend.DEFAULT_MAX_OUTPUT_BYTES.toLong()).toInt()
                val errLimit = minOf(stderrBytes, (NativeBackend.DEFAULT_MAX_OUTPUT_BYTES - outLimit).toLong()).toInt()
                val spillPath = if (spill) rootfs.resolve("root/.weagent/outputs/exec-${System.currentTimeMillis()}.log").also { path ->
                    path.outputStream(StandardOpenOption.CREATE_NEW).use { stream ->
                        stream.write("--- stdout ---\n".toByteArray()); stdout.copyTo(stream)
                        stream.write("\n--- stderr ---\n".toByteArray()); stderr.copyTo(stream)
                    }
                }.let { "/root/" + rootfs.resolve("root").relativize(it) } else null
                ExecResult(readPrefix(stdout, outLimit), readPrefix(stderr, errLimit), if (timedOut) null else exitCode, timedOut,
                    (System.nanoTime() - startedAt) / 1_000_000, spillPath)
            } finally {
                withContext(NonCancellable) {
                    try { ProcessTermination.drain(process) } finally {
                        process.close()
                        stdoutReader?.join()
                        stderrReader?.join()
                    }
                }
                stdout.deleteIfExists(); stderr.deleteIfExists()
            }
        }

    override suspend fun readUtf8(path: String, maxBytes: Long): String = withContext(Dispatchers.IO) {
        val target = resolve(path)
        require(target.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "not a regular file: $path" }
        require(target.fileSize() <= maxBytes) { "file exceeds $maxBytes bytes" }
        decode(target.readBytes())
    }

    override suspend fun edit(request: FileEditRequest) = withContext(Dispatchers.IO) {
        require(!request.replaceAll || request.oldString != null)
        val target = resolve(request.path)
        val exists = target.exists(LinkOption.NOFOLLOW_LINKS)
        val original = if (exists) {
            require(target.isRegularFile(LinkOption.NOFOLLOW_LINKS))
            require(target.fileSize() <= NativeBackend.MAX_EDIT_BYTES)
            decode(target.readBytes())
        } else ""
        val updated = request.oldString?.let { old ->
            require(old.isNotEmpty())
            val count = Regex(Regex.escape(old)).findAll(original).count()
            require(count > 0 && (request.replaceAll || count == 1)) { "oldString occurs $count times" }
            if (request.replaceAll) original.replace(old, request.newString) else original.replaceFirst(old, request.newString)
        } ?: request.newString.also { require(original.isEmpty()) { "creation requires a missing or empty file" } }
        val parent = target.parent
        require(parent.isDirectory()) { "parent directory does not exist" }
        val mode = if (exists) target.getPosixFilePermissions() else PosixFilePermissions.fromString("rw-------")
        val temporary = createTempFile(parent, ".weagent-edit-", ".tmp")
        try {
            temporary.writeText(updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
            temporary.setPosixFilePermissions(mode)
            temporary.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.deleteIfExists() }
        Unit
    }

    override fun resolvePath(path: String): String = guestPath(path)

    override suspend fun ensureBridge(): BridgeInstallArtifact {
        val bridge = rootfs.resolve("usr/bin/invoke_tool")
        require(bridge.isRegularFile() && bridge.isExecutable()) { "invoke_tool is missing from PRoot instance" }
        return BridgeInstallArtifact("/usr/bin/invoke_tool", "/usr/bin")
    }

    override suspend fun checkHealth(): EnvironmentHealth {
        if (!launcher.isExecutable()) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "PRoot launcher is missing")
        if (!loader.isExecutable()) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "PRoot loader is missing")
        if (!rootfs.resolve("bin/bash").isRegularFile()) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "Arch template is corrupt")
        val result = exec("test -x /usr/bin/invoke_tool && test -w /root", 15_000)
        return if (result.exitCode == 0) EnvironmentHealth(EnvironmentHealthState.HEALTHY)
        else EnvironmentHealth(EnvironmentHealthState.DEGRADED, result.stderr.ifBlank { "PRoot health command failed" })
    }

    private fun guestPath(path: String): String {
        val requested = path.asPath
        val guest = (if (requested.isAbsolute) requested else snapshot.workingDirectory.asPath.resolve(requested)).normalize()
        require(guest.isAbsolute && !guest.startsWith("/..")) { "path escapes guest root" }
        require(listOf("/proc", "/sys", "/dev").none { guest.startsWith(it) }) { "virtual and device files are not supported" }
        return guest.toString()
    }

    private fun resolve(path: String): Path {
        var guest = guestPath(path).asPath
        var host = rootfs
        var index = 0
        var links = 0
        while (index < guest.nameCount) {
            host = host.resolve(guest.getName(index).toString())
            if (host.isSymbolicLink()) {
                require(++links <= 40) { "too many symbolic links" }
                val link = host.readSymbolicLink()
                val remaining = if (index + 1 < guest.nameCount) guest.subpath(index + 1, guest.nameCount) else "".asPath
                guest = (if (link.isAbsolute) link else "/".asPath.resolve(rootfs.relativize(host.parent)).resolve(link)).resolve(remaining).normalize()
                require(guest.isAbsolute && !guest.startsWith("/..")) { "symbolic link escapes guest root" }
                host = rootfs
                index = 0
            } else index++
        }
        return host.normalize().also { require(it.startsWith(rootfs)) }
    }

    private fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun readPrefix(path: Path, limit: Int): String {
        if (limit == 0) return ""
        val output = ByteArrayOutputStream(limit)
        path.inputStream().use { input ->
            val buffer = ByteArray(minOf(8192, limit))
            var remaining = limit
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun drain(input: java.io.InputStream, path: Path, failure: AtomicReference<Throwable?>) =
        Thread {
            try {
                input.use { path.copyFrom(it, StandardOpenOption.TRUNCATE_EXISTING) }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { name = "wekit-owned-process-output"; start() }

    companion object {
        private const val TAG = "ProotBackend"
        private val APPROVED_STORAGE_ROOTS = listOf(
            "/storage/emulated".asPath, "/storage/self/primary".asPath, "/sdcard".asPath,
        )
    }
}

object ProotCommand {
    data class Bind(val host: Path, val guest: String)

    fun execArgv(launcher: Path, rootfs: Path, cwd: String, command: String, environment: Map<String, String>, storageBinds: List<Bind> = emptyList()): List<String> =
        launchArgv(launcher, rootfs, cwd, listOf("/bin/bash", "-c", command), environment, storageBinds)

    fun launchArgv(launcher: Path, rootfs: Path, cwd: String, guestArgv: List<String>, environment: Map<String, String>, storageBinds: List<Bind> = emptyList()): List<String> {
        require(guestArgv.isNotEmpty() && guestArgv.none(String::isEmpty))
        require(cwd.startsWith('/') && !cwd.asPath.normalize().startsWith("/.."))
        val binds = listOf(Bind("/dev".asPath, "/dev"), Bind("/proc".asPath, "/proc"), Bind("/sys".asPath, "/sys")) + storageBinds
        return buildList {
            add(launcher.toString()); add("--kill-on-exit"); add("--link2symlink"); add("-0")
            add("-r"); add(rootfs.toString()); add("-w"); add(cwd)
            binds.forEach { bind -> add("-b"); add("${bind.host}:${bind.guest}") }
            add("/usr/bin/env"); add("-i")
            add("HOME=/root"); add("USER=root"); add("LOGNAME=root"); add("SHELL=/bin/bash")
            add("PWD=$cwd")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin")
            environment.filterKeys { it != "PATH" && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
                .forEach { (key, value) -> add("$key=$value") }
            addAll(guestArgv)
        }
    }
}

fun processWithPidFile(pidFile: Path, argv: List<String>): List<String> =
    listOf("/system/bin/sh", "-c", "echo \$\$ > \"\$1\"; shift; exec \"\$@\"", "wekit-proot", pidFile.toString()) + argv
