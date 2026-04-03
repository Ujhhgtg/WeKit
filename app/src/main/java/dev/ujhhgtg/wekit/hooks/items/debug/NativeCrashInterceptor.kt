package dev.ujhhgtg.wekit.hooks.items.debug

import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.crash.CrashLogsManager
import dev.ujhhgtg.wekit.utils.crash.NativeCrashHandler
import dev.ujhhgtg.wekit.utils.showToast

@HookItem(
    path = "调试/崩溃拦截 (Native)",
    desc = "拦截 Native 层崩溃并记录详细信息，支持查看和导出日志"
)
object NativeCrashInterceptor : SwitchHookItem() {

    private val TAG = nameOf(NativeCrashInterceptor)

    override fun onEnable() {
        if (!NativeCrashHandler.install()) {
            WeLogger.e(TAG, "failed to install native crash interceptor")
        }

        checkPendingCrash()
    }

    private fun checkPendingCrash() {
        runCatching {
            if (!CrashInterceptorUtils.isMainProcess(HostInfo.application)) {
                WeLogger.d(TAG, "skipping pending crash check in non-main process")
                return
            }

            if (CrashLogsManager.hasPendingNativeCrash()) {
                WeLogger.i(
                    TAG,
                    "pending native crash detected, will show dialog when Activity is ready"
                )
                showToast("检测到上次 Native 崩溃, 正在准备崩溃报告...")
                CrashInterceptorUtils.startActivityPolling(TAG) {
                    showPendingNativeCrashDialog()
                }
            }
        }.onFailure { WeLogger.e(TAG, "failed to check for pending crash", it) }
    }

    private fun showPendingNativeCrashDialog() {
        runCatching {
            val activity = LauncherUI.getInstance()
            if (activity == null || activity.isFinishing || activity.isDestroyed) return
            val crashLogFile = CrashLogsManager.pendingNativeCrashLogFile ?: return
            CrashInterceptorUtils.showPendingCrashDialog(
                activity = activity,
                crashLogFile = crashLogFile,
                titleSummary = "检测到上次 Native 崩溃",
                titleDetail = "Native 崩溃详情",
                clearPendingFlag = CrashLogsManager::clearPendingNativeCrashFlag,
                extractSummary = ::extractCrashSummary
            )
        }.onFailure { WeLogger.e(TAG, "failed to show pending crash dialog", it) }
    }

    private fun extractCrashSummary(crashInfo: String): String {
        val lines = crashInfo.lines()
        val summary = StringBuilder()

        var foundStackTrace = false
        var stackTraceLineCount = 0

        for (line in lines) {
            when {
                line.startsWith("Crash Time:") -> {
                    summary.append(line).append("\n")
                }

                line.startsWith("Crash Type:") -> {
                    summary.append(line).append("\n\n")
                }

                line.startsWith("Signal:") -> {
                    summary.append(line).append("\n")
                }

                line.startsWith("Description:") -> {
                    summary.append(line).append("\n")
                }

                line.startsWith("Fault Address:") -> {
                    summary.append(line).append("\n\n")
                }

                line.contains("Stack Trace") -> {
                    foundStackTrace = true
                    summary.append("堆栈信息（前5行）:\n")
                }

                foundStackTrace -> {
                    if (line.trim().isNotEmpty() && !line.contains("====")) {
                        summary.append(line).append("\n")
                        stackTraceLineCount++
                    }
                }
            }

            if (stackTraceLineCount >= 5) break
        }

        if (summary.isEmpty()) {
            return "崩溃信息解析失败\n\n点击「查看详情」查看完整日志"
        }

        summary.append("\n点击「查看详情」查看完整日志")
        return summary.toString()
    }

    override fun onDisable() {
        NativeCrashHandler.uninstall()
    }
}
