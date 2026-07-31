package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.TargetProcesses
import org.json.JSONObject
import java.lang.reflect.Field

@Feature(name = "移除嵌入广告", categories = ["小程序"], description = "移除小程序嵌入广告")
object RemoveEmbeddedAds : SwitchFeature(), IResolveDex {

    // 广告数据请求: JS 侧通过 operateWXData / adOperateWXData 下发 webapi_getadvert,
    // 最终由 NetSceneJSOperateWxData 发出。构造时把 ad_unit_id 置空, 服务端就不会
    // 返回广告素材, 广告位自然不渲染。目标是让广告不出现, 而不是拦截点击后的跳转。
    private val ctorNetSceneJSOperateWxData by dexConstructor {
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
}
