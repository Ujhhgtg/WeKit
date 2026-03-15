package moe.ouom.wekit.hooks.items.system

import android.view.WindowManager
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem

@HookItem(path = "系统与隐私/禁止屏幕高亮度", desc = "禁止应用将屏幕亮度设置得过高")
object DisableHighBrightness : SwitchHookItem() {

    override fun onEnable() {
        "com.android.internal.policy.PhoneWindow".toClass().asResolver()
            .firstMethod {
                name = "setAttributes"
                parameters(WindowManager.LayoutParams::class)
            }
            .hookBefore { param ->
                val lp = param.args[0] as WindowManager.LayoutParams
                if (lp.screenBrightness >= 0.5f) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
    }
}