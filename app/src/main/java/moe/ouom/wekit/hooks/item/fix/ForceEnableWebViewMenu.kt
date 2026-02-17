package moe.ouom.wekit.hooks.item.fix

import android.app.Activity
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "优化与修复/强制启用 WebView 菜单", desc = "强制显示 WebView 页面右上角菜单按钮")
class ForceEnableWebViewMenu : BaseSwitchFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) {
        val webViewUiCls: Class<*> =
            XposedHelpers.findClass("com.tencent.mm.plugin.webview.ui.tools.WebViewUI", classLoader)
        val showOptionMenuMethod1 = XposedHelpers.findMethodExact(
            webViewUiCls,
            "showOptionMenu",
            Boolean::class.javaPrimitiveType
        )
        hookBefore(showOptionMenuMethod1, priority = 50) { param ->
            param.args[0] = true
            val activity = param.thisObject as Activity
            activity.intent.putExtra("hide_option_menu", false)
        }
        val showOptionMenuMethod2 = XposedHelpers.findMethodExact(
            webViewUiCls,
            "showOptionMenu",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        hookBefore(showOptionMenuMethod2, priority = 50) { param ->
            param.args[1] = true
            val activity = param.thisObject as Activity
            activity.intent.putExtra("hide_option_menu", false)
        }
    }

    override fun unload(classLoader: ClassLoader) {
    }
}
