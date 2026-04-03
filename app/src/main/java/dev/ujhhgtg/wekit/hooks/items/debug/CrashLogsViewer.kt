package dev.ujhhgtg.wekit.hooks.items.debug

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
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Article
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Text_snippet
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.copyToClipboard
import dev.ujhhgtg.wekit.utils.crash.CrashLogsManager
import dev.ujhhgtg.wekit.utils.formatBytesSize
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.showToast
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

@HookItem(
    path = "调试/崩溃日志查看器",
    desc = "查看历史崩溃日志"
)
object CrashLogsViewer : ClickableHookItem() {

    private val TAG = nameOf(CrashLogsViewer)

    override fun onClick(context: Context) {
        showCrashLogList(context)
    }

    private fun showCrashLogList(context: Context) {
        val logFiles = CrashLogsManager.allCrashLogs

        if (logFiles.isEmpty()) {
            showToast(context, "暂无崩溃日志")
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
                        confirmDeleteAllLogs(context, onDismiss)
                    }) { Text("全部删除") }
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } }
            )
        }
    }

    private fun showCrashLogOptions(context: Context, logFile: Path) {
        val options = listOf(
            "查看详情" to MaterialSymbols.Outlined.Article,
            "复制简易信息" to MaterialSymbols.Outlined.Text_snippet,
            "复制完整日志" to MaterialSymbols.Outlined.Content_copy,
            "分享日志" to MaterialSymbols.Outlined.Share,
            "删除日志" to MaterialSymbols.Outlined.Delete
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
                                        onDismiss()
                                        when (index) {
                                            0 -> showCrashLogDetail(context, logFile)
                                            1 -> {
                                                val summary = buildCrashSummary(logFile)
                                                copyToClipboard(context, summary)
                                                showToast(context, "简易信息已复制")
                                            }

                                            2 -> copyLogToClipboard(context, logFile)
                                            3 -> shareLog(context, logFile)
                                            4 -> confirmDeleteLog(context, logFile)
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 14.dp)
                            ) {
                                Icon(
                                    imageVector = option.second,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    text = option.first,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (index < options.lastIndex) HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onDismiss) { Text("返回") }
                }
            )
        }
    }

    private fun showCrashLogDetail(context: Context, logFile: Path) {
        val crashInfo = CrashLogsManager.readCrashLog(logFile) ?: run {
            showToast(context, "读取日志失败")
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
                        onDismiss()
                        showCrashLogOptions(context, logFile)
                    }) { Text("返回") }
                },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        copyLogToClipboard(context, logFile)
                    }) { Text("复制") }
                }
            )
        }
    }

    private fun copyLogToClipboard(context: Context, logFile: Path) {
        val crashInfo = CrashLogsManager.readCrashLog(logFile) ?: run {
            showToast(context, "读取日志失败")
            return
        }
        copyToClipboard(context, crashInfo)
        showToast(context, "已复制")
    }

    private fun shareLog(context: Context, logFile: Path) {
        val crashInfo = CrashLogsManager.readCrashLog(logFile) ?: run {
            showToast(context, "读取日志失败")
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
    }

    private fun confirmDeleteLog(context: Context, logFile: Path) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认删除") },
                text = { Text("确定要删除这条崩溃日志吗?") },
                confirmButton = {
                    Button(onClick = {
                        deleteLog(context, logFile)
                        onDismiss()
                    }) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            )
        }
    }

    private fun deleteLog(context: Context, logFile: Path) {
        try {
            if (CrashLogsManager.deleteCrashLog(logFile)) {
                WeLogger.i(TAG, "Crash log deleted: ${logFile.name}")
                showToast(context, "日志已删除")
            } else {
                showToast(context, "删除失败")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to delete log", e)
            showToast(context, "删除失败: ${e.message}")
        }
    }

    private fun confirmDeleteAllLogs(context: Context, dismissParent: () -> Unit) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认删除") },
                text = { Text("确定要删除所有崩溃日志吗?") },
                confirmButton = {
                    Button(onClick = {
                        deleteAllLogs(context)
                        onDismiss()
                        dismissParent()
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                }
            )
        }
    }

    private fun deleteAllLogs(context: Context) {
        try {
            val count = CrashLogsManager.deleteAllCrashLogs()
            WeLogger.i(TAG, "deleted $count crash logs")
            showToast(context, "已删除 $count 条日志")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to delete all logs", e)
            showToast(context, "删除失败: ${e.message}")
        }
    }

    private fun buildCrashSummary(logFile: Path): String {
        return try {
            val crashInfo = CrashLogsManager.readCrashLog(logFile) ?: return "读取日志失败"

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
            WeLogger.e(TAG, "failed to build crash summary", e)
            "构建简易信息失败: ${e.message}"
        }
    }

    override val noSwitchWidget = true
}
