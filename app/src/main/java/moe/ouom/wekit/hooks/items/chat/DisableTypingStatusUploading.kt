package moe.ouom.wekit.hooks.items.chat

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/禁止上传正在输入状态", desc = "禁止应用上传 '正在输入...' 状态")
object DisableTypingStatusUploading : SwitchHookItem(), IResolvesDex {

    private val TAG = nameof(DisableTypingStatusUploading)
    private val classMmTypingSendReq by dexClass()

    override fun onEnable() {
        classMmTypingSendReq.clazz.asResolver().firstMethod { name = "doScene" }
            .hookBefore { param ->
                WeLogger.i(TAG, "preventing upload of typing status")
                param.result = -1
            }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classMmTypingSendReq.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.modelsimple")
            matcher {
                usingEqStrings(
                    "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
                    "autoAuth"
                )
            }
        }

        return descriptors
    }
}