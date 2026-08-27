package dev.ujhhgtg.wekit.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Cloud
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.ReadReceipts
import dev.ujhhgtg.wekit.features.items.chat.ReadReceiptsServerMode
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.zip.ZipFile

/** Thrown when the built-in read-receipts backend needs cloudflared but it is not installed. */
class CloudflaredPackNotInstalledException(message: String) : RuntimeException(message)

/**
 * Cloudflared 扩展包：解压 arm64-v8a 的 libwekit_cloudflared.so
 * 到应用内部存储(dlopen 要求),由 NativeLoader.ensureCloudflaredLoaded() System.load。
 */
object CloudflaredPack : ExtensionPack {

    override val id = "cloudflared"
    override val displayOrder = 2
    override val nameRes = R.string.extensions_pack_cloudflared_name
    override val descriptionRes = R.string.extensions_pack_cloudflared_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Cloud

    private const val LIB_NAME = "libwekit_cloudflared.so"
    private const val ABI = "arm64-v8a"

    /** The error callers rethrow after showing the install dialog. */
    val notInstalledError: CloudflaredPackNotInstalledException
        get() = CloudflaredPackNotInstalledException("cloudflared extension pack is not installed")

    private val baseDir: File
        get() = File(HostInfo.application.filesDir, "wekit-extensions/cloudflared")

    override fun installDir(): File = baseDir

    override fun stagingDir(): File = File(baseDir, ".staging")

    /** Current-ABI library file, or null when not installed. */
    fun libraryFile(): File? {
        val manifest = installedManifest() ?: return null
        val lib = baseDir.resolve(manifest.version).resolve(LIB_NAME)
        return if (lib.isFile) lib else null
    }

    override fun isInUse(): Boolean =
        NativeLoader.isCloudflaredLoaded() ||
            ReadReceipts.configuration().mode == ReadReceiptsServerMode.BUILT_IN

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        val versionDir = baseDir.resolve(version).apply { deleteRecursively(); mkdirs() }

        ZipFile(verifiedTmp).use { zip ->
            val entry = zip.getEntry("$ABI/$LIB_NAME") ?: error("cloudflared pack has no library for $ABI")
            zip.getInputStream(entry).use { input ->
                File(versionDir, LIB_NAME).outputStream().use { output -> input.copyTo(output) }
            }
            File(versionDir, LIB_NAME).setReadable(true, true)
            File(versionDir, LIB_NAME).setExecutable(true, true)
        }
        PackFs.writeManifest(
            versionDir,
            PackManifest(id, version, sha256, System.currentTimeMillis()),
        )
        sweepOtherVersions(version)
        WeLogger.i("CloudflaredPack", "installed cloudflared $version")
    }
}
