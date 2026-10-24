package dev.ujhhgtg.wekit.loader.utils

import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.annotation.RequiresApi
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.extensions.PackFs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.asHidden
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.util.concurrent.ConcurrentHashMap

class InjectionHandle(
    val sha256: String,
    val cookie: Int,
    val keepAlive: Any,
)

object ResourcesInjector {
    private const val TAG = "ResourcesInjector"
    private const val UNREGISTERED_RESOURCES_ERROR =
        "Cannot modify resource loaders of ResourcesImpl not registered with ResourcesManager"
    private val handles = ConcurrentHashMap<String, InjectionHandle>()
    private lateinit var payloadApk: ParcelFileDescriptor
    private lateinit var payloadSha256: String

    fun configurePayload(apk: ParcelFileDescriptor, sha256: String) {
        payloadApk = apk
        payloadSha256 = sha256
    }

    fun injectModuleRes(resources: Resources?) {
        resources ?: return
        if (hasModuleRes(resources)) return
        injectApk(resources, payloadApk, payloadSha256)
        check(hasModuleRes(resources)) {
            "business resource sentinel is missing after FD injection"
        }
        WeLogger.d(TAG, "injected business resources from sealed APK $payloadSha256")
    }

    private fun hasModuleRes(resources: Resources): Boolean =
        try {
            resources.getValue(R.string.res_inject_success, TypedValue(), true)
            true
        } catch (_: Resources.NotFoundException) {
            false
        }

    /** Ordinary extension APKs keep their file input; business always uses the FD overload. */
    fun injectApk(
        resources: Resources,
        apk: File,
        expectedSha256: String? = null,
    ): InjectionHandle {
        val canonical = apk.canonicalFile
        require(canonical.isFile && canonical.canRead()) { "APK is not readable: $canonical" }
        val sha256 = expectedSha256 ?: PackFs.sha256(canonical)
        ParcelFileDescriptor.open(canonical, ParcelFileDescriptor.MODE_READ_ONLY).use {
            return injectApk(resources, it, sha256)
        }
    }

    fun injectApk(
        resources: Resources,
        apk: ParcelFileDescriptor,
        sha256: String,
    ): InjectionHandle {
        val handle =
            handles[sha256]
                ?: synchronized(handles) {
                    handles[sha256] ?: createHandle(apk, sha256).also { handles[sha256] = it }
                }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attachLoader(resources, handle.keepAlive as ResourcesLoader)
        } else {
            val assets = resources.assets
            val current = assets.asHidden().apkAssets
            if (current.none { it === handle.keepAlive }) {
                val array = ReflectArray.newInstance(handle.keepAlive.javaClass, current.size + 1)
                System.arraycopy(current, 0, array, 0, current.size)
                ReflectArray.set(array, current.size, handle.keepAlive)
                AssetManager::class
                    .java
                    .reflekt()
                    .firstMethod {
                        name = "setApkAssets"
                        parameterCount = 2
                    }
                    .invoke(assets, array, true)
            }
        }
        return handle
    }

    private fun createHandle(apk: ParcelFileDescriptor, sha256: String): InjectionHandle {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return createLoaderHandle(apk, sha256)
        val apkAssetsClass = Class.forName("android.content.res.ApkAssets")
        val assets =
            apkAssetsClass
                .reflekt()
                .firstMethod {
                    name = "loadFromFd"
                    parameterCount = 4
                }
                .invoke(apk.fileDescriptor, "wekit:$sha256", false, false)!!
        return InjectionHandle(sha256, 0, assets)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createLoaderHandle(apk: ParcelFileDescriptor, sha256: String): InjectionHandle {
        val provider = ResourcesProvider.loadFromApk(apk)
        val loader = ResourcesLoader().apply { addProvider(provider) }
        return InjectionHandle(sha256, 0, loader)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun attachLoader(resources: Resources, loader: ResourcesLoader) {
        try {
            resources.addLoaders(loader)
        } catch (error: IllegalArgumentException) {
            if (error.message != UNREGISTERED_RESOURCES_ERROR) throw error
            val assets = resources.assets
            val current = assets.asHidden().loaders
            if (loader !in current) {
                AssetManager::class
                    .java
                    .reflekt()
                    .firstMethod {
                        name = "setLoaders"
                        parameterCount = 1
                    }
                    .invoke(assets, current + loader)
            }
        }
    }
}
