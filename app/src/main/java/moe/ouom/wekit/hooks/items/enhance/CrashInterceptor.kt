package moe.ouom.wekit.hooks.items.enhance

import android.app.Activity
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
import moe.ouom.wekit.utils.crash.JavaCrashHandler
import moe.ouom.wekit.utils.io.SafUtils
import moe.ouom.wekit.utils.log.WeLogger
import java.io.File

@HookItem(
    path = "优化与修复/崩溃拦截",
    desc = "拦截 Java 层崩溃并记录详细信息，支持查看和导出日志"
)
object CrashInterceptor : BaseSwitchFunctionHookItem() {

    private var javaCrashHandler: JavaCrashHandler? = null
    private var crashLogManager: CrashLogManager? = null
    private var appContext: Context? = null
    private var hasPendingCrashToShow = false
    private var pendingDialog: MaterialDialog? = null

    override fun entry(classLoader: ClassLoader) {
        try {
            // 获取 Application Context
            val activityThreadClass = loadClass("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            appContext = currentApplicationMethod.invoke(null) as? Context

            if (appContext == null) {
                WeLogger.e("CrashInterceptor", "Failed to get application context")
                return
            }

            // 初始化崩溃日志管理器
            crashLogManager = CrashLogManager(appContext!!)

            // 安装 Java 崩溃拦截器
            javaCrashHandler = JavaCrashHandler(appContext!!)
            javaCrashHandler?.install()

            WeLogger.i("CrashInterceptor", "Crash interceptor installed successfully")

            // 检查是否有待处理的崩溃
            checkPendingCrash()

        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to install crash interceptor", e)
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
                WeLogger.d("CrashInterceptor", "Skipping pending crash check in non-main process")
                return
            }

            // 只检查Java崩溃
            if (manager.hasPendingJavaCrash()) {
                WeLogger.i(
                    "CrashInterceptor",
                    "Pending Java crash detected, will show dialog when Activity is ready"
                )
                hasPendingCrashToShow = true
                showToast("检测到上次 Java 崩溃,正在准备崩溃报告...")
                startActivityPolling()
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to check pending crash", e)
        }
    }

    /**
     * 启动Activity轮询机制
     */
    private fun startActivityPolling() {
        val handler = Handler(Looper.getMainLooper())
        var retryCount = 0
        val maxRetries = 20

        val pollingRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!hasPendingCrashToShow) return

                    val activity = RuntimeConfig.getLauncherUIActivity()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        WeLogger.i(
                            "CrashInterceptor",
                            "Activity is ready, showing pending crash dialog"
                        )
                        showPendingCrashDialog()
                        return
                    }

