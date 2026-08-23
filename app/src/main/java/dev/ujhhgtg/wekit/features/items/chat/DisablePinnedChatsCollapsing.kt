package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisablePinnedChatsCollapsing : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用置顶聊天折叠"
    override val nameRes = R.string.feature_disable_pinned_chats_collapsing_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_pinned_chats_collapsing_description

    private val methodAddCollapseChatItem by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingEqStrings("MicroMsg.FolderHelper", "fold item exist")
        }
    }
    private val methodIfShouldAddCollapseChatItem by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingEqStrings("MicroMsg.FolderHelper", "checkIfShowFoldItem, ifShow:")
            returnType(Boolean::class.java)
        }
    }

    override fun onEnable() {
        methodAddCollapseChatItem.hookBefore {
            if (WeDatabaseApi.isReady) {
                WeDatabaseApi.execStatement("DELETE FROM rconversation WHERE username = 'message_fold'")
            }
            result = null
        }
        methodIfShouldAddCollapseChatItem.hookBefore {
            if (WeDatabaseApi.isReady) {
                WeDatabaseApi.execStatement("DELETE FROM rconversation WHERE username = 'message_fold'")
            }
            result = false
        }
    }
}
