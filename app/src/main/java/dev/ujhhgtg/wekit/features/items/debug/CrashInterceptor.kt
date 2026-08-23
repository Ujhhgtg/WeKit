package dev.ujhhgtg.wekit.features.items.debug

import android.app.Activity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.crash.CrashLogsManager
import dev.ujhhgtg.wekit.utils.crash.JavaCrashHandler

object CrashInterceptor : SwitchFeature() {

    override val technicalId = "崩溃拦截"
    override val nameRes = R.string.feature_crash_interceptor_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_crash_interceptor_description

    private const val TAG = "CrashInterceptor"

    override val defaultEnabled = true

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
                showToast(localizedDebugString(R.string.debug_java_crash_preparing_report))
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
                titleSummaryRes = R.string.debug_java_crash_detected,
                titleDetailRes = R.string.debug_java_crash_details,
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
                    summary.append(localizedDebugString(R.string.debug_crash_exception_information)).append("\n")
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
        if (summary.isEmpty()) return localizedDebugString(R.string.debug_crash_summary_parse_failed)
        summary.append("\n").append(localizedDebugString(R.string.debug_crash_view_full_log_hint))
        return summary.toString()
    }

    override fun onDisable() {
        JavaCrashHandler.uninstall()
    }
}
