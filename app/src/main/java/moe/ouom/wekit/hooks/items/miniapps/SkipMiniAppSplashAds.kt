package moe.ouom.wekit.hooks.items.miniapps

import android.app.Activity
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "小程序/跳过开屏广告", desc = "跳过小程序开屏广告")
object SkipMiniAppSplashAds : SwitchHookItem(), IResolvesDex {

    private val methodAdDataCallback by dexMethod()

    override fun onEnable() {
        methodAdDataCallback.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.result = null
                }
            }
        }

        "com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI".toClass().asResolver()
            .firstMethod {
                name = "onCreate"
            }
            .hookBefore { param ->
                val activity = param.thisObject as Activity
                activity.finish()
                param.result = null
            }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodAdDataCallback.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.appbrand.jsapi.auth")
            matcher {
                usingEqStrings(
                    "MicroMsg.AppBrand.JsApiAdOperateWXData[AppBrandSplashAd]",
                    "cgi callback, callbackId:%s, service not running or preloaded"
                )
            }
        }

        return descriptors
    }
}