package dev.ujhhgtg.wekit.utils.monet

import android.app.ResourcesManager
import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.ResourcesImpl
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.util.ArrayMap
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.utils.reflection.asHidden
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Injects the Monet runtime table straight into WeChat's [AssetManager]s.
 *
 * `Resources.addLoaders` is not enough: WeChat's `MMResources` (what every `MMActivity` returns
 * from `getResources()`) is built with the public `Resources(AssetManager, …)` constructor, so it
 * is unknown to `ResourcesManager`, rejects `addLoaders` and never follows a provider swap. It does
 * share its base AssetManager object, as does Tinker's patched AssetManager, so the loader is
 * placed on every WeChat-owned AssetManager in place (`AssetManager.setLoaders`, hidden) and the
 * Java-side caches of every `ResourcesImpl` using them are flushed after a swap.
 */
@RequiresApi(Build.VERSION_CODES.R)
object MonetAssetInjector {

    /** One loader for the process lifetime; swaps replace its providers, never the loader. */
    val loader = ResourcesLoader()

    private val lock = Any()
    private val assetManagers = WeakHashMap<AssetManager, Unit>()
    private val impls = WeakHashMap<ResourcesImpl, Unit>()
    private val canonicalPaths = HashMap<String, String>()
    private var ownedPaths: Set<String> = emptySet()

    /**
     * WeChat's resource APKs as actually loaded by [assets] (base, resource splits, or a Tinker
     * patched `resources.apk` under the data dir), base first. Loader assets, the framework and
     * shared libraries such as WebView are excluded.
     */
    fun hostResourceApks(assets: AssetManager, sourceDirs: List<String>, dataDirs: List<String>): List<String> {
        val sources = sourceDirs.mapTo(hashSetOf(), ::canonical)
        val dataRoots = dataDirs.map { canonical(it) + "/" }
        return assets.asHidden().apkAssets.filterNot(ApkAssets::isForLoader).map { it.assetPath }.filter { path ->
            val canonical = canonical(path)
            canonical in sources || dataRoots.any(canonical::startsWith)
        }
    }

    fun init(hostApks: List<String>) = synchronized(lock) {
        ownedPaths = hostApks.mapTo(hashSetOf(), ::canonical)
    }

    /** Adds the loader to [assets] if it belongs to WeChat; other packages' tables must stay untouched. */
    fun attach(assets: AssetManager): Unit = synchronized(lock) {
        if (!isOwned(assets)) return
        assetManagers[assets] = Unit
        val current = assets.asHidden().loaders
        if (loader !in current) setLoaders(assets, current + loader)
    }

    /**
     * Tracks [resources]' implementation for cache flushes. [flush] is for implementations that
     * existed before injection and may already hold stale WeChat drawables.
     */
    fun register(resources: Resources, flush: Boolean) =
        registerImpl(resources.asHidden().impl!!, flush)

    fun registerImpl(impl: ResourcesImpl, flush: Boolean): Unit = synchronized(lock) {
        val assets = impl.assets
        if (!isOwned(assets)) return
        attach(assets)
        val known = impl in impls
        impls[impl] = Unit
        if (flush && !known) flush(impl)
    }

    /** Every `ResourcesImpl` `ResourcesManager` currently knows about (not WeChat's wrappers). */
    fun managedImpls(): List<ResourcesImpl> {
        val manager = ResourcesManager.getInstance()
        @Suppress("UNCHECKED_CAST")
        val implMap = manager.reflekt().firstField { name = "mResourceImpls" }.get() as ArrayMap<*, WeakReference<ResourcesImpl>>
        // API 31+ guards the map with mLock; API 30 synchronizes on the manager itself.
        val managerLock = manager.reflekt().firstFieldOrNull { name = "mLock" }?.get() ?: manager
        return synchronized(managerLock) {
            implMap.values.mapNotNull { it.get() }
        }
    }

    /** Replaces the provider in place on every tracked AssetManager; `null` clears the theme. */
    @MainThread
    fun swap(provider: ResourcesProvider?) = synchronized(lock) {
        loader.setProviders(listOfNotNull(provider))
        // An implementation may have been handed a new AssetManager since (Tinker re-patching).
        impls.keys.toList().forEach { attach(it.assets) }
        assetManagers.keys.toList().forEach { assets -> setLoaders(assets, assets.asHidden().loaders) }
        impls.keys.toList().forEach(::flush)
    }

    private fun isOwned(assets: AssetManager): Boolean =
        assets.asHidden().apkAssets.any { !it.isForLoader && canonical(it.assetPath) in ownedPaths }

    // Members are looked up on the framework classes: OEM builds subclass ResourcesImpl, and
    // WeChat subclasses Resources.
    private fun flush(impl: ResourcesImpl) {
        CACHE_FIELDS.forEach { field ->
            val cache = ResourcesImpl::class.reflekt().firstField { name = field }.get(impl)!!
            cache.reflekt().firstMethod {
                name = "clear"
                parameterCount = 0
                superclass()
            }.invoke()
        }
        impl.flushLayoutCache()
    }

    // setLoaders is package-private, so reflection is still required
    private fun setLoaders(assets: AssetManager, loaders: List<ResourcesLoader>) {
        assets.reflekt().firstMethod { name = "setLoaders" }.invoke(loaders)
    }

    private fun canonical(path: String): String = synchronized(canonicalPaths) {
        canonicalPaths.getOrPut(path) { File(path).canonicalPath }
    }

    private val CACHE_FIELDS = listOf(
        "mDrawableCache",
        "mColorDrawableCache",
        "mComplexColorCache",
        "mAnimatorCache",
        "mStateListAnimatorCache",
    )
}
