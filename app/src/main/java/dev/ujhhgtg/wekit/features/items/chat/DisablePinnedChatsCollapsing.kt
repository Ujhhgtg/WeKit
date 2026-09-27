package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int

object DisablePinnedChatsCollapsing : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用置顶聊天折叠"
    override val nameRes = R.string.feature_disable_pinned_chats_collapsing_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_pinned_chats_collapsing_description

    // Both folder helpers fold pinned chats only while this pinned-count threshold holds. Once it
    // fails, WeChat's own fold check skips adding the fold item and deletes an existing one,
    // restoring the folded chats' parentRef and the fold state.
    private val methodShouldFold by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.FolderHelper", "fold item exist")
            }
            paramTypes(int)
            returnType(bool)
        }
    }

    private val methodRecyclerShouldFold by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings(
                    "MicroMsg.RecyclerFolderHelper",
                    "performFoldItemClick: not ready",
                )
            }
            paramTypes(int)
            returnType(bool)
        }
    }

    override fun onEnable() {
        methodShouldFold.hookBefore {
            result = false
        }

        if (methodRecyclerShouldFold.isFound) {
            methodRecyclerShouldFold.hookBefore {
                result = false
            }
        }
    }
}
