package dev.ujhhgtg.wekit.hooks.items.debug

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.crash.NativeCrashHandler
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(
    path = "调试/测试崩溃",
    desc = "没事别点"
)
object TriggerCrash : ClickableHookItem() {

    private val TAG = nameOf(TriggerCrash)

    override fun onClick(context: Context) {
        showCrashCategoryDialog(context)
    }

    private fun showCrashCategoryDialog(context: Context) {
        val categories = listOf("Java 层崩溃", "Native 层崩溃")
        showCrashTypeListDialog(
            context = context,
            title = "选择崩溃类别",
            items = categories,
            onBack = null,
            onSelect = { index ->
                when (index) {
                    0 -> showJavaCrashTypeDialog(context)
                    1 -> showNativeCrashTypeDialog(context)
                }
            }
        )
    }

    private fun showJavaCrashTypeDialog(context: Context) {
        val crashTypes = listOf(
            "空指针异常 (NullPointerException)",
            "数组越界 (ArrayIndexOutOfBoundsException)",
            "类型转换异常 (ClassCastException)",
            "算术异常 (ArithmeticException)",
            "栈溢出 (StackOverflowError)"
        )
        showCrashTypeListDialog(
            context = context,
            title = "选择 Java 崩溃类型",
            items = crashTypes,
            onBack = { showCrashCategoryDialog(context) },
            onSelect = { index -> confirmTriggerCrash(context, "Java", index) }
        )
    }

    private fun showNativeCrashTypeDialog(context: Context) {
        val crashTypes = listOf(
            "段错误 (SIGSEGV - 空指针访问)",
            "异常终止 (SIGABRT - abort)",
            "浮点异常 (SIGFPE - 除零错误)",
            "非法指令 (SIGILL)",
            "总线错误 (SIGBUS - 未对齐访问)"
        )
        showCrashTypeListDialog(
            context = context,
            title = "选择 Native 崩溃类型",
            items = crashTypes,
            onBack = { showCrashCategoryDialog(context) },
            onSelect = { index -> confirmTriggerCrash(context, "Native", index) }
        )
    }

    /**
     * Shared composable list dialog for crash type selection.
     */
    private fun showCrashTypeListDialog(
        context: Context,
        title: String,
        items: List<String>,
        onBack: (() -> Unit)?,
        onSelect: (Int) -> Unit,
    ) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(title) },
                text = {
                    Column {
                        items.forEachIndexed { index, item ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = item,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onSelect(index)
                                }
                            )
                            if (index < items.lastIndex) HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                },
                confirmButton = {
                    if (onBack != null) {
                        TextButton(onClick = { onDismiss(); onBack() }) { Text("返回") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                }
            )
        }
    }

    private fun confirmTriggerCrash(context: Context, category: String, crashType: Int) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认触发崩溃") },
                text = { Text("确定要触发 $category 测试崩溃吗?\n这可能导致微信数据丢失") },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        when (category) {
                            "Java" -> triggerJavaCrash(crashType)
                            "Native" -> NativeCrashHandler.triggerCrash(crashType)
                        }
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            )
        }
    }

    private fun triggerJavaCrash(crashType: Int) {
        WeLogger.w(TAG, "Triggering Java test crash, type: $crashType")
        when (crashType) {
            0 -> triggerNullPointerException()
            1 -> triggerArrayIndexOutOfBoundsException()
            2 -> triggerClassCastException()
            3 -> triggerArithmeticException()
            4 -> triggerStackOverflowError()
            else -> triggerNullPointerException()
        }
    }

    private fun triggerNullPointerException() {
        val obj: String? = null
        @Suppress("KotlinConstantConditions")
        obj!!.length
    }

    private fun triggerArrayIndexOutOfBoundsException() {
        val array = arrayOf(1, 2, 3)

        @Suppress("UNUSED_VARIABLE", "unused")
        val value = array[10]
    }

    private fun triggerClassCastException() {
        val obj: Any = "String"

        @Suppress("UNUSED_VARIABLE", "UNCHECKED_CAST", "unused", "KotlinConstantConditions")
        val number = obj as Int
    }

    private fun triggerArithmeticException() {
        @Suppress("UNUSED_VARIABLE", "DIVISION_BY_ZERO", "unused")
        val result = 10 / 0
    }

    private fun triggerStackOverflowError() {
        recursiveMethod()
    }

    private fun recursiveMethod() {
        recursiveMethod()
    }

    override val noSwitchWidget: Boolean
        get() = true
}
