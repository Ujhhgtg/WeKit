package dev.ujhhgtg.wekit.hooks.items.debug

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.copyToClipboard
import dev.ujhhgtg.wekit.utils.crash.CrashLogsManager
import dev.ujhhgtg.wekit.utils.logging.WeLogger
import java.io.File
import java.nio.file.Path

internal object CrashInterceptorUtils {

    private val TAG = nameof(CrashInterceptorUtils)

    fun isMainProcess(appContext: Context): Boolean {
        return runCatching {
            getProcessName() == appContext.packageName
        }.getOrDefault(false)
    }

    fun getProcessName(): String {
        return try {
            File("/proc/${Process.myPid()}/cmdline").readText().trim('\u0000')
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Polls until a live launcher Activity is available, then invokes [onReady].
     * Gives up after [maxRetries] * 500ms + initial [initialDelayMs].
     */
    fun startActivityPolling(
        tag: String,
        maxRetries: Int = 20,
        initialDelayMs: Long = 1000L,
        onReady: (Activity) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        var retryCount = 0

        val runnable = object : Runnable {
            override fun run() {
                try {
                    val activity = RuntimeConfig.getLauncherUiActivity()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        WeLogger.i(tag, "activity is ready")
                        onReady(activity)
                        return
                    }
                    retryCount++
                    if (retryCount < maxRetries) {
                        handler.postDelayed(this, 500)
                    } else {
                        WeLogger.w(tag, "max retries reached, giving up on showing dialog")
                    }
                } catch (e: Throwable) {
                    WeLogger.e(tag, "error in activity polling", e)
                }
            }
        }

        handler.postDelayed(runnable, initialDelayMs)
    }

    /** Truncates [crashInfo] for display and appends an overflow notice if needed. */
    fun buildDisplayCrashInfo(crashInfo: String, maxLength: Int = 15 * 1024): String {
        return if (crashInfo.length > maxLength) {
            crashInfo.take(maxLength) +
                    "\n\n=============================\n" +
                    "日志内容过长，此处仅展示部分内容。\n" +
                    "请点击「复制完整日志」以保存完整日志。\n" +
                    "============================="
        } else {
            crashInfo
        }
    }

    fun showPendingCrashDialog(
        activity: Activity,
        crashLogFile: Path,
        titleSummary: String,
        titleDetail: String,
        clearPendingFlag: () -> Unit,
        extractSummary: (String) -> String
    ) {
        val crashInfo = CrashLogsManager.readCrashLog(crashLogFile) ?: return

        showComposeDialog(activity) {
            var showDetail by remember { mutableStateOf(false) }

            if (showDetail) {
                val displayInfo = remember(crashInfo) {
                    buildDisplayCrashInfo(crashInfo)
                }
                AlertDialogContent(
                    title = { Text(titleDetail) },
                    text = {
                        SelectionContainer {
                            Text(displayInfo,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.verticalScroll(rememberScrollState()))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val fullCrashInfo = CrashLogsManager.readFullCrashLog(crashLogFile) ?: crashInfo
                            copyToClipboard(activity, fullCrashInfo)
                            dismiss()
                            clearPendingFlag()
                        }) { Text("复制完整日志") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            dismiss()
                            clearPendingFlag()
                        }) { Text("关闭") }
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text(titleSummary) },
                    text = {
                        SelectionContainer {
                            Text(extractSummary(crashInfo),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.verticalScroll(rememberScrollState()))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDetail = true }) { Text("查看详情") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            dismiss()
                            clearPendingFlag()
                        }) { Text("忽略") }
                    }
                )
            }
        }
    }
}
