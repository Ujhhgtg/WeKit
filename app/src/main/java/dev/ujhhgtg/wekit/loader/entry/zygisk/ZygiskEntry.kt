@file:Suppress("unused")

package dev.ujhhgtg.wekit.loader.entry.zygisk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import androidx.annotation.Keep
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygisk JVM entry point.
 *
 * Called from native postAppSpecialize:
 *   ZygiskEntry.init(processName, dataDir, copiedApkPath)
 *
 * The native side has only:
 *   1. Copied the active module's APK into
 *      this app's data directory during postAppSpecialize.
 *   2. Read DEX directly from that APK through InMemoryDexClassLoader and called
 *      this entry point.
 *
 * This Java entry then initializes its native hook runtime,
 * trusts its own InMemoryDexClassLoader, and only then installs lifecycle hooks.
 */
@Keep
object ZygiskEntry {

    private const val TAG = "ZygiskEntry"
    private val entryLock = Any()
    private val moduleStarted = AtomicBoolean(false)
    private val finalClassLoaderHookInstalled = AtomicBoolean(false)
    private var loaderService: ZygiskLoaderService? = null
    private var hookBridge: ArtHookBridge? = null
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
            WeLogger.w(TAG, "ignoring unsupported Zygisk target: $targetPackage")
            return
        }
        synchronized(entryLock) {
            if (hookBridge != null) return

            try {
                WeLogger.i(TAG, "ZygiskEntry.init: process=$processName apk=$apkPath dataDir=$dataDir")
                check(nativeInitialize()) {
                    "failed to initialize ART hook runtime and trust ZygiskEntry loader"
                }
                NativeLoader.configureZygiskPayload(apkPath, dataDir)
                val service = ZygiskLoaderService(
                    modulePath = apkPath,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
                val bridge = ArtHookBridge()
                hostDataDir = dataDir
                modulePath = apkPath
                loaderService = service
                hookBridge = bridge

                // Waits at LoadedApk.createAppFactory. The ClassLoader
                // parameter here is only AOSP's default loader; the final
                // mClassLoader is selected by AppComponentFactory below.
                val loadedApk = Class.forName("android.app.LoadedApk")
                val createAppFactory = loadedApk.getDeclaredMethod(
                    "createAppFactory",
                    ApplicationInfo::class.java,
                    ClassLoader::class.java,
                ).makeAccessible()

                bridge.hookMethod(createAppFactory, object : IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = Unit

                    override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
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
                WeLogger.e(TAG, "ZygiskEntry.init failed", t)
            }
        }
    }

    @JvmStatic
    private external fun nativeInitialize(): Boolean

    /**
     * The native bootstrap creates this connection before app specialization,
     * when Zygisk is still allowed to connect to its root companion.
     */
    fun hasTelegramRootCompanion(): Boolean = nativeHasTelegramRootCompanion()

    fun listTelegramRootInstances(): List<String> = nativeListTelegramInstances().toList()

    /** Bit 0 and bit 1 indicate that the source had a WAL and SHM sidecar. */
    fun copyTelegramRootDatabaseSnapshot(
        packageName: String,
        databaseFd: Int,
        walFd: Int,
        shmFd: Int,
    ): Int = nativeCopyTelegramDatabaseSnapshot(packageName, databaseFd, walFd, shmFd)

    @JvmStatic
    private external fun nativeHasTelegramRootCompanion(): Boolean

    @JvmStatic
    private external fun nativeListTelegramInstances(): Array<String>

    @JvmStatic
    private external fun nativeCopyTelegramDatabaseSnapshot(
        packageName: String,
        databaseFd: Int,
        walFd: Int,
        shmFd: Int,
    ): Int

    private fun installFinalClassLoaderHook(
        bridge: ArtHookBridge,
        appComponentFactory: Any,
        targetPackage: String,
    ) {
        if (!finalClassLoaderHookInstalled.compareAndSet(false, true)) return
        try {
            val instantiateClassLoader = appComponentFactory.javaClass.getMethod(
                "instantiateClassLoader",
                ClassLoader::class.java,
                ApplicationInfo::class.java,
            ).makeAccessible()

            bridge.hookMethod(instantiateClassLoader, object : IHookBridge.IMemberHookCallback {
                override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = Unit

                override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                    if (param.throwable != null) return
                    val appInfo = param.args.getOrNull(1) as? ApplicationInfo ?: return
                    if (appInfo.packageName != targetPackage) return
                    val finalClassLoader = param.result as? ClassLoader ?: return
                    startModule(finalClassLoader)
                }
            }, priority = 10000)
            WeLogger.i(TAG, "hooked AppComponentFactory.instantiateClassLoader on ${appComponentFactory.javaClass.name}")
        } catch (t: Throwable) {
            finalClassLoaderHookInstalled.set(false)
            WeLogger.e(TAG, "failed to hook AppComponentFactory.instantiateClassLoader", t)
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
            WeLogger.e(TAG, "failed to start WeKit module", t)
            false
        }
        if (started) {
            WeLogger.i(TAG, "WeKit module started with host ClassLoader=$hostClassLoader")
        } else {
            // ModuleLoader deliberately leaves its guard unset on failure.
            // Do the same here so a later app lifecycle entry can retry.
            moduleStarted.set(false)
            WeLogger.w(TAG, "WeKit module startup failed; retry remains available")
        }
    }

}
