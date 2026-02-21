package moe.ouom.wekit.hooks.items.miniapps

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "小程序/跳过开屏广告", desc = "跳过小程序开屏广告")
object SkipMiniAppsSplashAds : BaseSwitchFunctionHookItem(), IDexFind {
    private val methodAdDataCallback by dexMethod()

    override fun entry(classLoader: ClassLoader) {
        methodAdDataCallback.toDexMethod {
            hook {
                replace {
                    null
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodAdDataCallback.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.appbrand.jsapi.auth")
            matcher {
                usingEqStrings("MicroMsg.AppBrand.JsApiAdOperateWXData[AppBrandSplashAd]", "cgi callback, callbackId:%s, service not running or preloaded")
            }
        }

        return descriptors
    }
}