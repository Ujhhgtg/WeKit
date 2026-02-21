package moe.ouom.wekit.hooks.items.miniapps

import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.json.JSONObject

@HookItem(path = "小程序/跳过视频广告", desc = "跳过小程序视频广告")
object SkipMiniAppVideoAds : BaseSwitchFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) {
        "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass(classLoader)
            .hookBefore("subscribeHandler") { param ->
                val args0 = param.args[0] as? String? ?: ""
                val args1 = param.args[1] as? String? ?: ""

                if (args0 == "onVideoTimeUpdate") {
                    val json = JSONObject(args1)
                    json.put("position", 60);
                    json.put("duration", 1);
                    param.args[1] = json.toString()
                }
            }
    }
}