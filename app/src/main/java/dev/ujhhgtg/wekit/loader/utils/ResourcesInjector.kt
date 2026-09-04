package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.annotation.RequiresApi
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.extensions.PackFs
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class InjectionHandle constructor(
    val canonicalApk: File,
    val sha256: String,
    val cookie: Int,
    val keepAlive: Any?,
)

object ResourcesInjector {

    private const val TAG = "ResourcesInjector"
    private const val UNREGISTERED_RESOURCES_ERROR =
        "Cannot modify resource loaders of ResourcesImpl not registered with ResourcesManager"
    private val handles = ConcurrentHashMap<String, InjectionHandle>()

    fun injectModuleRes(resources: Resources?) {
        resources ?: return
        if (hasModuleRes(resources)) return

        val moduleFile = File(StartupInfo.modulePath)
        runCatching { injectApk(resources, moduleFile) }
            .onFailure { logInjectionFailure(moduleFile.absolutePath, it, 0) }

        if (hasModuleRes(resources)) {
            WeLogger.d(TAG, "successfully injected module resources")
        } else {
            WeLogger.e(TAG, "failed to inject module resources")
        }
    }

    private fun hasModuleRes(resources: Resources): Boolean = try {
        resources.getValue(R.string.res_inject_success, TypedValue(), true)
        true
    } catch (_: Resources.NotFoundException) {
        false
    }

    fun injectApk(resources: Resources, apk: File, expectedSha256: String? = null): InjectionHandle {
        val canonical = apk.canonicalFile
        require(canonical.isFile && canonical.canRead()) { "APK is not readable: $canonical" }
        val sha256 = expectedSha256 ?: PackFs.sha256(canonical)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return injectResLt30(resources, canonical, sha256)
        }
        val key = "${canonical.absolutePath}:$sha256"
        val handle = handles[key] ?: synchronized(handles) {
            handles[key] ?: createHandle(canonical, sha256).also { handles[key] = it }
        }
        if (handle.keepAlive is ResourcesLoader) {
            try {
                resources.addLoaders(handle.keepAlive)
            } catch (error: IllegalArgumentException) {
                if (error.message != UNREGISTERED_RESOURCES_ERROR) throw error
                return injectResLt30(resources, canonical, sha256)
            }
        }
        return handle
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createHandle(apk: File, sha256: String): InjectionHandle {
        ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val provider = ResourcesProvider.loadFromApk(descriptor)
            val loader = ResourcesLoader().apply { addProvider(provider) }
            return InjectionHandle(apk, sha256, 0, loader)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    @Suppress("JavaReflectionMemberAccess")
    private fun injectResLt30(resources: Resources, apk: File, sha256: String): InjectionHandle {
        val addAssetPath = AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
        val cookie = addAssetPath.invoke(resources.assets, apk.absolutePath) as Int
        require(cookie != 0) { "AssetManager rejected ${apk.absolutePath}" }
        return InjectionHandle(apk, sha256, cookie, resources.assets)
    }

    private fun logInjectionFailure(path: String, error: Throwable, cookie: Int) {
        val moduleFile = File(path)
        WeLogger.e(
            TAG,
            "module resource injection failed: path=$path, cookie=$cookie, " +
                "loader=${ResourcesInjector::class.java.classLoader}, " +
                "exists=${moduleFile.exists()}, directory=${moduleFile.isDirectory}, " +
                "readable=${moduleFile.canRead()}, length=${moduleFile.length()}",
            error
        )
    }
}
