package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.util.concurrent.atomic.AtomicBoolean

object DisablePinnedChatsCollapsing : SwitchFeature(), IResolveDex {

    private const val FOLD_CONVERSATION_USERNAME = "message_fold"

    private val staleFoldConversationCleaned = AtomicBoolean()

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
        staleFoldConversationCleaned.set(false)
        cleanupStaleFoldConversationOnce()

        methodAddCollapseChatItem.hookBefore {
            result = null
        }
        methodIfShouldAddCollapseChatItem.hookBefore {
            if (staleFoldConversationCleaned.get()) result = false
        }
        methodIfShouldAddCollapseChatItem.hookAfter {
            cleanupStaleFoldConversationOnce()
            result = false
        }
    }

    private fun cleanupStaleFoldConversationOnce() {
        if (staleFoldConversationCleaned.get() || !WeDatabaseApi.isReady) return
        if (!staleFoldConversationCleaned.compareAndSet(false, true)) return

        val deletedRows = WeDatabaseApi.delete(
            table = "rconversation",
            conditions = "username=?",
            args = arrayOf(FOLD_CONVERSATION_USERNAME),
        )
        if (deletedRows > 0) WeConversationApi.reloadConversations()
    }
}
