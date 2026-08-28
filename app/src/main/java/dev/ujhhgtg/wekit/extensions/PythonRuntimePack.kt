package dev.ujhhgtg.wekit.extensions

import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Code
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.scripting_python.PythonScriptingFeature
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginManager
import dev.ujhhgtg.wekit.utils.HostInfo
import java.io.File

data class MountedPythonRuntime(
    val manifest: PackManifest,
    val directory: File,
    val runtimeApk: File,
    val nativeDirectory: File,
    val sdkDirectory: File,
    val metadata: PythonRuntimeMetadata,
)

object PythonRuntimePack : ExtensionPack {
    const val ID = "python-runtime"
    const val ABI = "arm64-v8a"

    override val id = ID
    override val displayOrder = 2
    override val nameRes = R.string.extensions_pack_python_runtime_name
    override val descriptionRes = R.string.extensions_pack_python_runtime_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Code

    private val lifecycleLock = Any()

    @Volatile
    private var mountedRuntime: MountedPythonRuntime? = null

    private val baseDir: File
        get() = File(HostInfo.application.filesDir, "wekit-extensions/$ID")

    override fun installDir(): File = baseDir

    override fun stagingDir(): File = File(baseDir, ".staging")

    override fun isSupported(): Boolean =
        Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains(ABI)

    override fun installedManifest(): PackManifest? = synchronized(lifecycleLock) {
        publishedVersionsLocked().maxByOrNull { it.first.installedAtEpochMs }?.first
    }

    override fun isInUse(): Boolean = mountedRuntime != null

    fun mounted(): MountedPythonRuntime? = mountedRuntime

    fun selectForMount(): MountedPythonRuntime? = synchronized(lifecycleLock) {
        mountedRuntime?.let { return@synchronized it }
        val (manifest, directory) = publishedVersionsLocked()
            .maxByOrNull { it.first.installedAtEpochMs }
            ?: return@synchronized null
        val runtimeApk = File(directory, "runtime.apk")
        require(PackFs.verify(runtimeApk, manifest.sha256)) { "Installed Python runtime SHA-256 mismatch" }
        val contents = PythonRuntimeArchive.inspect(
            runtimeApk,
            requireNotNull(manifest.meta) { "Installed Python runtime metadata is missing" },
        )
        val nativeDirectory = File(directory, "native")
        require(nativeDirectory.isDirectory) { "Python runtime native directory is missing" }
        contents.metadata.nativeLibraries.forEach { name ->
            require(File(nativeDirectory, name).isFile) { "Installed Python runtime is missing $name" }
        }
        val sdkDirectory = File(directory, "sdk")
        require(
            File(sdkDirectory, "wekit/__init__.py").isFile ||
                File(sdkDirectory, "wekit/__init__.pyc").isFile,
        ) { "Python runtime SDK is missing" }
        MountedPythonRuntime(manifest, directory, runtimeApk, nativeDirectory, sdkDirectory, contents.metadata)
            .also { mountedRuntime = it }
    }

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        synchronized(lifecycleLock) {
            require(version.isNotBlank() && !version.startsWith('.') && '/' !in version && '\\' !in version) {
                "Invalid Python runtime version: $version"
            }
            require(meta != null) { "Python runtime index metadata is missing" }
            baseDir.mkdirs()
            val staging = File(baseDir, ".$version-installing")
            val destination = File(baseDir, version)
            staging.deleteRecursively()
            require(staging.mkdirs()) { "Cannot create Python runtime staging directory" }
            try {
                val runtimeApk = File(staging, "runtime.apk")
                verifiedTmp.inputStream().use { input -> runtimeApk.outputStream().use(input::copyTo) }
                val contents = PythonRuntimeArchive.inspect(runtimeApk, meta)
                PythonRuntimeArchive.extractNativeLibraries(runtimeApk, contents, File(staging, "native"))
                PythonRuntimeArchive.extractSdk(runtimeApk, File(staging, "sdk"))
                val installedMeta = PythonRuntimeArchive.encodeMetadata(contents.metadata)
                PackFs.writeManifest(
                    staging,
                    PackManifest(ID, version, sha256, System.currentTimeMillis(), installedMeta),
                )

                if (destination.exists()) {
                    val existing = runCatching { PackFs.readManifest(destination) }.getOrNull()
                    require(existing?.sha256.equals(sha256, ignoreCase = true)) {
                        "Python runtime version $version already exists with different content"
                    }
                    staging.deleteRecursively()
                } else {
                    require(staging.renameTo(destination)) { "Cannot publish Python runtime $version" }
                }
                sweepUnusedVersionsLocked()
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    override fun deleteInstalled(): Boolean = synchronized(lifecycleLock) {
        if (mountedRuntime != null) return@synchronized false
        baseDir.deleteRecursively()
        !baseDir.exists()
    }

    override fun onInstalled() {
        if (PythonScriptingFeature.isActive) PythonPluginManager.activateDesired()
    }

    fun sweepUnusedVersions() = synchronized(lifecycleLock) { sweepUnusedVersionsLocked() }

    private fun sweepUnusedVersionsLocked() {
        val published = publishedVersionsLocked()
        val newest = published.maxByOrNull { it.first.installedAtEpochMs }?.first?.version
        val keep = setOfNotNull(newest, mountedRuntime?.manifest?.version)
        baseDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') && it.name !in keep }
            .forEach(File::deleteRecursively)
    }

    private fun publishedVersionsLocked(): List<Pair<PackManifest, File>> =
        baseDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { directory ->
                runCatching { PackFs.readManifest(directory) }
                    .getOrNull()
                    ?.takeIf { it.id == ID && it.version == directory.name }
                    ?.let { it to directory }
            }
}
