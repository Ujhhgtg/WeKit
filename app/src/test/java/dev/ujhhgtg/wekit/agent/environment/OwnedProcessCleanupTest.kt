package dev.ujhhgtg.wekit.agent.environment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OwnedProcessCleanupTest {
    @Test
    fun `native timeout cancellation and stream failure use non-cancellable owned cleanup`(@TempDir directory: Path) =
        runBlocking {
            verifyFailureCleanup { starter ->
                NativeBackend(nativeSnapshot(directory), startProcess = starter)
            }
        }

    @Test
    fun `proot timeout cancellation and stream failure use non-cancellable owned cleanup`(@TempDir directory: Path) =
        runBlocking {
            val rootfs = Files.createDirectories(directory.resolve("instance/rootfs"))
            verifyFailureCleanup { starter ->
                ProotBackend(
                    prootSnapshot(rootfs),
                    rootfs = rootfs,
                    launcher = "/package/lib/libproot.so".asPath,
                    loader = "/package/lib/libproot_loader.so".asPath,
                    startProcess = starter,
                )
            }
        }

    @Test
    fun `proot launch uses package managed executables`(@TempDir directory: Path) = runBlocking {
        val rootfs = Files.createDirectories(directory.resolve("instance/rootfs"))
        val process = FakeOwnedProcess()
        lateinit var capturedArgv: List<String>
        lateinit var capturedEnvironment: Map<String, String>
        val starter: OwnedProcessStarter = { argv, environment, _ ->
            capturedArgv = argv
            capturedEnvironment = environment
            process
        }
        val backend = ProotBackend(
            prootSnapshot(rootfs),
            rootfs = rootfs,
            launcher = "/package/lib/libproot.so".asPath,
            loader = "/package/lib/libproot_loader.so".asPath,
            startProcess = starter,
        )

        assertTrue(backend.exec("true", 1).timedOut)
        assertEquals("/package/lib/libproot.so", capturedArgv.first())
        assertEquals("/package/lib/libproot_loader.so", capturedEnvironment["PROOT_LOADER"])
        assertTrue(capturedEnvironment.getValue("PROOT_TMP_DIR").endsWith("/instance/tmp"))
        process.assertCleaned()
    }

    private suspend fun verifyFailureCleanup(
        backend: (OwnedProcessStarter) -> LinuxEnvironmentBackend,
    ) {
        val timedOut = FakeOwnedProcess()
        val timeoutResult = backend { _, _, _ -> timedOut }.exec("sleep", 1)
        assertTrue(timeoutResult.timedOut)
        timedOut.assertCleaned()

        val started = CompletableDeferred<Unit>()
        val cancelled = FakeOwnedProcess()
        val cancellationBackend = backend { _, _, _ -> cancelled.also { started.complete(Unit) } }
        val execution = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            cancellationBackend.exec("sleep", 10_000)
        }
        started.await()
        execution.cancelAndJoin()
        cancelled.assertCleaned()

        val failedStream = FakeOwnedProcess(streamFailure = true)
        assertThrows(IOException::class.java) {
            runBlocking { backend { _, _, _ -> failedStream }.exec("output", 10_000) }
        }
        failedStream.assertCleaned()
    }

    private class FakeOwnedProcess(streamFailure: Boolean = false) : OwnedProcessHandle {
        override val outputStream = ByteArrayOutputStream()
        override val inputStream: InputStream = if (streamFailure) FailingInputStream() else ByteArrayInputStream(ByteArray(0))
        override val errorStream: InputStream = ByteArrayInputStream(ByteArray(0))
        private var exitCode: Int? = null
        private val cleanupContexts = mutableListOf<Boolean>()
        private var closed = false

        override fun pollExit(): Int? = exitCode

        override suspend fun terminateGroup(graceMillis: Long) {
            yield()
            cleanupContexts += coroutineContext[Job]?.isActive == true
            exitCode = 143
        }

        override fun close() {
            closed = true
        }

        fun assertCleaned() {
            assertTrue(cleanupContexts.isNotEmpty())
            assertTrue(cleanupContexts.all { it })
            assertTrue(closed)
        }
    }

    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw IOException("stream failed")
    }

    private fun nativeSnapshot(directory: Path) = EnvironmentSnapshot(
        NATIVE_ENVIRONMENT_ID, "Native", LinuxEnvironmentType.NATIVE, "test", "test",
        "/bin/sh", directory.toString(), null, "test",
    )

    private fun prootSnapshot(rootfs: Path) = EnvironmentSnapshot(
        "proot", "PRoot", LinuxEnvironmentType.PROOT, "Arch Linux", "test",
        "/bin/bash", "/root", null, "test", rootfsPath = rootfs.toString(),
    )
}
