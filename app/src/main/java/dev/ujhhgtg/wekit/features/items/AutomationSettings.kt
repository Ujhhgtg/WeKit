package dev.ujhhgtg.wekit.features.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.BaseContactSelector
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.MINUTES_PER_DAY
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.WeTimeOfDayField
import dev.ujhhgtg.wekit.ui.content.formatMinuteOfDay
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
internal data class AutomationToggleRule(val enabled: Boolean = false)

@Serializable
internal data class AutomationTimeRangeRule(
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
internal enum class AutomationKeywordMode {
    STRING_LIST,
    EXACT,
    REGEX
}

@Serializable
internal data class AutomationKeywordRule(
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

internal class AtomicJsonConfigStore<T>(
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
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            temporary.writeText(DefaultJson.encodeToString(serializer, value))
            runCatching {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.getOrElse {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            WeLogger.e(tag, "failed to save $file", it)
        }
    }
}

@Composable
internal fun AutomationContactSettingsSelector(
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

@Composable
internal fun AutomationRuleHeader(
    title: String,
    summary: String,
    enabled: Boolean,
    isOverridden: Boolean? = null,
    parentLabel: String = "",
    onActivate: () -> Unit = {},
    onReset: () -> Unit = {},
    onEnabledChange: (Boolean) -> Unit,
    switchEnabled: Boolean = true,
) {
    val editable = isOverridden != false
    val effectiveSummary = if (isOverridden == false) {
        stringResource(R.string.automation_follow_parent, parentLabel, summary)
    } else summary
    ListItem(
        modifier = Modifier.clickable {
            if (editable && switchEnabled) onEnabledChange(!enabled) else onActivate()
        },
        leadingContent = {
            Switch(
                checked = enabled,
                enabled = editable && switchEnabled,
                onCheckedChange = if (editable && switchEnabled) onEnabledChange else null
            )
        },
        content = { Text(title) },
        supportingContent = { Text(effectiveSummary) },
        trailingContent = if (isOverridden != null) {
            {
                TextButton(enabled = isOverridden, onClick = onReset) {
                    Text(stringResource(R.string.action_reset))
                }
            }
        } else null
    )
}

@Composable
internal fun AutomationTimeRangeControls(
    rule: AutomationTimeRangeRule,
    editable: Boolean,
    onChange: (AutomationTimeRangeRule) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WeTimeOfDayField(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.automation_start),
            minuteOfDay = rule.startMinute,
            enabled = editable,
            onMinuteChange = { onChange(rule.copy(startMinute = it)) }
        )
        WeTimeOfDayField(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.automation_end),
            minuteOfDay = rule.endMinute,
            enabled = editable,
            onMinuteChange = { onChange(rule.copy(endMinute = it)) }
        )
    }
}

@Composable
internal fun AutomationKeywordControls(
    rule: AutomationKeywordRule,
    editable: Boolean,
    onChange: (AutomationKeywordRule) -> Unit,
    modes: List<AutomationKeywordMode> = AutomationKeywordMode.entries,
) {
    var pendingKeyword by remember { mutableStateOf("") }
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = rule.mode == mode,
                enabled = editable,
                onClick = { onChange(rule.copy(mode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size)
            ) {
                Text(
                    when (mode) {
                        AutomationKeywordMode.STRING_LIST -> stringResource(R.string.automation_keyword_mode_contains)
                        AutomationKeywordMode.EXACT -> stringResource(R.string.automation_keyword_mode_exact)
                        AutomationKeywordMode.REGEX -> stringResource(R.string.automation_keyword_mode_regex)
                    }
                )
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.automation_ignore_case), modifier = Modifier.weight(1f))
        Switch(
            checked = rule.ignoreCase,
            enabled = editable,
            onCheckedChange = { onChange(rule.copy(ignoreCase = it)) }
        )
    }
    if (rule.mode == AutomationKeywordMode.STRING_LIST || rule.mode == AutomationKeywordMode.EXACT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = pendingKeyword,
                enabled = editable,
                onValueChange = { pendingKeyword = it },
                label = { Text(stringResource(R.string.automation_new_keyword)) },
                singleLine = true
            )
            Button(
                enabled = editable && pendingKeyword.trim().isNotEmpty(),
                onClick = {
                    val keyword = pendingKeyword.trim()
                    if (keyword !in rule.strings) onChange(rule.copy(strings = rule.strings + keyword))
                    pendingKeyword = ""
                }
            ) { Text(stringResource(R.string.action_add)) }
        }
        rule.strings.forEach { keyword ->
            ListItem(
                content = { Text(keyword) },
                trailingContent = {
                    TextButton(
                        enabled = editable,
                        onClick = { onChange(rule.copy(strings = rule.strings - keyword)) }
                    ) { Text(stringResource(R.string.action_delete)) }
                }
            )
        }
    } else {
        val regexInvalid = rule.regex.isNotBlank() && runCatching { Regex(rule.regex) }.isFailure
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            value = rule.regex,
            enabled = editable,
            onValueChange = { onChange(rule.copy(regex = it)) },
            label = { Text(stringResource(R.string.automation_keyword_mode_regex)) },
            supportingText = if (regexInvalid) {
                { Text(stringResource(R.string.automation_regex_invalid)) }
            } else null,
            isError = regexInvalid,
            singleLine = true
        )
    }
}

@Composable
internal fun AutomationSettingsError(error: String?) {
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun AutomationScrollableColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

internal fun formatAutomationMinute(value: Int): String = formatMinuteOfDay(value)

@Composable
internal fun automationKeywordSummary(rule: AutomationKeywordRule, unrestrictedText: String): String {
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
