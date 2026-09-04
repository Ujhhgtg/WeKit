package dev.ujhhgtg.wekit.features.items

import dev.ujhhgtg.wekit.utils.fs.moveReplacing
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.BaseContactSelector
import dev.ujhhgtg.wekit.ui.content.MINUTES_PER_DAY
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.formatMinuteOfDay
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class AutomationToggleRule(val enabled: Boolean = false)

@Serializable
data class AutomationTimeRangeRule(
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 0
) {
    fun matches(now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return true
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) current in start until end else current !in end..<start
    }
}

@Serializable
enum class AutomationKeywordMode {
    STRING_LIST,
    EXACT,
    REGEX
}

@Serializable
data class AutomationKeywordRule(
    val enabled: Boolean = false,
    val mode: AutomationKeywordMode = AutomationKeywordMode.STRING_LIST,
    val strings: List<String> = emptyList(),
    val regex: String = "",
    val ignoreCase: Boolean = false,
) {
    fun matches(text: String): Boolean {
        if (!enabled) return true
        val keywords = strings
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        return when (mode) {
            AutomationKeywordMode.STRING_LIST -> keywords.any { text.contains(it, ignoreCase) }
            AutomationKeywordMode.EXACT -> keywords.any { text.equals(it, ignoreCase) }

            AutomationKeywordMode.REGEX -> runCatching {
                Regex(regex, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                    .containsMatchIn(text)
            }.getOrDefault(false)
        }
    }

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

class AtomicJsonConfigStore<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val tag: String,
    private val initialValue: () -> T
) {
    @Volatile
    private var cached: T? = null

    fun get(): T {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read().also { cached = it }
        }
    }

    fun update(transform: (T) -> T): T = synchronized(this) {
        val updated = transform(get())
        write(updated)
        cached = updated
        updated
    }

    private fun read(): T {
        if (!file.exists()) {
            return initialValue().also(::write)
        }
        return runCatching {
            DefaultJson.decodeFromString(serializer, file.readText())
        }.onFailure {
            WeLogger.e(tag, "failed to read $file", it)
        }.getOrElse { initialValue() }
    }

    private fun write(value: T) {
        runCatching {
            file.parent.createDirectories()
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            temporary.writeText(DefaultJson.encodeToString(serializer, value))
            temporary.moveReplacing(file)
        }.onFailure {
            WeLogger.e(tag, "failed to save $file", it)
        }
    }
}

@Composable
fun AutomationContactSettingsSelector(
    title: String,
    contacts: List<IWeContact>,
    selectionKey: Any,
    subtitle: (IWeContact) -> String,
    isConfigured: (IWeContact) -> Boolean,
    onDismiss: () -> Unit,
    onOpen: (IWeContact) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val currentLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val collator = remember(currentLocale) { Collator.getInstance(currentLocale) }
    val filteredContacts = remember(searchQuery, contacts, collator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { first, second ->
                    collator.compare(first.displayName, second.displayName)
                }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "",
        confirmButtonEnabled = false,
        showConfirmButton = false,
        dismissButtonText = stringResource(R.string.dialog_close),
        onDismiss = onDismiss,
        onConfirm = {},
        selectionKey = selectionKey,
        isSelected = isConfigured,
        subtitleProvider = subtitle,
        trailingControl = { contact ->
            TextButton(onClick = { onOpen(contact) }) { Text(stringResource(R.string.action_settings)) }
        },
        onItemClick = onOpen
    )
}

fun formatAutomationMinute(value: Int): String = formatMinuteOfDay(value)

@Composable
fun automationKeywordSummary(rule: AutomationKeywordRule, unrestrictedText: String): String {
    if (!rule.enabled) return unrestrictedText
    return when (rule.mode) {
        AutomationKeywordMode.STRING_LIST -> pluralStringResource(
            R.plurals.automation_keyword_contains_summary,
            rule.strings.size,
            rule.strings.size,
        )
        AutomationKeywordMode.EXACT -> pluralStringResource(
            R.plurals.automation_keyword_exact_summary,
            rule.strings.size,
            rule.strings.size,
        )
        AutomationKeywordMode.REGEX -> if (rule.regex.isBlank()) {
            stringResource(R.string.automation_regex_empty_summary)
        } else {
            stringResource(R.string.automation_regex_summary)
        }
    }
}

private fun localizedAutomationString(resourceId: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(resourceId, *formatArgs)
