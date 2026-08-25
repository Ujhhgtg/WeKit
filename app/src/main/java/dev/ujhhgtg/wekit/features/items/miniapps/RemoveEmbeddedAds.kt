package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.TargetProcesses
import org.json.JSONObject
import java.lang.reflect.Field

object RemoveEmbeddedAds : SwitchFeature(), IResolveDex {

    override val technicalId = "移除嵌入广告"
    override val nameRes = R.string.feature_remove_embedded_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_remove_embedded_ads_description

    /**
     * 奖励/激励视频广告位使用的 pos_id (微信内置广告位, 各版本稳定)。
     * 这类广告由用户主动点击 (看完拿奖励), 不属于被动展示的「嵌入广告」,
     * 不做拦截; 视频广告由 RemoveVideoAds / SkipRewardedAds 另行处理。
     */
    private val REWARDED_AD_POS_IDS = setOf(
        "1030436212907001", // rewardedVideoAd
        "5010365819466098", // wxAppVideo
        "7090665964306299", // wxAppPreVideoAd
    )

    // 广告数据请求: JS 侧通过 operateWXData / adOperateWXData 下发 webapi_getadvert,
    // 最终由 NetSceneJSOperateWxData 发出。构造时把 ad_unit_id 置空, 服务端就不会
    // 返回广告素材, 广告位自然不渲染。目标是让广告不出现, 而不是拦截点击后的跳转。
    internal val ctorNetSceneJSOperateWxData by dexConstructor {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.NetSceneJSOperateWxData", "doScene hash=%d, funcid=%d")
            }
        }
    }

    // 品牌服务 transfer 响应里的 ad_slot_data 清空。
    private val methodBaseTransferRequestOnLoad by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.BaseTransferRequest")
            paramTypes("com.tencent.mm.plugin.brandservice.api.TransferResultInfo")
        }
    }

    private lateinit var protoField: Field

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        // 不同版本构造函数的 data 参数位置不同 (8.0.65 在 args[1], 8.0.76 在 args[3]),
        // 直接扫描出带 api_name 的那个 JSON 字符串。
        ctorNetSceneJSOperateWxData.hookBefore {
            val dataIndex = args.indexOfFirst { arg ->
                arg is String && runCatching {
                    JSONObject(arg).optString("api_name") == "webapi_getadvert"
                }.getOrDefault(false)
            }
            if (dataIndex < 0) return@hookBefore
            val json = JSONObject(args[dataIndex] as String)
            val data = json.optJSONObject("data") ?: return@hookBefore
            if (isRewardedAdRequest(data)) {
                return@hookBefore
            }
            data.put("ad_unit_id", "")
            args[dataIndex] = json.toString()
        }

        methodBaseTransferRequestOnLoad.hookBefore {
            val transferResultInfo = args[0]!!
            if (!::protoField.isInitialized) {
                protoField = transferResultInfo.reflekt()
                    .firstField {
                        type { !it.isBuiltin }
                    }.self
            }

            val proto = protoField.get(transferResultInfo)
            proto.reflekt()
                .fields {
                    type = String::class
                }.forEach {
                    val jsonStr = it.get() as? String? ?: return@forEach
                    if (jsonStr.isBlank()) return@forEach
                    val json = runCatching { JSONObject(jsonStr) }.getOrElse { return@forEach }
                    if (!json.has("ad_slot_data")) return@forEach
                    it.set("{}")
                }
        }
    }

    /**
     * 奖励/激励广告请求的特征: 广告组件 (banner/插屏/自定义) 一定带各自的 pos_id;
     * 奖励广告走 RewardedVideoAd 数据通道, pos_id 要么是奖励视频广告位, 要么没有 pos_id。
     */
    private fun isRewardedAdRequest(data: JSONObject): Boolean {
        val posId = data.optString("pos_id")
        return posId.isBlank() || posId in REWARDED_AD_POS_IDS
    }
}
