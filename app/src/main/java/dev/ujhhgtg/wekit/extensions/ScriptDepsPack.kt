package dev.ujhhgtg.wekit.extensions

import dalvik.system.InMemoryDexClassLoader
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Extension
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * Java 脚本依赖扩展包:未混淆 DEX(fastjson2 + okhttp + kotlin-stdlib),供
 * Java 脚本引擎的每个解释器加载。由 [dev.ujhhgtg.wekit.features.items.scripting_java.JavaEngine]
 * 在 initPlugin 时挂载到 interpreter 的 classManager。
 */
object ScriptDepsPack : ExtensionPack {

    override val id = "script-deps"
    override val displayOrder = 0
    override val nameRes = R.string.extensions_pack_script_deps_name
    override val descriptionRes = R.string.extensions_pack_script_deps_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Extension

    private var cachedLoader: InMemoryDexClassLoader? = null

    override fun installDir(): File =
        KnownPaths.moduleData.resolve("extensions/script-deps").toFile()

    override fun stagingDir(): File =
        KnownPaths.moduleData.resolve("extensions/script-deps/.staging").toFile()

    override fun isInUse(): Boolean = cachedLoader != null

    /**
     * The mounted class loader for installed scripts, or null when the pack is
     * not installed. Loaded once per process; deletion requires a WeChat restart.
     */
    fun classLoader(): InMemoryDexClassLoader? {
        cachedLoader?.let { return it }
        val manifest = installedManifest() ?: return null
        val dex = installDir().resolve(manifest.version).resolve("classes.dex")
        if (!dex.isFile) return null
        val dexBytes = Files.readAllBytes(dex.toPath())
        val loader = InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), ScriptDepsPack::class.java.classLoader)
        cachedLoader = loader
        return loader
    }

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        val versionDir = installDir().resolve(version)
        versionDir.deleteRecursively()
        versionDir.mkdirs()
        PackFs.atomicReplace(verifiedTmp, versionDir.resolve("classes.dex"))
        PackFs.writeManifest(
            versionDir,
            PackManifest(id, version, sha256, System.currentTimeMillis()),
        )
        sweepOtherVersions(version)
    }
}
