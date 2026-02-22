package moe.ouom.wekit.hooks.items.enhance

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.afollestad.materialdialogs.MaterialDialog
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.utils.CommonContextWrapper
import moe.ouom.wekit.utils.Initiator.loadClass
import moe.ouom.wekit.utils.crash.CrashLogManager
import moe.ouom.wekit.utils.crash.NativeCrashHandler
import moe.ouom.wekit.utils.io.SafUtils
import moe.ouom.wekit.utils.log.WeLogger
import java.io.File

@HookItem(
    path = "优化与修复/崩溃拦截 (Native)",
    desc = "拦截 Native 层崩溃并记录详细信息，支持查看和导出日志"
)
object NativeCrashInterceptor : BaseSwitchFunctionHookItem() {

    private var nativeCrashHandler: NativeCrashHandler? = null
    private var crashLogManager: CrashLogManager? = null
    private var appContext: Context? = null
    private var hasPendingCrashToShow = false
    private var pendingDialog: MaterialDialog? = null

    override fun entry(classLoader: ClassLoader) {
        try {
            // 获取Application Context
            val activityThreadClass = loadClass("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            appContext = currentApplicationMethod.invoke(null) as? Context

            if (appContext == null) {
                WeLogger.e("NativeCrashInterceptor", "Failed to get application context")
                return
            }

            // 初始化崩溃日志管理器
            crashLogManager = CrashLogManager(appContext!!)

            // 安装 Native 崩溃拦截器
            nativeCrashHandler = NativeCrashHandler(appContext!!)
            val installed = nativeCrashHandler?.install() ?: false

            if (installed) {
                WeLogger.i(
                    "NativeCrashInterceptor",
                    "Native crash interceptor installed successfully"
                )
            } else {
                WeLogger.e("NativeCrashInterceptor", "Failed to install native crash interceptor")
            }

            // 检查是否有待处理的崩溃
            checkPendingCrash()

        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to install native crash interceptor", e)
        }
    }

    /**
     * 检查是否有待处理的崩溃
     */
    private fun checkPendingCrash() {
        try {
            val manager = crashLogManager ?: return

            // 只在主进程中检查待处理的崩溃
            if (!isMainProcess()) {
                WeLogger.d(
                    "NativeCrashInterceptor",
                    "Skipping pending crash check in non-main process"
                )
                return
            }

            if (manager.hasPendingNativeCrash()) {
                WeLogger.i(
                    "NativeCrashInterceptor",
                    "Pending native crash detected, will show dialog when Activity is ready"
                )

                // 读取崩溃日志并输出到 WeLogger
                logNativeCrashToWeLogger(manager)

                hasPendingCrashToShow = true

                showToast("检测到上次 Native 崩溃,正在准备崩溃报告...")
                startActivityPolling()
            }
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to check pending crash", e)
        }
    }

    /**
     * 将 Native 崩溃信息输出到 WeLogger
     */
    private fun logNativeCrashToWeLogger(manager: CrashLogManager) {
        try {
            val crashLogFile = manager.pendingNativeCrashLogFile ?: return
            val crashInfo = manager.readCrashLog(crashLogFile) ?: return

            WeLogger.e("NativeCrashInterceptor", "========================================")
            WeLogger.e("NativeCrashInterceptor", "Native crash detected!")
            WeLogger.e("NativeCrashInterceptor", "Crash log file: ${crashLogFile.name}")
            WeLogger.e("NativeCrashInterceptor", "========================================")

            // 输出崩溃详情（分行输出，避免单行过长）
            val lines = crashInfo.lines()
            var lineCount = 0
            val maxLines = 100 // 最多输出100行到WeLogger

            for (line in lines) {
                if (lineCount >= maxLines) {
                    WeLogger.e(
                        "NativeCrashInterceptor",
                        "... (日志过长，已截断，完整日志请查看崩溃报告)"
                    )
                    break
                }
                if (line.isNotEmpty()) {
                    WeLogger.e("NativeCrashInterceptor", line)
                    lineCount++
                }
            }

            WeLogger.e("NativeCrashInterceptor", "========================================")
            WeLogger.e("NativeCrashInterceptor", "Native crash log output completed")
            WeLogger.e("NativeCrashInterceptor", "========================================")
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to log native crash to WeLogger", e)
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
                            "NativeCrashInterceptor",
                            "No pending crash to show, stopping polling"
                        )
                        return
                    }

                    val activity = RuntimeConfig.getLauncherUIActivity()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        WeLogger.i(
                            "NativeCrashInterceptor",
                            "Activity is ready, showing pending crash dialog"
                        )
                        showPendingCrashDialog()
                        return
                    }

