package dev.ujhhgtg.wekit.utils.crash

import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.crash.CrashInfoCollector.collectCrashInfo
import dev.ujhhgtg.wekit.utils.polyfills.getThreadId
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.system.exitProcess

object JavaCrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "JavaCrashHandler"

    // 第二个崩溃线程最多等第一个线程写完崩溃日志多久; 超时就直接走终止流程, 避免整个进程卡死。
    private const val HANDOFF_TIMEOUT_SECONDS = 10L

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    // 真正的"递归崩溃": 同一线程在崩溃处理过程中又抛了未捕获异常, 必须立刻放弃处理。
    // 用 ThreadLocal 而不是共享标志位, 否则会把别的线程的正常崩溃误判成递归。
    private val isHandlingOnThisThread: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    // 跨线程串行化: 保证第一个线程把崩溃日志写完之前, 第二个崩溃线程不会抢先把进程带走。
    private val handlerLock = ReentrantLock()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    fun uninstall() {
        if (defaultHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (isHandlingOnThisThread.get()!!) {
            WeLogger.e(
                TAG,
                "recursive crash detected, delegating to default handler",
            )
            terminate(thread, throwable)
            return
        }

        isHandlingOnThisThread.set(true)
        var locked = false
        try {
            // 另一个线程正在写崩溃日志时先等它写完, 不要并发跑 collectCrashInfo/saveCrashLog,
            // 更不要抢在它前面让进程退出 (那样真正的崩溃报告就丢了)。
            locked =
                try {
                    handlerLock.tryLock(HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }

            if (locked) {
                collectAndSave(thread, throwable)
            } else {
                // 对方卡住了: 不再继续等, 直接终止, 保证不会死锁在崩溃处理里。
                WeLogger.e(TAG, "timed out waiting for another crashing thread, terminating anyway")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "error while handling crash", e)
        } finally {
            // 注意顺序: 必须在释放锁之前完成终止 (默认处理器通常直接杀进程),
            // 否则等锁的那个线程会在这里抢跑, 把进程带走。
            try {
                terminate(thread, throwable)
            } finally {
                if (locked) handlerLock.unlock()
                isHandlingOnThisThread.set(false)
            }
        }
    }

    private fun collectAndSave(thread: Thread, throwable: Throwable) {
        WeLogger.e(TAG, "========================================")
        WeLogger.e(TAG, "Uncaught exception detected!")
        WeLogger.e(
            TAG,
            "Thread: " + thread.name + " (ID: " + thread.getThreadId() + ")",
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
    }

    /** 让应用正常崩溃; 没有默认处理器时手动终止进程。 */
    private fun terminate(thread: Thread, throwable: Throwable) {
        val handler = defaultHandler
        if (handler != null) {
            WeLogger.i(TAG, "delegating to default handler")
        } else {
            WeLogger.e(TAG, "no default handler, killing process")
        }

        if (handler != null) {
            handler.uncaughtException(thread, throwable)
        } else {
            exitProcess(1)
        }
    }
}
