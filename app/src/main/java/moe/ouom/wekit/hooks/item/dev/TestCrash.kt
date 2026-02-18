package moe.ouom.wekit.hooks.item.dev

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.utils.Initiator.loadClass
import moe.ouom.wekit.utils.common.Toasts.showToast
import moe.ouom.wekit.utils.crash.NativeCrashHandler
import moe.ouom.wekit.utils.log.WeLogger

/**
 * 测试崩溃功能
 * 用于测试崩溃拦截功能是否正常工作
 *
 * @author cwuom
 * @since 1.0.0
 */
@HookItem(
    path = "开发者选项/测试崩溃",
    desc = "没事别点"
)
class TestCrash : BaseClickableFunctionHookItem() {

    private var appContext: Context? = null
    private var nativeCrashHandler: NativeCrashHandler? = null

    override fun entry(classLoader: ClassLoader) {
        WeLogger.i("TestCrash", "=== TestCrash entry() called ===")
        try {
            // 获取 Application Context
            val activityThreadClass = loadClass("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            appContext = currentApplicationMethod.invoke(null) as? Context

            WeLogger.i("TestCrash", "Application context obtained: ${appContext != null}")
            WeLogger.i("TestCrash", "Context class: ${appContext?.javaClass?.name}")

            if (appContext != null) {
                // 初始化 Native 崩溃处理器（用于测试）
                WeLogger.i("TestCrash", "Creating NativeCrashHandler...")
                nativeCrashHandler = NativeCrashHandler(appContext!!)
                WeLogger.i("TestCrash", "NativeCrashHandler created")

                // 安装 Native 崩溃拦截器（确保测试时能够拦截崩溃）
                WeLogger.i("TestCrash", "Installing native crash handler...")
                val installed = nativeCrashHandler?.install() ?: false
                if (installed) {
                    WeLogger.i(
                        "TestCrash",
                        "✓ Native crash handler installed successfully for testing"
                    )
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

    override fun onClick(context: Context?) {
        if (context == null) {
            WeLogger.e("TestCrash", "Context is null")
            return
        }

        showCrashCategoryDialog(context)
    }

    /**
     * 显示崩溃类别选择对话框
     */
    private fun showCrashCategoryDialog(context: Context) {
        Handler(Looper.getMainLooper()).post {
            try {
                val categories = listOf(
                    "Java 层崩溃",
                    "Native 层崩溃"
                )

                // 使用 CommonContextWrapper 包装 Context
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                MaterialDialog(wrappedContext)
                    .title(text = "选择崩溃类别")
                    .listItems(items = categories) { dialog, index, _ ->
                        dialog.dismiss()
                        when (index) {
                            0 -> showJavaCrashTypeDialog(context)
                            1 -> showNativeCrashTypeDialog(context)
                        }
                    }
                    .negativeButton(text = "取消")
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[TestCrash] Failed to show crash category dialog", e)
            }
        }
    }

    /**
     * 显示 Java 崩溃类型选择对话框
     */
    private fun showJavaCrashTypeDialog(context: Context) {
        Handler(Looper.getMainLooper()).post {
            try {
                val crashTypes = listOf(
                    "空指针异常 (NullPointerException)",
                    "数组越界 (ArrayIndexOutOfBoundsException)",
                    "类型转换异常 (ClassCastException)",
                    "算术异常 (ArithmeticException)",
                    "栈溢出 (StackOverflowError)"
                )

                // 使用 CommonContextWrapper 包装 Context
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                MaterialDialog(wrappedContext)
                    .title(text = "选择 Java 崩溃类型")
                    .listItems(items = crashTypes) { dialog, index, _ ->
                        dialog.dismiss()
                        confirmTriggerCrash(context, "Java", index)
                    }
                    .negativeButton(text = "返回") {
                        showCrashCategoryDialog(context)
                    }
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[TestCrash] Failed to show Java crash type dialog", e)
            }
        }
    }

    /**
     * 显示 Native 崩溃类型选择对话框
     */
    private fun showNativeCrashTypeDialog(context: Context) {
        Handler(Looper.getMainLooper()).post {
            try {
                val crashTypes = listOf(
                    "段错误 (SIGSEGV - 空指针访问)",
                    "异常终止 (SIGABRT - abort)",
                    "浮点异常 (SIGFPE - 除零错误)",
                    "非法指令 (SIGILL)",
                    "总线错误 (SIGBUS - 未对齐访问)"
                )

                // 使用 CommonContextWrapper 包装 Context
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                MaterialDialog(wrappedContext)
                    .title(text = "选择 Native 崩溃类型")
                    .listItems(items = crashTypes) { dialog, index, _ ->
                        dialog.dismiss()
                        confirmTriggerCrash(context, "Native", index)
                    }
                    .negativeButton(text = "返回") {
                        showCrashCategoryDialog(context)
                    }
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[TestCrash] Failed to show Native crash type dialog", e)
            }
        }
    }

    /**
     * 确认触发崩溃
     */
    private fun confirmTriggerCrash(context: Context, category: String, crashType: Int) {
        Handler(Looper.getMainLooper()).post {
            try {
                // 使用 CommonContextWrapper 包装 Context
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                MaterialDialog(wrappedContext)
                    .title(text = "确认触发崩溃")
                    .message(text = "确定要触发 $category 测试崩溃吗?\n\n这可能会导致微信数据丢失")
                    .positiveButton(text = "确定") { dialog ->
                        dialog.dismiss()
                        when (category) {
                            "Java" -> triggerJavaCrash(crashType)
                            "Native" -> triggerNativeCrash(crashType)
                        }
                    }
                    .negativeButton(text = "取消")
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[TestCrash] Failed to show confirmation dialog", e)
            }
        }
    }

    /**
     * 触发 Java 崩溃
     */
    private fun triggerJavaCrash(crashType: Int) {
        WeLogger.w("TestCrash", "Triggering Java test crash, type: $crashType")

        // 延迟触发,确保对话框已关闭
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

    /**
     * 触发 Native 崩溃
     */
    @SuppressLint("PrivateApi")
    private fun triggerNativeCrash(crashType: Int) {
        WeLogger.w("TestCrash", "Triggering Native test crash, type: $crashType")

        // 检查 Native 崩溃处理器是否已安装
        if (nativeCrashHandler == null) {
            WeLogger.w("TestCrash", "Native crash handler is null, attempting to initialize...")

            // 尝试重新初始化
            if (appContext == null) {
                try {
                    // 重新获取 Application Context
                    val activityThreadClass = Class.forName("android.app.ActivityThread")
                    val currentApplicationMethod =
                        activityThreadClass.getMethod("currentApplication")
                    appContext = currentApplicationMethod.invoke(null) as? Context
                    WeLogger.i("TestCrash", "Application context obtained: ${appContext != null}")
                } catch (e: Throwable) {
                    WeLogger.e("[TestCrash] Failed to get application context", e)
                }
            }

            if (appContext != null) {
                try {
                    nativeCrashHandler = NativeCrashHandler(appContext!!)
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

        // 延迟触发,确保对话框已关闭
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

    /**
     * 触发空指针异常
     */
    private fun triggerNullPointerException() {
        val obj: String? = null
        obj!!.length // 触发 NullPointerException
    }

    /**
     * 触发数组越界异常
     */
    private fun triggerArrayIndexOutOfBoundsException() {
        val array = arrayOf(1, 2, 3)

        @Suppress("UNUSED_VARIABLE", "unused")
        val value = array[10] // 触发 ArrayIndexOutOfBoundsException
    }

    /**
     * 触发类型转换异常
     */
    private fun triggerClassCastException() {
        val obj: Any = "String"

        @Suppress("UNUSED_VARIABLE", "UNCHECKED_CAST", "unused")
        val number = obj as Int // 触发 ClassCastException
    }

    /**
     * 触发算术异常
     */
    private fun triggerArithmeticException() {
        @Suppress("UNUSED_VARIABLE", "DIVISION_BY_ZERO", "unused")
        val result = 10 / 0 // 触发 ArithmeticException
    }

    /**
     * 触发栈溢出错误
     */
    private fun triggerStackOverflowError() {
        recursiveMethod() // 触发 StackOverflowError
    }

    /**
     * 递归方法,用于触发栈溢出
     */
    private fun recursiveMethod() {
        recursiveMethod()
    }

    /**
     * 隐藏开关控件
     */
    override fun noSwitchWidget(): Boolean = true
}