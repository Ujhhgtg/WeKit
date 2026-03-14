package moe.ouom.wekit.hooks.items.system

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/禁用 WebView 安全警告", desc = "禁用 WebView 相关的安全警告提示")
object DisableWebViewSafetyWarnings : SwitchHookItem(), IDexFind {
    private val methodGetIsInterceptEnabled by dexMethod()
    private val methodGetIsUrlSafe by dexMethod()

    override fun onLoad() {
        methodGetIsInterceptEnabled.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.result = true
                }
            }
        }

        methodGetIsUrlSafe.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.result = true
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodGetIsInterceptEnabled.find(dexKit, descriptors) {
            matcher {
                usingEqStrings(
                    "MicroMsg.WebViewHighRiskAdH5Interceptor",
                    "isInterceptEnabled, expt="
                )
            }
        }

        methodGetIsUrlSafe.find(dexKit, descriptors) {
            matcher {
                declaredClass(methodGetIsInterceptEnabled.method.declaringClass)
                usingEqStrings("http", "https")
            }
        }

        return descriptors
    }
}