package moe.ouom.wekit.hooks.items.system

import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgManager
import moe.ouom.wekit.hooks.sdk.protocol.intf.IWePkgInterceptor
import moe.ouom.wekit.utils.WeProtoData
import moe.ouom.wekit.utils.log.WeLogger
import org.json.JSONArray
import org.json.JSONObject

@HookItem(path = "系统与隐私/去除文章广告", desc = "清除文章中的广告数据")
object RemoveArticleAds : SwitchHookItem(), IWePkgInterceptor {

    private val TAG = nameof(RemoveArticleAds)

    override fun onLoad() {
        WePkgManager.addInterceptor(this)
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (cgiId != 21909) return null

        try {
            val data = WeProtoData()
            data.fromBytes(respBytes)
            val json = data.toJsonObject()

            // 获取字段2
            val field2 = json.optJSONObject("2") ?: return null
            // 获取字段3中的广告JSON字符串
            val adJsonStr = field2.optString("3") ?: return null

            // 解析广告JSON
            val adJson = JSONObject(adJsonStr)
            // 清空广告字段
            var modified = false

            // 清空广告数组
            if (adJson.has("ad_slot_data")) {
                adJson.put("ad_slot_data", JSONArray())
                modified = true
            }

            if (adJson.has("advertisement_info")) {
                adJson.put("advertisement_info", JSONArray())
                modified = true
            }

            // 广告数量设置为0
            if (adJson.has("advertisement_num")) {
                adJson.put("advertisement_num", 0)
                modified = true
            }

            // 清空广告曝光相关
            if (adJson.has("no_ad_indicator_info")) {
                adJson.put("no_ad_indicator_info", JSONArray())
                modified = true
            }

            // 清空广告响应
            if (adJson.has("check_ad_resp")) {
                adJson.put("check_ad_resp", JSONObject().apply {
                    put("aid", "0")
                    put("del_aid", JSONArray())
                    put("offline_aid", JSONArray())
                    put("online_aid", JSONArray())
                })
                modified = true
            }

            if (modified) {
                // 放回修改后的广告JSON
                field2.put("3", adJson.toString())
                data.applyViewJson(json, true)
                WeLogger.d(TAG, "已清空所有广告相关数据")
                return data.toPacketBytes()
            }

        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }

        return null
    }

    override fun onUnload() {
        WePkgManager.removeInterceptor(this)
    }
}