package dev.ujhhgtg.wekit.features.items.chat

import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.HookParam

/**
 * Sole annotator for real-name characters in group-chat message views.
 *
 * Reads cached data from [BruteForceGroupMemberRealNamesFirstChar] (first char) and
 * [DisplayGroupMemberRealNamesLastChar] (last char) and composes a single annotation.
 * Neither data-source feature touches the [TextView] directly — all view logic lives here.
 *
 * Annotation rules:
 *
 * | firstChar | lastChar | existing annotation | emits         |
 * |-----------|----------|---------------------|---------------|
 * | ✓         | ✓        | any                 | `(首·尾)`    |
 * | ✓         | null     | none                | `(首·?)`     |
 * | ✓         | null     | present             | no-op         |
 * | null      | ✓        | any                 | `(尾)`       |
 * | null      | null     | —                   | no-op         |
 *
 * When the last char is missing at view-bind time, a passive background fetch is triggered via
 * [DisplayGroupMemberRealNamesLastChar.fetchRealName]; on completion the same view is updated
 * if it has not yet been recycled to a different sender.
 */
@Feature(
    name = "显示群成员实名全字",
    categories = ["API"],
    description = "整合「爆破群成员实名首字」与「显示群成员实名尾字」的结果, 在昵称旁同时显示首字与尾字"
)
object DisplayGroupMemberRealName : ApiFeature(), WeChatMessageViewApi.ICreateViewListener {

    /**
     * Integer tag key stamped onto the username [TextView] so async fetch callbacks can verify
     * the view has not been recycled to a different sender before posting their update.
     * Uses 0x7E000001 (previously held by [DisplayGroupMemberRealNamesLastChar], which no
     * longer touches views). Placed in the 0x7E… range to avoid collisions with
     * Android-generated R.id values (0x7F…).
     */
    private const val VIEW_TAG_SENDER = 0x7E000001

    private const val DEFAULT_FG = "#FF9E9E9E"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun annotationColor(): Int =
        runCatching { DisplayGroupMemberRealNamesLastChar.annotationFg.toColorInt() }
            .getOrElse { DEFAULT_FG.toColorInt() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    // ── ICreateViewListener ───────────────────────────────────────────────────

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.isSend != 0) return
        val sender = runCatching { msgInfo.sender }.getOrNull() ?: return

        val textView = view.tag.reflekt()
            .firstField { name = "userTV"; superclass() }
            .get() as? TextView? ?: return

        if (textView.isGone) return

        // Stamp sender so the async callback can detect recycled views
        textView.setTag(VIEW_TAG_SENDER, sender)

        val firstChar = BruteForceGroupMemberRealNamesFirstChar
            .takeIf { it.isActive }?.realNames?.get(sender)
        val lastChar = DisplayGroupMemberRealNamesLastChar
            .takeIf { it.isActive }?.realNames?.get(sender)

        // Apply whatever is already cached immediately
        applyAnnotation(textView, sender, firstChar, lastChar)

        // If the last char is not yet known and the feature is enabled, request a background
        // fetch. The callback posts back to the main thread and updates the view if it still
        // belongs to the same sender (i.e. has not been recycled in the meantime).
        if (lastChar == null && DisplayGroupMemberRealNamesLastChar.isActive) {
            DisplayGroupMemberRealNamesLastChar.fetchRealName(sender, msgInfo.talker) { newLastChar ->
                mainHandler.post {
                    if (textView.getTag(VIEW_TAG_SENDER) == sender) {
                        applyAnnotation(
                            textView, sender,
                            BruteForceGroupMemberRealNamesFirstChar
                                .takeIf { it.isActive }?.realNames?.get(sender),
                            newLastChar
                        )
                    }
                }
            }
        }
    }

    // ── View update ───────────────────────────────────────────────────────────

    /**
     * Builds and applies the real-name annotation onto [textView].
     *
     * Any trailing `" (…)"` annotation previously written by this feature is stripped before
     * the new one is appended, so the two never stack. Spans set by other hooks (e.g. the
     * role badge from [DisplayGroupMemberRoles]) are preserved because the
     * [SpannableStringBuilder] is constructed from the live [CharSequence] truncated to
     * [plainLen], which retains all spans in the base-text portion.
     */
    private fun applyAnnotation(
        textView: TextView,
        sender: String,
        firstChar: String?,
        lastChar: String?
    ) {
        if (textView.getTag(VIEW_TAG_SENDER) != sender) return

        val existing = textView.text ?: return
        val base = existing.toString()

        // Locate any trailing " (…)" annotation previously written by this feature
        val annotStart = base.lastIndexOf(" (")
        val hasExistingAnnotation = annotStart >= 0 && base.endsWith(")")
        val plainLen = if (hasExistingAnnotation) annotStart else base.length

        val annotation = when {
            firstChar != null && lastChar != null -> {
                // lastChar = "<middle asterisks><last char>", e.g. "**C" for a 4-char name.
                // Reconstruct the masked display name, collapsing any number of middle asterisks
                // to exactly one so 3-char and 4-char names both render as e.g. "A*C".
                // 2-char names have no middle asterisks and render as e.g. "AB".
                val last = lastChar.last()
                val middle = lastChar.dropLast(1)
                val masked = if (middle.isEmpty()) "$firstChar$last" else "$firstChar*$last"
                " ($masked)"
            }
            // Show first-only placeholder only when there is no annotation yet;
            // if one exists it already contains the last char, which is more informative.
            // "?" signals unknown length — no asterisks, since we can't infer how many middle
            // chars exist yet.
            firstChar != null && !hasExistingAnnotation -> " ($firstChar?)"
            // lastChar already encodes the masked middle (e.g. "**C"), so show it verbatim.
            firstChar == null && lastChar != null -> " ($lastChar)"
            else -> return
        }

        // Guard: already showing exactly this annotation — nothing to do
        if (hasExistingAnnotation && base.substring(annotStart) == annotation) return
        if (!hasExistingAnnotation && base.endsWith(annotation)) return

        val sb = SpannableStringBuilder(existing, 0, plainLen)
        sb.append(annotation)

        sb.setSpan(
            ForegroundColorSpan(annotationColor()),
            plainLen, sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        textView.text = sb
    }
}
