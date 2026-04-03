package dev.ujhhgtg.wekit.utils.crash

import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.utils.WeLogger

object NativeCrashHandler {

    private val TAG = nameOf(NativeCrashHandler::class)

    var isInstalled: Boolean = false

    // Native 方法声明
    private external fun installNative(crashLogDir: String?): Boolean

    private external fun uninstallNative()

    private external fun triggerTestCrashNative(crashType: Int)

    /**
     * 安装 Native 崩溃拦截器
     *
     * @return 是否安装成功
     */
    fun install(): Boolean {
        if (isInstalled) {
            WeLogger.i(TAG, "Native crash handler already installed")
            return true
        }

        try {
            val crashLogDir = CrashLogsManager.crashLogDirPath
            val result = installNative(crashLogDir)

            if (result) {
                isInstalled = true
                WeLogger.i(TAG, "installed successfully")
            } else {
                WeLogger.e(TAG, "failed to install")
            }

            return result
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to install native crash handler", e)
            return false
        }
    }

    /**
     * 卸载 Native 崩溃拦截器
     */
    fun uninstall() {
        if (!isInstalled) {
            return
        }

        try {
            uninstallNative()
            isInstalled = false
            WeLogger.i(TAG, "Native crash handler uninstalled")
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashHandler] Failed to uninstall native crash handler", e)
        }
    }

    /**
     * 触发测试崩溃
     *
     * @param crashType 崩溃类型
     * 0 = SIGSEGV (空指针访问)
     * 1 = SIGABRT (abort)
     * 2 = SIGFPE (除零错误)
     * 3 = SIGILL (非法指令)
     * 4 = SIGBUS (总线错误)
     */
    fun triggerCrash(crashType: Int) {
        WeLogger.w(TAG, "Triggering test crash: type=$crashType")
        try {
            triggerTestCrashNative(crashType)
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashHandler] Failed to trigger test crash", e)
        }
    }
}
