package dev.ujhhgtg.wekit.agent.environment

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
        proot: File,
        prootLoader: File,
        bridge: File,
        instancesDirectory: File,
    ): ArchLinuxInstance = withContext(Dispatchers.IO) {
        require(instanceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid instance id" }
        require(rootfsArchive.isFile && proot.isFile && prootLoader.isFile && bridge.isFile) { "Arch template is corrupt" }
        require(instancesDirectory.mkdirs() || instancesDirectory.isDirectory) { "cannot create environment storage" }
        val required = rootfsArchive.length().coerceAtLeast(1) * 3
        require(instancesDirectory.usableSpace >= required) { "insufficient storage for Arch Linux instance" }
        val destination = File(instancesDirectory, instanceId)
        require(!destination.exists()) { "environment instance already exists" }
        val staging = File(instancesDirectory, ".$instanceId-${UUID.randomUUID()}.staging")
        try {
            val rootfs = File(staging, "rootfs").apply { mkdirs() }
            rootfsArchive.inputStream().use {
                ArchiveExtractor.extractTarGz(it, rootfs.toPath(), checkActive = { coroutineContext.ensureActive() })
            }
            val hostBin = File(staging, "bin").apply { mkdirs() }
            Files.copy(proot.toPath(), File(hostBin, "proot").toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            require(File(hostBin, "proot").setExecutable(true, true)) { "cannot make PRoot executable" }
            Files.copy(prootLoader.toPath(), File(hostBin, "loader").toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            require(File(hostBin, "loader").setExecutable(true, true)) { "cannot make PRoot loader executable" }
            val guestBridge = File(rootfs, "usr/bin/invoke_tool")
            requireNotNull(guestBridge.parentFile).mkdirs()
            Files.copy(bridge.toPath(), guestBridge.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            require(guestBridge.setExecutable(true, true)) { "cannot make invoke_tool executable" }
            File(rootfs, "etc/resolv.conf").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }
            File(rootfs, "root").mkdirs()
            val health = ProcessBuilder(
                ProotCommand.execArgv(
                    File(hostBin, "proot").toPath(), rootfs.toPath(), "/root",
                    "test -x /bin/bash && test -x /usr/bin/invoke_tool", emptyMap(),
                )
            ).directory(staging).redirectErrorStream(true).apply {
                environment()["PROOT_LOADER"] = File(hostBin, "loader").absolutePath
                environment()["PROOT_TMP_DIR"] = File(staging, "tmp").apply { mkdirs() }.absolutePath
            }.start()
            val healthOutput = health.inputStream.bufferedReader().use { it.readText() }
            require(health.waitFor() == 0) { "PRoot health check failed: ${healthOutput.trim()}" }
            require(staging.renameTo(destination)) { "cannot publish Arch Linux instance" }
            ArchLinuxInstance(File(destination, "rootfs"), contentVersion)
        } finally {
            staging.deleteRecursively()
        }
    }
}
