package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam

object HideMessagesAvatars : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "隐藏消息头像"
    override val nameRes = R.string.feature_hide_messages_avatars_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_hide_messages_avatars_description

    var hideIncoming by prefOption("chat_hide_avatar_incoming", true)
    private var hideOutgoing by prefOption("chat_hide_avatar_outgoing", false)

    private const val MASK_LAYOUT_CLASS = "com.tencent.mm.ui.base.MaskLayout"

    // Remembers the original width of each avatar MaskLayout we shrink, so it can be restored
    // when a recycled row should show its avatar again. Keyed weakly on the mask view itself.
    private val originalMaskWidths = java.util.WeakHashMap<View, Int>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(param: HookParam, view: View) {
        val tag = view.tag
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        val hide = if (msgInfo.isSelfSender) {
            hideOutgoing
        } else {
            !msgInfo.isInGroupChat && hideIncoming
        }

        val avatar = tag.reflekt()
            .firstField {
                name = "avatarIV"
                superclass()
            }.get() as? View? ?: return

        val parent = avatar.parent as? ViewGroup ?: return

        if (parent.javaClass.name == MASK_LAYOUT_CLASS) {
            // The avatar is wrapped in a MaskLayout (R.id.bk4) with a fixed 52dp size. In
            // RelativeLayout-based rows (text, image, voice) sibling views are anchored
            // toRightOf/toLeftOf this mask, so setting it GONE collapses the anchor to the
            // edge and shifts the whole bubble. Instead, keep the mask visible but shrink it
            // to zero width: the anchor stays valid while the avatar space disappears. This
            // behaves identically to GONE inside LinearLayout rows (e.g. incoming video).
            //
            // WeChat resets the avatar's visibility on every bind but never its width, so the
            // original width is remembered and restored on rows that should keep the avatar.
            val lp = parent.layoutParams
            if (hide) {
                originalMaskWidths.getOrPut(parent) { lp.width }
                lp.width = 0
                parent.layoutParams = lp
                avatar.visibility = View.GONE
            } else {
                originalMaskWidths.remove(parent)?.let { orig ->
                    lp.width = orig
                    parent.layoutParams = lp
                }
            }
        } else if (hide) {
            // The avatar is a direct child of the message row (e.g. outgoing video). A GONE
            // child collapses correctly in the row's LinearLayout. Visibility is restored by
            // WeChat itself on subsequent binds.
            avatar.visibility = View.GONE
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var incoming by remember { mutableStateOf(hideIncoming) }
            var outgoing by remember { mutableStateOf(hideOutgoing) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_hide_messages_avatars_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_hide_avatar_incoming),
                                description = stringResource(R.string.chat_hide_avatar_incoming_description),
                                checked = incoming,
                                onCheckedChange = {
                                    incoming = it
                                    hideIncoming = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_hide_avatar_outgoing),
                                description = stringResource(R.string.chat_hide_avatar_outgoing_description),
                                checked = outgoing,
                                onCheckedChange = {
                                    outgoing = it
                                    hideOutgoing = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
