package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.items.AtomicJsonConfigStore
import dev.ujhhgtg.wekit.features.items.AutomationContactSettingsSelector
import dev.ujhhgtg.wekit.features.items.AutomationKeywordControls
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationRuleHeader
import dev.ujhhgtg.wekit.features.items.AutomationScrollableColumn
import dev.ujhhgtg.wekit.features.items.AutomationSettingsError
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeControls
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.items.AutomationToggleRule
import dev.ujhhgtg.wekit.features.items.automationKeywordSummary
import dev.ujhhgtg.wekit.features.items.formatAutomationMinute
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlinx.serialization.Serializable
import kotlin.io.path.div

@Serializable
internal enum class MomentAutomationAction {
    LIKE,
    UNLIKE
}

@Serializable
internal enum class MomentAutomationMode {
    WHEN_SEEN,
    ALL_LOADED
}

@Serializable
internal data class MomentActionRule(
    val enabled: Boolean = true,
    val action: MomentAutomationAction = MomentAutomationAction.LIKE
)

@Serializable
internal data class MomentModeRule(
    val enabled: Boolean = true,
    val mode: MomentAutomationMode = MomentAutomationMode.WHEN_SEEN
)

@Serializable
internal data class MomentIntervalRule(
    val enabled: Boolean = false,
    val milliseconds: String = "0"
) {
    fun value(): Long = if (enabled) {
        (milliseconds.toLongOrNull() ?: 0L).coerceIn(0L, MAX_ACTION_DELAY_MS)
    } else {
        0L
    }
}

@Serializable
internal data class MomentTypeRule(
    val enabled: Boolean = false,
    val typeIds: Set<Int> = MomentsContentType.allTypeIds
)

@Serializable
internal data class MomentAgeRule(
    val enabled: Boolean = false,
    val maximumHours: String = "24"
) {
    fun matches(createTimeSeconds: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return true
        val hours = maximumHours.toLongOrNull()?.takeIf { it >= 0L } ?: return false
        if (createTimeSeconds <= 0) return false
        val ageSeconds = (nowMillis / 1000L - createTimeSeconds.toLong()).coerceAtLeast(0L)
        return ageSeconds <= hours * 60L * 60L
    }
}

@Serializable
internal data class MomentAutomationRuleSet(
    val process: AutomationToggleRule = AutomationToggleRule(enabled = true),
    val action: MomentActionRule = MomentActionRule(),
    val mode: MomentModeRule = MomentModeRule(),
    val interval: MomentIntervalRule = MomentIntervalRule(),
    val timeRange: AutomationTimeRangeRule = AutomationTimeRangeRule(),
    val keyword: AutomationKeywordRule = AutomationKeywordRule(),
    val contentType: MomentTypeRule = MomentTypeRule(),
    val maximumAge: MomentAgeRule = MomentAgeRule()
) {
    val effectiveAction: MomentAutomationAction
        get() = if (action.enabled) action.action else MomentAutomationAction.LIKE

    val effectiveMode: MomentAutomationMode
        get() = if (mode.enabled) mode.mode else MomentAutomationMode.WHEN_SEEN

    fun matchesMoment(snsInfo: Any): Boolean {
        if (!process.enabled || !timeRange.matches()) return false
        if (!keyword.enabled && !contentType.enabled && !maximumAge.enabled) return true
        val proto = WeMomentsApi.getTimelineProto(snsInfo) ?: return false
        if (!keyword.matches(proto.contentDesc.orEmpty())) return false
        if (contentType.enabled) {
            val type = proto.contentObj?.type ?: return false
            if (type !in contentType.typeIds) return false
        }
        return maximumAge.matches(proto.createTime)
    }
}

@Serializable
internal data class MomentAutomationOverrides(
    val process: AutomationToggleRule? = null,
    val action: MomentActionRule? = null,
    val mode: MomentModeRule? = null,
    val interval: MomentIntervalRule? = null,
    val timeRange: AutomationTimeRangeRule? = null,
    val keyword: AutomationKeywordRule? = null,
    val contentType: MomentTypeRule? = null,
    val maximumAge: MomentAgeRule? = null
) {
    fun overriddenCount(includeAction: Boolean): Int = listOfNotNull(
        process,
        action.takeIf { includeAction },
        mode,
        interval,
        timeRange,
        keyword,
        contentType,
        maximumAge
    ).size

    fun isEmpty(includeAction: Boolean): Boolean = overriddenCount(includeAction) == 0
}

