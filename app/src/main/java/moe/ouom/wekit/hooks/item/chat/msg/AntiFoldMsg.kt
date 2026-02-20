package moe.ouom.wekit.hooks.item.chat.msg

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.dsl.resultNull
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天与消息/防止消息折叠", desc = "阻止聊天消息被折叠")
class AntiFoldMsg : BaseSwitchFunctionHookItem(), IDexFind {

    private val TAG = "AntiFoldMsg"
    private val methodFoldMsg by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
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

    override fun entry(classLoader: ClassLoader) {
        // Hook 折叠方法，使其无效
        methodFoldMsg.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    WeLogger.i(TAG, "拦截到消息折叠方法")
                    param.resultNull()
                }
            }
        }
    }
}