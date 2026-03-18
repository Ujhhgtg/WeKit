package moe.ouom.wekit.hooks.items.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
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

    private val crashLogsManager by lazy { CrashLogsManager() }

    override fun onClick(context: Context) {
        showCrashLogList(context)
    }

    private fun showCrashLogList(context: Context) {
        val manager = crashLogsManager
        val logFiles = manager.allCrashLogs

        if (logFiles.isEmpty()) {
            ToastUtils.showToast(context, "暂无崩溃日志")
            return
        }

        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("崩溃日志 (${logFiles.size} 条)") },
                text = {
                    LazyColumn {
                        itemsIndexed(logFiles) { index, file ->
                            val time = formatEpoch(file.getLastModifiedTime().toMillis(), true)
                            val size = formatBytesSize(file.fileSize())
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = time,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = "$size · ${file.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                modifier = Modifier.clickable {
                                    showCrashLogOptions(context, file)
                                }
                            )
                            if (index < logFiles.lastIndex) HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dismiss()
                        confirmDeleteAllLogs(context)
                    }) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
                },
                confirmButton = { Button(dismiss) { Text("关闭") } }
            )
        }
    }

    private data class LogOption(val label: String, val icon: ImageVector, val destructive: Boolean = false)

    private fun showCrashLogOptions(context: Context, logFile: Path) {
        val options = listOf(
            LogOption("查看详情", Icons.AutoMirrored.Outlined.Article),
            LogOption("复制简易信息", Icons.AutoMirrored.Outlined.TextSnippet),
            LogOption("复制完整日志", Icons.Outlined.ContentCopy),
            LogOption("分享日志", Icons.Outlined.Share),
            LogOption("删除日志", Icons.Outlined.Delete, destructive = true)
        )

        showComposeDialog(context) {
            AlertDialogContent(
                title = {
                    Text(
                        text = logFile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column {
                        options.forEachIndexed { index, option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        dismiss()
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
                                    }
                                    .padding(horizontal = 4.dp, vertical = 14.dp)
                            ) {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (option.destructive)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (option.destructive)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (index < options.lastIndex) HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(dismiss) { Text("返回") }
                }
            )
        }
    }

    private fun showCrashLogDetail(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager
            val crashInfo = manager.readCrashLog(logFile) ?: run {
                ToastUtils.showToast(context, "读取日志失败")
                return
            }

            WeLogger.i(TAG, "Showing crash detail for: ${logFile.name}, size: ${crashInfo.length}")

            showComposeDialog(context) {
                AlertDialogContent(
                    title = {
                        Text(
                            text = logFile.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    text = {
                        val scrollState = rememberScrollState()
                        Text(
                            text = crashInfo,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .verticalScroll(scrollState)
                        )
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            dismiss()
                            showCrashLogOptions(context, logFile)
                        }) { Text("返回") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            dismiss()
                            copyLogToClipboard(context, logFile)
                        }) { Text("复制") }
                    }
                )
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to show crash log detail", e)
            ToastUtils.showToast(context, "显示详情失败: ${e.message}")
        }
    }

    private fun copyLogToClipboard(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager
            val crashInfo = manager.readCrashLog(logFile) ?: run {
                ToastUtils.showToast(context, "读取日志失败")
                return
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Crash Log", crashInfo))
            WeLogger.i(TAG, "Crash log copied to clipboard: ${logFile.name}")
            ToastUtils.showToast(context, "日志已复制到剪贴板")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to copy log to clipboard", e)
            ToastUtils.showToast(context, "复制失败: ${e.message}")
        }
    }

    private fun shareLog(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager
            val crashInfo = manager.readCrashLog(logFile) ?: run {
                ToastUtils.showToast(context, "读取日志失败")
                return
            }
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "WeKit Crash Log - ${logFile.name}")
                    putExtra(Intent.EXTRA_TEXT, crashInfo)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "分享崩溃日志"
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
            WeLogger.i(TAG, "Sharing crash log: ${logFile.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to share log", e)
            ToastUtils.showToast(context, "分享失败: ${e.message}")
        }
    }

    private fun confirmDeleteLog(context: Context, logFile: Path) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认删除") },
                text = { Text("确定要删除这条崩溃日志吗?") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteLog(context, logFile)
                        dismiss()
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) { Text("取消") }
                }
            )
        }
    }

    private fun deleteLog(context: Context, logFile: Path) {
        try {
            val manager = crashLogsManager
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

    private fun confirmDeleteAllLogs(context: Context) {
        showComposeDialog(context) {
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("确认删除") },
                text = { Text("确定要删除所有崩溃日志吗?") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteAllLogs(context)
                        dismiss()
                    }) {
                        Text("全部删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) { Text("取消") }
                }
            )
        }
    }

    private fun deleteAllLogs(context: Context) {
        try {
            val manager = crashLogsManager
            val count = manager.deleteAllCrashLogs()
            WeLogger.i(TAG, "Deleted $count crash logs")
            ToastUtils.showToast(context, "已删除 $count 条日志")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to delete all logs", e)
            ToastUtils.showToast(context, "删除失败: ${e.message}")
        }
    }

    private fun buildCrashSummary(logFile: Path): String {
        return try {
            val manager = crashLogsManager
            val crashInfo = manager.readCrashLog(logFile) ?: return "读取日志失败"

            buildString {
                append("文件名: ${logFile.name}\n")
                append("时间: ${formatEpoch(logFile.getLastModifiedTime().toMillis(), true)}\n")
                append("大小: ${formatBytesSize(logFile.fileSize())}\n\n")

                var foundException = false
                var lineCount = 0
                for (line in crashInfo.lines()) {
                    when {
                        line.startsWith("Crash Time:") || line.startsWith("Crash Type:") ->
                            append(line).append("\n")
                        line.contains("Exception Stack Trace") -> {
                            foundException = true
                            append("\n异常信息:\n")
                        }
                        foundException -> {
                            if (line.trim().isNotEmpty() && !line.contains("====")) {
                                append(line).append("\n")
                                lineCount++
                            }
                        }
                    }
                    if (lineCount >= 5) break
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to build crash summary", e)
            "构建简易信息失败: ${e.message}"
        }
    }

    private fun copyTextToClipboard(context: Context, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("CrashInfo", text))
            WeLogger.i(TAG, "Text copied to clipboard")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to copy text to clipboard", e)
            ToastUtils.showToast(context, "复制失败: ${e.message}")
        }
    }

    override fun noSwitchWidget(): Boolean = true
}