@Serializable
private data class StoredMomentAutomationConfig(
    val version: Int = CONFIG_VERSION,
    val global: MomentAutomationRuleSet = MomentAutomationRuleSet(),
    val contacts: Map<String, MomentAutomationOverrides> = emptyMap()
)

internal class MomentsAutomationSettings private constructor(
    @StringRes private val featureNameRes: Int,
    private val fileName: String,
    private val logTag: String,
    private val includeAction: Boolean,
    private val legacyKeys: List<String>,
    private val legacyUseWhitelistKey: String,
    private val legacyWhitelistKey: String,
    private val legacyBlacklistKey: String,
    private val legacyModeKey: String,
    private val legacyDelayKey: String,
    private val legacyActionKey: String? = null
) {
    private enum class RuleKey {
        PROCESS,
        ACTION,
        MODE,
        INTERVAL,
        TIME_RANGE,
        KEYWORD,
        CONTENT_TYPE,
        MAXIMUM_AGE
    }

    private val store by lazy {
        AtomicJsonConfigStore(
            file = KnownPaths.moduleData / fileName,
            serializer = StoredMomentAutomationConfig.serializer(),
            tag = logTag,
            initialValue = ::migrateLegacyConfig
        )
    }

    fun resolve(owner: String): MomentAutomationRuleSet {
        val config = store.get()
        return config.global.apply(config.contacts[owner])
    }

    fun hasAllLoadedTargets(): Boolean {
        val config = store.get()
        if (config.global.process.enabled && config.global.effectiveMode == MomentAutomationMode.ALL_LOADED) {
            return true
        }
        return config.contacts.keys.any { owner ->
            val rules = resolve(owner)
            rules.process.enabled && rules.effectiveMode == MomentAutomationMode.ALL_LOADED
        }
    }

    fun showMainDialog(context: Context, onSettingsChanged: () -> Unit) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(featureNameRes)) },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { showGlobalDialog(context, onSettingsChanged) },
                            content = { Text(stringResource(R.string.moments_automation_global_settings)) },
                            supportingContent = { Text(stringResource(R.string.moments_automation_global_summary)) }
                        )
                        ListItem(
                            modifier = Modifier.clickable { showContactSelector(context, onSettingsChanged) },
                            content = { Text(stringResource(R.string.moments_automation_contact_settings)) },
                            supportingContent = { Text(stringResource(R.string.moments_automation_contact_summary)) }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } }
            )
        }
    }

    private fun showGlobalDialog(context: Context, onSettingsChanged: () -> Unit) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(store.get().global) }
            val localizedContext = LocalContext.current
            val validationError = validate(localizedContext, draft)
            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(stringResource(R.string.moments_automation_global_settings)) },
                text = {
                    RuleSetEditor(
                        context = context,
                        rules = draft,
                        overriddenKeys = null,
                        parentLabel = "",
                        validationError = validationError,
                        onActivate = {},
                        onReset = {},
                        onChange = { _, updated -> draft = updated }
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            store.update { it.copy(version = CONFIG_VERSION, global = draft) }
                            onSettingsChanged()
                            showToast(localizedContext.getString(R.string.moments_automation_global_saved))
                            onDismiss()
                        }
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }

    private fun showContactSelector(context: Context, onSettingsChanged: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            val localizedContext = LocalContext.current
            AutomationContactSettingsSelector(
                title = stringResource(R.string.moments_automation_contact_settings),
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val count = contactOverrides(contact.wxId).overriddenCount(includeAction)
                    if (count == 0) {
                        localizedContext.getString(R.string.moments_automation_follow_global)
                    } else {
                        localizedContext.resources.getQuantityString(
                            R.plurals.moments_automation_overridden_count,
                            count,
                            count,
                        )
                    }
                },
                isConfigured = { contact ->
                    contactOverrides(contact.wxId).overriddenCount(includeAction) > 0
                },
                onDismiss = onDismiss,
                onOpen = { contact ->
                    showOverrideDialog(
                        context = context,
                        title = contact.displayName.ifBlank { contact.wxId },
                        parent = store.get().global,
                        initial = contactOverrides(contact.wxId),
                        onSave = {
                            setContactOverrides(contact.wxId, it)
                            revision++
                            onSettingsChanged()
                        }
                    )
                }
            )
        }
    }

    private fun showOverrideDialog(
        context: Context,
        title: String,
        parent: MomentAutomationRuleSet,
        initial: MomentAutomationOverrides,
        onSave: (MomentAutomationOverrides) -> Unit
    ) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(initial) }
            val effective = parent.apply(draft)
            val localizedContext = LocalContext.current
            val validationError = validate(localizedContext, effective, draft.keys())
            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(title) },
                text = {
                    RuleSetEditor(
                        context = context,
                        rules = effective,
                        overriddenKeys = draft.keys(),
                        parentLabel = localizedContext.getString(R.string.moments_automation_global_settings),
                        validationError = validationError,
                        onActivate = { draft = draft.withRule(it, effective) },
                        onReset = { draft = draft.withoutRule(it) },
                        onChange = { key, updated -> draft = draft.withRule(key, updated) }
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            showToast(localizedContext.getString(R.string.settings_saved))
                            onDismiss()
                        }
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }

    @Composable
    private fun RuleSetEditor(
        context: Context,
        rules: MomentAutomationRuleSet,
        overriddenKeys: Set<RuleKey>?,
        parentLabel: String,
        validationError: String?,
        onActivate: (RuleKey) -> Unit,
        onReset: (RuleKey) -> Unit,
        onChange: (RuleKey, MomentAutomationRuleSet) -> Unit
    ) {
        val isGlobal = overriddenKeys == null
        fun overridden(key: RuleKey): Boolean? = overriddenKeys?.let { key in it }
        fun editable(key: RuleKey): Boolean = overriddenKeys == null || key in overriddenKeys

        AutomationScrollableColumn {
            AutomationRuleHeader(
                title = stringResource(
                    if (includeAction) R.string.moments_automation_default_auto_like
                    else R.string.moments_automation_default_auto_repost
                ),
                summary = when {
                    isGlobal && rules.process.enabled -> stringResource(R.string.moments_automation_process_all)
                    isGlobal -> stringResource(R.string.moments_automation_process_none)
                    rules.process.enabled -> stringResource(R.string.moments_automation_process_contact)
                    else -> stringResource(R.string.moments_automation_skip_contact)
                },
                enabled = rules.process.enabled,
                isOverridden = overridden(RuleKey.PROCESS),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.PROCESS) },
                onReset = { onReset(RuleKey.PROCESS) },
                onEnabledChange = {
                    onChange(RuleKey.PROCESS, rules.copy(process = rules.process.copy(enabled = it)))
                }
            )

            if (includeAction) {
                AutomationRuleHeader(
                    title = stringResource(R.string.moments_automation_action_type),
                    summary = stringResource(
                        if (!rules.action.enabled) R.string.moments_automation_default_like_action
                        else if (rules.action.action == MomentAutomationAction.LIKE) R.string.moments_automation_like
                        else R.string.moments_automation_unlike
                    ),
                    enabled = rules.action.enabled,
                    isOverridden = overridden(RuleKey.ACTION),
                    parentLabel = parentLabel,
                    onActivate = { onActivate(RuleKey.ACTION) },
                    onReset = { onReset(RuleKey.ACTION) },
                    onEnabledChange = {
                        onChange(RuleKey.ACTION, rules.copy(action = rules.action.copy(enabled = it)))
                    }
                )
                if (rules.action.enabled) {
                    Column(Modifier.selectableGroup()) {
                        ChoiceRow(
                            title = stringResource(R.string.moments_automation_like),
                            selected = rules.action.action == MomentAutomationAction.LIKE,
                            enabled = editable(RuleKey.ACTION),
                            onClick = {
                                onChange(
                                    RuleKey.ACTION,
                                    rules.copy(action = rules.action.copy(action = MomentAutomationAction.LIKE))
                                )
                            }
                        )
                        ChoiceRow(
                            title = stringResource(R.string.moments_automation_unlike),
                            selected = rules.action.action == MomentAutomationAction.UNLIKE,
                            enabled = editable(RuleKey.ACTION),
                            onClick = {
                                onChange(
                                    RuleKey.ACTION,
                                    rules.copy(action = rules.action.copy(action = MomentAutomationAction.UNLIKE))
                                )
                            }
                        )
                    }
                }
            }

            AutomationRuleHeader(
                title = stringResource(R.string.moments_automation_processing_mode),
                summary = when {
                    !rules.mode.enabled -> stringResource(R.string.moments_automation_when_seen_only)
                    rules.mode.mode == MomentAutomationMode.WHEN_SEEN -> stringResource(R.string.moments_automation_when_seen)
                    else -> stringResource(R.string.moments_automation_all_cached)
                },
                enabled = rules.mode.enabled,
                isOverridden = overridden(RuleKey.MODE),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.MODE) },
                onReset = { onReset(RuleKey.MODE) },
                onEnabledChange = {
                    onChange(RuleKey.MODE, rules.copy(mode = rules.mode.copy(enabled = it)))
                }
            )
            if (rules.mode.enabled) {
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(
                        title = stringResource(R.string.moments_automation_when_seen),
                        selected = rules.mode.mode == MomentAutomationMode.WHEN_SEEN,
                        enabled = editable(RuleKey.MODE),
                        onClick = {
                            onChange(
                                RuleKey.MODE,
                                rules.copy(mode = rules.mode.copy(mode = MomentAutomationMode.WHEN_SEEN))
                            )
                        }
                    )
                    ChoiceRow(
                        title = stringResource(R.string.moments_automation_all_cached),
                        summary = stringResource(R.string.moments_automation_all_cached_requires_refresh),
                        selected = rules.mode.mode == MomentAutomationMode.ALL_LOADED,
                        enabled = editable(RuleKey.MODE),
                        onClick = {
                            onChange(
                                RuleKey.MODE,
                                rules.copy(mode = rules.mode.copy(mode = MomentAutomationMode.ALL_LOADED))
                            )
                        }
                    )
                }
            }

            AutomationRuleHeader(
                title = stringResource(R.string.moments_automation_interval),
                summary = if (rules.interval.enabled) {
                    stringResource(R.string.moments_automation_interval_value, rules.interval.milliseconds.ifBlank { "0" })
                } else stringResource(R.string.moments_automation_no_extra_wait),
                enabled = rules.interval.enabled,
                isOverridden = overridden(RuleKey.INTERVAL),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.INTERVAL) },
                onReset = { onReset(RuleKey.INTERVAL) },
                onEnabledChange = {
                    onChange(RuleKey.INTERVAL, rules.copy(interval = rules.interval.copy(enabled = it)))
                }
            )
            if (rules.interval.enabled) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    value = rules.interval.milliseconds,
                    enabled = editable(RuleKey.INTERVAL),
                    onValueChange = {
                        onChange(
                            RuleKey.INTERVAL,
                            rules.copy(interval = rules.interval.copy(milliseconds = it.filter(Char::isDigit).take(7)))
                        )
                    },
                    label = { Text(stringResource(R.string.moments_automation_interval_ms)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            AutomationRuleHeader(
                title = stringResource(R.string.moments_automation_time_range),
                summary = if (rules.timeRange.enabled) {
                    "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                } else stringResource(R.string.moments_automation_time_unrestricted),
                enabled = rules.timeRange.enabled,
                isOverridden = overridden(RuleKey.TIME_RANGE),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.TIME_RANGE) },
                onReset = { onReset(RuleKey.TIME_RANGE) },
                onEnabledChange = {
                    onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = rules.timeRange.copy(enabled = it)))
                }
            )
            if (rules.timeRange.enabled) {
                AutomationTimeRangeControls(
                    rule = rules.timeRange,
                    editable = editable(RuleKey.TIME_RANGE),
                    onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) }
                )
            }

            AutomationRuleHeader(
                title = stringResource(R.string.moments_automation_content_keywords),
                summary = automationKeywordSummary(rules.keyword, stringResource(R.string.moments_automation_keyword_unrestricted)),
                enabled = rules.keyword.enabled,
                isOverridden = overridden(RuleKey.KEYWORD),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.KEYWORD) },
                onReset = { onReset(RuleKey.KEYWORD) },
                onEnabledChange = {
                    onChange(RuleKey.KEYWORD, rules.copy(keyword = rules.keyword.copy(enabled = it)))
                }
            )
            if (rules.keyword.enabled) {
                AutomationKeywordControls(
                    rule = rules.keyword,
                    editable = editable(RuleKey.KEYWORD),
                    onChange = { onChange(RuleKey.KEYWORD, rules.copy(keyword = it)) }
                )
            }

            AutomationRuleHeader(
                title = stringResource(R.string.moments_automation_content_type),
                summary = if (rules.contentType.enabled) {
                    pluralStringResource(R.plurals.moments_automation_selected_type_count, rules.contentType.typeIds.size, rules.contentType.typeIds.size)
                } else stringResource(R.string.moments_automation_type_unrestricted),
                enabled = rules.contentType.enabled,
                isOverridden = overridden(RuleKey.CONTENT_TYPE),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.CONTENT_TYPE) },
                onReset = { onReset(RuleKey.CONTENT_TYPE) },
                onEnabledChange = {
                    onChange(
                        RuleKey.CONTENT_TYPE,
                        rules.copy(contentType = rules.contentType.copy(enabled = it))
                    )
                }
            )
            if (rules.contentType.enabled) {
                MomentsContentType.entries.forEach { type ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = editable(RuleKey.CONTENT_TYPE)) {
                            val updated = rules.contentType.typeIds.toMutableSet()
                            if (!updated.add(type.typeId)) updated.remove(type.typeId)
                            onChange(
                                RuleKey.CONTENT_TYPE,
                                rules.copy(contentType = rules.contentType.copy(typeIds = updated))
                            )
                        },
                        leadingContent = {
                            Checkbox(
                                checked = type.typeId in rules.contentType.typeIds,
                                enabled = editable(RuleKey.CONTENT_TYPE),
                                onCheckedChange = null
                            )
                        },
                        content = { Text(stringResource(type.nameRes)) }
                    )
                }
            }

            AutomationRuleHeader(
                title = stringResource(R.string.moments_automation_maximum_age),
                summary = if (rules.maximumAge.enabled) {
                    val maximumHours = rules.maximumAge.maximumHours.toIntOrNull() ?: 0
                    pluralStringResource(
                        R.plurals.moments_automation_maximum_age_value,
                        maximumHours,
                        maximumHours,
                    )
                } else stringResource(R.string.moments_automation_age_unrestricted),
                enabled = rules.maximumAge.enabled,
                isOverridden = overridden(RuleKey.MAXIMUM_AGE),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.MAXIMUM_AGE) },
                onReset = { onReset(RuleKey.MAXIMUM_AGE) },
                onEnabledChange = {
                    onChange(
                        RuleKey.MAXIMUM_AGE,
                        rules.copy(maximumAge = rules.maximumAge.copy(enabled = it))
                    )
                }
            )
            if (rules.maximumAge.enabled) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    value = rules.maximumAge.maximumHours,
                    enabled = editable(RuleKey.MAXIMUM_AGE),
                    onValueChange = {
                        onChange(
                            RuleKey.MAXIMUM_AGE,
                            rules.copy(
                                maximumAge = rules.maximumAge.copy(
                                    maximumHours = it.filter(Char::isDigit).take(6)
                                )
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.moments_automation_maximum_age_hours)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            AutomationSettingsError(validationError)
        }
    }

    @Composable
    private fun ChoiceRow(
        title: String,
        summary: String? = null,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            leadingContent = {
                RadioButton(selected = selected, enabled = enabled, onClick = null)
            },
            content = { Text(title) },
            supportingContent = summary?.let { value -> { Text(value) } }
        )
    }

    private fun MomentAutomationRuleSet.apply(overrides: MomentAutomationOverrides?): MomentAutomationRuleSet {
        if (overrides == null) return this
        return copy(
            process = overrides.process ?: process,
            action = overrides.action ?: action,
            mode = overrides.mode ?: mode,
            interval = overrides.interval ?: interval,
            timeRange = overrides.timeRange ?: timeRange,
            keyword = overrides.keyword ?: keyword,
            contentType = overrides.contentType ?: contentType,
            maximumAge = overrides.maximumAge ?: maximumAge
        )
    }

    private fun MomentAutomationOverrides.keys(): Set<RuleKey> = buildSet {
        if (process != null) add(RuleKey.PROCESS)
        if (includeAction && action != null) add(RuleKey.ACTION)
        if (mode != null) add(RuleKey.MODE)
        if (interval != null) add(RuleKey.INTERVAL)
        if (timeRange != null) add(RuleKey.TIME_RANGE)
        if (keyword != null) add(RuleKey.KEYWORD)
        if (contentType != null) add(RuleKey.CONTENT_TYPE)
        if (maximumAge != null) add(RuleKey.MAXIMUM_AGE)
    }

    private fun MomentAutomationOverrides.withRule(
        key: RuleKey,
        rules: MomentAutomationRuleSet
    ): MomentAutomationOverrides = when (key) {
        RuleKey.PROCESS -> copy(process = rules.process)
        RuleKey.ACTION -> copy(action = rules.action)
        RuleKey.MODE -> copy(mode = rules.mode)
        RuleKey.INTERVAL -> copy(interval = rules.interval)
        RuleKey.TIME_RANGE -> copy(timeRange = rules.timeRange)
        RuleKey.KEYWORD -> copy(keyword = rules.keyword)
        RuleKey.CONTENT_TYPE -> copy(contentType = rules.contentType)
        RuleKey.MAXIMUM_AGE -> copy(maximumAge = rules.maximumAge)
    }

    private fun MomentAutomationOverrides.withoutRule(key: RuleKey): MomentAutomationOverrides = when (key) {
        RuleKey.PROCESS -> copy(process = null)
        RuleKey.ACTION -> copy(action = null)
        RuleKey.MODE -> copy(mode = null)
        RuleKey.INTERVAL -> copy(interval = null)
        RuleKey.TIME_RANGE -> copy(timeRange = null)
        RuleKey.KEYWORD -> copy(keyword = null)
        RuleKey.CONTENT_TYPE -> copy(contentType = null)
        RuleKey.MAXIMUM_AGE -> copy(maximumAge = null)
    }

    private fun validate(context: Context, rules: MomentAutomationRuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys
        if (validates(RuleKey.INTERVAL) && rules.interval.enabled) {
            val value = rules.interval.milliseconds.toLongOrNull()
                ?: return context.getString(R.string.moments_automation_invalid_interval)
            if (value !in 0L..MAX_ACTION_DELAY_MS) {
                return context.getString(R.string.moments_automation_interval_too_large, MAX_ACTION_DELAY_MS)
            }
        }
        if (validates(RuleKey.KEYWORD)) {
            rules.keyword.validationError(context.getString(R.string.moments_automation_content_keywords))?.let { return it }
        }
        if (validates(RuleKey.CONTENT_TYPE) && rules.contentType.enabled && rules.contentType.typeIds.isEmpty()) {
            return context.getString(R.string.moments_automation_select_type)
        }
        if (validates(RuleKey.MAXIMUM_AGE) && rules.maximumAge.enabled) {
            if (rules.maximumAge.maximumHours.toLongOrNull() == null) {
                return context.getString(R.string.moments_automation_invalid_maximum_age)
            }
        }
        return null
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        WeDatabaseApi.getFriends().distinctBy(IWeContact::wxId)
    }.onFailure {
        WeLogger.e(logTag, "failed to load friends", it)
    }.getOrDefault(emptyList())

    private fun contactOverrides(wxId: String): MomentAutomationOverrides =
        store.get().contacts[wxId] ?: MomentAutomationOverrides()

    private fun setContactOverrides(wxId: String, overrides: MomentAutomationOverrides) {
        store.update { config ->
            val contacts = config.contacts.toMutableMap()
            if (overrides.isEmpty(includeAction)) contacts.remove(wxId) else contacts[wxId] = overrides
            config.copy(version = CONFIG_VERSION, contacts = contacts)
        }
    }

    private fun migrateLegacyConfig(): StoredMomentAutomationConfig {
        val hasLegacyPrefs = legacyKeys.any(WePrefs::containsKey)
        if (!hasLegacyPrefs) return StoredMomentAutomationConfig()

        val useWhitelist = WePrefs.getBoolOrDef(legacyUseWhitelistKey, true)
        val selected = if (useWhitelist) {
            WePrefs.getStringSetOrDef(legacyWhitelistKey, emptySet())
        } else {
            WePrefs.getStringSetOrDef(legacyBlacklistKey, emptySet())
        }
        val mode = if (WePrefs.getIntOrDef(legacyModeKey, LEGACY_MODE_WHEN_SEEN) == LEGACY_MODE_ALL_LOADED) {
            MomentAutomationMode.ALL_LOADED
        } else {
            MomentAutomationMode.WHEN_SEEN
        }
        val delay = WePrefs.getLongOrDef(legacyDelayKey, 0L).coerceIn(0L, MAX_ACTION_DELAY_MS)
        val action = if (
            includeAction && legacyActionKey != null &&
            WePrefs.getIntOrDef(legacyActionKey, LEGACY_ACTION_LIKE) == LEGACY_ACTION_UNLIKE
        ) {
            MomentAutomationAction.UNLIKE
        } else {
            MomentAutomationAction.LIKE
        }
        val global = MomentAutomationRuleSet(
            process = AutomationToggleRule(enabled = !useWhitelist),
            action = MomentActionRule(enabled = includeAction, action = action),
            mode = MomentModeRule(enabled = true, mode = mode),
            interval = MomentIntervalRule(enabled = delay > 0L, milliseconds = delay.toString())
        )
        val contacts = selected.associateWith {
            MomentAutomationOverrides(process = AutomationToggleRule(enabled = useWhitelist))
        }
        WeLogger.i(logTag, "migrated legacy settings")
        return StoredMomentAutomationConfig(global = global, contacts = contacts)
    }

    companion object {
        val Like = MomentsAutomationSettings(
            featureNameRes = R.string.feature_auto_like_moments_name,
            fileName = "auto_like_moments_settings.json",
            logTag = "AutoLikeMomentsSettings",
            includeAction = true,
            legacyKeys = listOf(
                "moments_auto_like_mode",
                "moments_auto_like_action",
                "moments_auto_like_action_delay_ms",
                "moments_use_whitelist",
                "moments_whitelist",
                "moments_blacklist"
            ),
            legacyUseWhitelistKey = "moments_use_whitelist",
            legacyWhitelistKey = "moments_whitelist",
            legacyBlacklistKey = "moments_blacklist",
            legacyModeKey = "moments_auto_like_mode",
            legacyDelayKey = "moments_auto_like_action_delay_ms",
            legacyActionKey = "moments_auto_like_action"
        )

        val Repost = MomentsAutomationSettings(
            featureNameRes = R.string.feature_auto_repost_moments_name,
            fileName = "auto_repost_moments_settings.json",
            logTag = "AutoRepostMomentsSettings",
            includeAction = false,
            legacyKeys = listOf(
                "moments_auto_forward_mode",
                "moments_auto_forward_action_delay_ms",
                "moments_auto_forward_use_whitelist",
                "moments_auto_forward_whitelist",
                "moments_auto_forward_blacklist"
            ),
            legacyUseWhitelistKey = "moments_auto_forward_use_whitelist",
            legacyWhitelistKey = "moments_auto_forward_whitelist",
            legacyBlacklistKey = "moments_auto_forward_blacklist",
            legacyModeKey = "moments_auto_forward_mode",
            legacyDelayKey = "moments_auto_forward_action_delay_ms"
        )
    }
}

private const val CONFIG_VERSION = 1
private const val MAX_ACTION_DELAY_MS = 300_000L
private const val LEGACY_MODE_WHEN_SEEN = 0
private const val LEGACY_MODE_ALL_LOADED = 1
private const val LEGACY_ACTION_LIKE = 0
private const val LEGACY_ACTION_UNLIKE = 1
