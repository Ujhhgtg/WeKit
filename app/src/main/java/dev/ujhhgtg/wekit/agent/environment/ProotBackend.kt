package dev.ujhhgtg.wekit.agent.environment

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ProotBackend(
    override val snapshot: EnvironmentSnapshot,
    private val rootfs: Path = Path.of(requireNotNull(snapshot.rootfsPath)),
    private val storageBinds: List<ProotCommand.Bind> = emptyList(),
) : LinuxEnvironmentBackend {
    private val instance = rootfs.parent
    private val launcher = instance.resolve("bin/proot")

    init {
        require(snapshot.type == LinuxEnvironmentType.PROOT)
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
            val outputs = rootfs.resolve("root/.weagent/outputs").also(Files::createDirectories)
            val stdout = Files.createTempFile(outputs, "exec-", ".stdout")
            val stderr = Files.createTempFile(outputs, "exec-", ".stderr")
            val startedAt = System.nanoTime()
            val argv = ProotCommand.execArgv(launcher, rootfs, snapshot.workingDirectory, command, environmentVariables, storageBinds)
            val process = ProcessBuilder(argv).directory(instance.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).apply {
                environment()["PROOT_LOADER"] = instance.resolve("bin/loader").toString()
                environment()["PROOT_TMP_DIR"] = instance.resolve("tmp").also(Files::createDirectories).toString()
            }.start()
            var timedOut = false
            try {
                val deadline = System.nanoTime() + timeoutMillis * 1_000_000
                while (process.isAlive) {
                    coroutineContext.ensureActive()
                    if (System.nanoTime() >= deadline) {
                        timedOut = true
                        process.destroy()
                        if (process.isAlive) process.destroyForcibly()
                        break
                    }
                    Thread.sleep(25)
                }
                process.waitFor()
                val stdoutBytes = Files.size(stdout)
                val stderrBytes = Files.size(stderr)
                val spill = stdoutBytes + stderrBytes > NativeBackend.DEFAULT_MAX_OUTPUT_BYTES
                val outLimit = minOf(stdoutBytes, NativeBackend.DEFAULT_MAX_OUTPUT_BYTES.toLong()).toInt()
                val errLimit = minOf(stderrBytes, (NativeBackend.DEFAULT_MAX_OUTPUT_BYTES - outLimit).toLong()).toInt()
                val spillPath = if (spill) rootfs.resolve("root/.weagent/outputs/exec-${System.currentTimeMillis()}.log").also { path ->
                    Files.newOutputStream(path, StandardOpenOption.CREATE_NEW).use { stream ->
                        stream.write("--- stdout ---\n".toByteArray()); Files.copy(stdout, stream)
                        stream.write("\n--- stderr ---\n".toByteArray()); Files.copy(stderr, stream)
                    }
                }.let { "/root/" + rootfs.resolve("root").relativize(it) } else null
                ExecResult(readPrefix(stdout, outLimit), readPrefix(stderr, errLimit), if (timedOut) null else process.exitValue(), timedOut,
                    (System.nanoTime() - startedAt) / 1_000_000, spillPath)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
                Files.deleteIfExists(stdout); Files.deleteIfExists(stderr)
            }
        }

    override suspend fun readUtf8(path: String, maxBytes: Long): String = withContext(Dispatchers.IO) {
        val target = resolve(path)
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "not a regular file: $path" }
        require(Files.size(target) <= maxBytes) { "file exceeds $maxBytes bytes" }
        decode(Files.readAllBytes(target))
    }

    override suspend fun edit(request: FileEditRequest) = withContext(Dispatchers.IO) {
        require(!request.replaceAll || request.oldString != null)
        val target = resolve(request.path)
        val exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        val original = if (exists) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            require(Files.size(target) <= NativeBackend.MAX_EDIT_BYTES)
            decode(Files.readAllBytes(target))
        } else ""
        val updated = request.oldString?.let { old ->
            require(old.isNotEmpty())
            val count = Regex(Regex.escape(old)).findAll(original).count()
            require(count > 0 && (request.replaceAll || count == 1)) { "oldString occurs $count times" }
            if (request.replaceAll) original.replace(old, request.newString) else original.replaceFirst(old, request.newString)
        } ?: request.newString.also { require(original.isEmpty()) { "creation requires a missing or empty file" } }
        val parent = target.parent
        require(Files.isDirectory(parent)) { "parent directory does not exist" }
        val mode = if (exists) Files.getPosixFilePermissions(target) else PosixFilePermissions.fromString("rw-------")
        val temporary = Files.createTempFile(parent, ".weagent-edit-", ".tmp")
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
            Files.setPosixFilePermissions(temporary, mode)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        Unit
    }

    override fun resolvePath(path: String): String = guestPath(path)

    override suspend fun ensureBridge(): BridgeInstallArtifact {
        val bridge = rootfs.resolve("usr/bin/invoke_tool")
        require(Files.isRegularFile(bridge) && Files.isExecutable(bridge)) { "invoke_tool is missing from PRoot instance" }
        return BridgeInstallArtifact("/usr/bin/invoke_tool", "/usr/bin")
    }

    override suspend fun checkHealth(): EnvironmentHealth {
        if (!Files.isExecutable(launcher)) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "PRoot launcher is missing")
        if (!Files.isExecutable(instance.resolve("bin/loader"))) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "PRoot loader is missing")
        if (!Files.isRegularFile(rootfs.resolve("bin/bash"))) return EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "Arch template is corrupt")
        val result = exec("test -x /usr/bin/invoke_tool && test -w /root", 15_000)
        return if (result.exitCode == 0) EnvironmentHealth(EnvironmentHealthState.HEALTHY)
        else EnvironmentHealth(EnvironmentHealthState.DEGRADED, result.stderr.ifBlank { "PRoot health command failed" })
    }

    private fun guestPath(path: String): String {
        val requested = Path.of(path)
        val guest = (if (requested.isAbsolute) requested else Path.of(snapshot.workingDirectory).resolve(requested)).normalize()
        require(guest.isAbsolute && !guest.startsWith("/..")) { "path escapes guest root" }
        require(listOf("/proc", "/sys", "/dev").none { guest.startsWith(it) }) { "virtual and device files are not supported" }
        return guest.toString()
    }

    private fun resolve(path: String): Path {
        var guest = Path.of(guestPath(path))
        var host = rootfs
        var index = 0
        var links = 0
        while (index < guest.nameCount) {
            host = host.resolve(guest.getName(index).toString())
            if (Files.isSymbolicLink(host)) {
                require(++links <= 40) { "too many symbolic links" }
                val link = Files.readSymbolicLink(host)
                val remaining = if (index + 1 < guest.nameCount) guest.subpath(index + 1, guest.nameCount) else Path.of("")
                guest = (if (link.isAbsolute) link else Path.of("/").resolve(rootfs.relativize(host.parent)).resolve(link)).resolve(remaining).normalize()
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
        Files.newInputStream(path).use { input ->
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

    companion object {
        private val APPROVED_STORAGE_ROOTS = listOf(
            Path.of("/storage/emulated"), Path.of("/storage/self/primary"), Path.of("/sdcard"),
        )
    }
}

object ProotCommand {
    data class Bind(val host: Path, val guest: String)

    fun execArgv(launcher: Path, rootfs: Path, cwd: String, command: String, environment: Map<String, String>, storageBinds: List<Bind> = emptyList()): List<String> =
        launchArgv(launcher, rootfs, cwd, listOf("/bin/bash", "-lc", command), environment, storageBinds)

    fun launchArgv(launcher: Path, rootfs: Path, cwd: String, guestArgv: List<String>, environment: Map<String, String>, storageBinds: List<Bind> = emptyList()): List<String> {
        require(guestArgv.isNotEmpty() && guestArgv.none(String::isEmpty))
        require(cwd.startsWith('/') && !Path.of(cwd).normalize().startsWith("/.."))
        val binds = listOf(Bind(Path.of("/dev"), "/dev"), Bind(Path.of("/proc"), "/proc"), Bind(Path.of("/sys"), "/sys")) + storageBinds
        return buildList {
            add(launcher.toString()); add("--kill-on-exit"); add("--link2symlink"); add("-0")
            add("-r"); add(rootfs.toString()); add("-w"); add(cwd)
            binds.forEach { bind -> add("-b"); add("${bind.host}:${bind.guest}") }
            add("/usr/bin/env"); add("-i")
            add("HOME=/root"); add("USER=root"); add("LOGNAME=root"); add("SHELL=/bin/bash")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin")
            environment.filterKeys { it != "PATH" && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
                .forEach { (key, value) -> add("$key=$value") }
            addAll(guestArgv)
        }
    }
}
