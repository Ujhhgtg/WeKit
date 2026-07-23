@file:Suppress("unused")

package dev.ujhhgtg.wekit.loader.entry.zygisk

import android.util.Log
import androidx.annotation.Keep
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygisk JVM entry point.
 *
 * Called from C++ postAppSpecialize via:
 *   JNI FindClass("dev/ujhhgtg/wekit/loader/entry/zygisk/ZygiskEntry")
 *   GetStaticMethodID("init", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
 *   CallStaticVoidMethod(apkPath, dataDir, zygiskSoPath, targetPackage)
 *
 * The C++ side has already:
 *   1. Copied wekit.apk → <dataDir>/files/wekit/wekit.apk
 *   2. Loaded the module APK through a PathClassLoader and registered the
 *      native methods used by ZygiskHookBridge.
 */
@Keep
object ZygiskEntry {

    private const val TAG = "ZygiskEntry"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private val entryLock = Any()
    private val moduleStarted = AtomicBoolean(false)
    private var loaderService: ZygiskLoaderService? = null
    private var hookBridge: ZygiskHookBridge? = null
    private var hostDataDir: String = ""
    private var modulePath: String = ""

    @Keep
    @JvmStatic
    fun init(
        apkPath: String,
        dataDir: String,
        zygiskSoPath: String,
        targetPackage: String,
    ) {
        if (targetPackage != WECHAT_PACKAGE) {
            Log.w(TAG, "ignoring unsupported Zygisk target: $targetPackage")
            return
        }
        synchronized(entryLock) {
            if (hookBridge != null) return

            try {
                Log.i(TAG, "ZygiskEntry.init: apk=$apkPath dataDir=$dataDir target=$targetPackage")
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

                // FunBox waits for LoadedApk.createAppFactory: its ClassLoader
                // argument is the real host loader, unlike the loader that
                // loaded this bootstrap DEX.
                val loadedApk = Class.forName("android.app.LoadedApk")
                val createAppFactory = loadedApk.getDeclaredMethod(
                    "createAppFactory",
                    android.content.pm.ApplicationInfo::class.java,
                    ClassLoader::class.java,
                ).apply { isAccessible = true }

                bridge.hookMethod(createAppFactory, object : dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) {
                        val appInfo = param.args.getOrNull(0) as? android.content.pm.ApplicationInfo
                        val hostClassLoader = param.args.getOrNull(1) as? ClassLoader
                        if (appInfo?.packageName == targetPackage && hostClassLoader != null) {
                            startModule(hostClassLoader)
                        }
                    }

                    override fun afterHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) = Unit
                }, priority = 10000)
            } catch (t: Throwable) {
                // All fields are published before the hook can become reachable;
                // roll them back when installation itself fails so init can retry.
                loaderService = null
                hookBridge = null
                hostDataDir = ""
                modulePath = ""
                moduleStarted.set(false)
                Log.e(TAG, "ZygiskEntry.init failed", t)
            }
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

    /**
     * Helper used by the C++ loader to build the in-memory DEX payload from the
     * embedded WeKit APK. Reads all classes*.dex entries and returns them as a
     * list of ByteBuffers so the native side can construct an InMemoryDexClassLoader.
     *
     * Called from C++ as a convenience; the caller may also extract DEX entries
     * natively using our own zip reader — this path exists for reference.
     */
    @Keep
    @JvmStatic
    fun extractDexBuffersFromApk(apkPath: String): Array<ByteBuffer> {
        val result = mutableListOf<ByteBuffer>()
        ZipFile(apkPath).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
            for (entry in entries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                result += ByteBuffer.wrap(bytes)
            }
        }
        return result.toTypedArray()
    }
}
