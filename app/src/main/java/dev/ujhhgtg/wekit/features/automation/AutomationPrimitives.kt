package dev.ujhhgtg.wekit.features.automation

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.MINUTES_PER_DAY
import dev.ujhhgtg.wekit.utils.HostInfo
import java.util.Calendar
import kotlin.random.Random

/*
 * Rule value types shared by every automation feature. Each is a plain immutable value; a feature
 * stores one value per rule key per scope (see [AutomationSpec]) and the resolver overlays them.
 */

data class AutomationToggleRule(val enabled: Boolean = false)

data class AutomationTimeRangeRule(
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 0,
) {
    /** True when the rule is disabled or [now] falls inside the (possibly overnight) range. */
    fun matches(now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return true
        return containsMinute(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
    }

    /** Like [matches] but false when disabled: "is this window currently active". */
    fun isActive(now: Calendar = Calendar.getInstance()): Boolean = enabled && matches(now)

    fun containsMinute(minute: Int): Boolean {
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) minute in start until end else minute !in end..<start
    }
}

enum class AutomationKeywordMode {
    STRING_LIST,
    EXACT,
    REGEX,
}

data class AutomationKeywordRule(
    val enabled: Boolean = false,
    val mode: AutomationKeywordMode = AutomationKeywordMode.STRING_LIST,
    val strings: List<String> = emptyList(),
    val regex: String = "",
    val ignoreCase: Boolean = false,
) {
    /** Disabled rules impose no restriction. */
    fun matches(text: String): Boolean {
        if (!enabled) return true
        return matchesEnabled(text)
    }

    /** Evaluates the matcher regardless of [enabled]; used by "exclude" style rules. */
    fun matchesEnabled(text: String): Boolean {
        val keywords = strings.asSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return when (mode) {
            AutomationKeywordMode.STRING_LIST -> keywords.any { text.contains(it, ignoreCase) }
            AutomationKeywordMode.EXACT -> keywords.any { text.equals(it, ignoreCase) }
            AutomationKeywordMode.REGEX -> runCatching {
                Regex(regex, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(text)
            }.getOrDefault(false)
        }
    }

    /** "Exclude" semantics: true only when enabled and matching. */
    fun excludes(text: String): Boolean = enabled && matchesEnabled(text)

    fun validationError(label: String): String? {
        if (!enabled) return null
        return when (mode) {
            AutomationKeywordMode.STRING_LIST, AutomationKeywordMode.EXACT ->
                if (strings.none(String::isNotBlank)) {
                    localizedAutomationString(R.string.automation_keyword_list_required, label)
                } else null

            AutomationKeywordMode.REGEX -> when {
                regex.isBlank() -> localizedAutomationString(R.string.automation_regex_required, label)
                runCatching { Regex(regex) }.isFailure ->
                    localizedAutomationString(R.string.automation_regex_invalid_for_label, label)
                else -> null
            }
        }
    }
}

/** Base delay plus a symmetric random offset, both in milliseconds (kept as text for editing). */
data class AutomationDelayRule(
    val enabled: Boolean = true,
    val baseMs: String = "500",
    val randomRangeMs: String = "300",
) {
    fun millis(): Long {
        if (!enabled) return 0L
        val base = (baseMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val range = (randomRangeMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        if (range == 0L) return base
        val safeRange = range.coerceAtMost(Long.MAX_VALUE - 1)
        return (base + Random.nextLong(-safeRange, safeRange + 1)).coerceAtLeast(0L)
    }

    fun validationError(): String? {
        if (!enabled) return null
        if (baseMs.toLongOrNull() == null) return localizedAutomationString(R.string.automation_invalid_base_delay)
        if (randomRangeMs.toLongOrNull() == null) return localizedAutomationString(R.string.automation_invalid_random_delay)
        return null
    }
}

/** An optional non-negative integer limit (count, seconds, ...). */
data class AutomationLimitRule(val enabled: Boolean = false, val value: Long = 0L)

/** A single free-form text value behind a switch (e.g. a remark template). */
data class AutomationTextRule(val enabled: Boolean = false, val text: String = "")

/** Which kind of conversation a talker is. Used by CATEGORY scopes and target filters. */
enum class AutomationTalkerCategory {
    PRIVATE,
    GROUP,
    OFFICIAL;

    companion object {
        fun of(talker: String): AutomationTalkerCategory = when {
            talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom") -> GROUP
            talker.startsWith("gh_") || talker.endsWith("@app") || talker == "newsapp" -> OFFICIAL
            else -> PRIVATE
        }

        private val SYSTEM_TALKERS = setOf(
            "filehelper", "fmessage", "tmessage", "qqmail", "weixin", "floatbottle",
            "medianote", "medianote@chatroom", "masssend", "feedsapp", "blogapp",
        )

        fun isSystemTalker(talker: String): Boolean = talker.trim() in SYSTEM_TALKERS
    }
}

/**
 * Which conversations/senders a rule applies to.
 *
 * Member tokens are either `groupId/memberId` or a bare `memberId` (any group).
 */
data class AutomationTargetRule(
    val mode: Mode = Mode.ALL,
    val categories: Set<AutomationTalkerCategory> = emptySet(),
    val include: Set<String> = emptySet(),
    val includeMembers: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val excludeMembers: Set<String> = emptySet(),
) {
    enum class Mode { ALL, CATEGORIES, SPECIFIC }

    fun matches(talker: String, sender: String): Boolean {
        val group = AutomationTalkerCategory.of(talker) == AutomationTalkerCategory.GROUP
        val memberToken = "$talker/$sender"
        if (talker in exclude || sender in exclude || (group && memberToken in exclude)) return false
        if (group && (sender in excludeMembers || memberToken in excludeMembers)) return false
        return when (mode) {
            Mode.ALL -> true
            Mode.CATEGORIES -> AutomationTalkerCategory.of(talker) in categories
            Mode.SPECIFIC -> talker in include ||
                    (!group && sender in include) ||
                    (group && (memberToken in include || memberToken in includeMembers || sender in includeMembers))
        }
    }
}

fun localizedAutomationString(resourceId: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(resourceId, *formatArgs)
