@file:Suppress("unused")

package dev.ujhhgtg.wekit.loader.entry.zygisk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.Keep
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygisk JVM entry point.
 *
 * Called from C++ postAppSpecialize via the FunBox-compatible shape:
 *   ZygiskEntry.init(processName, dataDir, copiedApkPath)
 *
 * The C++ side has already:
 *   1. Copied the active module's APK and every classes*.dex payload into
 *      this app's data directory during preAppSpecialize.
 *   2. Loaded those copied DEX files through InMemoryDexClassLoader and
 *      registered the native methods used by ZygiskHookBridge.
 */
@Keep
object ZygiskEntry {

    private const val TAG = "ZygiskEntry"
    private val entryLock = Any()
    private val moduleStarted = AtomicBoolean(false)
    private val finalClassLoaderHookInstalled = AtomicBoolean(false)
    private var loaderService: ZygiskLoaderService? = null
    private var hookBridge: ZygiskHookBridge? = null
    private var hostDataDir: String = ""
    private var modulePath: String = ""

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    @Keep
    @JvmStatic
    fun init(
        processName: String,
        dataDir: String,
        apkPath: String,
    ) {
        val targetPackage = processName.substringBefore(':')
        if (!PackageNames.isWeChat(targetPackage)) {
            Log.w(TAG, "ignoring unsupported Zygisk target: $targetPackage")
            return
        }
        synchronized(entryLock) {
            if (hookBridge != null) return

            try {
                Log.i(TAG, "ZygiskEntry.init: process=$processName apk=$apkPath dataDir=$dataDir")
                NativeLoader.configureZygiskPayload(apkPath, dataDir)
                val service = ZygiskLoaderService(
                    modulePath = apkPath,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
                val bridge = ZygiskHookBridge()
                hostDataDir = dataDir
                modulePath = apkPath
                loaderService = service
                hookBridge = bridge

                // FunBox waits at LoadedApk.createAppFactory. The ClassLoader
                // parameter here is only AOSP's default loader; the final
                // mClassLoader is selected by AppComponentFactory below.
                val loadedApk = Class.forName("android.app.LoadedApk")
                val createAppFactory = loadedApk.getDeclaredMethod(
                    "createAppFactory",
                    ApplicationInfo::class.java,
                    ClassLoader::class.java,
                ).apply { isAccessible = true }

                bridge.hookMethod(createAppFactory, object : dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) = Unit

                    override fun afterHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) {
                        if (param.throwable != null) return
                        val appInfo = param.args.getOrNull(0) as? ApplicationInfo ?: return
                        if (appInfo.packageName != targetPackage) return
                        val factory = param.result ?: return
                        installFinalClassLoaderHook(bridge, factory, targetPackage)
                    }
                }, priority = 10000)
            } catch (t: Throwable) {
                // All fields are published before the hook can become reachable;
                // roll them back when installation itself fails so init can retry.
                loaderService = null
                hookBridge = null
                hostDataDir = ""
                modulePath = ""
                moduleStarted.set(false)
                finalClassLoaderHookInstalled.set(false)
                Log.e(TAG, "ZygiskEntry.init failed", t)
            }
        }
    }

    private fun installFinalClassLoaderHook(
        bridge: ZygiskHookBridge,
        appComponentFactory: Any,
        targetPackage: String,
    ) {
        if (!finalClassLoaderHookInstalled.compareAndSet(false, true)) return
        try {
            val instantiateClassLoader = appComponentFactory.javaClass.getMethod(
                "instantiateClassLoader",
                ClassLoader::class.java,
                ApplicationInfo::class.java,
            ).apply { isAccessible = true }

            bridge.hookMethod(instantiateClassLoader, object : dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback {
                override fun beforeHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) = Unit

                override fun afterHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) {
                    if (param.throwable != null) return
                    val appInfo = param.args.getOrNull(1) as? ApplicationInfo ?: return
                    if (appInfo.packageName != targetPackage) return
                    val finalClassLoader = param.result as? ClassLoader ?: return
                    startModule(finalClassLoader)
                }
            }, priority = 10000)
            Log.i(TAG, "hooked AppComponentFactory.instantiateClassLoader on ${appComponentFactory.javaClass.name}")
        } catch (t: Throwable) {
            finalClassLoaderHookInstalled.set(false)
            Log.e(TAG, "failed to hook AppComponentFactory.instantiateClassLoader", t)
        }
    }

    private fun startModule(hostClassLoader: ClassLoader) {
        if (!moduleStarted.compareAndSet(false, true)) return

        val started = try {
            val service = loaderService ?: error("Zygisk loader service not initialized")
            val bridge = hookBridge ?: error("Zygisk hook bridge not initialized")
            ModuleLoader.init(
                hostDataDir = hostDataDir,
                initialClassLoader = hostClassLoader,
                loaderService = service,
                hookBridge = bridge,
                modulePath = modulePath,
                allowDynamicLoad = false,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start WeKit module", t)
            false
        }
        if (started) {
            Log.i(TAG, "WeKit module started with host ClassLoader=$hostClassLoader")
        } else {
            // ModuleLoader deliberately leaves its guard unset on failure.
            // Do the same here so a later app lifecycle entry can retry.
            moduleStarted.set(false)
            Log.w(TAG, "WeKit module startup failed; retry remains available")
        }
    }

}
