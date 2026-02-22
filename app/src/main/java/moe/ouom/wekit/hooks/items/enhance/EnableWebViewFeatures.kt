package moe.ouom.wekit.hooks.items.enhance

import android.app.Activity
import android.content.Intent
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "优化与修复/强制启用 WebView 菜单", desc = "强制显示 WebView 页面右上角菜单按钮")
object EnableWebViewFeatures : BaseSwitchFunctionHookItem(), IDexFind {
    private const val WEBVIEW_UI_CLASS_NAME = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"

    private val TRUE_INTENT_KEYS = setOf("show_feedback", "KRightBtn", "KShowFixToolsBtn", "key_enable_fts_quick")

    private val methodInitWebViewFeatures by dexMethod()

    override fun entry(classLoader: ClassLoader) {
        val cls = WEBVIEW_UI_CLASS_NAME.toClass(classLoader)

        cls.asResolver()
            .method {
                name = "showOptionMenu"
            }
            .forEach {
                it.hookBefore { param ->
                    if (param.args[0] is Boolean) {
                        param.args[0] = true
                    } else if (param.args[1] is Boolean) {
                        param.args[1] = true
                    }

                    val activity = param.thisObject as Activity
                    activity.intent.putExtra("hide_option_menu", false)
                }
            }

        methodInitWebViewFeatures.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val intent = param.thisObject.asResolver().firstMethod {
                        name = "getIntent"
                        superclass()
                    }.invoke() as Intent
                    intent.putExtra("show_feedback", false)
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodInitWebViewFeatures.find(dexKit, descriptors) {
            matcher {
                declaredClass = WEBVIEW_UI_CLASS_NAME
                usingEqStrings("banRightBtn:%b, showFixToolsBtn:%b", "MicroMsg.WebViewFtsQuickHelper")
            }
        }

        return descriptors
    }
}
