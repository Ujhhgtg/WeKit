package moe.ouom.wekit.utils.crash

import android.content.Context
import android.os.Process
import moe.ouom.wekit.utils.crash.CrashInfoCollector.collectCrashInfo
import moe.ouom.wekit.utils.log.WeLogger
import kotlin.system.exitProcess

/**
 * Java 层崩溃拦截处理器
 * 实现 UncaughtExceptionHandler 接口，拦截未捕获的异常
 * 
 * @author cwuom
 * @since 1.0.0
 */
class JavaCrashHandler(context: Context) : Thread.UncaughtExceptionHandler {
    private val context: Context = context.applicationContext
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    /**
     * 获取崩溃日志管理器
     * 
     * @return 崩溃日志管理器
     */
    val crashLogManager: CrashLogManager = CrashLogManager(this.context)
    private var isHandling = false

    /**
     * 安装崩溃拦截器
     */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        WeLogger.i("JavaCrashHandler", "Java crash handler installed")
    }

    /**
     * 卸载崩溃拦截器
     */
    fun uninstall() {
        if (defaultHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
            WeLogger.i("JavaCrashHandler", "Java crash handler uninstalled")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 防止递归调用
        if (isHandling) {
            WeLogger.e(
                "JavaCrashHandler",
                "Recursive crash detected, delegating to default handler"
            )
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        isHandling = true

        try {
            WeLogger.e("JavaCrashHandler", "========================================")
            WeLogger.e("JavaCrashHandler", "Uncaught exception detected!")
            WeLogger.e(
                "JavaCrashHandler",
                "Thread: " + thread.name + " (ID: " + thread.id + ")"
            )
            WeLogger.e("JavaCrashHandler", "Exception: " + throwable.javaClass.name)
            WeLogger.e("JavaCrashHandler", "Message: " + throwable.message)
            WeLogger.e("JavaCrashHandler", "========================================")

            // 收集崩溃信息
            val crashInfo = collectCrashInfo(context, throwable, "JAVA")

            // 保存崩溃日志（标记为Java崩溃）
            val logPath = crashLogManager.saveCrashLog(crashInfo, true)
            if (logPath != null) {
                WeLogger.i("JavaCrashHandler", "Java crash log saved to: $logPath")
            } else {
                WeLogger.e("JavaCrashHandler", "Failed to save Java crash log")
            }

            // 使用WeLogger记录崩溃
            WeLogger.e("[JavaCrashHandler] Crash details", throwable)
        } catch (e: Throwable) {
            WeLogger.e("[JavaCrashHandler] Error while handling crash", e)
        } finally {
            isHandling = false

            // 调用默认处理器，让应用正常崩溃
            if (defaultHandler != null) {
                WeLogger.i("JavaCrashHandler", "Delegating to default handler")
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                // 如果没有默认处理器，手动终止进程
                WeLogger.e("JavaCrashHandler", "No default handler, killing process")
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
