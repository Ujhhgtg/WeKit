package dev.ujhhgtg.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.app.Application
import android.os.Build
import com.tencent.tinker.loader.app.TinkerApplication
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
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

    private val TAG = This.Class.simpleName

    private var initialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String
    ) {
        if (initialized) return
        initialized = true

        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        ensureHiddenApiAccess()
        checkWriteXorExecuteForModulePath(modulePath)

        val ctx = getBaseApplication()
        HostInfo.init(ctx)
        LibXposedApiByteCodeGenerator.init()
        NativeLoader.init(ctx)
        WeLauncher.init(ctx)

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
            return TinkerApplication.getInstance()
        }.onFailure { WeLogger.e(TAG, "getBaseApplication: failed to call TinkerApplication.getInstance()", it) }

        runCatching {
            return ActivityThread.currentApplication()!!
        }.onFailure { WeLogger.e(TAG, "getBaseApplication: failed to call ActivityThread.currentApplication()", it) }

        error("failed to retrieve Application instance")
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
