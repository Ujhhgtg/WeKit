package moe.ouom.wekit.hooks.items.miniapps

import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.json.JSONObject

@HookItem(path = "小程序/跳过视频广告", desc = "跳过小程序视频广告")
object SkipMiniAppVideoAds : SwitchHookItem() {

    override fun onLoad() {
        "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass()
            .hookBefore("subscribeHandler") { param ->
                val arg0 = param.args[0] as String? ?: ""
                val arg1 = param.args[1] as String? ?: ""

                if (arg0 == "onVideoTimeUpdate") {
                    val json = JSONObject(arg1)
                    json.put("position", 60)
                    json.put("duration", 1)
                    param.args[1] = json.toString()
                }
            }
    }
}