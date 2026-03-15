package moe.ouom.wekit.hooks.items.debug

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import com.afollestad.materialdialogs.MaterialDialog
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.utils.RuntimeConfig
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.utils.CommonContextWrapper
import moe.ouom.wekit.utils.crash.CrashLogsManager
import moe.ouom.wekit.utils.crash.NativeCrashHandler
import moe.ouom.wekit.utils.logging.WeLogger
import java.io.File
import kotlin.io.path.name

@HookItem(
    path = "调试/崩溃拦截 (Native)",
    desc = "拦截 Native 层崩溃并记录详细信息，支持查看和导出日志"
)
object NativeCrashInterceptor : SwitchHookItem() {

    private val TAG = nameof(NativeCrashInterceptor)

    private var nativeCrashHandler: NativeCrashHandler? = null
    private var crashLogsManager: CrashLogsManager? = null
    private var appContext: Context? = null
    private var hasPendingCrashToShow = false

    @SuppressLint("StaticFieldLeak")
    private var pendingDialog: MaterialDialog? = null

    override fun onEnable() {
        try {
            // 获取Application Context
            val activityThreadClass = "android.app.ActivityThread".toClass()
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            appContext = currentApplicationMethod.invoke(null) as? Context

            if (appContext == null) {
                WeLogger.e(TAG, "Failed to get application context")
                return
            }

            // 初始化崩溃日志管理器
            crashLogsManager = CrashLogsManager()

            // 安装 Native 崩溃拦截器
            nativeCrashHandler = NativeCrashHandler()
            val installed = nativeCrashHandler?.install() ?: false

            if (installed) {
                WeLogger.i(
                    TAG,
                    "Native crash interceptor installed successfully"
                )
            } else {
                WeLogger.e(TAG, "Failed to install native crash interceptor")
            }

            // 检查是否有待处理的崩溃
            checkPendingCrash()

        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to install native crash interceptor", e)
        }
    }

    /**
     * 检查是否有待处理的崩溃
     */
    private fun checkPendingCrash() {
        try {
            val manager = crashLogsManager ?: return

            // 只在主进程中检查待处理的崩溃
            if (!isMainProcess()) {
                WeLogger.d(
                    TAG,
                    "Skipping pending crash check in non-main process"
                )
                return
            }

            if (manager.hasPendingNativeCrash()) {
                WeLogger.i(
                    TAG,
                    "Pending native crash detected, will show dialog when Activity is ready"
                )

                // 读取崩溃日志并输出到 WeLogger
                logNativeCrashToWeLogger(manager)

                hasPendingCrashToShow = true

                showToast("检测到上次 Native 崩溃,正在准备崩溃报告...")
                startActivityPolling()
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to check pending crash", e)
        }
    }

    /**
     * 将 Native 崩溃信息输出到 WeLogger
     */
    private fun logNativeCrashToWeLogger(manager: CrashLogsManager) {
        try {
            val crashLogFile = manager.pendingNativeCrashLogFile ?: return
            val crashInfo = manager.readCrashLog(crashLogFile) ?: return

            WeLogger.e(TAG, "========================================")
            WeLogger.e(TAG, "Native crash detected!")
            WeLogger.e(TAG, "Crash log file: ${crashLogFile.name}")
            WeLogger.e(TAG, "========================================")

            // 输出崩溃详情（分行输出，避免单行过长）
            val lines = crashInfo.lines()
            var lineCount = 0
            val maxLines = 100 // 最多输出100行到WeLogger

            for (line in lines) {
                if (lineCount >= maxLines) {
                    WeLogger.e(
                        TAG,
                        "... (日志过长，已截断，完整日志请查看崩溃报告)"
                    )
                    break
                }
                if (line.isNotEmpty()) {
                    WeLogger.e(TAG, line)
                    lineCount++
                }
            }

            WeLogger.e(TAG, "========================================")
            WeLogger.e(TAG, "Native crash log output completed")
            WeLogger.e(TAG, "========================================")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to log native crash to WeLogger", e)
        }
    }

    /**
     * 启动 Activity 轮询机制
     * 定期检查 Activity 是否可用, 如果可用则显示待处理的崩溃对话框
     */
    private fun startActivityPolling() {
        val handler = Handler(Looper.getMainLooper())
        var retryCount = 0
        val maxRetries = 20

        val pollingRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!hasPendingCrashToShow) {
                        WeLogger.d(
                            TAG,
                            "No pending crash to show, stopping polling"
                        )
                        return
                    }

                    val activity = RuntimeConfig.getLauncherUiActivity()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        WeLogger.i(
                            TAG,
                            "Activity is ready, showing pending crash dialog"
                        )
                        showPendingCrashDialog()
                        return
                    }

