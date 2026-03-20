package dev.ujhhgtg.wekit.hooks.items.debug

import android.annotation.SuppressLint
import android.app.Activity
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.core.model.SwitchHookItem
import dev.ujhhgtg.wekit.hooks.utils.annotation.HookItem
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.ToastUtils
import dev.ujhhgtg.wekit.utils.crash.CrashLogsManager
import dev.ujhhgtg.wekit.utils.crash.JavaCrashHandler
import dev.ujhhgtg.wekit.utils.logging.WeLogger

@HookItem(
    path = "调试/崩溃拦截",
    desc = "拦截 Java 层崩溃并记录详细信息，支持查看和导出日志"
)
@SuppressLint("StaticFieldLeak")
object CrashInterceptor : SwitchHookItem() {

    private val TAG = nameof(CrashInterceptor)

    override fun onEnable() {
        JavaCrashHandler.install()
        checkPendingCrash()
    }

    private fun checkPendingCrash() {
        runCatching {
            if (!CrashInterceptorUtils.isMainProcess(HostInfo.application)) {
                WeLogger.d(TAG, "skipping pending crash check in non-main process")
                return
            }

            if (CrashLogsManager.hasPendingJavaCrash()) {
                WeLogger.i(
                    TAG,
                    "pending Java crash detected, will show dialog when Activity is ready"
                )
                ToastUtils.showToast("检测到上次 Java 崩溃, 正在准备崩溃报告...")
                CrashInterceptorUtils.startActivityPolling(TAG) { activity ->
                    showPendingJavaCrashDialog(activity)
                }
            }
        }.onFailure { WeLogger.e(TAG, "failed to check for pending crash", it) }
    }

    private fun showPendingJavaCrashDialog(activity: Activity) {
        runCatching {
            val crashLogFile = CrashLogsManager.pendingJavaCrashLogFile ?: return
            WeLogger.i(TAG, "crashLogFile: $crashLogFile")
            CrashInterceptorUtils.showPendingCrashDialog(
                activity = activity,
                crashLogFile = crashLogFile,
                titleSummary = "检测到上次 Java 崩溃",
                titleDetail = "Java 崩溃详情",
                clearPendingFlag = CrashLogsManager::clearPendingJavaCrashFlag,
                extractSummary = ::extractCrashSummary
            )
        }.onFailure { WeLogger.e(TAG, "failed to show pending crash dialog", it) }
    }

    private fun extractCrashSummary(crashInfo: String): String {
        val lines = crashInfo.lines()
        val summary = StringBuilder()
        var foundException = false
        var exceptionLineCount = 0

        for (line in lines) {
            when {
                line.startsWith("Crash Time:") -> summary.append(line).append("\n")
                line.startsWith("Crash Type:") -> summary.append(line).append("\n\n")
                line.contains("Exception Stack Trace") -> {
                    foundException = true
                    summary.append("异常信息:\n")
                }

                foundException -> {
                    if (line.trim().isNotEmpty() && !line.contains("====")) {
                        summary.append(line).append("\n")
                        exceptionLineCount++
                    }
                }
            }
            if (exceptionLineCount >= 10) break
        }
        if (summary.isEmpty()) return "崩溃信息解析失败\n\n点击「查看详情」查看完整日志"
        summary.append("\n点击「查看详情」查看完整日志")
        return summary.toString()
    }

    override fun onDisable() {
        JavaCrashHandler.uninstall()
    }
}
