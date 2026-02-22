package moe.ouom.wekit.hooks.item.contact

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeChatContactInfoAdapterItemHook
import moe.ouom.wekit.hooks.sdk.ui.WeChatContactInfoAdapterItemHook.ContactInfoItem
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(
    path = "联系人/显示微信 ID",
    desc = "在联系人页面显示微信 ID"
)
object ShowWeChatIdHook : BaseSwitchFunctionHookItem() {

    private const val TAG = "ShowWeChatIdHook"
    private const val PREF_KEY = "wechat_id_display"

    // 创建初始化回调
    private val initCallback = WeChatContactInfoAdapterItemHook.InitContactInfoViewCallback { activity ->
        val wechatId = try {
            "微信ID: ${activity.intent.getStringExtra("Contact_User") ?: "未知"}"
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取微信ID失败", e)
            "微信ID: 获取失败"
        }
        if (wechatId.contains("gh_")) {
            WeLogger.d(TAG, "检测到公众号，不处理")
            return@InitContactInfoViewCallback null
        }

        ContactInfoItem(
            key = PREF_KEY,
            title = wechatId,
            position = 1
        )
    }

    private val clickListener = WeChatContactInfoAdapterItemHook.OnContactInfoItemClickListener { activity, key ->
        if (key == PREF_KEY) {
            handleWeChatIdClick(activity)
            true
        } else {
            false
        }
    }

    override fun entry(classLoader: ClassLoader) {
        try {
            // 添加初始化回调
            WeChatContactInfoAdapterItemHook.addInitCallback(initCallback)
            // 添加点击监听器
            WeChatContactInfoAdapterItemHook.addClickListener(clickListener)
            WeLogger.i(TAG, "显示微信ID Hook 注册成功")
        } catch (e: Exception) {
            WeLogger.e(TAG, "注册失败: ${e.message}", e)
        }
    }

    private fun handleWeChatIdClick(activity: Activity): Boolean {
        try {
            val contactUser = activity.intent.getStringExtra("Contact_User")
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = ClipData.newPlainText("微信ID", contactUser)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            WeLogger.d(TAG, "Contact User: $contactUser")
            return true
        } catch (e: Exception) {
            WeLogger.e(TAG, "处理点击失败: ${e.message}", e)
            return false
        }
    }


    override fun unload(classLoader: ClassLoader) {
        WeChatContactInfoAdapterItemHook.removeInitCallback(initCallback)
        WeChatContactInfoAdapterItemHook.removeClickListener(clickListener)
        WeLogger.i(TAG, "已移除显示微信ID Hook")
    }
}