                    retryCount++
                    if (retryCount < maxRetries) {
                        WeLogger.d(
                            TAG,
                            "Activity not ready, retry $retryCount/$maxRetries"
                        )
                        handler.postDelayed(this, 500) // 每500ms重试一次
                    } else {
                        WeLogger.w(
                            TAG,
                            "Max retries reached, giving up on showing dialog"
                        )
                        hasPendingCrashToShow = false
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Error in activity polling", e)
                }
            }
        }

        handler.postDelayed(pollingRunnable, 1000)
        WeLogger.i(TAG, "Started activity polling mechanism")
    }

    /**
     * 检查是否为主进程
     */
    private fun isMainProcess(): Boolean {
        return try {
            val context = appContext ?: return false
            val processName = getProcessName()
            processName == context.packageName
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to check main process", e)
            false
        }
    }

    /**
     * 获取当前进程名
     */
    private fun getProcessName(): String {
        return try {
            val file = File("/proc/${Process.myPid()}/cmdline")
            file.readText().trim('\u0000')
        } catch (_: Throwable) {
            "UNKNOWN"
        }
    }

    /**
     * 关闭待处理的对话框
     */
    private fun dismissPendingDialog() {
        try {
            pendingDialog?.dismiss()
            pendingDialog = null
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to dismiss pending dialog", e)
        }
    }

    /**
     * 显示待处理的崩溃对话框
     */
    private fun showPendingCrashDialog() {
        try {
            val manager = crashLogsManager ?: return
            val activity = RuntimeConfig.getLauncherUiActivity()

            // 如果 Activity 不可用, 重新设置标记等待下次
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                WeLogger.w(TAG, "Activity not available, will retry later")
                hasPendingCrashToShow = true
                return
            }

            val crashLogFile = manager.pendingNativeCrashLogFile ?: run {
                WeLogger.w(TAG, "No pending native crash log file found")
                hasPendingCrashToShow = false
                return
            }

            val crashInfo = manager.readCrashLog(crashLogFile) ?: run {
                WeLogger.w(TAG, "Failed to read crash log file")
                hasPendingCrashToShow = false
                return
            }

            // 提取崩溃摘要信息
            val summary = extractCrashSummary(crashInfo)

            WeLogger.i(TAG, "Preparing to show crash dialog on main thread")

            Handler(Looper.getMainLooper()).post {
                try {
                    // 先关闭之前的对话框
                    dismissPendingDialog()

                    // 使用 CommonContextWrapper 包装 Activity Context
                    val wrappedContext =
                        CommonContextWrapper.createAppCompatContext(activity)

                    WeLogger.i(
                        TAG,
                        "Creating MaterialDialog for native crash report"
                    )

                    pendingDialog = MaterialDialog(wrappedContext)
                        .title(text = "检测到上次 Native 崩溃")
                        .message(text = summary)
                        .positiveButton(text = "查看详情") { dialog ->
                            dialog.dismiss()
                            hasPendingCrashToShow = false
                            showCrashDetailDialog(crashInfo)
                        }
                        .negativeButton(text = "忽略") { dialog ->
                            dialog.dismiss()
                            hasPendingCrashToShow = false
                            manager.clearPendingNativeCrashFlag()
                        }
                        .cancelable(false)

                    pendingDialog?.show()

                    // 成功显示对话框后重置标记
                    hasPendingCrashToShow = false
                    WeLogger.i(TAG, "Native crash dialog shown successfully")
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to show pending crash dialog", e)
                    hasPendingCrashToShow = false
                    manager.clearPendingNativeCrashFlag()
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to show pending crash dialog", e)
            hasPendingCrashToShow = false
        }
    }

    /**
     * 显示崩溃详情对话框
     */
    private fun showCrashDetailDialog(crashInfo: String) {
        try {
            val activity = RuntimeConfig.getLauncherUiActivity()
            val manager = crashLogsManager ?: return

            // 如果 Activity 不可用,使用 Toast 提示
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                WeLogger.w(TAG, "Activity not available for detail dialog")
                showToast("无法显示详情,请稍后重试")
                return
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    // 先关闭之前的对话框
                    dismissPendingDialog()

                    // 使用 CommonContextWrapper 包装 Activity Context
                    val wrappedContext =
                        CommonContextWrapper.createAppCompatContext(activity)

                    pendingDialog = MaterialDialog(wrappedContext)
                        .title(text = "Native 崩溃详情")
                        .message(text = crashInfo)
                        .positiveButton(text = "复制") { dialog ->
                            copyToClipboard(activity, crashInfo)
                            dialog.dismiss()
                            manager.clearPendingNativeCrashFlag()
                        }
                        .negativeButton(text = "关闭") { dialog ->
                            dialog.dismiss()
                            manager.clearPendingNativeCrashFlag()
                        }
                        .cancelable(true)

                    pendingDialog?.show()
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Failed to show crash detail dialog", e)
                    manager.clearPendingNativeCrashFlag()
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to show crash detail dialog", e)
        }
    }

    /**
     * 提取崩溃摘要信息
     */
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
            return "崩溃信息解析失败\n\n点击\"查看详情\"查看完整日志"
        }

        summary.append("\n点击\"查看详情\"查看完整日志")
        return summary.toString()
    }

    /**
     * 复制到剪贴板
     */
    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Native Crash Log", text)
            clipboard?.setPrimaryClip(clip)
            WeLogger.i(TAG, "Native crash log copied to clipboard")
            showToast("Native 崩溃日志已复制到剪贴板")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to copy to clipboard", e)
            showToast("复制失败: ${e.message}")
        }
    }

    /**
     * 显示Toast提示
     */
    private fun showToast(message: String) {
        try {
            val context = appContext ?: return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to show toast", e)
        }
    }

    override fun onDisable() {
        nativeCrashHandler?.uninstall()
    }
}