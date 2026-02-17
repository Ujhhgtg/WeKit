package moe.ouom.wekit.hooks.item.fix

import android.app.Activity
import android.widget.Button
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.log.WeLogger

@HookItem(path = "优化与修复/自动批准设备登录", desc = "其他设备请求登录时自动勾选选项并点击按钮")
class AutoApproveDeviceLogin : BaseSwitchFunctionHookItem() {
    companion object {
        private const val AUTO_SYNC_MESSAGES = 0x1
        private const val SHOW_LOGIN_DEVICE = 0x2
        private const val AUTO_LOGIN_DEVICE = 0x4
    }

    override fun entry(classLoader: ClassLoader) {
        val targetClass = "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI".toClass(classLoader)

        // Hook onCreate — inject function control flags into intent
        targetClass.hookBefore("onCreate") { param ->
            val activity = param.thisObject as Activity
            var functionControl = 0
            functionControl = functionControl or AUTO_SYNC_MESSAGES
            functionControl = functionControl or SHOW_LOGIN_DEVICE
            functionControl = functionControl or AUTO_LOGIN_DEVICE
            activity.intent.putExtra("intent.key.function.control", functionControl)
            activity.intent.putExtra("intent.key.need.show.privacy.agreement", false)
        }

        // Hook initView — auto-click the login button after view is set up
        targetClass.hookAfter("initView") { param ->
            val fields = param.thisObject.javaClass.declaredFields
            val buttonField = fields.firstOrNull { it.type == Button::class.java }
                ?: run {
                    WeLogger.w("AutoApproveDeviceLogin", "Button field not found in initView")
                    return@hookAfter
                }
            buttonField.isAccessible = true
            val button = buttonField.get(param.thisObject) as? Button
                ?: run {
                    WeLogger.w("AutoApproveDeviceLogin", "Button field value is null")
                    return@hookAfter
                }
            button.callOnClick()
        }
    }

    override fun unload(classLoader: ClassLoader) {
    }
}
