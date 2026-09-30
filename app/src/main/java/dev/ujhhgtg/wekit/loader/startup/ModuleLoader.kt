package dev.ujhhgtg.wekit.loader.startup

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import dalvik.system.InMemoryDexClassLoader
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.core.FeaturesLoader
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.environment.EnvironmentHider
import dev.ujhhgtg.wekit.loader.startup.ModuleLoader.launch
import dev.ujhhgtg.wekit.loader.startup.ModuleLoader.startup
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field

/**
 * The module's startup flow, shared by every loader entry point:
 *
 * 1. [init] — called by an entry point: installs [HybridClassLoader] as the module loader's
 *    parent and hooks the host `Application` creation.
 * 2. [startup] — after `Instrumentation.callApplicationOnCreate`: host info, native libraries.
 * 3. [launch] — injects resources and loads features.
 */
object ModuleLoader {

    private const val TAG = "ModuleLoader"

    /** Guards both stages; the flags are only read and written while holding it. */
    private val lock = Any()
    private var isInitialized = false
    private var isStarted = false

    // ---------------------------------------------------------------------------
    //  1. Entry
    // ---------------------------------------------------------------------------

    @JvmStatic
    fun init(
        initialClassLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge,
        modulePath: String,
    ): Boolean = synchronized(lock) {
        if (isInitialized) return@synchronized true

        try {
            NativeLoader.initDecoder(modulePath)
        } catch (t: Throwable) {
            // The normal logger may itself contain protected strings.
            Log.e("WeKit", "string decoder bootstrap failed", t)
            return@synchronized false
        }

        try {
            WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
            // Published before any hook is installed: the hook helpers read it.
            StartupInfo.hookBridge = hookBridge
            installHybridClassLoader()
            hookApplicationCreate(loaderService, hookBridge, initialClassLoader, modulePath)
            isInitialized = true
            true
        } catch (t: Throwable) {
            // Do not poison this process's loader state: a later lifecycle
            // callback may have a usable host class loader.
            WeLogger.e(TAG, "module entry failed", t)
            false
        }
    }

    private fun installHybridClassLoader() {
        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        if (self is InMemoryDexClassLoader) {
            // The Zygisk payload's parent is the system loader. Keep the payload loader
            // separately so HybridClassLoader can search its DEX without parent delegation.
            HybridClassLoader.moduleClassLoader = self
        }
        HybridClassLoader.moduleParentClassLoader = selfParent
        self.reflekt()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)
    }

    private fun hookApplicationCreate(
        loaderService: ILoaderService,
        hookBridge: IHookBridge,
        initialClassLoader: ClassLoader,
        modulePath: String,
    ) {
        "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                val context = thisObject as Context
                EnvironmentHider.install(context, modulePath)
                val currentClassLoader = context.classLoader
                "android.app.Instrumentation".toClass(currentClassLoader).reflekt()
                    .firstMethod("callApplicationOnCreate").hookAfterDirectly {
                        runCatching {
                            startup(
                                loaderService,
                                hookBridge,
                                modulePath,
                                args[0] as Application
                            )
                        }.onFailure { WeLogger.e(TAG, "startup failed", it) }
                    }
            }
    }

    // ---------------------------------------------------------------------------
    //  2. Startup
    // ---------------------------------------------------------------------------

    private fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge,
        modulePath: String,
        application: Application
    ) = synchronized(lock) {
        if (isStarted) {
            return@synchronized
        }

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader

        if (R.string.res_inject_success ushr 24 == 0x7f) {
            throw AssertionError("module resource package ID must not be 0x7f")
        }

        ensureHiddenApiAccess()
        StartupInfo.modulePath = modulePath
        StartupInfo.loaderService = loaderService

        HostInfo.init(application)
        NativeLoader.init()
        EnvironmentHider.afterNativeLoad(hookBridge)

        launch(application)

        // Only commit after every required startup phase completes. The caller
        // already logs a thrown failure, and a later lifecycle callback can retry.
        isStarted = true
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

    private fun isHiddenApiAccessible(): Boolean {
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

    // ---------------------------------------------------------------------------
    //  3. Launch
    // ---------------------------------------------------------------------------

    private fun launch(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init()

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        WeKitLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }
}
