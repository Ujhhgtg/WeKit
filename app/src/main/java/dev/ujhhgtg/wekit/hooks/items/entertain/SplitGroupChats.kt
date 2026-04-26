package dev.ujhhgtg.wekit.hooks.items.entertain

import android.content.Context
import android.content.Intent
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "娱乐/分裂群组", description = "让群聊一分为二")
object SplitGroupChats : ClickableHookItem() {

    private val TAG = This.Class.simpleName

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            SingleContactSelector(
                "分裂群组",
                WeDatabaseApi.getGroups(),
                initialSelectedWxId = null,
                onDismiss = onDismiss,
            ) {
                onDismiss()
                jumpToSplitChatroom(it)
            }
        }
    }

    private fun jumpToSplitChatroom(wxId: String) {
        runCatching {
            val activity = LauncherUI.getInstance()!!

            val rawId = wxId.substringBefore("@")
            val targetSplitId = "${rawId}@@chatroom"
            WeLogger.i(TAG, "launching ChattingUI for chatroom: $wxId")

            val intent = Intent(activity, ChattingUI::class.java).apply {
                putExtra("Chat_User", targetSplitId)
                putExtra("Chat_Mode", 1)
            }

            activity.startActivity(intent)
        }.onFailure { WeLogger.e(TAG, "exception occured", it) }
    }

    override val noSwitchWidget = true
}
