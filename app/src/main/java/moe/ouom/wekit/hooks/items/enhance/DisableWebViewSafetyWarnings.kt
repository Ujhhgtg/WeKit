package moe.ouom.wekit.hooks.items.enhance

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.dsl.resultNull
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "优化与修复/禁用 WebView 安全警告", desc = "禁用 WebView 相关的安全警告提示")
object DisableWebViewSafetyWarnings : BaseSwitchFunctionHookItem(), IDexFind {
    private val methodGetIsInterceptEnabled by dexMethod()
    private val methodAddDisabledUrl by dexMethod()
    private val methodGetIsUrlSafe by dexMethod()

    override fun entry(classLoader: ClassLoader) {
        methodGetIsInterceptEnabled.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.result = false
                }
            }
        }

        methodAddDisabledUrl.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.resultNull()
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
                usingEqStrings("MicroMsg.WebViewHighRiskAdH5Interceptor", "isInterceptEnabled, expt=")
            }
        }

        methodAddDisabledUrl.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("AdH5URLManager", "addDisableUrl, url=")
            }
        }

        methodGetIsUrlSafe.find(dexKit, descriptors) {
            matcher {
                declaredClass = methodAddDisabledUrl.method.declaringClass.canonicalName!!
                usingEqStrings("http", "https")
            }
        }

        return descriptors
    }
}