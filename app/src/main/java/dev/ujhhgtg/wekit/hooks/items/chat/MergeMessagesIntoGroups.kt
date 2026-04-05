package dev.ujhhgtg.wekit.hooks.items.chat

import android.util.SparseBooleanArray
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.model.MessageInfo
import dev.ujhhgtg.wekit.hooks.api.core.model.MessageType
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import java.lang.reflect.Field

@HookItem(path = "聊天/合并消息显示", description = "将同来源的连续多条消息合并为一组消息显示 (Telegram 风格)")
object MergeMessagesIntoGroups : SwitchHookItem(), WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        timeVisibilityCache.clear()
    }

    // ── field cache ──────────────────────────────────────────────────────────

    private var avatarField: Field? = null
    private var displayNameField: Field? = null
    private var timeTVField: Field? = null

    private fun ensureFields(tag: Any) {
        if (avatarField == null) {
            avatarField = tag.asResolver()
                .firstField { name = "avatarIV"; superclass() }.self
        }
        if (displayNameField == null) {
            displayNameField = tag.asResolver()
                .firstField { name = "userTV"; superclass() }.self
        }
        if (timeTVField == null) {
            timeTVField = tag.asResolver()
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
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.isType(MessageType.SYSTEM) || msgInfo.isType(MessageType.PAT)) return

        val currentSender = msgInfo.sender
        val position = param.args[2] as Int

        val adapter = param.thisObject.asResolver()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get() ?: return

        ensureFields(tag)

        // Record whether THIS message's timestamp is visible so that the
        // message at position-1 can use it when it is (re-)bound.
        val currentHasVisibleTime =
            (timeTVField?.get(tag) as? View)?.visibility == View.VISIBLE
        timeVisibilityCache.put(position, currentHasVisibleTime)

        val prevSender = senderAt(adapter, position - 1)
        val nextSender = senderAt(adapter, position + 1)

        // A visible timeTV means WeChat inserted a time-gap separator *above*
        // this bubble → hard group break regardless of sender identity.
        val isFirstInGroup = prevSender != currentSender || currentHasVisibleTime

        // The next message having a visible timeTV means a time-gap separator
        // appears *below* this bubble → this bubble closes the group.
        val nextHasVisibleTime = timeVisibilityCache.get(position + 1, false)
        val isLastInGroup = nextSender != currentSender || nextHasVisibleTime

        // Avatar: INVISIBLE (not GONE) so the text column stays aligned.
        (avatarField?.get(tag) as? View)?.let { avatar ->
            val avatarContainer = avatar.parent as? View ?: avatar
            avatarContainer.visibility =
                if (isLastInGroup) View.VISIBLE else View.INVISIBLE
        }

        // Nickname: GONE collapses the row height for mid-group bubbles.
        (displayNameField?.get(tag) as? View)?.visibility =
            if (isFirstInGroup) View.VISIBLE else View.GONE
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun senderAt(adapter: Any, position: Int): String? = runCatching {
        val raw = adapter.asResolver()
            .firstMethod { name = "getItem" }
            .invoke(position) ?: return null
        MessageInfo(raw).sender
    }.getOrNull()
}
