package dev.ujhhgtg.wekit.features.automation

/**
 * One outgoing action of a reply sequence; see [AutomationReplyExecutor] for each kind's content.
 */
data class AutomationReplyStep(
    val kind: AutomationReplyStepKind = AutomationReplyStepKind.TEXT,
    val content: String = "",
    val delayMs: Long = 0L,
    val randomDelay: Boolean = false,
)

enum class AutomationReplyStepKind(val usesFiles: Boolean = false) {
    /** Text; `|` separates several messages, `[AtWx=wxid]` mentions. */
    TEXT,
    IMAGE(usesFiles = true),
    VOICE(usesFiles = true),

    /** Sends one randomly chosen audio file from the listed files or folders. */
    RANDOM_VOICE(usesFiles = true),
    EMOJI(usesFiles = true),
    VIDEO(usesFiles = true),
    FILE(usesFiles = true),

    /** Favorite local ids. */
    FAVORITE,

    /** Contact wxids to share as cards. */
    CARD,

    /** Group ids to invite the sender into. */
    INVITE_GROUP,

    /** Raw AppMsg XML; plain text is wrapped into a minimal AppMsg. */
    XML,
}

data class AutomationReplyRule(
    val enabled: Boolean = false,
    val steps: List<AutomationReplyStep> = emptyList(),
    val asQuote: Boolean = false,
) {
    fun validationError(): String? {
        if (!enabled) return null
        if (steps.isEmpty())
            return localizedAutomationString(dev.ujhhgtg.wekit.R.string.automation_reply_required)
        return null
    }
}

/** Splits a multi-value step content (paths, ids): one per line, or separated by `;;;` / `|`. */
fun splitReplyValues(value: String): List<String> =
    value.split(";;;", "\n", "|").map(String::trim).filter(String::isNotEmpty)
