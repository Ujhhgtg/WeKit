package moe.ouom.wekit.hooks.items.beautify

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "美化/美化组件按下效果", desc = "将 View 的背景替换为 RippleDrawable")
object BeautifyViewPressEffect : BaseSwitchFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) {
        View::class.asResolver()
            .firstMethod {
                name = "setBackgroundDrawable"
                parameters(Drawable::class)
            }
            .hookBefore { param ->
                val view = param.thisObject as View
                val original = param.args[0] as? Drawable ?: return@hookBefore

                // 只针对微信的组件，避开系统组件
                if (!view.javaClass.name.startsWith("com.tencent.mm")) return@hookBefore

                // 如果已经是 RippleDrawable 或者正在设置 Ripple，跳过
                if (original is RippleDrawable) return@hookBefore

                if (view.isClickable) {
                    val rippleColor = ColorStateList.valueOf(0x1F000000)
                    val newRipple = RippleDrawable(rippleColor, original, null)
                    param.args[0] = newRipple
                }
            }
    }
}