package dev.ujhhgtg.wekit.features.items.debug

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.crash.CrashLogsManager
import java.io.File
import java.nio.file.Path

object CrashInterceptorUtils {

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
                    val activity = LauncherUI.getInstance()
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
    fun buildDisplayCrashInfo(
        crashInfo: String,
        overflowNotice: String,
        maxLength: Int = 15 * 1024,
    ): String {
        return if (crashInfo.length > maxLength) {
            crashInfo.take(maxLength) +
                    "\n\n=============================\n" +
                    overflowNotice + "\n" +
                    "============================="
        } else {
            crashInfo
        }
    }

    fun showPendingCrashDialog(
        activity: Activity,
        crashLogFile: Path,
        @StringRes titleSummaryRes: Int,
        @StringRes titleDetailRes: Int,
        clearPendingFlag: () -> Unit,
        extractSummary: (String) -> String
    ) {
        val crashInfo = CrashLogsManager.readCrashLog(crashLogFile) ?: return

        showComposeDialog(activity) {
            var showDetail by remember { mutableStateOf(false) }

            if (showDetail) {
                val overflowNotice = stringResource(R.string.debug_crash_log_truncated_notice)
                val displayInfo = remember(crashInfo, overflowNotice) {
                    buildDisplayCrashInfo(crashInfo, overflowNotice)
                }
                AlertDialogContent(
                    title = { Text(stringResource(titleDetailRes)) },
                    text = {
                        SelectionContainer {
                            Text(
                                displayInfo,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val fullCrashInfo = CrashLogsManager.readFullCrashLog(crashLogFile) ?: crashInfo
                            copyToClipboard(activity, fullCrashInfo)
                            onDismiss()
                            clearPendingFlag()
                        }) { Text(stringResource(R.string.debug_crash_copy_full_log)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            onDismiss()
                            clearPendingFlag()
                        }) { Text(stringResource(R.string.action_close)) }
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text(stringResource(titleSummaryRes)) },
                    text = {
                        Text(
                            extractSummary(crashInfo),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showDetail = true }) {
                            Text(stringResource(R.string.debug_crash_view_details))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            onDismiss()
                            clearPendingFlag()
                        }) { Text(stringResource(R.string.debug_crash_ignore)) }
                    }
                )
            }
        }
    }
}
