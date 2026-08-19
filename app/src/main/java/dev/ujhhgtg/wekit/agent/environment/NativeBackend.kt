package dev.ujhhgtg.wekit.agent.environment

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.ujhhgtg.wekit.loader.utils.NativeLoader

class NativeBackend(
    override val snapshot: EnvironmentSnapshot,
    private val environmentVariables: Map<String, String> = emptyMap(),
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    private val defaultFilePermissions: Set<PosixFilePermission> = DEFAULT_NEW_FILE_PERMISSIONS,
) : LinuxEnvironmentBackend {
    init {
        require(snapshot.type == LinuxEnvironmentType.NATIVE)
        require(snapshot.id == NATIVE_ENVIRONMENT_ID)
        require(maxOutputBytes >= 0) { "max output bytes must not be negative" }
    }

    override suspend fun exec(
        command: String,
        timeoutMillis: Long,
        environmentVariables: Map<String, String>,
    ): ExecResult = withContext(Dispatchers.IO) {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "timeout must be between 1 and $MAX_TIMEOUT_MILLIS ms" }
        val workingDirectory = Paths.get(snapshot.workingDirectory).toRealPath()
        val outputDirectory = workingDirectory.resolve(".weagent/outputs")
        Files.createDirectories(outputDirectory)
        val stdoutFile = Files.createTempFile(outputDirectory, "exec-", ".stdout")
        val stderrFile = Files.createTempFile(outputDirectory, "exec-", ".stderr")
        val pidFile = Files.createTempFile(outputDirectory, "exec-", ".pid")
        val startedAt = System.nanoTime()
        val launcher = "echo \$\$ > ${shellQuote(pidFile.toString())}; exec ${shellQuote(snapshot.shell)} -c ${shellQuote(command)}"
        val process = ProcessBuilder(snapshot.shell, "-c", launcher)
            .directory(workingDirectory.toFile())
            .redirectOutput(stdoutFile.toFile())
            .redirectError(stderrFile.toFile())
            .apply {
                environment().putAll(this@NativeBackend.environmentVariables)
                environment().putAll(environmentVariables)
            }
            .start()
        var timedOut = false
        var completedNormally = false
        try {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (process.isAlive) {
                coroutineContext.ensureActive()
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    ProcessTermination.terminateTree(process, readPid(pidFile))
                    break
                }
                Thread.sleep(25)
            }
            process.waitFor()
            val stdoutSize = Files.size(stdoutFile)
            val stderrSize = Files.size(stderrFile)
            val spilled = stdoutSize + stderrSize > maxOutputBytes
            val stdoutLimit = minOf(stdoutSize, maxOutputBytes.toLong()).toInt()
            val stderrLimit = minOf(stderrSize, (maxOutputBytes - stdoutLimit).coerceAtLeast(0).toLong()).toInt()
            val stdout = String(readPrefix(stdoutFile, stdoutLimit), StandardCharsets.UTF_8)
            val stderr = String(readPrefix(stderrFile, stderrLimit), StandardCharsets.UTF_8)
            val spillPath = if (spilled) {
                val spill = outputDirectory.resolve("exec-${System.currentTimeMillis()}.log")
                Files.newOutputStream(spill, StandardOpenOption.CREATE_NEW).use { stream ->
                    stream.write("--- stdout ---\n".toByteArray())
                    Files.copy(stdoutFile, stream)
                    stream.write("\n--- stderr ---\n".toByteArray())
                    Files.copy(stderrFile, stream)
                }
                spill.toString()
            } else null
            ExecResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = if (timedOut) null else process.exitValue(),
                timedOut = timedOut,
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                spillPath = spillPath,
            ).also { completedNormally = true }
        } finally {
            if (!completedNormally || process.isAlive) {
                ProcessTermination.terminateTree(
                    process,
                    readPid(pidFile),
                )
            }
            Files.deleteIfExists(stdoutFile)
            Files.deleteIfExists(stderrFile)
            Files.deleteIfExists(pidFile)
        }
    }

    private fun readPid(pidFile: Path): Int? =
        runCatching { Files.readString(pidFile).trim().toInt() }.getOrNull()

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    override suspend fun readUtf8(path: String, maxBytes: Long): String = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        val target = resolve(path)
        require(Files.isRegularFile(target)) { "not a regular file: $path" }
        require(Files.size(target) <= maxBytes) { "file exceeds $maxBytes bytes" }
        decodeUtf8(Files.readAllBytes(target))
    }

    override suspend fun edit(request: FileEditRequest) = withContext(Dispatchers.IO) {
        require(!request.replaceAll || request.oldString != null) { "replaceAll is invalid in creation mode" }
        val target = resolve(request.path)
        val exists = Files.exists(target)
        val original = if (exists) {
            require(Files.isRegularFile(target)) { "not a regular file: ${request.path}" }
            require(Files.size(target) <= MAX_EDIT_BYTES) { "file exceeds $MAX_EDIT_BYTES bytes" }
            decodeUtf8(Files.readAllBytes(target))
        } else ""
        val updated = when (val old = request.oldString) {
            null -> {
                require(original.isEmpty()) { "creation requires a missing or empty file" }
                request.newString
            }
            else -> {
                require(old.isNotEmpty()) { "oldString must not be empty" }
                val matches = countOccurrences(original, old)
                require(matches > 0) { "oldString was not found" }
                require(request.replaceAll || matches == 1) { "oldString occurs $matches times" }
                if (request.replaceAll) original.replace(old, request.newString)
                else original.replaceFirst(old, request.newString)
            }
        }
        val parent = target.parent ?: error("target has no parent")
        require(Files.isDirectory(parent)) { "parent directory does not exist" }
        val originalPermissions = if (exists) {
            try {
                Files.getPosixFilePermissions(target)
            } catch (error: Exception) {
                throw IllegalStateException("cannot read mode for existing file ${request.path}", error)
            }
        } else null
        val temporary = Files.createTempFile(parent, ".weagent-edit-", ".tmp")
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.setPosixFilePermissions(temporary, originalPermissions ?: defaultFilePermissions)
            } catch (error: Exception) {
                throw IllegalStateException("cannot set mode for edited file ${request.path}", error)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override fun resolvePath(path: String): String = resolve(path).toString()

    override suspend fun ensureBridge(): BridgeInstallArtifact = withContext(Dispatchers.IO) {
        val packaged = NativeLoader.invokeToolExecutable()
        val bin = Paths.get(snapshot.workingDirectory).resolve(".weagent/bin")
        Files.createDirectories(bin)
        val link = bin.resolve("invoke_tool")
        if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) != packaged.toPath()) {
            Files.delete(link)
        }
        if (!Files.exists(link)) Files.createSymbolicLink(link, packaged.toPath())
        BridgeInstallArtifact(link.toString(), bin.toString())
    }

    override suspend fun checkHealth(): EnvironmentHealth = withContext(Dispatchers.IO) {
        val directory = Paths.get(snapshot.workingDirectory)
        if (Files.isDirectory(directory) && Files.isWritable(directory)) {
            EnvironmentHealth(EnvironmentHealthState.HEALTHY)
        } else {
            EnvironmentHealth(EnvironmentHealthState.UNAVAILABLE, "working directory is not writable")
        }
    }

    private fun resolve(path: String): Path {
        val requested = Paths.get(path)
        val root = Paths.get(snapshot.workingDirectory).toRealPath()
        val lexical = if (requested.isAbsolute) requested.normalize() else root.resolve(requested).normalize()
        if (!requested.isAbsolute) {
            require(lexical.startsWith(root)) { "relative path escapes the working directory" }
        }
        val checked = if (Files.exists(lexical)) lexical.toRealPath() else {
            val parent = lexical.parent ?: error("relative path has no parent")
            parent.toRealPath().resolve(lexical.fileName).normalize()
        }
        if (!requested.isAbsolute) {
            require(checked.startsWith(root)) { "relative path escapes the working directory through a symlink" }
        }
        require(FORBIDDEN_EDIT_ROOTS.none(checked::startsWith)) { "virtual and device files are not supported" }
        return checked
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun readPrefix(path: Path, limit: Int): ByteArray {
        if (limit == 0) return ByteArray(0)
        val output = ByteArrayOutputStream(limit)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(minOf(8192, limit))
            var remaining = limit
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        return output.toByteArray()
    }

    private fun countOccurrences(content: String, needle: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val match = content.indexOf(needle, start)
            if (match < 0) return count
            count++
            start = match + needle.length
        }
    }

    internal object ProcessTree {
        fun descendants(rootPid: Int, parentOf: Map<Int, Int>): List<Int> {
            val children = parentOf.entries.groupBy({ it.value }, { it.key })
            val result = ArrayList<Int>()
            fun visit(pid: Int) {
                children[pid].orEmpty().forEach { child -> visit(child); result += child }
            }
            visit(rootPid)
            return result
        }
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
        const val MAX_TIMEOUT_MILLIS = 10 * 60 * 1000L
        const val MAX_EDIT_BYTES = 4 * 1024 * 1024L
        val DEFAULT_NEW_FILE_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")
        private val FORBIDDEN_EDIT_ROOTS = listOf(Paths.get("/proc"), Paths.get("/sys"), Paths.get("/dev"))
    }
}
