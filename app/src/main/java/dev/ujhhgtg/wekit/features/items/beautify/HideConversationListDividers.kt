package dev.ujhhgtg.wekit.features.items.beautify

import android.view.ViewGroup
import androidx.core.view.isGone
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes

@Feature(name = "隐藏对话列表分割线", categories = ["聊天", "界面美化"], description = "隐藏主页对话列表里对话间的分割线")
object HideConversationListDividers : SwitchFeature(), IResolveDex {

    private val methodConversationWithCacheAdapterGetView by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            usingEqStrings("MicroMsg.ConversationWithCacheAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
        }
    }

    private val methodMvvmConversationAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
            }
            name = "getView"
        }
    }

    override fun onEnable() {
        if (!methodConversationWithCacheAdapterGetView.isPlaceholder) {
            methodConversationWithCacheAdapterGetView.hookAfter {
                val viewGroup = result as? ViewGroup? ?: return@hookAfter
                handleViewGroup(viewGroup)
            }
        }

        if (!methodMvvmConversationAdapterGetView.isPlaceholder) {
            methodMvvmConversationAdapterGetView.hookAfter {
                val viewGroup = result as? ViewGroup? ?: return@hookAfter
                handleViewGroup(viewGroup)
            }
        }
    }

    private fun handleViewGroup(viewGroup: ViewGroup) {
        val divider = viewGroup.findViewByChildIndexes(0, 1, 1, 1)
            ?: viewGroup.findViewByChildIndexes(0, 1, 1)
        divider?.isGone = true
    }
}
