package dev.ujhhgtg.wekit.features.items.system

import android.app.Activity
import android.content.Intent
import com.tencent.mm.plugin.webview.ui.tools.WebViewUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(name = "强制启用 WebView 菜单", categories = ["系统与隐私"], description = "强制显示 WebView 页面右上角菜单按钮")
object EnableWebViewFeatures : SwitchFeature(), IResolveDex {

    private val TRUE_INTENT_KEYS =
        setOf("show_feedback", "KRightBtn", "KShowFixToolsBtn", "key_enable_fts_quick")

    private val methodInitWebViewFeatures by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"
            usingEqStrings(
                "banRightBtn:%b, showFixToolsBtn:%b",
                "MicroMsg.WebViewFtsQuickHelper"
            )
        }
    }

    override fun onEnable() {
        WebViewUI::class.reflekt()
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
            val intent = thisObject!!.reflekt().firstMethod {
                name = "getIntent"
                superclass()
            }.invoke() as Intent
            for (key in TRUE_INTENT_KEYS) {
                intent.putExtra(key, true)
            }
        }
    }
}
