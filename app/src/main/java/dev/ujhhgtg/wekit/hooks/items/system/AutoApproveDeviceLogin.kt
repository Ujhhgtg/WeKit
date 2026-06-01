package dev.ujhhgtg.wekit.hooks.items.system

import android.app.Activity
import android.widget.Button
import com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve

@HookItem(path = "系统与隐私/自动批准设备登录", description = "其他设备请求登录时自动勾选选项并点击按钮")
object AutoApproveDeviceLogin : SwitchHookItem() {

    private const val AUTO_SYNC_MESSAGES = 0x1
    private const val SHOW_LOGIN_DEVICE = 0x2
    private const val AUTO_LOGIN_DEVICE = 0x4

    override fun onEnable() {
        val targetClass = ExtDeviceWXLoginUI::class.java

        targetClass.hookBeforeOnCreate {
            val activity = thisObject as Activity
            var functionControl = 0
            functionControl = functionControl or AUTO_SYNC_MESSAGES
            functionControl = functionControl or SHOW_LOGIN_DEVICE
            functionControl = functionControl or AUTO_LOGIN_DEVICE
            activity.intent.putExtra("intent.key.function.control", functionControl)
            activity.intent.putExtra("intent.key.need.show.privacy.agreement", false)
        }

        targetClass.resolve().firstMethod { name = "initView" }.hookAfter {
            val button = thisObject.asResolver()
                .firstField {
                    type = Button::class
                }.get()!! as Button
            button.performClick()
        }
    }
}
