package dev.ujhhgtg.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.SignatureVerifier
import dev.ujhhgtg.wekit.utils.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private val TAG = This.Class.simpleName

    private var initialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application,
        realClassLoader: ClassLoader
    ) {
        if (initialized) return
        initialized = true

        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        ensureHiddenApiAccess()
        checkWriteXorExecuteForModulePath(modulePath)

        HostInfo.init(application)
        NativeLoader.init(application)
        SignatureVerifier.verify(application)
        WeLauncher.init(application)

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }
    }

    private fun checkWriteXorExecuteForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nThis may cause issues on Android 15+, please check your Xposed framework")
        }
    }

    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            WeLogger.w(
                TAG,
                "hidden api is not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
            )
            HiddenApiBypass.setHiddenApiExemptions("L")
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    fun isHiddenApiAccessible(): Boolean {
        val kContextImpl = runCatching {
            Class.forName("android.app.ContextImpl")
        }.getOrElse { return false }

        var mActivityToken: Field? = null
        var mToken: Field? = null

        try {
            mActivityToken = kContextImpl.getDeclaredField("mActivityToken")
        } catch (_: NoSuchFieldException) {
        }
        try {
            mToken = kContextImpl.getDeclaredField("mToken")
        } catch (_: NoSuchFieldException) {
        }

        return mActivityToken != null || mToken != null
    }
}
