package dev.ujhhgtg.wekit.bootstrap

import android.app.LocaleManager
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import dev.ujhhgtg.reflekt.reflekt
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Array as ReflectArray

/** Public bootstrap UI resources; no protected business APK is needed to bind or show an error. */
object BootstrapResources {
    private const val UNREGISTERED_RESOURCES_ERROR =
        "Cannot modify resource loaders of ResourcesImpl not registered with ResourcesManager"
    private var keepAlive: Any? = null

    fun initialize(context: Context, apk: File) {
        if (keepAlive == null) {
            // The framework's public source is trusted; don't hash the entire APK on the UI thread.
            val source = apk.canonicalFile
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                keepAlive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) createLoader(fd) else {
                    Class.forName("android.content.res.ApkAssets").reflekt().firstMethod {
                        name = "loadFromFd"
                        parameters(FileDescriptor::class.java, String::class.java,
                            Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
                    }.invoke(fd.fileDescriptor, "wekit-bootstrap:${source.absolutePath}", false, false)!!
                }
            }
        }
        applyTo(context.resources)
        // Fail at UI setup if the public source was not accepted, before composing SDK resource reads.
        context.resources.getString(R.string.res_inject_success)
    }

    fun getString(context: Context, bootstrapApk: File, @StringRes id: Int, vararg args: Any): String {
        initialize(context, bootstrapApk)
        return systemContext(context).getString(id, *args)
    }

    /** Host app language overrides must not select the bootstrap UI's language. */
    fun systemContext(base: Context): Context {
        val systemLocales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.getSystemService(LocaleManager::class.java).systemLocales
        } else Resources.getSystem().configuration.locales
        if (base.resources.configuration.locales == systemLocales) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(systemLocales)
        }).also { applyTo(it.resources) }
    }

    /** Configuration contexts need their own attachment, especially the API29 AssetManager path. */
    fun applyTo(resources: Resources) {
        val handle = keepAlive ?: return // Installed MainActivity already has bootstrap resources.
        val assets = resources.assets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attachLoader(resources, handle as ResourcesLoader)
        } else {
            val current = AssetManager::class.java.reflekt().firstMethod {
                name = "getApkAssets"
                parameters()
            }.invoke(assets) as Array<*>
            if (current.any { it === handle }) return
            val combined = ReflectArray.newInstance(handle.javaClass, current.size + 1)
            System.arraycopy(current, 0, combined, 0, current.size)
            ReflectArray.set(combined, current.size, handle)
            AssetManager::class.java.reflekt().firstMethod { name = "setApkAssets"; parameterCount = 2 }
                .invoke(assets, combined, true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createLoader(fd: ParcelFileDescriptor): ResourcesLoader = ResourcesLoader().apply {
        addProvider(ResourcesProvider.loadFromApk(fd))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun attachLoader(resources: Resources, loader: ResourcesLoader) {
        try {
            resources.addLoaders(loader)
        } catch (error: IllegalArgumentException) {
            if (error.message != UNREGISTERED_RESOURCES_ERROR) throw error
            val assets = resources.assets
            val current = AssetManager::class.java.reflekt().firstMethod { name = "getLoaders"; parameters() }
                .invoke(assets) as List<*>
            if (loader !in current) {
                AssetManager::class.java.reflekt().firstMethod { name = "setLoaders"; parameterCount = 1 }
                    .invoke(assets, current + loader)
            }
        }
    }
}
