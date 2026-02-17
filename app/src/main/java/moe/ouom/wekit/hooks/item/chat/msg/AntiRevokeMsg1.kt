package moe.ouom.wekit.hooks.item.chat.msg

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.dsl.resultNull
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天与消息/阻止消息撤回 1", desc = "无撤回提示")
class AntiRevokeMsg1 : BaseSwitchFunctionHookItem(), IDexFind {
    private val methodRevokeMsg by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()
        methodRevokeMsg.find(dexKit, descriptors = descriptors) {
            matcher {
                usingEqStrings("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
            }
        }
        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        methodRevokeMsg.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.resultNull()
                }
            }
        }
    }
}
