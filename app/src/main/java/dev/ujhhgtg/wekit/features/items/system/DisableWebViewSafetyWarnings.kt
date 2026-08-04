package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(name = "禁用 WebView 安全警告", categories = ["系统与隐私"], description = "禁用 WebView 相关的安全警告提示")
object DisableWebViewSafetyWarnings : SwitchFeature(), IResolveDex {
    private val methodGetIsInterceptEnabled by dexMethod {
        matcher {
            usingEqStrings(
                "MicroMsg.WebViewHighRiskAdH5Interceptor",
                "isInterceptEnabled, expt="
            )
        }
    }
    private val methodGetIsUrlSafe by dexMethod {
        matcher {
            declaredClass(methodGetIsInterceptEnabled.data.declaredClassName)
            usingEqStrings("http", "https")
        }
    }

    override fun onEnable() {
        methodGetIsInterceptEnabled.hookBefore {
            result = false
        }

        methodGetIsUrlSafe.hookBefore {
            result = true
        }
    }
}
