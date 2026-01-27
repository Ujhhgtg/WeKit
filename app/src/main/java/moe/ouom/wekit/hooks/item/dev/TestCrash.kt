package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.util.log.WeLogger

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

    override fun entry(classLoader: ClassLoader) {
        WeLogger.i("TestCrash", "Test crash feature initialized")
    }

    override fun onClick(context: Context?) {
        if (context == null) {
            WeLogger.e("TestCrash", "Context is null")
            return
        }

        showCrashTypeDialog(context)
    }

    /**
     * 显示崩溃类型选择对话框
     */
    private fun showCrashTypeDialog(context: Context) {
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
                    .title(text = "选择崩溃类型")
                    .listItems(items = crashTypes) { dialog, index, _ ->
                        dialog.dismiss()
                        confirmTriggerCrash(context, index)
                    }
                    .negativeButton(text = "取消")
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[TestCrash] Failed to show crash type dialog", e)
            }
        }
    }

    /**
     * 确认触发崩溃
     */
    private fun confirmTriggerCrash(context: Context, crashType: Int) {
        Handler(Looper.getMainLooper()).post {
            try {
                // 使用 CommonContextWrapper 包装 Context
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

                MaterialDialog(wrappedContext)
                    .title(text = "确认触发崩溃")
                    .message(text = "确定要触发测试崩溃吗?\n\n这可能会导致微信数据丢失")
                    .positiveButton(text = "确定") { dialog ->
                        dialog.dismiss()
                        triggerCrash(crashType)
                    }
                    .negativeButton(text = "取消")
                    .show()
            } catch (e: Throwable) {
                WeLogger.e("[TestCrash] Failed to show confirmation dialog", e)
            }
        }
    }

    /**
     * 触发崩溃
     */
    private fun triggerCrash(crashType: Int) {
        WeLogger.w("TestCrash", "Triggering test crash, type: $crashType")

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
        @Suppress("UNUSED_VARIABLE")
        val value = array[10] // 触发 ArrayIndexOutOfBoundsException
    }

    /**
     * 触发类型转换异常
     */
    private fun triggerClassCastException() {
        val obj: Any = "String"
        @Suppress("UNUSED_VARIABLE", "UNCHECKED_CAST")
        val number = obj as Int // 触发 ClassCastException
    }

    /**
     * 触发算术异常
     */
    private fun triggerArithmeticException() {
        @Suppress("UNUSED_VARIABLE", "DIVISION_BY_ZERO")
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