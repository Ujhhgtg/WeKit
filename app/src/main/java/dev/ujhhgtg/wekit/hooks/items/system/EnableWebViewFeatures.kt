package dev.ujhhgtg.wekit.hooks.items.system

import android.app.Activity
import android.content.Intent
import com.tencent.mm.plugin.webview.ui.tools.WebViewUI
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/强制启用 WebView 菜单", description = "强制显示 WebView 页面右上角菜单按钮")
object EnableWebViewFeatures : SwitchHookItem(), IResolvesDex {

    private val TRUE_INTENT_KEYS =
        setOf("show_feedback", "KRightBtn", "KShowFixToolsBtn", "key_enable_fts_quick")

    private val methodInitWebViewFeatures by dexMethod()

    override fun onEnable() {
        WebViewUI::class.resolve()
            .firstMethod {
                name = "showOptionMenu"
            }.hookBefore {
                if (args[0] is Boolean) {
                    args[0] = true
                } else if (args[1] is Boolean) {
                    args[1] = true
                }

                val activity = thisObject as Activity
                activity.intent.putExtra("hide_option_menu", false)
            }

        methodInitWebViewFeatures.hookBefore {
            val intent = thisObject.asResolver().firstMethod {
                name = "getIntent"
                superclass()
            }.invoke() as Intent
            for (key in TRUE_INTENT_KEYS) {
                intent.putExtra(key, true)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodInitWebViewFeatures.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"
                usingEqStrings(
                    "banRightBtn:%b, showFixToolsBtn:%b",
                    "MicroMsg.WebViewFtsQuickHelper"
                )
            }
        }
    }
}