                    retryCount++
                    if (retryCount < maxRetries) {
                        WeLogger.d(
                            "NativeCrashInterceptor",
                            "Activity not ready, retry $retryCount/$maxRetries"
                        )
                        handler.postDelayed(this, 500) // 每500ms重试一次
                    } else {
                        WeLogger.w(
                            "NativeCrashInterceptor",
                            "Max retries reached, giving up on showing dialog"
                        )
                        hasPendingCrashToShow = false
                    }
                } catch (e: Throwable) {
                    WeLogger.e("[NativeCrashInterceptor] Error in activity polling", e)
                }
            }
        }

        handler.postDelayed(pollingRunnable, 1000)
        WeLogger.i("NativeCrashInterceptor", "Started activity polling mechanism")
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
            WeLogger.e("[NativeCrashInterceptor] Failed to check main process", e)
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
        } catch (e: Throwable) {
            ""
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
            WeLogger.e("[NativeCrashInterceptor] Failed to dismiss pending dialog", e)
        }
    }

    /**
     * 显示待处理的崩溃对话框
     */
    private fun showPendingCrashDialog() {
        try {
            val manager = crashLogManager ?: return
            val activity = RuntimeConfig.getLauncherUIActivity()

            // 如果 Activity 不可用, 重新设置标记等待下次
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                WeLogger.w("NativeCrashInterceptor", "Activity not available, will retry later")
                hasPendingCrashToShow = true
                return
            }

            val crashLogFile = manager.pendingNativeCrashLogFile ?: run {
                WeLogger.w("NativeCrashInterceptor", "No pending native crash log file found")
                hasPendingCrashToShow = false
                return
            }

            val crashInfo = manager.readCrashLog(crashLogFile) ?: run {
                WeLogger.w("NativeCrashInterceptor", "Failed to read crash log file")
                hasPendingCrashToShow = false
                return
            }

            // 提取崩溃摘要信息
            val summary = extractCrashSummary(crashInfo)

            WeLogger.i("NativeCrashInterceptor", "Preparing to show crash dialog on main thread")

            Handler(Looper.getMainLooper()).post {
                try {
                    // 先关闭之前的对话框
                    dismissPendingDialog()

                    // 使用 CommonContextWrapper 包装 Activity Context
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(activity)

                    WeLogger.i(
                        "NativeCrashInterceptor",
                        "Creating MaterialDialog for native crash report"
                    )

                    pendingDialog = MaterialDialog(wrappedContext)
                        .title(text = "检测到上次 Native 崩溃")
                        .message(text = summary)
                        .positiveButton(text = "查看详情") { dialog ->
                            dialog.dismiss()
                            hasPendingCrashToShow = false
                            showCrashDetailDialog(crashInfo, crashLogFile)
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
                    WeLogger.i("NativeCrashInterceptor", "Native crash dialog shown successfully")
                } catch (e: Throwable) {
                    WeLogger.e("[NativeCrashInterceptor] Failed to show pending crash dialog", e)
                    hasPendingCrashToShow = false
                    manager.clearPendingNativeCrashFlag()
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to show pending crash dialog", e)
            hasPendingCrashToShow = false
        }
    }

    /**
     * 显示崩溃详情对话框
     */
    private fun showCrashDetailDialog(crashInfo: String, crashLogFile: File) {
        try {
            val activity = RuntimeConfig.getLauncherUIActivity()
            val manager = crashLogManager ?: return

            // 如果 Activity 不可用,使用 Toast 提示
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                WeLogger.w("NativeCrashInterceptor", "Activity not available for detail dialog")
                showToast("无法显示详情,请稍后重试")
                return
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    // 先关闭之前的对话框
                    dismissPendingDialog()

                    // 使用 CommonContextWrapper 包装 Activity Context
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(activity)

                    pendingDialog = MaterialDialog(wrappedContext)
                        .title(text = "Native 崩溃详情")
                        .message(text = crashInfo)
                        .positiveButton(text = "复制日志") { dialog ->
                            copyToClipboard(activity, crashInfo)
                            dialog.dismiss()
                            manager.clearPendingNativeCrashFlag()
                        }
                        .negativeButton(text = "关闭") { dialog ->
                            dialog.dismiss()
                            manager.clearPendingNativeCrashFlag()
                        }
                        .neutralButton(text = "导出文件") { dialog ->
                            exportLog(activity, crashLogFile)
                            dialog.dismiss()
                            manager.clearPendingNativeCrashFlag()
                        }
                        .cancelable(true)

                    pendingDialog?.show()
                } catch (e: Throwable) {
                    WeLogger.e("[NativeCrashInterceptor] Failed to show crash detail dialog", e)
                    manager.clearPendingNativeCrashFlag()
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to show crash detail dialog", e)
        }
    }

    /**
     * 提取崩溃摘要信息
     */
    private fun extractCrashSummary(crashInfo: String): String {
        val lines = crashInfo.lines()
        val summary = StringBuilder()

        var foundCrashTime = false
        var foundSignal = false
        var foundStackTrace = false
        var stackTraceLineCount = 0

        for (line in lines) {
            when {
                line.startsWith("Crash Time:") -> {
                    summary.append(line).append("\n")
                    foundCrashTime = true
                }

                line.startsWith("Crash Type:") -> {
                    summary.append(line).append("\n\n")
                }

                line.startsWith("Signal:") -> {
                    summary.append(line).append("\n")
                    foundSignal = true
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
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Native Crash Log", text)
            clipboard?.setPrimaryClip(clip)
            WeLogger.i("NativeCrashInterceptor", "Native crash log copied to clipboard")
            showToast("Native 崩溃日志已复制到剪贴板")
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to copy to clipboard", e)
            showToast("复制失败: ${e.message}")
        }
    }

    /**
     * 分享日志
     */
    private fun shareLog(context: Context, logFile: File) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "WeKit Native Crash Log")
            intent.putExtra(
                android.content.Intent.EXTRA_TEXT,
                crashLogManager?.readCrashLog(logFile) ?: ""
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            val chooser = android.content.Intent.createChooser(intent, "分享 Native 崩溃日志")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            WeLogger.i("NativeCrashInterceptor", "Sharing native crash log")
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to share log", e)
            showToast("分享失败: ${e.message}")
        }
    }

    /**
     * 使用 SAF 导出日志
     */
    private fun exportLog(activity: android.app.Activity, logFile: File) {
        try {
            val wrappedContext = CommonContextWrapper.createAppCompatContext(activity)
            val fileName = "native_crash_${logFile.name}"

            SafUtils.requestSaveFile(wrappedContext)
                .setDefaultFileName(fileName)
                .setMimeType("text/plain")
                .onResult { uri ->
                    writeLogToUri(activity, logFile, uri)
                }
                .onCancel {
                    showToast("取消导出")
                }
                .commit()

        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to start SAF export", e)
            showToast("启动导出失败: ${e.message}")
        }
    }

    /**
     * 将日志写入 Uri
     */
    private fun writeLogToUri(context: Context, sourceFile: File, targetUri: android.net.Uri) {
        Thread {
            try {
                val manager = crashLogManager ?: return@Thread
                val crashInfo = manager.readCrashLog(sourceFile) ?: run {
                    Handler(Looper.getMainLooper()).post { showToast("读取源文件失败") }
                    return@Thread
                }

                context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    outputStream.write(crashInfo.toByteArray())
                    outputStream.flush()
                }

                Handler(Looper.getMainLooper()).post {
                    showToast("导出成功")
                }
                WeLogger.i("NativeCrashInterceptor", "Exported log to: $targetUri")
            } catch (e: Throwable) {
                WeLogger.e("[NativeCrashInterceptor] Failed to write to URI", e)
                Handler(Looper.getMainLooper()).post {
                    showToast("写入失败: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 显示Toast提示
     */
    private fun showToast(message: String) {
        try {
            val context = appContext ?: return
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: Throwable) {
            WeLogger.e("[NativeCrashInterceptor] Failed to show toast", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {
        nativeCrashHandler?.uninstall()
        super.unload(classLoader)
    }
}
