package dev.ujhhgtg.wekit.utils.crash

import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.crash.CrashInfoCollector.collectCrashInfo
import dev.ujhhgtg.wekit.utils.polyfills.getThreadId
import kotlin.system.exitProcess

object JavaCrashHandler : Thread.UncaughtExceptionHandler {

    private val TAG = nameOf(JavaCrashHandler::class)

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    private var isHandling = false

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    fun uninstall() {
        if (defaultHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (isHandling) {
            WeLogger.e(
                TAG,
                "recursive crash detected, delegating to default handler"
            )
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        isHandling = true

        try {
            WeLogger.e(TAG, "========================================")
            WeLogger.e(TAG, "Uncaught exception detected!")
            WeLogger.e(
                TAG,
                "Thread: " + thread.name + " (ID: " + thread.getThreadId() + ")"
            )
            WeLogger.e(TAG, "Exception: " + throwable.javaClass.name)
            WeLogger.e(TAG, "Message: " + throwable.message)
            WeLogger.e(TAG, "========================================")

            // 收集崩溃信息
            val crashInfo = collectCrashInfo(HostInfo.application, throwable, "JAVA")

            // 保存崩溃日志（标记为Java崩溃）
            val logPath = CrashLogsManager.saveCrashLog(crashInfo, true)
            if (logPath != null) {
                WeLogger.i(TAG, "java crash log saved to: $logPath")
            } else {
                WeLogger.e(TAG, "failed to save Java crash log")
            }

            WeLogger.e(TAG, "crash details", throwable)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "error while handling crash", e)
        } finally {
            WeLogger.flush()

            isHandling = false

            // 调用默认处理器，让应用正常崩溃
            if (defaultHandler != null) {
                WeLogger.i(TAG, "delegating to default handler")
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                // 如果没有默认处理器，手动终止进程
                WeLogger.e(TAG, "no default handler, killing process")
                exitProcess(1)
            }
        }
    }
}
