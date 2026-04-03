package dev.ujhhgtg.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.app.Application
import android.os.Build
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.utils.LibXposedApiByteCodeGenerator
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private val TAG = nameOf(StartupAgent)

    private var sInitialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        modulePath: String
    ) {
        if (sInitialized) return
        sInitialized = true

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
            WeLogger.w(TAG, "module path is writable: $modulePath\nThis may cause issues on Android 15+, please check your Xposed framework")
        }
    }

    fun getBaseApplication(): Application {
        runCatching {
            val tinkerAppClz = "com.tencent.tinker.loader.app.TinkerApplication".toClass()
            val getInstanceMethod = tinkerAppClz.getMethod("getInstance")
            return getInstanceMethod.invoke(null) as Application
        }.onFailure { WeLogger.e(TAG, "getBaseApplication: failed to call TinkerApplication.getInstance()", it) }

        runCatching {
            val app = ActivityThread.currentApplication()!!
            return app
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
