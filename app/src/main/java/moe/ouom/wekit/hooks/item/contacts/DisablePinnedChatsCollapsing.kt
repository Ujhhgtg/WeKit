package moe.ouom.wekit.hooks.item.contacts

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeDatabaseApi
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "联系人/禁用置顶聊天折叠", desc = "隐藏 '折叠置顶聊天' 选项\n启用本功能后, 需重启应用 2 次以使更改完全生效")
object DisablePinnedChatsCollapsing : BaseSwitchFunctionHookItem(), IDexFind {

    private const val TAG = "DisablePinnedChatsCollapsing"
    private val methodAddCollapseChatItem by dexMethod()
    private val methodIfShouldAddCollapseChatItem by dexMethod()

    override fun entry(classLoader: ClassLoader) {
        methodAddCollapseChatItem.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    WeLogger.d(TAG, "invoked methodAddCollapseChatItem")
                    WeDatabaseApi.executeUpdate("DELETE FROM rconversation WHERE username = 'message_fold'")
                    param.result = null
                }
            }
        }
        methodIfShouldAddCollapseChatItem.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    WeLogger.d(TAG, "invoked methodIfShouldAddCollapseChatItem")
                    WeDatabaseApi.executeUpdate("DELETE FROM rconversation WHERE username = 'message_fold'")
                    param.result = false
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodAddCollapseChatItem.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.conversation")
            matcher {
                usingEqStrings("MicroMsg.FolderHelper", "fold item exist")
            }
        }

        methodIfShouldAddCollapseChatItem.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.conversation")
            matcher {
                usingEqStrings("MicroMsg.FolderHelper", "checkIfShowFoldItem, ifShow:")
                returnType(Boolean::class.java)
            }
        }

        return descriptors
    }

}