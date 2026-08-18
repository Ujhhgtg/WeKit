package dev.ujhhgtg.wekit.extensions

import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Cloud
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/** Thrown when the built-in read-receipts backend needs cloudflared but it is not installed. */
class CloudflaredPackNotInstalledException(message: String) : RuntimeException(message)

/**
 * Cloudflared 扩展包:双 ABI zip,安装时仅解压当前 ABI 的 libwekit_cloudflared.so
 * 到应用内部存储(dlopen 要求),由 NativeLoader.ensureCloudflaredLoaded() System.load。
 */
object CloudflaredPack : ExtensionPack {

    override val id = ExtensionLock.Cloudflared.ID
    override val pinnedVersion = ExtensionLock.Cloudflared.VERSION
    override val pinnedSha256 = ExtensionLock.Cloudflared.SHA256
    override val assetName = ExtensionLock.Cloudflared.ASSET_NAME
    override val nameRes = R.string.extensions_pack_cloudflared_name
    override val descriptionRes = R.string.extensions_pack_cloudflared_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Cloud

    private const val LIB_NAME = "libwekit_cloudflared.so"

    /** The error callers rethrow after showing the install dialog. */
    val notInstalledError: CloudflaredPackNotInstalledException
        get() = CloudflaredPackNotInstalledException(
            "cloudflared extension pack $pinnedVersion is not installed"
        )

    private val baseDir: File
        get() = File(HostInfo.application.filesDir, "wekit-extensions/cloudflared")

    override fun installDir(): File = baseDir

    override fun stagingDir(): File = File(baseDir, ".staging")

    /** Current-ABI library file, or null when not installed. */
    fun libraryFile(): File? {
        if (!isInstalled()) return null
        val lib = baseDir.resolve(pinnedVersion).resolve(LIB_NAME)
        return if (lib.isFile) lib else null
    }

    override fun isInUse(): Boolean = NativeLoader.isCloudflaredLoaded()

    override fun install(verifiedTmp: File) {
        val versionDir = baseDir.resolve(pinnedVersion)
        versionDir.deleteRecursively()
        versionDir.mkdirs()

        val abi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        ZipFile(verifiedTmp).use { zip ->
            // Inner manifest (written by xtask): per-file sha256 for post-extraction re-verification.
            val manifestEntry = zip.getEntry("manifest.json") ?: error("cloudflared pack has no inner manifest")
            val hashes = Json.parseToJsonElement(zip.getInputStream(manifestEntry).readBytes().decodeToString())
                .jsonObject["files"]!!.jsonObject
                .mapValues { it.value.jsonPrimitive.content }

            val entry = zip.getEntry("$abi/$LIB_NAME") ?: error("cloudflared pack has no library for $abi")
            val tmpSo = File(versionDir, "$LIB_NAME.tmp")
            zip.getInputStream(entry).use { input ->
                tmpSo.outputStream().use { output -> input.copyTo(output) }
            }
            if (!PackFs.verify(tmpSo, hashes.getValue("$abi/$LIB_NAME"))) {
                tmpSo.delete()
                error("inner manifest SHA-256 mismatch for $abi/$LIB_NAME")
            }
            val dst = File(versionDir, LIB_NAME)
            tmpSo.setReadable(true, true)
            tmpSo.setExecutable(true, true)
            PackFs.atomicReplace(tmpSo, dst)
        }
        PackFs.writeManifest(
            versionDir,
            PackManifest(id, pinnedVersion, pinnedSha256, System.currentTimeMillis()),
        )
        WeLogger.i("CloudflaredPack", "installed cloudflared $pinnedVersion")
    }
}
