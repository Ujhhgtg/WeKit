package dev.ujhhgtg.wekit.features.items.chat

import android.util.SparseBooleanArray
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import java.lang.reflect.Field

@Feature(name = "合并消息显示", categories = ["聊天"], description = "将同一发送者的连续多条消息合并为一组消息显示 (Telegram 风格)")
object MergeMessagesIntoGroups : SwitchFeature(), WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        timeVisibilityCache.clear()
    }

    // ── field cache ──────────────────────────────────────────────────────────

    private lateinit var avatarField: Field
    private lateinit var displayNameField: Field
    private lateinit var timeField: Field

    private fun ensureFields(tag: Any) {
        if (!::avatarField.isInitialized) {
            avatarField = tag.reflekt()
                .firstField { name = "avatarIV"; superclass() }.self
        }
        if (!::displayNameField.isInitialized) {
            displayNameField = tag.reflekt()
                .firstField { name = "userTV"; superclass() }.self
        }
        if (!::timeField.isInitialized) {
            timeField = tag.reflekt()
                .firstField { name = "timeTV"; superclass() }.self
        }
    }

    // ── timeTV visibility cache ──────────────────────────────────────────────

    // Keyed by adapter position. Populated as views are bound by the RecyclerView.
    //
    // Used so that when message at position N is being laid out we can ask
    // "does position N+1 start with a timestamp?" without having its View in hand.
    //
    // Default (missing entry) → false, which is the safe fallback: it may
    // occasionally leave a message without its avatar when the next item hasn't
    // been bound yet, but that corrects itself on the next rebind (e.g. a scroll).
    private val timeVisibilityCache = SparseBooleanArray()

    // ── ICreateViewListener ──────────────────────────────────────────────────

    override fun onCreateView(param: HookParam, view: View) {
        val tag = view.tag ?: return

        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.isSend != 0) return
        if (msgInfo.type?.isSystem ?: false || msgInfo.type == MessageType.PAT) return

        val isGroupChat = msgInfo.isInGroupChat
        val currentSender = msgInfo.sender
        val position = param.args[2] as Int

        val adapter = param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get() ?: return

        ensureFields(tag)

        val currentHasVisibleTime =
            (timeField.get(tag) as? View)?.visibility == View.VISIBLE
        timeVisibilityCache.put(position, currentHasVisibleTime)

        val isFirstInGroup = run {
            val prevSender = senderAt(adapter, position - 1)
            prevSender != currentSender || currentHasVisibleTime
        }

        val nextHasVisibleTime = timeVisibilityCache.get(position + 1, false)
        val isLastInGroup = run {
            val nextSender = senderAt(adapter, position + 1)
            nextSender != currentSender || nextHasVisibleTime
        }

        // Avatar: INVISIBLE (not GONE) so the text column stays aligned
        (avatarField.get(tag) as? View)?.let { avatar ->
            val avatarContainer = avatar.parent as? View ?: avatar
            avatarContainer.visibility =
                if (isLastInGroup) View.VISIBLE else View.INVISIBLE
        }

        // Display Name: only shown in group chats
        if (isGroupChat) {
            (displayNameField.get(tag) as? View)?.visibility =
                if (isFirstInGroup) View.VISIBLE else View.GONE
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun senderAt(adapter: Any, position: Int): String? = runCatching {
        val raw = adapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(position) ?: return null
        MessageInfo(raw).sender
    }.getOrNull()
}
