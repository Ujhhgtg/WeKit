package moe.ouom.wekit.hooks.items.chat

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.abc.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/禁止上传正在输入状态", desc = "禁止应用上传 '正在输入...' 状态")
object DisableTypingStatusUploading : SwitchHookItem(), IResolvesDex {

    private val classMmTypingSendReq by dexClass()

    override fun onEnable() {
        classMmTypingSendReq.clazz.asResolver().firstMethod { name = "doScene" }
            .hookBefore { param ->
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