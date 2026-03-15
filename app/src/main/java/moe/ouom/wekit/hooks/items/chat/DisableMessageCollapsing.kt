package moe.ouom.wekit.hooks.items.chat

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/禁用消息折叠", desc = "阻止聊天消息被折叠")
object DisableMessageCollapsing : SwitchHookItem(), IResolvesDex {

    private val methodFoldMsg by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodFoldMsg.find(dexKit, descriptors = descriptors) {
            matcher {
                usingStrings(".msgsource.sec_msg_node.clip-len")
                paramTypes(
                    Int::class.java,
                    CharSequence::class.java,
                    null,
                    Boolean::class.javaPrimitiveType,
                    null,
                    null
                )
            }
        }

        return descriptors
    }

    override fun onEnable() {
        methodFoldMsg.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.result = null
                }
            }
        }
    }
}