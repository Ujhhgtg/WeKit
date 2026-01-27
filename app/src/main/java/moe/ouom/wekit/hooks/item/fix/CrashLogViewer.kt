package moe.ouom.wekit.hooks.item.fix

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.util.common.Toasts.showToast
import moe.ouom.wekit.util.crash.CrashLogManager
import moe.ouom.wekit.util.log.WeLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志查看器
 * 查看、导出、分享、删除崩溃日志
 *
 * @author cwuom
 * @since 1.0.0
 */
@HookItem(
    path = "优化与修复/崩溃日志查看器",
    desc = "查看历史崩溃日志"
)
class CrashLogViewer : BaseClickableFunctionHookItem() {

    private var crashLogManager: CrashLogManager? = null
    private var appContext: Context? = null

    override fun entry(classLoader: ClassLoader) {
        try {
            // 获取 Application Context
            val activityThreadClass = classLoader.loadClass("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            appContext = currentApplicationMethod.invoke(null) as? Context

            if (appContext == null) {
                WeLogger.e("CrashLogViewer", "Failed to get application context")
                return
            }

            // 初始化崩溃日志管理器
            crashLogManager = CrashLogManager(appContext!!)

            WeLogger.i("CrashLogViewer", "Crash log viewer initialized")
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to initialize crash log viewer", e)
        }
    }

    override fun onClick(context: Context?) {
        val ctx = context ?: appContext
        if (ctx == null) {
            WeLogger.e("CrashLogViewer", "Context is null")
            return
        }

        // 懒加载初始化 crashLogManager
        if (crashLogManager == null) {
            WeLogger.i("CrashLogViewer", "Lazy initializing CrashLogManager")
            try {
                crashLogManager = CrashLogManager(ctx)
            } catch (e: Throwable) {
                WeLogger.e("[CrashLogViewer] Failed to initialize CrashLogManager", e)
                showToast(ctx, "初始化失败: ${e.message}")
                return
            }
        }

        showCrashLogList(ctx)
    }

    /**
     * 显示崩溃日志列表
     */
    private fun showCrashLogList(context: Context) {
        try {
            val manager = crashLogManager ?: return
            val logFiles = manager.allCrashLogs

            if (logFiles.isEmpty()) {
                showToast(context, "暂无崩溃日志")
                return
            }

            // 构建日志列表
            val logItems = logFiles.map { file ->
                val time = formatTime(file.lastModified())
                val size = formatFileSize(file.length())
                "$time ($size)"
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                    val listDialog = MaterialDialog(wrappedContext)
                        .title(text = "崩溃日志列表 (共${logFiles.size}条)")
                        .listItems(
                            items = logItems,
                            waitForPositiveButton = false
                        ) { dialog, index, _ ->
                            WeLogger.d("CrashLogViewer", "List item clicked: index=$index")
                            dialog.dismiss()
                            Handler(Looper.getMainLooper()).postDelayed({
                                showCrashLogOptions(wrappedContext, logFiles[index])
                            }, 100)
                        }
                        .positiveButton(text = "全部删除") {
                            confirmDeleteAllLogs(context)
                        }
                        .negativeButton(text = "关闭")

                    listDialog.show()
                } catch (e: Throwable) {
                    WeLogger.e("[CrashLogViewer] Failed to show crash log list", e)
                    showToast(context, "显示列表失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to show crash log list", e)
            showToast(context, "加载日志列表失败: ${e.message}")
        }
    }

    /**
     * 显示崩溃日志操作选项
     */
    private fun showCrashLogOptions(context: Context, logFile: File) {
        try {
            val options = listOf("查看详情", "复制简易信息", "复制完整日志", "分享日志", "导出日志", "删除日志")

            Handler(Looper.getMainLooper()).post {
                try {
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                    val optionsDialog = MaterialDialog(wrappedContext)
                        .title(text = logFile.name)
                        .listItems(items = options) { dialog, index, _ ->
                            dialog.dismiss()
                            Handler(Looper.getMainLooper()).postDelayed({
                                when (index) {
                                    0 -> showCrashLogDetail(context, logFile)
                                    1 -> {
                                        // 复制简易信息
                                        val summary = buildCrashSummary(logFile)
                                        copyTextToClipboard(context, summary, "崩溃简易信息")
                                        showToast(context, "简易信息已复制")
                                    }
                                    2 -> copyLogToClipboard(context, logFile)
                                    3 -> shareLog(context, logFile)
                                    4 -> exportLog(context, logFile)
                                    5 -> confirmDeleteLog(context, logFile)
                                }
                            }, 100)
                        }
                        .negativeButton(text = "返回") {
                            Handler(Looper.getMainLooper()).postDelayed({
                                showCrashLogList(context)
                            }, 100)
                        }

                    optionsDialog.show()
                } catch (e: Throwable) {
                    WeLogger.e("[CrashLogViewer] Failed to show crash log options", e)
                    showToast(context, "显示选项失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to show crash log options", e)
        }
    }

    /**
     * 显示崩溃日志详情
     */
    private fun showCrashLogDetail(context: Context, logFile: File) {
        try {
            val manager = crashLogManager ?: run {
                showToast(context, "管理器未初始化")
                return
            }

            val crashInfo = manager.readCrashLog(logFile) ?: run {
                showToast(context, "读取日志失败")
                return
            }

            WeLogger.i("CrashLogViewer", "Showing crash detail for: ${logFile.name}, size: ${crashInfo.length}")

            Handler(Looper.getMainLooper()).post {
                try {
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                    // 创建可选择文本的对话框
                    val dialog = MaterialDialog(wrappedContext)
                        .title(text = "崩溃详情 - ${logFile.name}")
                        .message(text = crashInfo) {
                            // 设置消息文本可选择
                            messageTextView.setTextIsSelectable(true)
                        }
                        .positiveButton(text = "复制全部") {
                            copyLogToClipboard(context, logFile)
                        }
                        .negativeButton(text = "关闭")
                        .neutralButton(text = "分享") {
                            shareLog(context, logFile)
                        }

                    dialog.show()
                    WeLogger.i("CrashLogViewer", "Crash detail dialog shown successfully")
                } catch (e: Throwable) {
                    WeLogger.e("[CrashLogViewer] Failed to show crash log detail", e)
                    showToast(context, "显示详情失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to show crash log detail", e)
            showToast(context, "显示详情失败: ${e.message}")
        }
    }

    /**
     * 复制日志到剪贴板
     */
    private fun copyLogToClipboard(context: Context, logFile: File) {
        try {
            val manager = crashLogManager ?: return
            val crashInfo = manager.readCrashLog(logFile) ?: run {
                showToast(context, "读取日志失败")
                return
            }

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Crash Log", crashInfo)
            clipboard?.setPrimaryClip(clip)

            WeLogger.i("CrashLogViewer", "Crash log copied to clipboard: ${logFile.name}")
            showToast(context, "日志已复制到剪贴板")
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to copy log to clipboard", e)
            showToast(context, "复制失败: ${e.message}")
        }
    }

    /**
     * 分享日志
     */
    private fun shareLog(context: Context, logFile: File) {
        try {
            val manager = crashLogManager ?: return
            val crashInfo = manager.readCrashLog(logFile) ?: run {
                showToast(context, "读取日志失败")
                return
            }

            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, "WeKit Crash Log - ${logFile.name}")
            intent.putExtra(Intent.EXTRA_TEXT, crashInfo)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val chooser = Intent.createChooser(intent, "分享崩溃日志")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            WeLogger.i("CrashLogViewer", "Sharing crash log: ${logFile.name}")
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to share log", e)
            showToast(context, "分享失败: ${e.message}")
        }
    }

    /**
     * 导出日志到Download文件夹
     */
    private fun exportLog(context: Context, logFile: File) {
        try {
            val manager = crashLogManager ?: return

            // 获取Download目录
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )

            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }

            // 生成导出文件名
            val exportFileName = "wekit_${logFile.name}"
            val exportFile = File(downloadDir, exportFileName)

            // 复制文件
            logFile.copyTo(exportFile, overwrite = true)

            val message = "日志已导出到:\n${exportFile.absolutePath}"

            Handler(Looper.getMainLooper()).post {
                try {
                    val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                    MaterialDialog(wrappedContext)
                        .title(text = "导出成功")
                        .message(text = message)
                        .positiveButton(text = "复制路径") {
                            copyTextToClipboard(context, exportFile.absolutePath, "文件路径")
                            showToast(context, "路径已复制")
                        }
                        .negativeButton(text = "关闭")
                        .show()
                } catch (e: Throwable) {
                    WeLogger.e("[CrashLogViewer] Failed to show export dialog", e)
                }
            }

            WeLogger.i("CrashLogViewer", "Exported crash log to: ${exportFile.absolutePath}")
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to export log", e)
            showToast(context, "导出失败: ${e.message}")
        }
    }

    /**
     * 确认删除日志
     */
    private fun confirmDeleteLog(context: Context, logFile: File) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                MaterialDialog(wrappedContext)
                    .title(text = "确认删除")
                    .message(text = "确定要删除这条崩溃日志吗?")
                    .positiveButton(text = "删除") {
                        deleteLog(context, logFile)
                        // 延迟一下再显示列表
                        Handler(Looper.getMainLooper()).postDelayed({
                            showCrashLogList(context)
                        }, 100)
                    }
                    .negativeButton(text = "取消")
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[CrashLogViewer] Failed to show delete confirmation", e)
            }
        }
    }

