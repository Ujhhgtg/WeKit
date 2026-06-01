package dev.ujhhgtg.wekit.hooks.items.chat

import android.util.SparseBooleanArray
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageType
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import java.lang.reflect.Field

@HookItem(path = "聊天/合并消息显示", description = "将同一发送者的连续多条消息合并为一组消息显示 (Telegram 风格)")
object MergeMessagesIntoGroups : SwitchHookItem(), WeChatMessageViewApi.ICreateViewListener {

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
            avatarField = tag.asResolver()
                .firstField { name = "avatarIV"; superclass() }.self
        }
        if (!::displayNameField.isInitialized) {
            displayNameField = tag.asResolver()
                .firstField { name = "userTV"; superclass() }.self
        }
        if (!::timeField.isInitialized) {
            timeField = tag.asResolver()
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

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val tag = view.tag ?: return

        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.isSend != 0) return
        if (msgInfo.type?.isSystem ?: false || msgInfo.type == MessageType.PAT) return

        val isGroupChat = msgInfo.isInGroupChat
        val currentSender = msgInfo.sender
        val position = param.args[2] as Int

        val adapter = param.thisObject.asResolver()
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
        val raw = adapter.asResolver()
            .firstMethod { name = "getItem" }
            .invoke(position) ?: return null
        MessageInfo(raw).sender
    }.getOrNull()
}
