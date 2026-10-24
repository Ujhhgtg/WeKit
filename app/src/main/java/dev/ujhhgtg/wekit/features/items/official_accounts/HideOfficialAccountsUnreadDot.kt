package dev.ujhhgtg.wekit.features.items.official_accounts

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object HideOfficialAccountsUnreadDot : SwitchFeature(), IResolveDex {

    override val technicalId = "隐藏公众号未读红点"
    override val nameRes = R.string.feature_hide_official_accounts_unread_dot_name
    override val categoryIds = listOf(FeatureCategoryIds.OFFICIAL_ACCOUNTS)
    override val descriptionRes = R.string.feature_hide_official_accounts_unread_dot_description

    // ConversationUnreadHelper.getShowUnreadCountType(Conversation): 0 = none, 1 = dot, 2 = count.
    // Every conversation-list row (and the double-tap "jump to next unread") asks it; the
    // "officialaccounts" entry only ever gets 0 or 1, and the tab badge total already excludes it.
    private val methodGetShowUnreadCountType by dexMethod {
        returnType = "int"
        paramCount = 1
        modifiers = 8
        declaredClass {
            usingEqStrings("MicroMsg.ConversationUnreadHelper")
            usingEqStrings("getTotalUnread totalUnread:%s cost:%s")
        }
    }

    override fun onEnable() {
        methodGetShowUnreadCountType.hookBefore {
            // The host method itself accepts a null conversation.
            val conversation = args[0] ?: return@hookBefore
            if (conversation.reflekt().getField("field_username", true) == "officialaccounts")
                result = 0
        }
    }
}