    /**
     * 删除日志
     */
    private fun deleteLog(context: Context, logFile: File) {
        try {
            val manager = crashLogManager ?: return
            if (manager.deleteCrashLog(logFile)) {
                WeLogger.i("CrashLogViewer", "Crash log deleted: ${logFile.name}")
                showToast(context, "日志已删除")
            } else {
                showToast(context, "删除失败")
            }
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to delete log", e)
            showToast(context, "删除失败: ${e.message}")
        }
    }

    /**
     * 确认删除所有日志
     */
    private fun confirmDeleteAllLogs(context: Context) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                MaterialDialog(wrappedContext)
                    .title(text = "确认删除")
                    .message(text = "确定要删除所有崩溃日志吗?")
                    .positiveButton(text = "删除") {
                        deleteAllLogs(context)
                    }
                    .negativeButton(text = "取消")
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[CrashLogViewer] Failed to show delete all confirmation", e)
            }
        }
    }

    /**
     * 删除所有日志
     */
    private fun deleteAllLogs(context: Context) {
        try {
            val manager = crashLogManager ?: return
            val count = manager.deleteAllCrashLogs()
            WeLogger.i("CrashLogViewer", "Deleted $count crash logs")
            showToast(context, "已删除 $count 条日志")
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to delete all logs", e)
            showToast(context, "删除失败: ${e.message}")
        }
    }

    /**
     * 构建崩溃简易信息
     */
    private fun buildCrashSummary(logFile: File): String {
        try {
            val manager = crashLogManager ?: return "管理器未初始化"
            val crashInfo = manager.readCrashLog(logFile) ?: return "读取日志失败"

            val summary = StringBuilder()
            summary.append("文件名: ${logFile.name}\n")
            summary.append("时间: ${formatTime(logFile.lastModified())}\n")
            summary.append("大小: ${formatFileSize(logFile.length())}\n\n")

            // 提取关键信息
            val lines = crashInfo.lines()
            var foundException = false
            var lineCount = 0

            for (line in lines) {
                when {
                    line.startsWith("Crash Time:") || line.startsWith("Crash Type:") -> {
                        summary.append(line).append("\n")
                    }
                    line.contains("Exception Stack Trace") -> {
                        foundException = true
                        summary.append("\n异常信息:\n")
                    }
                    foundException && lineCount < 5 -> {
                        if (line.trim().isNotEmpty() && !line.contains("====")) {
                            summary.append(line).append("\n")
                            lineCount++
                        }
                    }
                }
                if (lineCount >= 5) break
            }

            return summary.toString()
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to build crash summary", e)
            return "构建简易信息失败: ${e.message}"
        }
    }

    /**
     * 复制文本到剪贴板
     */
    private fun copyTextToClipboard(context: Context, text: String, label: String = "Text") {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
            WeLogger.i("CrashLogViewer", "Text copied to clipboard: $label")
        } catch (e: Throwable) {
            WeLogger.e("[CrashLogViewer] Failed to copy text to clipboard", e)
            showToast(context, "复制失败: ${e.message}")
        }
    }

    /**
     * 格式化时间
     */
    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / 1024 / 1024} MB"
        }
    }

    /**
     * 隐藏开关控件
     */
    override fun noSwitchWidget(): Boolean = true
}
