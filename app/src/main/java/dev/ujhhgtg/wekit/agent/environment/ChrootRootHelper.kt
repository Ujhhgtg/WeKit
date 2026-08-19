package dev.ujhhgtg.wekit.agent.environment

import com.topjohnwu.superuser.Shell
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed class ChrootFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class Root(cause: Throwable? = null) : ChrootFailure("root access denied", cause)
    class Namespace(detail: String) : ChrootFailure("private mount namespace failed: $detail")
    class Selinux(detail: String) : ChrootFailure("SELinux denied chroot mount or execution: $detail")
    class Mount(detail: String) : ChrootFailure("chroot mount failed: $detail")
    class Cleanup(detail: String) : ChrootFailure("chroot mount cleanup failed: $detail")
}

internal class ChrootRootHelper(private val configuration: ChrootConfiguration) {
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    suspend fun prepareInstance() {
        val health = exec("test -x /bin/bash && test -x /usr/bin/invoke_tool", HEALTH_TIMEOUT_MILLIS, emptyMap())
        check(health.exitCode == 0) { health.stderr.ifBlank { "chroot health check failed" } }
    }

    suspend fun removeInstance() {
        require(configuration.rootfs.fileName.toString() == "rootfs") { "invalid chroot instance layout" }
        executeFixed(
            "rm -rf -- ${ChrootConfiguration.shell(configuration.instance.toString())}",
            PREPARE_TIMEOUT_MILLIS,
            "chroot instance cleanup failed",
        )
    }