                    retryCount++
                    if (retryCount < maxRetries) {
                        handler.postDelayed(this, 500)
                    } else {
                        WeLogger.w("CrashInterceptor", "Max retries reached")
                        hasPendingCrashToShow = false
                    }
                } catch (e: Throwable) {
                    WeLogger.e("[CrashInterceptor] Error in activity polling", e)
                }
            }
        }
        handler.postDelayed(pollingRunnable, 1000)
    }

    private fun isMainProcess(): Boolean {
        return try {
            val context = appContext ?: return false
            val processName = getProcessName()
            processName == context.packageName
        } catch (e: Throwable) {
            false
        }
    }

    private fun getProcessName(): String {
        return try {
            File("/proc/${Process.myPid()}/cmdline").readText().trim('\u0000')
        } catch (e: Throwable) {
            ""
        }
    }

    private fun dismissPendingDialog() {
        try {
            pendingDialog?.dismiss()
            pendingDialog = null
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to dismiss pending dialog", e)
        }
    }

    /**
     * 显示待处理的崩溃对话框
     */
    private fun showPendingCrashDialog() {
        try {
            val manager = crashLogManager ?: return
            val activity = RuntimeConfig.getLauncherUIActivity()

            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                hasPendingCrashToShow = true
                return
            }

            // 使用Java专用方法获取崩溃日志
            val crashLogFile = manager.pendingJavaCrashLogFile ?: run {
                hasPendingCrashToShow = false
                return
            }

            val crashInfo = manager.readCrashLog(crashLogFile) ?: run {
                hasPendingCrashToShow = false
                return
            }

            val summary = extractCrashSummary(crashInfo)

            Handler(Looper.getMainLooper()).post {
                try {
                    dismissPendingDialog()
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(activity)

                    pendingDialog = MaterialDialog(wrappedContext)
                        .title(text = "检测到上次 Java 崩溃")
                        .message(text = summary)
                        .positiveButton(text = "查看详情") { dialog ->
                            dialog.dismiss()
                            hasPendingCrashToShow = false
                            showCrashDetailDialog(crashInfo, crashLogFile)
                        }
                        .negativeButton(text = "忽略") { dialog ->
                            dialog.dismiss()
                            hasPendingCrashToShow = false
                            manager.clearPendingJavaCrashFlag()
                        }
                        .cancelable(false)

                    pendingDialog?.show()
                    hasPendingCrashToShow = false
                } catch (e: Throwable) {
                    WeLogger.e("[CrashInterceptor] Failed to show pending crash dialog", e)
                    hasPendingCrashToShow = false
                    manager.clearPendingJavaCrashFlag()
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to show pending crash dialog", e)
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

            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                showToast("无法显示详情, 请稍后重试")
                return
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    dismissPendingDialog()
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(activity)

                    // 限制显示长度，防止卡死
                    val maxDisplayLength = 15 * 1024
                    val displayInfo = if (crashInfo.length > maxDisplayLength) {
                        crashInfo.take(maxDisplayLength) +
                                "\n\n========================================\n" +
                                "【提示】日志内容过长，此处仅展示部分内容。\n" +
                                "请点击「导出文件」以保存完整日志。\n" +
                                "========================================"
                    } else {
                        crashInfo
                    }

                    pendingDialog = MaterialDialog(wrappedContext)
                        .title(text = "Java 崩溃详情")
                        .message(text = displayInfo) {
                            messageTextView.setTextIsSelectable(true)
                        }
                        .positiveButton(text = "复制完整日志") { dialog ->
                            // 读取完整日志用于复制
                            val fullCrashInfo = manager.readFullCrashLog(crashLogFile) ?: crashInfo
                            copyToClipboard(activity, fullCrashInfo)
                            dialog.dismiss()
                            manager.clearPendingJavaCrashFlag()
                        }
                        .negativeButton(text = "关闭") { dialog ->
                            dialog.dismiss()
                            manager.clearPendingJavaCrashFlag()
                        }
                        .neutralButton(text = "导出文件") { dialog ->
                            exportLog(activity, crashLogFile)
                            dialog.dismiss()
                            manager.clearPendingJavaCrashFlag()
                        }
                        .cancelable(true)

                    pendingDialog?.show()
                } catch (e: Throwable) {
                    WeLogger.e("[CrashInterceptor] Failed to show crash detail dialog", e)
                    manager.clearPendingCrashFlag()
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to show crash detail dialog", e)
        }
    }

    /**
     * 使用 SAF 导出日志
     */
    private fun exportLog(activity: Activity, logFile: File) {
        try {
            val wrappedContext = CommonContextWrapper.createAppCompatContext(activity)
            val fileName = "crash_${logFile.name}"

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
            WeLogger.e("[CrashInterceptor] Failed to start SAF export", e)
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
                // 使用 readFullCrashLog 读取完整日志，不截断
                val crashInfo = manager.readFullCrashLog(sourceFile) ?: run {
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
                WeLogger.i("CrashInterceptor", "Exported log to: $targetUri")
            } catch (e: Throwable) {
                WeLogger.e("[CrashInterceptor] Failed to write to URI", e)
                Handler(Looper.getMainLooper()).post {
                    showToast("写入失败: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 提取崩溃摘要信息
     */
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
        if (summary.isEmpty()) return "崩溃信息解析失败\n\n点击\"查看详情\"查看完整日志"
        summary.append("\n点击\"查看详情\"查看完整日志")
        return summary.toString()
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Crash Log", text)
            clipboard?.setPrimaryClip(clip)
            showToast("已复制到剪贴板")
        } catch (e: Throwable) {
            showToast("复制失败")
        }
    }

    private fun showToast(message: String) {
        try {
            val context = appContext ?: return
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to show toast", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {
        javaCrashHandler?.uninstall()
        super.unload(classLoader)
    }
}