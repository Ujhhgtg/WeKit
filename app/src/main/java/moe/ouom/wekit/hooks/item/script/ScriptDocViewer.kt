package moe.ouom.wekit.hooks.item.script

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.common.Toasts.showToast
import moe.ouom.wekit.util.log.WeLogger

/**
 * 脚本文档查看器
 * 点击跳转到脚本文档网页
 */
@HookItem(
    path = "脚本管理/脚本文档",
    desc = "查看脚本文档"
)
class ScriptDocViewer : BaseClickableFunctionHookItem() {

    override fun onClick(context: Context?) {
        if (context == null) {
            WeLogger.e("ScriptDocViewer", "Context is null")
            return
        }

        try {
            // 跳转到脚本文档网页
            val url = "https://github.com/cwuom/WeKit/blob/master/SCRIPT_API_DOCUMENT.md"
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            showToast(context, "正在打开脚本文档...")
        } catch (e: Exception) {
            WeLogger.e("ScriptDocViewer", "Failed to open script documentation", e)
            showToast(context, "打开文档失败: ${e.message}")
        }
    }

    override fun noSwitchWidget(): Boolean = true
}
