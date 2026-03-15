package moe.ouom.wekit.hooks.items.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.utils.CommonContextWrapper
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.crash.CrashLogsManager
import moe.ouom.wekit.utils.formatBytesSize
import moe.ouom.wekit.utils.formatEpoch
import moe.ouom.wekit.utils.logging.WeLogger
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

@HookItem(
    path = "调试/崩溃日志查看器",
    desc = "查看历史崩溃日志"
)
object CrashLogsViewer : ClickableHookItem() {

    private val TAG = nameof(CrashLogsViewer)

    private var crashLogsManager: CrashLogsManager? = null

    override fun onClick(context: Context) {
        // 懒加载初始化 crashLogManager
        if (crashLogsManager == null) {
            WeLogger.i(TAG, "Lazy initializing CrashLogManager")
            try {
                crashLogsManager = CrashLogsManager()
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Failed to initialize CrashLogManager", e)
                ToastUtils.showToast(context, "初始化失败: ${e.message}")
                return
            }
        }

        showCrashLogList(context)
    }

    /**
     * 显示崩溃日志列表
     */
    private fun showCrashLogList(context: Context) {
        try {
            val manager = crashLogsManager ?: return
            val logFiles = manager.allCrashLogs

            if (logFiles.isEmpty()) {
                ToastUtils.showToast(context, "暂无崩溃日志")
                return
            }

            // 构建日志列表
            val logItems = logFiles.map { file ->
                val time = formatEpoch(file.getLastModifiedTime().toMillis(), true)
                val size = formatBytesSize(file.fileSize())
                "$time ($size)"
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    val wrappedContext =
                        CommonContextWrapper.createAppCompatContext(context)

                    val listDialog = MaterialDialog(wrappedContext)
                        .title(text = "崩溃日志列表 (共${logFiles.size}条)")
                        .listItems(
                            items = logItems,
                            waitForPositiveButton = false
                        ) { dialog, index, _ ->
                            WeLogger.d(TAG, "List item clicked: index=$index")
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
                    WeLogger.e(TAG, "Failed to show crash log list", e)
                    ToastUtils.showToast(context, "显示列表失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to show crash log list", e)
            ToastUtils.showToast(context, "加载日志列表失败: ${e.message}")
        }
    }

    /**
     * 显示崩溃日志操作选项
     */
    private fun showCrashLogOptions(context: Context, logFile: Path) {
        try {
            val options = listOf(
                "查看详情",
                "复制简易信息",
                "复制完整日志",
                "分享日志",
                "删除日志"
            )

            Handler(Looper.getMainLooper()).post {
                try {
                    val wrappedContext =
                        CommonContextWrapper.createAppCompatContext(context)

                    val optionsDialog = MaterialDialog(wrappedContext)
                        .title(text = logFile.name)
                        .listItems(items = options) { dialog, index, _ ->
                            dialog.dismiss()
                            Handler(Looper.getMainLooper()).postDelayed({
                                when (index) {
                                    0 -> showCrashLogDetail(context, logFile)
                                    1 -> {
                                        val summary = buildCrashSummary(logFile)
                                        copyTextToClipboard(context, summary)
                                        ToastUtils.showToast(context, "简易信息已复制")
                                    }
                                    2 -> copyLogToClipboard(context, logFile)
                                    3 -> shareLog(context, logFile)
                                    4 -> confirmDeleteLog(context, logFile)
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
                    WeLogger.e(TAG, "Failed to show crash log options", e)
                    ToastUtils.showToast(context, "显示选项失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to show crash log options", e)
        }
    }

    /**
     * 显示崩溃日志详情
     */
    private fun showCrashLogDetail(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager ?: run {
                ToastUtils.showToast(context, "管理器未初始化")
                return
            }

            val crashInfo = manager.readCrashLog(logFile) ?: run {
                ToastUtils.showToast(context, "读取日志失败")
                return
            }

            WeLogger.i(
                "CrashLogViewer",
                "Showing crash detail for: ${logFile.name}, size: ${crashInfo.length}"
            )

            Handler(Looper.getMainLooper()).post {
                try {
                    val wrappedContext =
                        CommonContextWrapper.createAppCompatContext(context)

                    // 创建可选择文本的对话框
                    val dialog = MaterialDialog(wrappedContext)
                        .title(text = "崩溃详情 - ${logFile.name}")
                        .message(text = crashInfo) {
                            // 设置消息文本可选择
                            messageTextView.setTextIsSelectable(true)
                        }
                        .positiveButton(text = "复制") {
                            copyLogToClipboard(context, logFile)
                        }
                        .negativeButton(text = "关闭")

                    dialog.show()
                    WeLogger.i(TAG, "Crash detail dialog shown successfully")
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Failed to show crash log detail", e)
                    ToastUtils.showToast(context, "显示详情失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to show crash log detail", e)
            ToastUtils.showToast(context, "显示详情失败: ${e.message}")
        }
    }

    /**
     * 复制日志到剪贴板
     */
    private fun copyLogToClipboard(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager ?: return
            val crashInfo = manager.readCrashLog(logFile) ?: run {
                ToastUtils.showToast(context, "读取日志失败")
                return
            }

            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Crash Log", crashInfo)
            clipboard?.setPrimaryClip(clip)

            WeLogger.i(TAG, "Crash log copied to clipboard: ${logFile.name}")
            ToastUtils.showToast(context, "日志已复制到剪贴板")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to copy log to clipboard", e)
            ToastUtils.showToast(context, "复制失败: ${e.message}")
        }
    }

    /**
     * 分享日志
     */
    private fun shareLog(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager ?: return
            val crashInfo = manager.readCrashLog(logFile) ?: run {
                ToastUtils.showToast(context, "读取日志失败")
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

            WeLogger.i(TAG, "Sharing crash log: ${logFile.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to share log", e)
            ToastUtils.showToast(context, "分享失败: ${e.message}")
        }
    }

    /**
     * 确认删除日志
     */
    private fun confirmDeleteLog(context: Context, logFile: Path) {
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
            WeLogger.e(TAG, "Failed to show delete confirmation", e)
        }
    }

    /**
     * 删除日志
     */
    private fun deleteLog(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager ?: return
            if (manager.deleteCrashLog(logFile)) {
                WeLogger.i(TAG, "Crash log deleted: ${logFile.name}")
                ToastUtils.showToast(context, "日志已删除")
            } else {
                ToastUtils.showToast(context, "删除失败")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to delete log", e)
            ToastUtils.showToast(context, "删除失败: ${e.message}")
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
                WeLogger.e(TAG, "Failed to show delete all confirmation", e)
            }
        }
    }

    /**
     * 删除所有日志
     */
    private fun deleteAllLogs(context: Context) {
        try {
            val manager = crashLogsManager ?: return
            val count = manager.deleteAllCrashLogs()
            WeLogger.i(TAG, "Deleted $count crash logs")
            ToastUtils.showToast(context, "已删除 $count 条日志")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to delete all logs", e)
            ToastUtils.showToast(context, "删除失败: ${e.message}")
        }
    }

    /**
     * 构建崩溃简易信息
     */
    private fun buildCrashSummary(logFile: Path): String {
        try {
            val manager = crashLogsManager ?: return "管理器未初始化"
            val crashInfo = manager.readCrashLog(logFile) ?: return "读取日志失败"

            val summary = StringBuilder()
            summary.append("文件名: ${logFile.name}\n")
            summary.append("时间: ${formatEpoch(logFile.getLastModifiedTime().toMillis(), true)}\n")
            summary.append("大小: ${formatBytesSize(logFile.fileSize())}\n\n")

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

                    foundException -> {
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
            WeLogger.e(TAG, "Failed to build crash summary", e)
            return "构建简易信息失败: ${e.message}"
        }
    }

    /**
     * 复制文本到剪贴板
     */
    private fun copyTextToClipboard(context: Context, text: String) {
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("CrashInfo", text)
            clipboard?.setPrimaryClip(clip)
            WeLogger.i(TAG, "Text copied to clipboard: $text")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to copy text to clipboard", e)
            ToastUtils.showToast(context, "复制失败: ${e.message}")
        }
    }

    /**
     * 隐藏开关控件
     */
    override fun noSwitchWidget(): Boolean = true
}