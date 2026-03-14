package moe.ouom.wekit.loader.startup

import android.app.Application
import android.content.Context
import com.highcapable.kavaref.extension.ClassLoaderProvider
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.loader.hookapi.ILoaderService
import moe.ouom.wekit.utils.log.WeLogger

object UnifiedEntryPoint {

    private val TAG = nameof(UnifiedEntryPoint)

    fun entry(
        modulePath: String,
        loaderService: ILoaderService,
        hostClassLoader: ClassLoader
    ) {
        ClassLoaderProvider.classLoader = hostClassLoader

        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.app.Application",
                hostClassLoader,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Hook Instrumentation.callApplicationOnCreate 以处理 Tinker 热更新场景
                        try {
                            hookInstrumentationForTinker(
                                modulePath,
                                loaderService
                            )
                        } catch (t: Throwable) {
                            WeLogger.e(
                                "failed to hook Instrumentation.callApplicationOnCreate",
                                t
                            )
                        }
                    }
                }
            )
            WeLogger.i("waiting for Application.attachBaseContext")
        } catch (t: Throwable) {
            WeLogger.e("failed to hook shell Application", t)
        }
    }

    /**
     * Hook Instrumentation.callApplicationOnCreate 以确保在 Tinker 热更新完成后再进行延迟初始化
     * 这可以解决某些模块在热更新环境下找不到入口的问题
     */
    private fun hookInstrumentationForTinker(
        modulePath: String,
        loaderService: ILoaderService
    ) {
        try {
            val instrumentationClass = "android.app.Instrumentation".toClass()
            XposedHelpers.findAndHookMethod(
                instrumentationClass,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val hostApp = param.args[0] as Application?
                        StartupInfo.setHostApp(hostApp)

                        try {
                            StartupAgent.startup(
                                modulePath,
                                loaderService
                            )
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "StartupAgent failed", e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to hook Instrumentation.callApplicationOnCreate", e)
        }
    }
}
