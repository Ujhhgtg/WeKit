package dev.ujhhgtg.wekit.features.automation

import dev.ujhhgtg.wekit.features.items.chat.autoreply.AutoReplyAutomation
import dev.ujhhgtg.wekit.features.items.contacts.FriendAutomation
import dev.ujhhgtg.wekit.features.items.moments.MomentsAutomation
import dev.ujhhgtg.wekit.features.items.payment.RedPacketAutomation
import dev.ujhhgtg.wekit.features.items.payment.TransferAutomation

/** Every automation feature's spec, looked up by id when the settings Activity starts. */
object AutomationSpecs {
    private val all: List<AutomationSpec> by lazy {
        listOf(
            RedPacketAutomation, TransferAutomation, MomentsAutomation.Like, MomentsAutomation.Repost, FriendAutomation,
            AutoReplyAutomation,
        )
    }

    fun byId(id: String): AutomationSpec = all.first { it.id == id }
}
