package moe.ouom.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.annotation.Keep
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.loader.hookapi.IHookBridge
import moe.ouom.wekit.loader.hookapi.ILoaderService
import moe.ouom.wekit.utils.Initiator.init
import moe.ouom.wekit.utils.log.WeLogger
import java.lang.reflect.InvocationTargetException

@Keep
@Suppress("unused")
object UnifiedEntryPoint {
    private const val TAG = "UnifiedEntryPoint"

    private var initialized = false

    @Keep
    fun entry(
        modulePath: String,
        hostDataDir: String,
        loaderService: ILoaderService,
        hostClassLoader: ClassLoader,
        hookBridge: IHookBridge?
    ) {
        check(!initialized) { "UnifiedEntryPoint already initialized" }
        initialized = true
        // fix up the class loader
        val loader = HybridClassLoader.INSTANCE
        val self = checkNotNull(UnifiedEntryPoint::class.java.classLoader)
        val parent = self.parent
        HybridClassLoader.setLoaderParentClassLoader(parent)
        injectClassLoader(self, loader)
        callNextStep(modulePath, hostDataDir, loaderService, hostClassLoader, hookBridge)
    }

    private fun callNextStep(
        modulePath: String,
        hostDataDir: String,
        loaderService: ILoaderService,
        initialClassLoader: ClassLoader,
        hookBridge: IHookBridge?
    ) {
        try {
            // Hook 壳 Application
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.app.Application",
                initialClassLoader,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        WeLogger.i(
                            "UnifiedEntryPoint",
                            "Shell attached (Application.attachBaseContext done)."
                        )

                        val context = param.thisObject as Context
                        val currentClassLoader = context.classLoader

                        // Hook Instrumentation.callApplicationOnCreate 以处理 Tinker 热更新场景
                        try {
                            hookInstrumentationForTinker(
                                currentClassLoader,
                                modulePath,
                                hostDataDir,
                                loaderService,
                                initialClassLoader,
                                hookBridge
                            )
                        } catch (t: Throwable) {
                            WeLogger.e(
                                "Failed to hook Instrumentation.callApplicationOnCreate",
                                t
                            )
                        }
                    }
                }
            )
            WeLogger.i("Hook applied: waiting for Application.attachBaseContext")
        } catch (t: Throwable) {
            WeLogger.e("Failed to hook Shell Application", t)
        }
    }

    /**
     * Hook Instrumentation.callApplicationOnCreate 以确保在 Tinker 热更新完成后再进行延迟初始化
     * 这可以解决某些模块在热更新环境下找不到入口的问题
     */
    private fun hookInstrumentationForTinker(
        hostClassLoader: ClassLoader,
        modulePath: String,
        hostDataDir: String,
        loaderService: ILoaderService,
        initialClassLoader: ClassLoader,
        hookBridge: IHookBridge?
    ) {
        try {
            val instrumentationClass =
                Class.forName("android.app.Instrumentation", false, hostClassLoader)
            XposedHelpers.findAndHookMethod(
                instrumentationClass,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val application = param.args[0] as Application
                        val hostApp = param.args[0] as Application?
                        StartupInfo.setHostApp(hostApp)

                        WeLogger.i(TAG, "Instrumentation.callApplicationOnCreate captured!")
                        WeLogger.i(TAG, "Application: " + application.javaClass.name)

                        val realClassLoader = application.baseContext.classLoader
                        WeLogger.i(
                            TAG,
                            "Real ClassLoader: " + realClassLoader.javaClass.name
                        )
                        init(realClassLoader)

                        WeLogger.i(TAG, "Invoking StartupAgent immediately...")
                        try {
                            StartupAgent.startup(
                                modulePath,
                                hostDataDir,
                                loaderService,
                                realClassLoader,
                                hookBridge
                            )
                            WeLogger.i(TAG, "StartupAgent invoked successfully")
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "StartupAgent.startup failed", e)
                        }
                    }
                }
            )
            WeLogger.i(TAG, "Instrumentation.callApplicationOnCreate hook installed successfully")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to hook Instrumentation.callApplicationOnCreate", e)
        }
    }


    @SuppressLint("DiscouragedPrivateApi")
    private fun injectClassLoader(self: ClassLoader, newParent: ClassLoader?) {
        try {
            val fParent = ClassLoader::class.java.getDeclaredField("parent")
            fParent.isAccessible = true
            fParent.set(self, newParent)
        } catch (e: Exception) {
            WeLogger.e("injectClassLoader failed", e)
        }
    }

    private fun getInvocationTargetExceptionCause(e: Throwable): Throwable {
        var e = e
        while (e is InvocationTargetException) {
            val cause = e.targetException
            if (cause != null) {
                e = cause
            } else {
                break
            }
        }
        return e
    }
}
