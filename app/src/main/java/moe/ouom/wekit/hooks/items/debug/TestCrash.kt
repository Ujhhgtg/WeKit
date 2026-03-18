package moe.ouom.wekit.hooks.items.debug

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.ToastUtils.showToast
import moe.ouom.wekit.utils.crash.NativeCrashHandler
import moe.ouom.wekit.utils.logging.WeLogger

@HookItem(
    path = "调试/测试崩溃",
    desc = "没事别点"
)
object TestCrash : ClickableHookItem() {

    private var appContext: Context? = null

    @SuppressLint("StaticFieldLeak")
    private var nativeCrashHandler: NativeCrashHandler? = null

    override fun onEnable() {
        WeLogger.i("TestCrash", "=== TestCrash entry() called ===")
        try {
            val activityThreadClass = "android.app.ActivityThread".toClass()
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            appContext = currentApplicationMethod.invoke(null) as? Context

            WeLogger.i("TestCrash", "Application context obtained: ${appContext != null}")
            WeLogger.i("TestCrash", "Context class: ${appContext?.javaClass?.name}")

            if (appContext != null) {
                WeLogger.i("TestCrash", "Creating NativeCrashHandler...")
                nativeCrashHandler = NativeCrashHandler()
                WeLogger.i("TestCrash", "NativeCrashHandler created")

                WeLogger.i("TestCrash", "Installing native crash handler...")
                val installed = nativeCrashHandler?.install() ?: false
                if (installed) {
                    WeLogger.i("TestCrash", "✓ Native crash handler installed successfully for testing")
                } else {
                    WeLogger.e("TestCrash", "✗ Failed to install native crash handler for testing")
                }
            } else {
                WeLogger.e("TestCrash", "✗ Application context is null, cannot initialize handler")
            }

            WeLogger.i("TestCrash", "=== Test crash feature initialized ===")
        } catch (e: Throwable) {
            WeLogger.e("[TestCrash] Failed to initialize", e)
        }
    }

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
                                    dismiss()
                                    onSelect(index)
                                }
                            )
                            if (index < items.lastIndex) HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                },
                confirmButton = {
                    if (onBack != null) {
                        TextButton(onClick = { dismiss(); onBack() }) { Text("返回") }
                    } else {
                        TextButton(onClick = dismiss) { Text("取消") }
                    }
                }
            )
        }
    }

    private fun confirmTriggerCrash(context: Context, category: String, crashType: Int) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认触发崩溃") },
                text = { Text("确定要触发 $category 测试崩溃吗?\n这可能会导致微信数据丢失") },
                confirmButton = {
                    TextButton(onClick = {
                        dismiss()
                        when (category) {
                            "Java" -> triggerJavaCrash(crashType)
                            "Native" -> triggerNativeCrash(crashType)
                        }
                    }) {
                        Text("确定", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) { Text("取消") }
                }
            )
        }
    }

    private fun triggerJavaCrash(crashType: Int) {
        WeLogger.w("TestCrash", "Triggering Java test crash, type: $crashType")
        Handler(Looper.getMainLooper()).postDelayed({
            when (crashType) {
                0 -> triggerNullPointerException()
                1 -> triggerArrayIndexOutOfBoundsException()
                2 -> triggerClassCastException()
                3 -> triggerArithmeticException()
                4 -> triggerStackOverflowError()
                else -> triggerNullPointerException()
            }
        }, 500)
    }

    @SuppressLint("PrivateApi")
    private fun triggerNativeCrash(crashType: Int) {
        WeLogger.w("TestCrash", "Triggering Native test crash, type: $crashType")

        if (nativeCrashHandler == null) {
            WeLogger.w("TestCrash", "Native crash handler is null, attempting to initialize...")

            if (appContext == null) {
                try {
                    val activityThreadClass = Class.forName("android.app.ActivityThread")
                    val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
                    appContext = currentApplicationMethod.invoke(null) as? Context
                    WeLogger.i("TestCrash", "Application context obtained: ${appContext != null}")
                } catch (e: Throwable) {
                    WeLogger.e("[TestCrash] Failed to get application context", e)
                }
            }

            if (appContext != null) {
                try {
                    nativeCrashHandler = NativeCrashHandler()
                    WeLogger.i("TestCrash", "Native crash handler created")
                } catch (e: Throwable) {
                    WeLogger.e("[TestCrash] Failed to create native crash handler", e)
                    showToast(appContext, "无法创建 Native 崩溃处理器: ${e.message}")
                    return
                }
            } else {
                WeLogger.e("TestCrash", "Application context is null, cannot create handler")
                showToast(appContext, "无法获取应用上下文")
                return
            }
        }

        if (!nativeCrashHandler!!.isInstalled) {
            WeLogger.w("TestCrash", "Native crash handler not installed, attempting to install...")
            val installed = nativeCrashHandler!!.install()
            if (!installed) {
                WeLogger.e("TestCrash", "Failed to install native crash handler")
                showToast(appContext, "Native 崩溃拦截器安装失败")
                return
            }
            WeLogger.i("TestCrash", "Native crash handler installed successfully")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                WeLogger.i("TestCrash", "About to trigger native crash type: $crashType")
                nativeCrashHandler?.triggerTestCrash(crashType)
                WeLogger.e("TestCrash", "Native crash should have occurred but didn't!")
            } catch (e: Throwable) {
                WeLogger.e("[TestCrash] Exception while triggering Native crash", e)
                showToast(appContext, "触发 Native 崩溃时发生异常: ${e.message}")
            }
        }, 500)
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

    override fun noSwitchWidget(): Boolean = true
}