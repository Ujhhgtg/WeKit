package dev.ujhhgtg.wekit.features.items.contacts

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

object SplitGroupChats : ClickableFeature() {

    override val technicalId = "分裂群组"
    override val nameRes = R.string.feature_split_group_chats_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_split_group_chats_description

    private const val TAG = "SplitGroupChats"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            SingleContactSelector(
                context.localizedContactsString(R.string.feature_split_group_chats_name),
                WeDatabaseApi.getGroups(),
                initialSelectedWxId = null,
                onDismiss = onDismiss,
            ) {
                onDismiss()
                jumpToSplitChatroom(context, it)
            }
        }
    }

    private fun jumpToSplitChatroom(context: Context, wxId: String) {
        runCatching {
            val rawId = wxId.substringBefore("@")
            val targetSplitId = "${rawId}@@chatroom"
            WeLogger.i(TAG, "launching ChattingUI for chatroom: $wxId")

            val intent = Intent(context, ChattingUI::class.java).apply {
                putExtra("Chat_User", targetSplitId)
                putExtra("Chat_Mode", 1)
            }

            context.startActivity(intent)
        }.onFailure { WeLogger.e(TAG, "exception occured", it) }
    }

    override val noSwitchWidget = true
}
