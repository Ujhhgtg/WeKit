package dev.ujhhgtg.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge
import dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskLoaderService
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.data.LegacyDocumentMigration
import dev.ujhhgtg.wekit.data.JsonDataMigration
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcess
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.LegacyStorageMigration
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
        if (initialized) {
            return@synchronized
        }

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
        if (loaderService !is ZygiskLoaderService) {
            checkWxForModulePath(modulePath)
        }

        HostInfo.init(application)
        NativeLoader.init()
        // Legacy storage and document migrations are one-shot writers.  Only the main WeChat
        // process owns that responsibility; push/worker processes may still open Room later,
        // but must not race the migration or initialize it from an isolated process.
        if (TargetProcesses.isInMain) {
            LegacyStorageMigration.run(application)
            LegacyDocumentMigration.run(application)
            JsonDataMigration.run()
        }
        if (hookBridge is ArtHookBridge) {
            hideModuleLibraries(hookBridge)
        }
        // Isolated processes have no module features and must never touch the shared Room file.
        // In particular, FeaturesLoader would otherwise initialize DexCache/Room while the main
        // process is still relocating the legacy database.
        if (TargetProcesses.currentType != TargetProcess.ISOLATED) {
            WeLauncher.init(application)
        }

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }

        // Only commit after every required startup phase completes. The caller
        // already logs a thrown failure, and a later lifecycle callback can retry.
        initialized = true
    }

    private fun hideModuleLibraries(hookBridge: ArtHookBridge) {
        runCatching { hookBridge.hideLoadedModuleLibraries() }
            .onSuccess { hidden ->
                WeLogger.i(
                    TAG,
                    "hid loaded module libraries"
                )
                if (!hidden) WeLogger.w(TAG, "module native-library hiding was incomplete")
            }
            .onFailure {
                WeLogger.e(TAG, "failed to hide module libraries", it)
            }
    }

    private fun checkWxForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nthis may cause issues on Android 15+, please check your Xposed framework")
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
