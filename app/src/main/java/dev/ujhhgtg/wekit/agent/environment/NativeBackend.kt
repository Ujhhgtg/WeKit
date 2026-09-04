package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.utils.fs.copyTo
import dev.ujhhgtg.wekit.utils.fs.copyFrom
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.isWritable
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.ujhhgtg.wekit.loader.utils.NativeLoader

class NativeBackend internal constructor(
    override val snapshot: EnvironmentSnapshot,
    private val environmentVariables: Map<String, String> = emptyMap(),
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    private val defaultFilePermissions: Set<PosixFilePermission> = DEFAULT_NEW_FILE_PERMISSIONS,
    private val startProcess: OwnedProcessStarter = OwnedProcess::start,
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
        outputDirectory.createDirectories()
        val stdoutFile = createTempFile(outputDirectory, "exec-", ".stdout")
        val stderrFile = createTempFile(outputDirectory, "exec-", ".stderr")
        val startedAt = System.nanoTime()
        val processEnvironment = System.getenv().toMutableMap().apply {
            putAll(this@NativeBackend.environmentVariables)
            putAll(environmentVariables)
        }
        val process = startProcess(listOf(snapshot.shell, "-c", command), processEnvironment, workingDirectory.toString())
        val streamFailure = AtomicReference<Throwable?>()
        var stdoutReader: Thread? = null
        var stderrReader: Thread? = null
        var timedOut = false
        try {
            process.outputStream.close()
            stdoutReader = drain(process.inputStream, stdoutFile, streamFailure)
            stderrReader = drain(process.errorStream, stderrFile, streamFailure)
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
            val stdoutSize = stdoutFile.fileSize()
            val stderrSize = stderrFile.fileSize()
            val spilled = stdoutSize + stderrSize > maxOutputBytes
            val stdoutLimit = minOf(stdoutSize, maxOutputBytes.toLong()).toInt()
            val stderrLimit = minOf(stderrSize, (maxOutputBytes - stdoutLimit).coerceAtLeast(0).toLong()).toInt()
            val stdout = String(readPrefix(stdoutFile, stdoutLimit), StandardCharsets.UTF_8)
            val stderr = String(readPrefix(stderrFile, stderrLimit), StandardCharsets.UTF_8)
            val spillPath = if (spilled) {
                val spill = outputDirectory.resolve("exec-${System.currentTimeMillis()}.log")
                spill.outputStream(StandardOpenOption.CREATE_NEW).use { stream ->
                    stream.write("--- stdout ---\n".toByteArray())
                    stdoutFile.copyTo(stream)
                    stream.write("\n--- stderr ---\n".toByteArray())
                    stderrFile.copyTo(stream)
                }
                spill.toString()
            } else null
            ExecResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = if (timedOut) null else exitCode,
                timedOut = timedOut,
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                spillPath = spillPath,
            )
        } finally {
            withContext(NonCancellable) {
                try {
                    ProcessTermination.drain(process)
                } finally {
                    process.close()
                    stdoutReader?.join()
                    stderrReader?.join()
                }
            }
            stdoutFile.deleteIfExists()
            stderrFile.deleteIfExists()
        }
    }

    private fun drain(input: java.io.InputStream, path: Path, failure: AtomicReference<Throwable?>) =
        Thread {
            try {
                input.use { path.copyFrom(it, StandardOpenOption.TRUNCATE_EXISTING) }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { name = "wekit-owned-process-output"; start() }

    override suspend fun readUtf8(path: String, maxBytes: Long): String = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        val target = resolve(path)
        require(target.isRegularFile()) { "not a regular file: $path" }
        require(target.fileSize() <= maxBytes) { "file exceeds $maxBytes bytes" }
        decodeUtf8(target.readBytes())
    }

    override suspend fun edit(request: FileEditRequest) = withContext(Dispatchers.IO) {
        require(!request.replaceAll || request.oldString != null) { "replaceAll is invalid in creation mode" }
        val target = resolve(request.path)
        val exists = target.exists()
        val original = if (exists) {
            require(target.isRegularFile()) { "not a regular file: ${request.path}" }
            require(target.fileSize() <= MAX_EDIT_BYTES) { "file exceeds $MAX_EDIT_BYTES bytes" }
            decodeUtf8(target.readBytes())
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
        require(parent.isDirectory()) { "parent directory does not exist" }
        val originalPermissions = if (exists) {
            try {
                target.getPosixFilePermissions()
            } catch (error: Exception) {
                throw IllegalStateException("cannot read mode for existing file ${request.path}", error)
            }
        } else null
        val temporary = createTempFile(parent, ".weagent-edit-", ".tmp")
        try {
            temporary.writeText(updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                temporary.setPosixFilePermissions(originalPermissions ?: defaultFilePermissions)
            } catch (error: Exception) {
                throw IllegalStateException("cannot set mode for edited file ${request.path}", error)
            }
            temporary.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.deleteIfExists()
        }
        Unit
    }

    override fun resolvePath(path: String): String = resolve(path).toString()

    override suspend fun ensureBridge(): BridgeInstallArtifact = withContext(Dispatchers.IO) {
        val packaged = NativeLoader.invokeToolExecutable()
        val bin = Paths.get(snapshot.workingDirectory).resolve(".weagent/bin")
        bin.createDirectories()
        val link = bin.resolve("invoke_tool")
        if (link.isSymbolicLink() && link.readSymbolicLink() != packaged.toPath()) {
            link.deleteExisting()
        }
        if (!link.exists()) link.createSymbolicLinkPointingTo(packaged.toPath())
        BridgeInstallArtifact(link.toString(), bin.toString())
    }

    override suspend fun checkHealth(): EnvironmentHealth = withContext(Dispatchers.IO) {
        val directory = Paths.get(snapshot.workingDirectory)
        if (directory.isDirectory() && directory.isWritable()) {
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
        val checked = if (lexical.exists()) lexical.toRealPath() else {
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
        path.inputStream().use { input ->
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
        fun descendants(rootPid: Int, parentOf: Map<Int, Int>): List<Int> =
            ProcessTermination.descendants(rootPid, parentOf)
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
