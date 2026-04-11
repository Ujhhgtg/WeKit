package dev.ujhhgtg.wekit.hooks.items.miniapps

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import org.json.JSONObject

@HookItem(path = "小程序/移除视频广告", description = "跳过小程序视频广告")
object RemoveVideoAds : SwitchHookItem() {

    override fun onEnable() {
        "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass().asResolver()
            .firstMethod { name = "subscribeHandler" }
            .hookBefore {
                val type = args[0] as String? ?: ""
                val json = args[1] as String? ?: ""

                if (type == "onVideoTimeUpdate") {
                    val json = JSONObject(json)
                    json.put("position", 60)
                    json.put("duration", 1)
                    args[1] = json.toString()
                }
            }
    }
}
