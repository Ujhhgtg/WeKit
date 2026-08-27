package dev.ujhhgtg.wekit.extensions

import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Terminal
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.environment.ArchLinuxInstance
import dev.ujhhgtg.wekit.agent.environment.ArchLinuxInstanceInstaller
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ArchLinuxPackNotInstalledException : IllegalStateException("Arch Linux ARM64 extension pack is not installed")

object ArchLinuxPack : ExtensionPack {
    override val id = "archlinux-arm64"
    override val displayOrder = 3
    override val nameRes = R.string.extensions_pack_archlinux_name
    override val descriptionRes = R.string.extensions_pack_archlinux_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Terminal

    private const val ROOTFS = "ArchLinuxARM-aarch64-rootfs.tar.gz"
    private const val BRIDGE = "invoke_tool"
    private const val SOURCE_MANIFEST = "source-manifest.json"

    private val baseDir: File get() = File(HostInfo.application.filesDir, "wekit-extensions/$id")
    override fun installDir(): File = baseDir
    override fun stagingDir(): File = File(baseDir, ".staging")
    override fun isInUse(): Boolean = false

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        val staging = File(baseDir, ".$version-installing").apply { deleteRecursively(); mkdirs() }
        ZipFile(verifiedTmp).use { archive ->
            val manifestBytes = archive.getInputStream(archive.getEntry("manifest.json")).readBytes()
            for (name in listOf(ROOTFS, BRIDGE)) {
                val entry = archive.getEntry(name) ?: error("Arch pack is missing $name")
                val output = File(staging, name)
                archive.getInputStream(entry).use { input -> output.outputStream().use(input::copyTo) }
                if (name != ROOTFS) output.setExecutable(true, true)
            }
            File(staging, SOURCE_MANIFEST).writeBytes(manifestBytes)
        }
        PackFs.writeManifest(staging, PackManifest(id, version, sha256, System.currentTimeMillis()))
        val destination = File(baseDir, version).apply { deleteRecursively() }
        require(staging.renameTo(destination)) { "cannot publish Arch template" }
        sweepOtherVersions(version)
    }

    suspend fun createInstance(instanceId: String): ArchLinuxInstance {
        require(Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
            "Arch Linux PRoot requires an ARM64 process and device"
        }
        val manifest = installedManifest() ?: throw ArchLinuxPackNotInstalledException()
        val template = File(baseDir, manifest.version)
        val source = Json.parseToJsonElement(File(template, SOURCE_MANIFEST).readText()).jsonObject["source"]!!.jsonObject
        val maxExtractedBytes = source["rootfs_max_extracted_bytes"]!!.jsonPrimitive.content.toLong()
        return ArchLinuxInstanceInstaller.install(
            instanceId = instanceId,
            contentVersion = manifest.version,
            rootfsArchive = File(template, ROOTFS),
            prootExecutable = NativeLoader.prootExecutable(),
            prootLoaderExecutable = NativeLoader.prootLoaderExecutable(),
            bridge = File(template, BRIDGE),
            instancesDirectory = File(HostInfo.application.filesDir, "wekit-agent/environment/instances"),
            maxExtractedBytes = maxExtractedBytes,
        )
    }
}
