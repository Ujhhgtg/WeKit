package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class ArchLinuxInstance(
    val rootfs: File,
    val contentVersion: String,
    val bridgePath: String = "/usr/bin/invoke_tool",
    val workingDirectory: String = "/root",
)

object ArchLinuxInstanceInstaller {
    suspend fun install(
        instanceId: String,
        contentVersion: String,
        rootfsArchive: File,
        prootExecutable: File,
        prootLoaderExecutable: File,
        bridge: File,
        instancesDirectory: File,
        maxExtractedBytes: Long,
    ): ArchLinuxInstance = withContext(Dispatchers.IO) {
        require(instanceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid instance id" }
        require(
            rootfsArchive.isFile && bridge.isFile &&
                prootExecutable.isFile && prootExecutable.canExecute() &&
                prootLoaderExecutable.isFile && prootLoaderExecutable.canExecute()
        ) { "Arch template is corrupt" }
        require(instancesDirectory.mkdirs() || instancesDirectory.isDirectory) { "cannot create environment storage" }
        require(maxExtractedBytes > 0) { "invalid Arch extracted-size limit" }
        val required = Math.addExact(maxExtractedBytes, INSTALL_HEADROOM_BYTES)
        require(instancesDirectory.usableSpace >= required) { "insufficient storage for Arch Linux instance" }
        val destination = File(instancesDirectory, instanceId)
        require(!destination.exists()) { "environment instance already exists" }
        val staging = File(instancesDirectory, ".$instanceId-${UUID.randomUUID()}.staging")
        try {
            val rootfs = File(staging, "rootfs").apply { mkdirs() }
            rootfsArchive.inputStream().use {
                ArchiveExtractor.extractTarGz(
                    it,
                    rootfs.toPath(),
                    limits = ArchiveExtractor.Limits(maxTotalBytes = maxExtractedBytes),
                    checkActive = { coroutineContext.ensureActive() },
                )
            }
            val guestBridge = File(rootfs, "usr/bin/invoke_tool")
            requireNotNull(guestBridge.parentFile).mkdirs()
            Files.copy(bridge.toPath(), guestBridge.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            require(guestBridge.setExecutable(true, true)) { "cannot make invoke_tool executable" }

            val resolvConf = File(rootfs, "etc/resolv.conf")
            requireNotNull(resolvConf.parentFile).mkdirs()
            if (Files.isSymbolicLink(resolvConf.toPath())) {
                Files.delete(resolvConf.toPath())
            }
            resolvConf.writeText(
                "nameserver 1.1.1.1\n" +
                        "nameserver 8.8.8.8\n"
            )

            File(rootfs, "root").mkdirs()
            val healthPidFile = File(staging, "health.pid")
            val healthArgv = ProotCommand.execArgv(
                prootExecutable.toPath(), rootfs.toPath(), "/root",
                "test -x /bin/bash && test -x /usr/bin/invoke_tool", emptyMap(),
            )
            val health = ProcessBuilder(processWithPidFile(healthPidFile.toPath(), healthArgv))
                .directory(staging).redirectErrorStream(true).apply {
                environment()["PROOT_LOADER"] = prootLoaderExecutable.absolutePath
                environment()["PROOT_TMP_DIR"] = File(staging, "tmp").apply { mkdirs() }.absolutePath
            }.start()
            val deadline = System.nanoTime() + HEALTH_TIMEOUT_MILLIS * 1_000_000
            val healthOutput = ByteArrayOutputStream(MAX_HEALTH_OUTPUT_BYTES)
            val outputExceeded = AtomicBoolean()
            val outputReader = thread(name = "wekit-proot-health-output", isDaemon = true) {
                health.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val retained = minOf(count, MAX_HEALTH_OUTPUT_BYTES - healthOutput.size())
                        if (retained > 0) healthOutput.write(buffer, 0, retained)
                        if (retained < count) {
                            outputExceeded.set(true)
                            break
                        }
                    }
                }
            }
            try {
                while (health.isAlive && !outputExceeded.get() && System.nanoTime() < deadline) {
                    coroutineContext.ensureActive()
                    Thread.sleep(25)
                }
                if (health.isAlive) ProcessTermination.terminateTree(
                    health,
                    runCatching { healthPidFile.readText().trim().toInt() }.getOrNull(),
                )
                outputReader.join(2_000)
                val diagnostic = healthOutput.toByteArray().decodeToString().trim()
                require(!outputExceeded.get() && !health.isAlive && health.exitValue() == 0) {
                    "PRoot health check failed${diagnostic.takeIf(String::isNotEmpty)?.let { ": $it" } ?: ""}"
                }
            } finally {
                if (health.isAlive) ProcessTermination.terminateTree(
                    health,
                    runCatching { healthPidFile.readText().trim().toInt() }.getOrNull(),
                )
                health.inputStream.close()
                outputReader.join(2_000)
                healthPidFile.delete()
            }
            File(staging, PUBLISHED_MARKER).writeText(contentVersion)
            require(staging.renameTo(destination)) { "cannot publish Arch Linux instance" }
            ArchLinuxInstance(File(destination, "rootfs"), contentVersion)
        } catch (error: Throwable) {
            WeLogger.e(TAG, "installation failed for instance=$instanceId contentVersion=$contentVersion", error)
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { staging.deleteRecursively() }
        }
    }

    private const val TAG = "ArchLinuxInstanceInstaller"
    private const val INSTALL_HEADROOM_BYTES = 512L * 1024 * 1024
    private const val HEALTH_TIMEOUT_MILLIS = 30_000L
    private const val MAX_HEALTH_OUTPUT_BYTES = 64 * 1024
    internal const val PUBLISHED_MARKER = ".wekit-arch-published"
}