    suspend fun exec(command: String, timeoutMillis: Long, environment: Map<String, String>): ExecResult = withContext(Dispatchers.IO) {
        require(timeoutMillis in 1..NativeBackend.MAX_TIMEOUT_MILLIS)
        if (!hasRoot()) throw ChrootFailure.Root()
        Files.createDirectories(configuration.instance.resolve("outputs"))
        val stdout = Files.createTempFile(configuration.instance.resolve("outputs"), "chroot-", ".stdout")
        val stderr = Files.createTempFile(configuration.instance.resolve("outputs"), "chroot-", ".stderr")
        val startedAt = System.nanoTime()
        val shell = rootShell()
        var timedOut = false
        var spill = false
        ChrootMountRegistry.begin(configuration.rootfs)
        val run = try {
            configuration.createRun()
        } catch (error: Throwable) {
            ChrootMountRegistry.end(configuration.rootfs)
            throw error
        }
        try {
            val launch = "exec setsid unshare -m -- /system/bin/sh -c ${ChrootConfiguration.shell(configuration.execScript(run, command, environment))}" +
                " > ${ChrootConfiguration.shell(stdout.toString())} 2> ${ChrootConfiguration.shell(stderr.toString())}"
            val future = shell.newJob().add(launch).enqueue()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (!future.isDone) {
                coroutineContext.ensureActive()
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    cleanupNamespace(run)
                    break
                }
                Thread.sleep(25)
            }
            val result = awaitCleanup(future)
            val stderrText = readBounded(stderr, NativeBackend.DEFAULT_MAX_OUTPUT_BYTES)
            classifyFailure(run, result.code, stderrText)
            val stdoutSize = Files.size(stdout)
            val stderrSize = Files.size(stderr)
            spill = stdoutSize + stderrSize > NativeBackend.DEFAULT_MAX_OUTPUT_BYTES
            val outLimit = minOf(stdoutSize, NativeBackend.DEFAULT_MAX_OUTPUT_BYTES.toLong()).toInt()
            val errLimit = minOf(stderrSize, (NativeBackend.DEFAULT_MAX_OUTPUT_BYTES - outLimit).toLong()).toInt()
            ExecResult(
                readBounded(stdout, outLimit), readBounded(stderr, errLimit),
                if (timedOut) null else result.code, timedOut,
                (System.nanoTime() - startedAt) / 1_000_000,
                if (spill) stdout.toString() else null,
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cleanupNamespace(run) }
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                try {
                    cleanupNamespace(run)
                } catch (error: Throwable) {
                    cleanupFailure = error
                } finally {
                    try { closeBounded(shell) } catch (error: Throwable) {
                        cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
                    }
                    if (!timedOut && !spill) { Files.deleteIfExists(stdout); Files.deleteIfExists(stderr) }
                }
                cleanupFailure?.let { throw it }
                ChrootMountRegistry.end(configuration.rootfs)
            }
        }
    }

    suspend fun resolveSuExecutable(): Path = withContext(Dispatchers.IO) {
        val shell = rootShell()
        try {
            val result = shell.newJob().add("command -v su").exec()
            val value = result.out.singleOrNull()?.trim().orEmpty()
            val path = runCatching { Path.of(value) }.getOrNull()
            if (!result.isSuccess || path == null || !path.isAbsolute || !Files.isExecutable(path) ||
                TRUSTED_SU_PATHS.none { path == it }
            ) {
                throw ChrootFailure.Root(IllegalStateException("trusted absolute su executable is unavailable"))
            }
            val verification = shell.newJob().add("${ChrootConfiguration.shell(path.toString())} -c 'test \"\$(id -u)\" = 0'").exec()
            if (!verification.isSuccess) throw ChrootFailure.Root(IllegalStateException("resolved su executable cannot grant root"))
            path
        } finally {
            closeBounded(shell)
        }
    }

    suspend fun readUtf8(guestPath: String, maxBytes: Long): String = withContext(Dispatchers.IO) {
        require(maxBytes in 0L..NativeBackend.MAX_EDIT_BYTES)
        Files.createDirectories(configuration.instance.resolve("outputs"))
        val output = Files.createTempFile(configuration.instance.resolve("outputs"), "read-", ".tmp")
        try {
            executeFixed(
                "chroot ${ChrootConfiguration.shell(configuration.rootfs.toString())} /bin/sh -c " +
                    ChrootConfiguration.shell("test -f \"\$1\" && test \$(stat -c %s \"\$1\") -le \"\$2\" && cat -- \"\$1\"") +
                    " wekit-read ${ChrootConfiguration.shell(guestPath)} $maxBytes > ${ChrootConfiguration.shell(output.toString())}",
                HEALTH_TIMEOUT_MILLIS,
                "rooted file read failed",
            )
            val bytes = Files.readAllBytes(output)
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally {
            Files.deleteIfExists(output)
        }
    }

    suspend fun edit(request: FileEditRequest) = withContext(Dispatchers.IO) {
        require(!request.replaceAll || request.oldString != null)
        val original = if (pathExists(request.path)) readUtf8(request.path, NativeBackend.MAX_EDIT_BYTES) else ""
        val updated = request.oldString?.let { old ->
            require(old.isNotEmpty())
            val count = Regex(Regex.escape(old)).findAll(original).count()
            require(count > 0 && (request.replaceAll || count == 1)) { "oldString occurs $count times" }
            if (request.replaceAll) original.replace(old, request.newString) else original.replaceFirst(old, request.newString)
        } ?: request.newString.also { require(original.isEmpty()) { "creation requires a missing or empty file" } }
        Files.createDirectories(configuration.instance.resolve("outputs"))
        val input = Files.createTempFile(configuration.instance.resolve("outputs"), "edit-", ".tmp")
        try {
            Files.writeString(input, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
            executeFixed(editCommand(request.path, input), HEALTH_TIMEOUT_MILLIS, "rooted atomic edit failed")
        } finally {
            Files.deleteIfExists(input)
        }
    }

    internal fun editCommand(guestPath: String, input: Path): String {
        val stagedName = ".weagent-${input.fileName}"
        val stagedHost = configuration.rootfs.resolve("tmp").resolve(stagedName)
        val stagedGuest = "/tmp/$stagedName"
        val script = """
            set -eu
            target="${'$'}1"; input="${'$'}2"; parent="${'$'}{target%/*}"; name="${'$'}{target##*/}"
            test -d "${'$'}parent"
            temporary="${'$'}parent/.${'$'}name.weagent.${'$'}${'$'}"
            trap 'rm -f -- "${'$'}temporary"' EXIT HUP INT TERM
            if test -e "${'$'}target"; then test -f "${'$'}target"; mode=${'$'}(stat -c %a "${'$'}target"); else mode=600; fi
            umask 077; cat -- "${'$'}input" > "${'$'}temporary"; chmod "${'$'}mode" "${'$'}temporary"
            mv -f -- "${'$'}temporary" "${'$'}target"; trap - EXIT HUP INT TERM
        """.trimIndent()
        return "mkdir -p ${ChrootConfiguration.shell(stagedHost.parent.toString())}; " +
            "cp -- ${ChrootConfiguration.shell(input.toString())} ${ChrootConfiguration.shell(stagedHost.toString())}; " +
            "trap 'rm -f -- ${ChrootConfiguration.shell(stagedHost.toString())}' EXIT HUP INT TERM; " +
            "chroot ${ChrootConfiguration.shell(configuration.rootfs.toString())} /bin/sh -c ${ChrootConfiguration.shell(script)}" +
            " wekit-edit ${ChrootConfiguration.shell(guestPath)} ${ChrootConfiguration.shell(stagedGuest)}"
    }

    private suspend fun pathExists(guestPath: String): Boolean = withContext(Dispatchers.IO) {
        val shell = rootShell()
        try {
            val command = "chroot ${ChrootConfiguration.shell(configuration.rootfs.toString())} /bin/sh -c " +
                "${ChrootConfiguration.shell("test -e \"\$1\"")} wekit-exists ${ChrootConfiguration.shell(guestPath)}"
            val result = shell.newJob().add(command).exec()
            when (result.code) {
                0 -> true
                1 -> false
                else -> error((result.err + result.out).joinToString("\n").ifBlank { "rooted file existence check failed" })
            }
        } finally {
            closeBounded(shell)
        }
    }

    private suspend fun executeFixed(command: String, timeoutMillis: Long, failureMessage: String) = withContext(Dispatchers.IO) {
        val shell = rootShell()
        try {
            val result = shell.newJob().add(command).enqueue().get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!result.isSuccess) {
                val detail = (result.err + result.out).joinToString("\n").take(500)
                if (SELINUX_DENIAL.containsMatchIn(detail)) throw ChrootFailure.Selinux(detail)
                error("$failureMessage${detail.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""}")
            }
        } catch (error: TimeoutException) {
            throw ChrootFailure.Root(error)
        } finally { closeBounded(shell) }
    }

    private fun rootShell(): Shell = try {
        Shell.Builder.create().setTimeout(ROOT_PROMPT_TIMEOUT_SECONDS).build().also {
            if (!it.isRoot) { it.close(); throw ChrootFailure.Root() }
        }
    } catch (error: ChrootFailure) {
        throw error
    } catch (error: Throwable) {
        throw ChrootFailure.Root(error)
    }

    suspend fun recoverPendingRuns() {
        configuration.pendingRuns().forEach { run ->
            cleanupNamespace(run)
            if (ChrootMountRegistry.isBusy(configuration.rootfs)) ChrootMountRegistry.end(configuration.rootfs)
        }
    }

    suspend fun cleanupNamespace(run: ChrootRun) = withContext(NonCancellable + Dispatchers.IO) {
        if (!Files.exists(run.directory)) return@withContext
        val storedNonce = readMetadata(run.directory.resolve("nonce"))
        if (storedNonce != run.nonce || run.directory.fileName.toString() != run.nonce ||
            run.directory.parent != configuration.runsDirectory
        ) {
            throw ChrootFailure.Cleanup("run nonce identity does not match ${run.directory}")
        }
        val pid = readMetadata(run.pidFile)?.toIntOrNull()
            ?: throw ChrootFailure.Cleanup("run ${run.nonce} has no process identity yet")
        val startTime = readMetadata(run.startTimeFile)
            ?: throw ChrootFailure.Cleanup("run ${run.nonce} has incomplete process identity")
        val shell = rootShell()
        try {
            val result = shell.newJob().add(cleanupCommand(run, pid, startTime)).enqueue().get(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (!result.isSuccess) throw ChrootFailure.Cleanup((result.err + result.out).joinToString("\n").ifBlank { "namespace process $pid remains" })
        } catch (error: TimeoutException) {
            throw ChrootFailure.Cleanup("namespace cleanup timed out")
        } finally {
            closeBounded(shell)
        }
        removeRunMetadata(run)
    }

    internal fun cleanupCommand(run: ChrootRun, pid: Int, startTime: String): String {
        require(run.nonce.matches(RUN_NONCE)) { "invalid chroot run nonce" }
        require(startTime.matches(PROCESS_START_TIME)) { "invalid chroot process start time" }
        val targets = configuration.mountArguments().asReversed().joinToString("; ") { mount ->
            val guest = mount.last()
            "umount -l ${ChrootConfiguration.shell(configuration.rootfs.resolve(guest.removePrefix("/")).toString())} 2>/dev/null || true"
        }
        val currentStartTime = "sed 's/.*) //' /proc/$pid/stat 2>/dev/null | cut -d ' ' -f 20"
        return "if test ! -e /proc/$pid; then exit 0; fi; " +
            "test \"\$($currentStartTime)\" = ${ChrootConfiguration.shell(startTime)} || exit 75; " +
            "kill -TERM -$pid 2>/dev/null || kill -TERM $pid 2>/dev/null || true; " +
            "if test -e /proc/$pid/ns/mnt; then nsenter -t $pid -m -- /system/bin/sh -c ${ChrootConfiguration.shell(targets)} || true; fi; " +
            "kill -KILL -$pid 2>/dev/null || kill -KILL $pid 2>/dev/null || true; " +
            "i=0; while test -e /proc/$pid && test \$i -lt 50; do sleep 0.05; i=\$((i+1)); done; test ! -e /proc/$pid"
    }

    internal fun removeRunMetadata(run: ChrootRun) {
        Files.newDirectoryStream(run.directory).use { entries -> entries.forEach(Files::deleteIfExists) }
        Files.deleteIfExists(run.directory)
        run.directory.parent?.let { runs ->
            if (Files.isDirectory(runs) && Files.newDirectoryStream(runs).use { !it.iterator().hasNext() }) Files.deleteIfExists(runs)
        }
    }

    private fun readMetadata(path: Path): String? =
        runCatching { Files.readString(path).trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    private fun awaitCleanup(future: java.util.concurrent.Future<Shell.Result>): Shell.Result = try {
        future.get(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        throw ChrootFailure.Cleanup("timed out after ${CLEANUP_TIMEOUT_MILLIS}ms")
    }

    private fun classifyFailure(run: ChrootRun, code: Int, stderr: String) {
        if (code == 0) return
        classifyChrootFailure(runCatching { Files.readString(run.stageFile) }.getOrDefault(""), code, stderr)?.let { throw it }
    }

    private fun readBounded(path: java.nio.file.Path, maxBytes: Int): String {
        if (maxBytes == 0) return ""
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            return input.readNBytes(maxBytes).decodeToString()
        }
    }

    private suspend fun closeBounded(shell: Shell) {
        val closed = runCatching {
            withContext(Dispatchers.IO) { shell.waitAndClose(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        }.getOrElse { throw ChrootFailure.Cleanup(it.message ?: "root shell did not close") }
        if (!closed) throw ChrootFailure.Cleanup("root shell did not close within ${CLEANUP_TIMEOUT_MILLIS}ms")
    }

    companion object {
        private const val ROOT_PROMPT_TIMEOUT_SECONDS = 10L
        private const val PREPARE_TIMEOUT_MILLIS = 120_000L
        private const val HEALTH_TIMEOUT_MILLIS = 15_000L
        private const val CLEANUP_TIMEOUT_MILLIS = 5_000L
        internal val SELINUX_DENIAL = Regex("(?i)(avc:.*denied|permission denied|operation not permitted)")
        internal val CHROOT_DENIAL = Regex("(?i)chroot:.*(permission denied|operation not permitted)")
        private val RUN_NONCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val PROCESS_START_TIME = Regex("[0-9]+")
        private val TRUSTED_SU_PATHS = listOf(
            Path.of("/system/bin/su"), Path.of("/system/xbin/su"), Path.of("/sbin/su"),
            Path.of("/vendor/bin/su"), Path.of("/debug_ramdisk/su"),
        )
    }
}

internal fun classifyChrootFailure(stage: String, code: Int, stderr: String): ChrootFailure? {
    if (code == 0) return null
    val detail = stderr.trim().take(500).ifBlank { "exit code $code" }
    if (stage in setOf("NAMESPACE", "MOUNT") && ChrootRootHelper.SELINUX_DENIAL.containsMatchIn(stderr) ||
        stage == "EXEC" && ChrootRootHelper.CHROOT_DENIAL.containsMatchIn(stderr)
    ) return ChrootFailure.Selinux(detail)
    if (stage == "EXEC" && code != 74) return null
    return when {
        code == 74 || stage == "CLEANUP" -> ChrootFailure.Cleanup(detail)
        stage.isEmpty() || stage == "NAMESPACE" -> ChrootFailure.Namespace(detail)
        stage == "MOUNT" -> ChrootFailure.Mount(detail)
        else -> ChrootFailure.Namespace(detail)
    }
}
