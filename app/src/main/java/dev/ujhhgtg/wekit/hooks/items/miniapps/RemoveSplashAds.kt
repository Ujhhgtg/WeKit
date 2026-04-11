package dev.ujhhgtg.wekit.hooks.items.miniapps

import android.app.Activity
import com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "小程序/移除开屏广告", description = "跳过小程序开屏广告")
object RemoveSplashAds : SwitchHookItem(), IResolvesDex {

    private val methodAdDataCallback by dexMethod()
    private val methodCheckCanShowAd by dexMethod()

    override fun onEnable() {
        methodAdDataCallback.hookBefore {
            result = null
        }

        methodCheckCanShowAd.hookBefore {
            result = false
        }

        AppBrandAdUI::class.java.hookBeforeOnCreate {
            val activity = thisObject as Activity
            activity.finish()
            result = null
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodAdDataCallback.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.appbrand.jsapi.auth")
            matcher {
                usingEqStrings(
                    "MicroMsg.AppBrand.JsApiAdOperateWXData[AppBrandSplashAd]", "cgi callback, callbackId:%s, service not running or preloaded"
                )
            }
        }

        methodCheckCanShowAd.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.appbrand")
            matcher {
                usingEqStrings("MicroMsg.AppBrandAdUtils[AppBrandSplashAd]", "checkCanShowAd, show ad (splash ad debug mode open)")
            }
        }
    }
}
