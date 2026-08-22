package dev.ujhhgtg.wekit.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Extension
import dalvik.system.InMemoryDexClassLoader
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_API_VERSION
import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_ENTRYPOINT_V1
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGeneratorApiV1
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/** Isolated Monet resource-overlay generator DEX and its version-matched payload files. */
object MonetGeneratorPack : ExtensionPack {

    override val id = "monet-generator"
    override val nameRes = R.string.feature_monet_engine_name
    override val descriptionRes = R.string.feature_monet_engine_description
    override val icon: ImageVector = MaterialSymbols.Outlined.Extension

    private var cachedLoader: InMemoryDexClassLoader? = null
    @Volatile
    private var cachedGenerator: MonetGeneratorApiV1? = null
    private var loadedVersion: String? = null

    override fun installDir(): File =
        KnownPaths.moduleData.resolve("extensions/monet-generator").toFile()

    override fun stagingDir(): File =
        KnownPaths.moduleData.resolve("extensions/monet-generator/.staging").toFile()

    override fun isInUse(): Boolean = cachedGenerator != null

    @Synchronized
    fun generator(): MonetGeneratorApiV1? {
        cachedGenerator?.let { return it }
        val manifest = installedManifest() ?: return null
        val dex = installDir().resolve(manifest.version).resolve("classes.dex")
        if (!dex.isFile) return null
        val loader = InMemoryDexClassLoader(
            ByteBuffer.wrap(Files.readAllBytes(dex.toPath())),
            MonetGeneratorPack::class.java.classLoader,
        )
        val instance = loader.loadClass(MONET_GENERATOR_ENTRYPOINT_V1)
            .getDeclaredConstructor()
            .newInstance()
        require(instance is MonetGeneratorApiV1) { "incompatible Monet generator entrypoint" }
        cachedLoader = loader
        loadedVersion = manifest.version
        cachedGenerator = instance
        return instance
    }

    @Synchronized
    fun payloadDir(): File? {
        val version = loadedVersion ?: installedManifest()?.version ?: return null
        val payload = installDir().resolve(version).resolve("payload")
        return if (payload.isDirectory) payload else null
    }

    @Synchronized
    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        require(!isInUse()) { "cannot update Monet generator while it is in use" }
        val baseDir = installDir().also { it.mkdirs() }
        val staging = File(baseDir, ".$version-installing")
        val destination = baseDir.resolve(version)
        val previous = File(baseDir, ".$version-previous")
        if (!destination.exists() && previous.isDirectory) {
            require(previous.renameTo(destination)) { "cannot restore prior Monet generator $version" }
        }
        staging.deleteRecursively()
        previous.deleteRecursively()
        staging.mkdirs()
        try {
            MonetExtensionArchive.extractAndVerify(
                verifiedTmp,
                staging,
                MONET_GENERATOR_API_VERSION,
                MONET_GENERATOR_ENTRYPOINT_V1,
            )
            PackFs.writeManifest(
                staging,
                PackManifest(id, version, sha256, System.currentTimeMillis()),
            )
            if (destination.exists()) {
                require(destination.renameTo(previous)) {
                    "cannot preserve prior Monet generator $version"
                }
            }
            if (!staging.renameTo(destination)) {
                if (previous.exists()) {
                    require(previous.renameTo(destination)) {
                        "cannot restore prior Monet generator $version"
                    }
                }
                error("cannot publish Monet generator $version")
            }
            previous.deleteRecursively()
            sweepOtherVersions(version)
            WeLogger.i("MonetGeneratorPack", "installed Monet generator $version")
        } finally {
            staging.deleteRecursively()
            if (!destination.exists() && previous.isDirectory) previous.renameTo(destination)
        }
    }
}
