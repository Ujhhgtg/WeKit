package dev.ujhhgtg.wekit.agent.environment

import com.topjohnwu.superuser.Shell
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
        executeFixed(
            "chown -R 0:0 ${ChrootConfiguration.shell(configuration.rootfs.toString())}",
            PREPARE_TIMEOUT_MILLIS,
            "chroot ownership preparation failed",
        )
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
        try {
            Files.deleteIfExists(configuration.stageFile)
            Files.deleteIfExists(configuration.pidFile)
            val launch = "exec setsid unshare -m -- /system/bin/sh -c ${ChrootConfiguration.shell(configuration.execScript(command, environment))}" +
                " > ${ChrootConfiguration.shell(stdout.toString())} 2> ${ChrootConfiguration.shell(stderr.toString())}"
            val future = shell.newJob().add(launch).enqueue()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (!future.isDone) {
                coroutineContext.ensureActive()
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    terminate()
                    break
                }
                Thread.sleep(25)
            }
            val result = awaitCleanup(future)
            val stderrText = readBounded(stderr, NativeBackend.DEFAULT_MAX_OUTPUT_BYTES)
            classifyFailure(result.code, stderrText)
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
            withContext(NonCancellable) { terminate() }
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    closeBounded(shell)
                } finally {
                    ChrootMountRegistry.end(configuration.rootfs)
                    Files.deleteIfExists(configuration.pidFile)
                    Files.deleteIfExists(configuration.stageFile)
                    if (!timedOut && !spill) { Files.deleteIfExists(stdout); Files.deleteIfExists(stderr) }
                }
            }
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

    private suspend fun terminate() {
        val pid = runCatching { Files.readString(configuration.pidFile).trim().toInt() }.getOrNull() ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                val control = rootShell()
                try {
                    control.newJob().add("kill -TERM -$pid 2>/dev/null || kill -TERM $pid 2>/dev/null || true").exec()
                    Thread.sleep(250)
                    control.newJob().add("kill -KILL -$pid 2>/dev/null || kill -KILL $pid 2>/dev/null || true").exec()
                } finally { control.waitAndClose(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
            }
        }
    }

    private fun awaitCleanup(future: java.util.concurrent.Future<Shell.Result>): Shell.Result = try {
        future.get(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        throw ChrootFailure.Cleanup("timed out after ${CLEANUP_TIMEOUT_MILLIS}ms")
    }

    private fun classifyFailure(code: Int, stderr: String) {
        if (code == 0) return
        val stage = runCatching { Files.readString(configuration.stageFile) }.getOrDefault("")
        if (stage == "EXEC" && CHROOT_DENIAL.containsMatchIn(stderr)) {
            throw ChrootFailure.Selinux(stderr.trim().take(500))
        }
        if (stage == "EXEC" && code != 74) return
        val detail = stderr.trim().take(500).ifBlank { "exit code $code" }
        when {
            code == 74 || stage == "CLEANUP" -> throw ChrootFailure.Cleanup(detail)
            stage.isEmpty() || stage == "NAMESPACE" -> throw ChrootFailure.Namespace(detail)
            stage == "MOUNT" && SELINUX_DENIAL.containsMatchIn(stderr) -> {
                throw ChrootFailure.Selinux(detail)
            }
            stage == "MOUNT" -> throw ChrootFailure.Mount(detail)
            else -> throw ChrootFailure.Namespace(detail)
        }
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
        private val SELINUX_DENIAL = Regex("(?i)(avc:.*denied|permission denied|operation not permitted)")
        private val CHROOT_DENIAL = Regex("(?i)chroot:.*(permission denied|operation not permitted)")
    }
}
