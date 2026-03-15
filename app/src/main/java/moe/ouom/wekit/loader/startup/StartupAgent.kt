package moe.ouom.wekit.loader.startup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.loader.hookapi.ILoaderService
import moe.ouom.wekit.utils.logging.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field

object StartupAgent {

    private val TAG = nameof(StartupAgent)

    private var sInitialized = false

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
        StartupInfo.setModulePath(modulePath)
        StartupInfo.setLoaderService(loaderService)

        ensureHiddenApiAccess()
        checkWriteXorExecuteForModulePath(modulePath)
        val ctx = getBaseApplication()

        StartupHook.initializeAfterAppCreate(ctx)
    }

    private fun checkWriteXorExecuteForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            Log.w(BuildConfig.TAG, "Module path is writable: $modulePath")
            Log.w(
                BuildConfig.TAG,
                "This may cause issues on Android 15+, please check your Xposed framework"
            )
        }
    }

    fun getBaseApplication(): Context {
        try {
            val tinkerAppClz = "com.tencent.tinker.loader.app.TinkerApplication".toClass()
            val getInstanceMethod = tinkerAppClz.getMethod("getInstance")
            val app = getInstanceMethod.invoke(null) as? Context
            if (app != null) return app
        } catch (e: Throwable) {
            Log.w(BuildConfig.TAG, "Failed to call TinkerApplication.getInstance()", e)
        }

        try {
            @SuppressLint("PrivateApi")
            val activityThreadClz = "android.app.ActivityThread".toClass()

            @SuppressLint("DiscouragedPrivateApi")
            val currentAppMethod = activityThreadClz.getDeclaredMethod("currentApplication")
            currentAppMethod.isAccessible = true
            val app = currentAppMethod.invoke(null) as? Context
            if (app != null) return app
        } catch (e: Exception) {
            WeLogger.e("getBaseApplication: ActivityThread fallback failed", e)
        }

        throw UnsupportedOperationException("Failed to retrieve Application instance.")
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            Log.w(
                BuildConfig.TAG,
                "Hidden API access not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
            )
            HiddenApiBypass.setHiddenApiExemptions("L")
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    fun isHiddenApiAccessible(): Boolean {
        val kContextImpl = try {
            Class.forName("android.app.ContextImpl")
        } catch (_: ClassNotFoundException) {
            return false
        }

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
