package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.net.WePacketManager
import dev.ujhhgtg.wekit.features.api.net.WeProtoData
import dev.ujhhgtg.wekit.features.api.net.abc.IWePacketInterceptor
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject

object RemoveArticleAds : SwitchFeature(), IWePacketInterceptor {

    override val technicalId = "去除文章广告"
    override val nameRes = R.string.feature_remove_article_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_remove_article_ads_description

    private const val TAG = "RemoveArticleAds"

    override fun onEnable() {
        WePacketManager.addInterceptor(this)
    }

    override fun onDisable() {
        WePacketManager.removeInterceptor(this)
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (cgiId != 21909) return null

        try {
            val data = WeProtoData.fromBytes(respBytes)
            val json = data.toJsonObject()

            // 获取字段2
            val field2 = json.optJSONObject("2") ?: return null
            // 获取字段3中的广告JSON字符串
            val adJsonStr = field2.optString("3") ?: return null

            // 解析广告JSON
            val adJson = runCatching { JSONObject(adJsonStr) }.getOrElse { return null }
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
                WeLogger.d(TAG, "cleared article ads")
                return data.toPacketBytes()
            }

        } catch (e: Exception) {
            WeLogger.e(TAG, "onResponse failed", e)
        }

        return null
    }
}
