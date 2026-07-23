package dev.ujhhgtg.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskHookBridge
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private const val TAG = "StartupAgent"

    private val startupLock = Any()

    @Volatile
    private var initialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application
    ) = synchronized(startupLock) {
        if (initialized) return@synchronized

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader
        if (R.string.res_inject_success ushr 24 == 0x7f) {
            throw AssertionError("module resource package ID must not be 0x7f")
        }
        StartupInfo.modulePath = modulePath
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        ensureHiddenApiAccess()
        checkWriteXorExecuteForModulePath(modulePath)

        HostInfo.init(application)
        NativeLoader.init(application)
        if (hookBridge is ZygiskHookBridge) {
            runCatching { hookBridge.hideLoadedModuleLibraries() }
                .onSuccess { hidden ->
                    if (!hidden) WeLogger.w(TAG, "module native-library hiding was incomplete")
                }
                .onFailure { WeLogger.e(TAG, "failed to hide loaded module libraries", it) }
        }
        // FIXME: some people have hiding on, which causes false positives in signature verifier
//        SignatureVerifier.verify(application)
        WeLauncher.init(application)

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }

        // Only commit after every required startup phase completes. The caller
        // already logs a thrown failure, and a later lifecycle callback can retry.
        initialized = true
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
