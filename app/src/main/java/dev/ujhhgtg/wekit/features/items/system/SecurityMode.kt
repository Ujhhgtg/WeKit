package dev.ujhhgtg.wekit.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** 模块级「安全模式」开关（Nuke 1.0.2 移植，文件标记实现）。 */
object SecurityMode {

    private const val TAG = "SecurityMode"
    private val flagFile = KnownPaths.moduleData / "safe_mode.flag"

    const val TITLE = "安全模式"
    const val DESCRIPTION = "在不稳定环境中保守加载模块能力。"
    const val ENABLE_TITLE = "开启安全模式？"
    const val ENABLE_MESSAGE =
        "开启后会立即卸载当前已经运行的普通 Hook，只保留 ignoreSecurityMode = true 的核心 Hook。\n\n" +
            "下次启动时，普通 Hook 不会被加载，但仍会在设置页显示，方便你关闭安全模式后恢复使用。\n\n" +
            "确认开启安全模式？"

    val isEnabled: Boolean
        get() = flagFile.exists()

    fun showEnableConfirmDialog(context: Context, onConfirmed: () -> Unit) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(ENABLE_TITLE) },
                text = { Text(ENABLE_MESSAGE) },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        onConfirmed()
                    }) { Text("开启") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            runCatching {
                flagFile.parent?.let { java.nio.file.Files.createDirectories(it) }
                flagFile.writeText("")
            }.onFailure {
                WeLogger.e(TAG, "failed to create safe mode flag", it)
            }
        } else {
            runCatching { flagFile.deleteIfExists() }.onFailure {
                WeLogger.e(TAG, "failed to delete safe mode flag", it)
            }
        }
        WeLogger.i(TAG, "safe mode flag ${if (enabled) "created" else "deleted"}: $flagFile")
    }
}
