package moe.ouom.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.loader.abc.ILoaderService
import moe.ouom.wekit.loader.utils.LibXposedApiByteCodeGenerator
import moe.ouom.wekit.loader.utils.NativeLoader
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.utils.logging.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private val TAG = nameof(StartupAgent)

    private var sInitialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        modulePath: String,
        loaderService: ILoaderService
    ) {
        if (sInitialized) {
            WeLogger.w(TAG, "already initialized")
            return
        }
        sInitialized = true

        if (System.getProperty(TAG) == "true") {
            WeLogger.e(TAG, "WeKit reloaded??")
            return
        }

        System.setProperty(TAG, "true")
        StartupInfo.modulePath = modulePath
        StartupInfo.loaderService = loaderService

        ensureHiddenApiAccess()
        checkWriteXorExecuteForModulePath(modulePath)

        val ctx = getBaseApplication()
        HostInfo.init(ctx)
        LibXposedApiByteCodeGenerator.init()
        NativeLoader.init(ctx)
        WeLauncher.init(ctx.classLoader, ctx)
        runCatching {
            ctx.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }
    }

    private fun checkWriteXorExecuteForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "Module path is writable: $modulePath\nThis may cause issues on Android 15+, please check your Xposed framework")
        }
    }

    fun getBaseApplication(): Application {
        runCatching {
            val tinkerAppClz = "com.tencent.tinker.loader.app.TinkerApplication".toClass()
            val getInstanceMethod = tinkerAppClz.getMethod("getInstance")
            return getInstanceMethod.invoke(null) as Application
        }.onFailure { WeLogger.e(TAG, "getBaseApplication: failed to call TinkerApplication.getInstance()", it) }

        runCatching {
            val activityThreadClz = "android.app.ActivityThread".toClass()
            val currentAppMethod = activityThreadClz.getDeclaredMethod("currentApplication")
            currentAppMethod.isAccessible = true
            return currentAppMethod.invoke(null) as Application
        }.onFailure { WeLogger.e(TAG, "getBaseApplication: ActivityThread fallback failed", it) }

        error("failed to retrieve Application instance")
    }

    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            WeLogger.w(
                TAG,
                "Hidden API access not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
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
