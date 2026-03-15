package moe.ouom.wekit.hooks.items.contacts

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.ui.WeChatContactDetailsApi
import moe.ouom.wekit.hooks.api.ui.WeChatContactDetailsApi.ContactInfoItem
import moe.ouom.wekit.utils.logging.WeLogger

@HookItem(
    path = "联系人与群组/显示微信 ID",
    desc = "在联系人详情页面显示微信 ID"
)
object ShowWxIdInContactDetails : SwitchHookItem() {

    private val TAG = nameof(ShowWxIdInContactDetails)
    private const val PREF_KEY = "wechat_id_display"

    private val initCallback =
        WeChatContactDetailsApi.InitContactInfoViewCallback { activity ->
            val wechatId = try {
                "微信 ID: ${activity.intent.getStringExtra("Contact_User") ?: "未知"}"
            } catch (e: Exception) {
                WeLogger.e(TAG, "获取微信 ID 失败", e)
                "微信 ID: 获取失败"
            }

            ContactInfoItem(
                key = PREF_KEY,
                title = wechatId,
                position = 1
            )
        }

    private val clickListener =
        WeChatContactDetailsApi.OnContactInfoItemClickListener { activity, key ->
            if (key == PREF_KEY) {
                handleWeChatIdClick(activity)
                true
            } else {
                false
            }
        }

    override fun onEnable() {
        try {
            WeChatContactDetailsApi.addInitCallback(initCallback)
            WeChatContactDetailsApi.addClickListener(clickListener)
        } catch (e: Exception) {
            WeLogger.e(TAG, "注册失败", e)
        }
    }

    private fun handleWeChatIdClick(activity: Activity): Boolean {
        try {
            val contactUser = activity.intent.getStringExtra("Contact_User")
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WxId", contactUser)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            WeLogger.e(TAG, "处理点击失败", e)
            return false
        }
    }


    override fun onDisable() {
        WeChatContactDetailsApi.removeInitCallback(initCallback)
        WeChatContactDetailsApi.removeClickListener(clickListener)
        WeLogger.i(TAG, "已移除显示微信ID Hook")
    }
}