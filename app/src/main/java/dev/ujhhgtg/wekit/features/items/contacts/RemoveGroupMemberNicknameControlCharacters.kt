package dev.ujhhgtg.wekit.features.items.contacts

import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam

@Feature(
    name = "移除群成员昵称控制字符",
    categories = ["聊天"],
    description = "自动移除群聊成员昵称中影响显示的 Unicode 控制字符"
)
object RemoveGroupMemberNicknameControlCharacters : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.isSend != 0) return

        val textView = view.tag.reflekt()
            .firstField { name = "userTV"; superclass() }
            .get() as? TextView? ?: return

        val currentText = textView.text ?: return
        val nicknameRange = currentText.groupMemberNicknameRange()
        if (nicknameRange.start >= nicknameRange.endExclusive) return

        var sanitizedText: SpannableStringBuilder? = null
        var cursor = nicknameRange.endExclusive
        while (cursor > nicknameRange.start) {
            val codePoint = Character.codePointBefore(currentText, cursor)
            val codePointStart = cursor - Character.charCount(codePoint)
            if (codePoint.isDisplayDisruptingControl()) {
                val builder = sanitizedText
                    ?: SpannableStringBuilder(currentText).also { sanitizedText = it }
                builder.delete(codePointStart, cursor)
            }
            cursor = codePointStart
        }

        sanitizedText?.let { textView.text = it }
    }

    private fun Int.isDisplayDisruptingControl(): Boolean = when (this) {
        in 0x0000..0x001F, // C0 controls, including tabs and line breaks
        in 0x007F..0x009F, // DEL and C1 controls
        0x00AD, // soft hyphen
        0x061C, // Arabic letter mark
        0x180E, // Mongolian vowel separator
        0x200B, // zero-width space; ZWNJ/ZWJ are intentionally preserved
        0x200E, 0x200F, // left-to-right/right-to-left marks
        0x2028, 0x2029, // line/paragraph separators
        in 0x202A..0x202E, // bidirectional embeddings, overrides, and PDF
        in 0x2060..0x2064, // word joiner and invisible operators
        in 0x2066..0x206F, // bidirectional isolates and deprecated controls
        0xFEFF, // zero-width no-break space / BOM
        in 0xFFF9..0xFFFB, // interlinear annotation controls
            -> true

        else -> false
    }
}
