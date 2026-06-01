package dev.ujhhgtg.wekit.hooks.items.miniapps

import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.isBuiltin
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field

@HookItem(path = "小程序/移除嵌入广告", description = "移除小程序嵌入广告")
object RemoveEmbeddedAds : SwitchHookItem(), IResolvesDex {

    private val ctorNetSceneJSOperateWxData by dexConstructor()
    private val methodBaseTransferRequestOnLoad by dexMethod()

    private lateinit var protoField: Field

    override fun onEnable() {
        ctorNetSceneJSOperateWxData.hookBefore {
            val json = runCatching { JSONObject(args[1] as String) }.getOrElse { return@hookBefore }
            if (json.getString("api_name") == "webapi_getadvert") {
                json.put("data", json.getJSONObject("data").put("ad_unit_id", ""))
                args[1] = json.toString()
            }
        }

        methodBaseTransferRequestOnLoad.hookBefore {
            val transferResultInfo = args[0]
            if (!::protoField.isInitialized) {
                protoField = transferResultInfo.asResolver()
                    .firstField {
                        type { !it.isBuiltin }
                    }.self
            }

            val proto = protoField.get(transferResultInfo)
            proto.asResolver()
                .field {
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

    override fun resolveDex(dexKit: DexKitBridge) {
        ctorNetSceneJSOperateWxData.find(dexKit) {
            matcher {
                declaredClass {
                    usingEqStrings("MicroMsg.NetSceneJSOperateWxData", "doScene hash=%d, funcid=%d")
                }
            }
        }

        methodBaseTransferRequestOnLoad.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.BaseTransferRequest")
                paramTypes("com.tencent.mm.plugin.brandservice.api.TransferResultInfo")
            }
        }
    }
}
