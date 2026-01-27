package moe.ouom.wekit.hooks.item.fix

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.afollestad.materialdialogs.MaterialDialog
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.util.crash.CrashLogManager
import moe.ouom.wekit.util.crash.JavaCrashHandler
import moe.ouom.wekit.util.log.WeLogger
import java.io.File

/**
 * 崩溃拦截功能
 * 拦截 Java 层崩溃,收集崩溃信息并在下次启动时展示
 *
 * @author cwuom
 * @since 1.0.0
 */
@HookItem(
    path = "优化与修复/崩溃拦截",
    desc = "拦截 Java 崩溃并记录详细信息，支持查看和导出日志"
)
class CrashInterceptor : BaseSwitchFunctionHookItem() {

    private var javaCrashHandler: JavaCrashHandler? = null
    private var crashLogManager: CrashLogManager? = null
    private var appContext: Context? = null
    private var hasPendingCrashToShow = false
    private var pendingDialog: MaterialDialog? = null

    override fun entry(classLoader: ClassLoader) {
        try {
            // 获取Application Context
            val activityThreadClass = classLoader.loadClass("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            appContext = currentApplicationMethod.invoke(null) as? Context

            if (appContext == null) {
                WeLogger.e("CrashInterceptor", "Failed to get application context")
                return
            }

            // 初始化崩溃日志管理器
            crashLogManager = CrashLogManager(appContext!!)

            // 安装Java崩溃拦截器
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

            if (manager.hasPendingCrash()) {
                WeLogger.i("CrashInterceptor", "Pending crash detected, will show dialog when Activity is ready")

                // 设置标记,等待Activity可用时显示对话框
                hasPendingCrashToShow = true

                // 同时显示Toast提示用户
                showToast("检测到上次崩溃,正在准备崩溃报告...")

                // 启动轮询机制,等待Activity可用
                startActivityPolling()
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to check pending crash", e)
        }
    }

    /**
     * 启动Activity轮询机制
     * 定期检查Activity是否可用,如果可用则显示待处理的崩溃对话框
     */
    private fun startActivityPolling() {
        val handler = Handler(Looper.getMainLooper())
        var retryCount = 0
        val maxRetries = 20 // 最多重试 20 次

        val pollingRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!hasPendingCrashToShow) {
                        WeLogger.d("CrashInterceptor", "No pending crash to show, stopping polling")
                        return
                    }

                    val activity = RuntimeConfig.getLauncherUIActivity()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        WeLogger.i("CrashInterceptor", "Activity is ready, showing pending crash dialog")
                        showPendingCrashDialog()
                        return
                    }

                    retryCount++
                    if (retryCount < maxRetries) {
                        WeLogger.d("CrashInterceptor", "Activity not ready, retry $retryCount/$maxRetries")
                        handler.postDelayed(this, 500) // 每500ms重试一次
                    } else {
                        WeLogger.w("CrashInterceptor", "Max retries reached, giving up on showing dialog")
                        hasPendingCrashToShow = false
                    }
                } catch (e: Throwable) {
                    WeLogger.e("[CrashInterceptor] Error in activity polling", e)
                }
            }
        }

        // 延迟1秒后开始第一次检查,给Activity足够的初始化时间
        handler.postDelayed(pollingRunnable, 1000)
        WeLogger.i("CrashInterceptor", "Started activity polling mechanism")
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
            WeLogger.e("[CrashInterceptor] Failed to check main process", e)
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

            // 如果Activity不可用,重新设置标记等待下次
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                WeLogger.w("CrashInterceptor", "Activity not available, will retry later")
                hasPendingCrashToShow = true
                return
            }

            val crashLogFile = manager.pendingCrashLogFile ?: run {
                WeLogger.w("CrashInterceptor", "No pending crash log file found")
                hasPendingCrashToShow = false
                return
            }

            val crashInfo = manager.readCrashLog(crashLogFile) ?: run {
                WeLogger.w("CrashInterceptor", "Failed to read crash log file")
                hasPendingCrashToShow = false
                return
            }

            // 提取崩溃摘要信息
            val summary = extractCrashSummary(crashInfo)

            WeLogger.i("CrashInterceptor", "Preparing to show crash dialog on main thread")

            Handler(Looper.getMainLooper()).post {
                try {
                    // 先关闭之前的对话框
                    dismissPendingDialog()

                    // 使用 CommonContextWrapper 包装 Activity Context
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(activity)

                    WeLogger.i("CrashInterceptor", "Creating MaterialDialog for crash report")

                    pendingDialog = MaterialDialog(wrappedContext)
                        .title(text = "检测到上次崩溃")
                        .message(text = summary)
                        .positiveButton(text = "查看详情") { dialog ->
                            dialog.dismiss()
                            hasPendingCrashToShow = false
                            showCrashDetailDialog(crashInfo, crashLogFile)
                        }
                        .negativeButton(text = "忽略") { dialog ->
                            dialog.dismiss()
                            hasPendingCrashToShow = false
                            manager.clearPendingCrashFlag()
                        }
                        .cancelable(false)

                    pendingDialog?.show()

                    // 成功显示对话框后重置标记
                    hasPendingCrashToShow = false
                    WeLogger.i("CrashInterceptor", "Crash dialog shown successfully")
                } catch (e: Throwable) {
                    WeLogger.e("[CrashInterceptor] Failed to show pending crash dialog", e)
                    hasPendingCrashToShow = false
                    manager.clearPendingCrashFlag()
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

            // 如果Activity不可用,使用Toast提示
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                WeLogger.w("CrashInterceptor", "Activity not available for detail dialog")
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
                        .title(text = "崩溃详情")
                        .message(text = crashInfo)
                        .positiveButton(text = "复制日志") { dialog ->
                            copyToClipboard(activity, crashInfo)
                            dialog.dismiss()
                            manager.clearPendingCrashFlag()
                        }
                        .negativeButton(text = "关闭") { dialog ->
                            dialog.dismiss()
                            manager.clearPendingCrashFlag()
                        }
                        .neutralButton(text = "分享日志") { dialog ->
                            shareLog(activity, crashLogFile)
                            dialog.dismiss()
                            manager.clearPendingCrashFlag()
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
     * 提取崩溃摘要信息
     */
    private fun extractCrashSummary(crashInfo: String): String {
        val lines = crashInfo.lines()
        val summary = StringBuilder()

        var foundCrashTime = false
        var foundException = false
        var exceptionLineCount = 0

        for (line in lines) {
            when {
                line.startsWith("Crash Time:") -> {
                    summary.append(line).append("\n")
                    foundCrashTime = true
                }
                line.startsWith("Crash Type:") -> {
                    summary.append(line).append("\n\n")
                }
                line.contains("Exception Stack Trace") -> {
                    foundException = true
                    summary.append("异常信息:\n")
                }
                foundException && exceptionLineCount < 10 -> {
                    if (line.trim().isNotEmpty() && !line.contains("====")) {
                        summary.append(line).append("\n")
                        exceptionLineCount++
                    }
                }
            }

            if (exceptionLineCount >= 10) break
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
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Crash Log", text)
            clipboard?.setPrimaryClip(clip)
            WeLogger.i("CrashInterceptor", "Crash log copied to clipboard")
            showToast("崩溃日志已复制到剪贴板")
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to copy to clipboard", e)
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
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "WeKit Crash Log")
            intent.putExtra(android.content.Intent.EXTRA_TEXT, crashLogManager?.readCrashLog(logFile) ?: "")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            val chooser = android.content.Intent.createChooser(intent, "分享崩溃日志")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            WeLogger.i("CrashInterceptor", "Sharing crash log")
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to share log", e)
            showToast("分享失败: ${e.message}")
        }
    }

    /**
     * 显示Toast提示
     */
    private fun showToast(message: String) {
        try {
            val context = appContext ?: return
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashInterceptor] Failed to show toast", e)
        }
    }
}
