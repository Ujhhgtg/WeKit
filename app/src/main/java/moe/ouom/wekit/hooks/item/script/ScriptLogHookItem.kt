package moe.ouom.wekit.hooks.item.script

import android.content.Context
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.util.common.Toasts.showToast
import moe.ouom.wekit.util.log.WeLogger
import moe.ouom.wekit.util.script.ScriptLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 脚本日志Hook项
 */
@HookItem(
    path = "脚本管理/脚本日志",
    desc = "查看 JavaScript 脚本执行日志"
)
class ScriptLogHookItem : BaseClickableFunctionHookItem() {

    private var scriptLogger: ScriptLogger? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onClick(context: Context) {

        if (scriptLogger == null) {
            try {
                scriptLogger = ScriptLogger.getInstance()
                scriptLogger?.initialize()
            } catch (e: Throwable) {
                WeLogger.e("[ScriptLogHookItem] Failed to initialize ScriptLogger", e)
                showToast(context, "初始化失败: ${e.message}")
                return
            }
        }

        showLogViewer(context)
    }

    override fun noSwitchWidget(): Boolean = true

    /**
     * 显示日志查看器
     */
    private fun showLogViewer(context: Context) {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        val options = listOf(
            "查看所有日志",
            "按脚本查看",
            "按级别查看",
            "清除所有日志",
            "导出日志"
        )

        MaterialDialog(wrappedContext)
            .title(text = "脚本日志管理")
            .listItems(items = options) { dialog, index, _ ->
                dialog.dismiss()
                when (index) {
                    0 -> showAllLogs(context)
                    1 -> showScriptFilter(context)
                    2 -> showLevelFilter(context)
                    3 -> confirmClearLogs(context)
                    4 -> exportLogs(context)
                }
            }
            .negativeButton(text = "关闭")
            .show()
    }

    /**
     * 显示所有日志
     */
    private fun showAllLogs(context: Context) {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        val logs = scriptLogger?.getAllLogs() ?: emptyList()
        if (logs.isEmpty()) {
            showToast(context, "暂无日志")
            return
        }

        val logItems = logs.map { log ->
            val time = dateFormat.format(Date(log.timestamp))
            "[${log.level}] $time - ${log.entryScriptName}\n${log.message}"
        }

        MaterialDialog(wrappedContext)
            .title(text = "脚本日志 (共${logs.size}条)")
            .listItems(items = logItems) { dialog, index, _ ->
                dialog.dismiss()
                showLogDetail(context, logs[index])
            }
            .positiveButton(text = "清空") {
                confirmClearLogs(context)
            }
            .negativeButton(text = "返回") {
                showLogViewer(context)
            }
            .show()
    }

    /**
     * 显示日志详情
     */
    private fun showLogDetail(context: Context, logEntry: ScriptLogger.LogEntry) {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        val detail = buildString {
            append("时间: ${dateFormat.format(Date(logEntry.timestamp))}\n")
            append("脚本: ${logEntry.entryScriptName}\n")
            append("级别: ${logEntry.level}\n")
            append("\n消息:\n${logEntry.message}")
        }

        MaterialDialog(wrappedContext)
            .title(text = "日志详情")
            .message(text = detail)
            .positiveButton(text = "复制") {
                copyToClipboard(context, detail)
                showToast(context, "已复制到剪贴板")
            }
            .negativeButton(text = "返回") {
                showAllLogs(context)
            }
            .show()
    }

    /**
     * 按脚本筛选
     */
    private fun showScriptFilter(context: Context) {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        val logs = scriptLogger?.getAllLogs() ?: emptyList()
        val scripts = logs.map { it.entryScriptName }.distinct()
        if (scripts.isEmpty()) {
            showToast(context, "暂无日志")
            return
        }

        MaterialDialog(wrappedContext)
            .title(text = "选择脚本")
            .listItems(items = scripts) { dialog, index, _ ->
                dialog.dismiss()
                val scriptName = scripts[index]
                val scriptLogs = scriptLogger?.getLogsByScript(scriptName) ?: emptyList()
                showFilteredLogs(context, scriptLogs, "脚本: $scriptName")
            }
            .negativeButton(text = "返回") {
                showLogViewer(context)
            }
            .show()
    }

    /**
     * 按级别筛选
     */
    private fun showLevelFilter(context: Context) {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        val logs = scriptLogger?.getAllLogs() ?: emptyList()
        val levels = logs.map { it.level }.distinct()
        if (levels.isEmpty()) {
            showToast(context, "暂无日志")
            return
        }

        MaterialDialog(wrappedContext)
            .title(text = "选择级别")
            .listItems(items = levels) { dialog, index, _ ->
                dialog.dismiss()
                val level = levels[index]
                val levelLogs = scriptLogger?.getLogsByLevel(level) ?: emptyList()
                showFilteredLogs(context, levelLogs, "级别: $level")
            }
            .negativeButton(text = "返回") {
                showLogViewer(context)
            }
            .show()
    }

    /**
     * 显示筛选后的日志
     */
    private fun showFilteredLogs(
        context: Context,
        logs: List<ScriptLogger.LogEntry>,
        title: String
    ) {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        if (logs.isEmpty()) {
            showToast(context, "无相关日志")
            return
        }

        val logItems = logs.map { log ->
            val time = dateFormat.format(Date(log.timestamp))
            "[${log.level}] $time - ${log.entryScriptName}\n${log.message}"
        }

        MaterialDialog(wrappedContext)
            .title(text = "$title (共${logs.size}条)")
            .listItems(items = logItems) { dialog, index, _ ->
                dialog.dismiss()
                showLogDetail(context, logs[index])
            }
            .negativeButton(text = "返回") {
                showLogViewer(context)
            }
            .show()
    }

    /**
     * 确认清除日志
     */
    private fun confirmClearLogs(context: Context) {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

        MaterialDialog(wrappedContext)
            .title(text = "确认清除")
            .message(text = "确定要清除所有脚本日志吗？")
            .positiveButton(text = "清除") {
                scriptLogger?.clearAll()
                showToast(context, "日志已清除")
                showLogViewer(context)
            }
            .negativeButton(text = "取消")
            .show()
    }

    /**
     * 导出日志
     */
    private fun exportLogs(context: Context) {
        val logs = scriptLogger?.getAllLogs() ?: emptyList()
        if (logs.isEmpty()) {
            showToast(context, "暂无日志可导出")
            return
        }

        val exportContent = logs.joinToString("\n\n") { log ->
            val time = dateFormat.format(Date(log.timestamp))
            "[${log.level}] $time - ${log.entryScriptName}\n${log.message}"
        }

        copyToClipboard(context, exportContent)
        showToast(context, "日志已导出到剪贴板")
    }

    /**
     * 复制到剪贴板
     */
    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Script Log", text)
            clipboard?.setPrimaryClip(clip)
        } catch (e: Exception) {
            WeLogger.e("Failed to copy to clipboard", e)
        }
    }
}