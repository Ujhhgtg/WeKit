package dev.ujhhgtg.wekit.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Extension
import dalvik.system.DelegateLastClassLoader
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_API_VERSION
import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_ENTRYPOINT
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGeneratorApi
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File

/** Isolated Monet resource-overlay generator DEX and its version-matched payload files. */
object MonetGeneratorPack : ExtensionPack {

    override val id = "monet-generator"
    override val displayOrder = 1
    override val nameRes = R.string.extensions_pack_monet_generator_name
    override val descriptionRes = R.string.extensions_pack_monet_generator_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Extension

    @Volatile
    private var cachedResolution: Resolved? = null
    private var cachedLoader: DelegateLastClassLoader? = null

    override fun installDir(): File =
        KnownPaths.moduleData.resolve("extensions/monet-generator").toFile()

    override fun stagingDir(): File =
        KnownPaths.moduleData.resolve("extensions/monet-generator/.staging").toFile()

    override fun isInUse(): Boolean = cachedResolution != null

    @Synchronized
    internal fun resolve(): Resolved? {
        cachedResolution?.let { return it }
        val manifest = installedManifest() ?: return null
        val paths = MonetInstallPaths.resolve(installDir(), manifest.version)
        val metadata = MonetExtensionArchive.verifyInstalled(
            paths.destination,
            MONET_GENERATOR_API_VERSION,
            MONET_GENERATOR_ENTRYPOINT,
        )
        val installedDex = paths.destination.resolve("classes.dex")
        val payloadDir = paths.destination.resolve("payload")
        val dex = stageReadOnlyMonetDex(
            installedDex,
            KnownPaths.codeCacheDir
                .resolve("monet-generator")
                .resolve(manifest.version)
                .resolve("classes.dex")
                .toFile(),
            metadata.files.getValue("classes.dex"),
        )
        val loader = DelegateLastClassLoader(
            dex.absolutePath,
            MonetGeneratorPack::class.java.classLoader,
        )
        val instance = loader.loadClass(MONET_GENERATOR_ENTRYPOINT)
            .getDeclaredConstructor()
            .newInstance()
        require(instance is MonetGeneratorApi) { "incompatible Monet generator entrypoint" }
        cachedLoader = loader
        return Resolved(instance, payloadDir).also { cachedResolution = it }
    }

    @Synchronized
    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        require(!isInUse()) { "cannot update Monet generator while it is in use" }
        val paths = MonetInstallPaths.resolve(installDir(), version)
        paths.baseDir.mkdirs()
        val staging = paths.staging
        val destination = paths.destination
        staging.deleteRecursively()
        staging.mkdirs()
        MonetExtensionArchive.extractAndVerify(verifiedTmp, staging, MONET_GENERATOR_API_VERSION, MONET_GENERATOR_ENTRYPOINT)
        PackFs.writeManifest(staging, PackManifest(id, version, sha256, System.currentTimeMillis()))
        destination.deleteRecursively()
        require(staging.renameTo(destination)) { "cannot publish Monet generator $version" }
        sweepOtherVersions(version)
        WeLogger.i("MonetGeneratorPack", "installed Monet generator $version")
    }

    internal class Resolved(
        val generator: MonetGeneratorApi,
        val payloadDir: File,
    )
}
