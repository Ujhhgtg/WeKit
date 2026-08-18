package dev.ujhhgtg.wekit.extensions

import dalvik.system.InMemoryDexClassLoader
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

    override val id = ExtensionLock.ScriptDeps.ID
    override val pinnedVersion = ExtensionLock.ScriptDeps.VERSION
    override val pinnedSha256 = ExtensionLock.ScriptDeps.SHA256
    override val assetName = ExtensionLock.ScriptDeps.ASSET_NAME

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
        val versionDir = installDir().resolve(pinnedVersion)
        val manifest = PackFs.readManifest(versionDir) ?: return null
        if (manifest.version != pinnedVersion) return null
        val dex = versionDir.resolve("classes.dex")
        if (!dex.isFile) return null
        val dexBytes = Files.readAllBytes(dex.toPath())
        val loader = InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), ScriptDepsPack::class.java.classLoader)
        cachedLoader = loader
        return loader
    }

    override fun install(verifiedTmp: File) {
        val versionDir = installDir().resolve(pinnedVersion)
        versionDir.deleteRecursively()
        versionDir.mkdirs()
        PackFs.atomicReplace(verifiedTmp, versionDir.resolve("classes.dex"))
        PackFs.writeManifest(
            versionDir,
            PackManifest(id, pinnedVersion, pinnedSha256, System.currentTimeMillis()),
        )
    }
}
