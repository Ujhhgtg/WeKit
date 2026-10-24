package dev.ujhhgtg.wekit.features.items.moments

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationFieldReader
import dev.ujhhgtg.wekit.features.automation.AutomationFieldWriter
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSection
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.AutomationValueCodec
import dev.ujhhgtg.wekit.features.automation.localizedAutomationString
import dev.ujhhgtg.wekit.ui.automation.AutomationChipSet
import dev.ujhhgtg.wekit.ui.automation.AutomationMiniChips
import dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor
import dev.ujhhgtg.wekit.ui.automation.choiceInline
import dev.ujhhgtg.wekit.ui.automation.keywordEditor
import dev.ujhhgtg.wekit.ui.automation.numberInline
import dev.ujhhgtg.wekit.ui.automation.timeRangeEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor

private const val MAX_ACTION_DELAY_MS = 300_000L

enum class MomentAutomationAction {
    LIKE,
    UNLIKE,
}

enum class MomentAutomationMode {
    WHEN_SEEN,
    ALL_LOADED,
}

data class MomentActionRule(
    val enabled: Boolean = true,
    val action: MomentAutomationAction = MomentAutomationAction.LIKE,
)

data class MomentModeRule(
    val enabled: Boolean = true,
    val mode: MomentAutomationMode = MomentAutomationMode.WHEN_SEEN,
)

data class MomentIntervalRule(val enabled: Boolean = false, val milliseconds: String = "0") {
    fun value(): Long =
        if (enabled) (milliseconds.toLongOrNull() ?: 0L).coerceIn(0L, MAX_ACTION_DELAY_MS) else 0L
}

data class MomentTypeRule(
    val enabled: Boolean = false,
    val typeIds: Set<Int> = MomentsContentType.allTypeIds,
)

data class MomentAgeRule(val enabled: Boolean = false, val maximumHours: String = "24") {
    fun matches(createTimeSeconds: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return true
        val hours = maximumHours.toLongOrNull()?.takeIf { it >= 0L } ?: return false
        if (createTimeSeconds <= 0) return false
        val ageSeconds = (nowMillis / 1000L - createTimeSeconds.toLong()).coerceAtLeast(0L)
        return ageSeconds <= hours * 60L * 60L
    }
}

/** Resolved moments rules of one owner. */
class MomentAutomationRuleSet(
    private val spec: MomentsAutomation,
    private val rules: AutomationRules,
) {
    val process: AutomationToggleRule
        get() = rules[spec.process]

    val interval: MomentIntervalRule
        get() = rules[spec.interval]

    val effectiveAction: MomentAutomationAction
        get() =
            rules[spec.action].let {
                if (spec.includeAction && it.enabled) it.action else MomentAutomationAction.LIKE
            }

    val effectiveMode: MomentAutomationMode
        get() = rules[spec.mode].let { if (it.enabled) it.mode else MomentAutomationMode.WHEN_SEEN }

    fun matchesMoment(snsInfo: Any): Boolean {
        val keyword = rules[spec.keyword]
        val contentType = rules[spec.contentType]
        val maximumAge = rules[spec.maximumAge]
        if (!process.enabled || !rules[spec.timeRange].matches()) return false
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

/** Per-friend rules of [AutoLikeMoments] ([Like]) and [AutoRepostMoments] ([Repost]). */
class MomentsAutomation
private constructor(id: String, override val titleRes: Int, val includeAction: Boolean) :
    AutomationSpec(id) {
    val process =
        AutomationRuleKey("process", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val action = AutomationRuleKey("action", actionCodec, MomentActionRule())
    val mode = AutomationRuleKey("mode", modeCodec, MomentModeRule())
    val interval = AutomationRuleKey("interval", intervalCodec, MomentIntervalRule())
    val timeRange =
        AutomationRuleKey("timeRange", AutomationCodecs.timeRange, AutomationTimeRangeRule())
    val keyword = AutomationRuleKey("keyword", AutomationCodecs.keyword, AutomationKeywordRule())
    val contentType = AutomationRuleKey("contentType", contentTypeCodec, MomentTypeRule())
    val maximumAge = AutomationRuleKey("maximumAge", ageCodec, MomentAgeRule())

    override val keys =
        listOfNotNull(
            process,
            action.takeIf { includeAction },
            mode,
            interval,
            timeRange,
            keyword,
            contentType,
            maximumAge,
        )

    override val scopeTypes =
        setOf(AutomationScopeType.GLOBAL, AutomationScopeType.CONTACT, AutomationScopeType.PRESET)
    override val contactKinds = setOf(AutomationTalkerCategory.PRIVATE)
    override val globalSummaryRes = R.string.moments_automation_global_summary

    fun resolve(owner: String) = MomentAutomationRuleSet(this, store.resolve(owner))

    fun hasAllLoadedTargets(): Boolean {
        val snapshot = store.snapshot()

        fun allLoaded(rules: MomentAutomationRuleSet) =
            rules.process.enabled && rules.effectiveMode == MomentAutomationMode.ALL_LOADED
        if (allLoaded(MomentAutomationRuleSet(this, snapshot.resolve(null)))) return true
        return snapshot.ofType(AutomationScopeType.CONTACT).any {
            allLoaded(resolve(it.scope.scopeId))
        }
    }

    override val editors =
        listOfNotNull(
            toggleEditor(
                process,
                if (includeAction) {
                    R.string.moments_automation_auto_like
                } else {
                    R.string.moments_automation_auto_repost
                },
                R.string.moments_automation_process_contact,
                R.string.moments_automation_skip_contact,
                globalTitleRes =
                    if (includeAction) {
                        R.string.moments_automation_default_auto_like
                    } else {
                        R.string.moments_automation_default_auto_repost
                    },
            ),
            if (!includeAction) {
                null
            } else {
                AutomationRuleEditor(
                    key = action,
                    titleRes = R.string.moments_automation_action_type,
                    summary = {
                        stringResource(
                            when {
                                !it.enabled -> {
                                    R.string.moments_automation_default_like_action
                                }

                                it.action == MomentAutomationAction.LIKE -> {
                                    R.string.moments_automation_like
                                }

                                else -> {
                                    R.string.moments_automation_unlike
                                }
                            },
                        )
                    },
                    isOn = { it.enabled },
                    setOn = { rule, on -> rule.copy(enabled = on) },
                    inline =
                        choiceInline(
                            listOf(
                                MomentAutomationAction.LIKE to R.string.moments_automation_like,
                                MomentAutomationAction.UNLIKE to R.string.moments_automation_unlike,
                            ),
                            get = { it.action },
                            set = { v, a -> v.copy(action = a) },
                        ),
                )
            },
            AutomationRuleEditor(
                key = mode,
                titleRes = R.string.moments_automation_processing_mode,
                summary = {
                    stringResource(
                        when {
                            !it.enabled -> {
                                R.string.moments_automation_when_seen_only
                            }

                            it.mode == MomentAutomationMode.WHEN_SEEN -> {
                                R.string.moments_automation_when_seen
                            }

                            else -> {
                                R.string.moments_automation_all_cached
                            }
                        },
                    )
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                inline =
                    choiceInline(
                        listOf(
                            MomentAutomationMode.WHEN_SEEN to
                                R.string.moments_automation_when_seen_short,
                            MomentAutomationMode.ALL_LOADED to
                                R.string.moments_automation_all_cached_short,
                        ),
                        get = { it.mode },
                        set = { v, m -> v.copy(mode = m) },
                        descriptions =
                            mapOf(
                                MomentAutomationMode.ALL_LOADED to
                                    R.string.moments_automation_all_cached_requires_refresh,
                            ),
                    ),
            ),
            AutomationRuleEditor(
                key = interval,
                titleRes = R.string.moments_automation_interval,
                summary = {
                    if (it.enabled) {
                        stringResource(
                            R.string.moments_automation_interval_value,
                            it.milliseconds.ifBlank { "0" },
                        )
                    } else {
                        stringResource(R.string.moments_automation_no_extra_wait)
                    }
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                inline =
                    numberInline(
                        R.string.moments_automation_interval_ms,
                        0..10_000,
                        100,
                        R.string.automation_unit_ms,
                        get = { it.milliseconds.toLongOrNull() ?: 0L },
                        set = { v, n -> v.copy(milliseconds = n.toString()) },
                        maxExact = MAX_ACTION_DELAY_MS,
                    ),
            ),
            timeRangeEditor(
                timeRange,
                R.string.moments_automation_time_range,
                R.string.moments_automation_time_unrestricted,
            ),
            keywordEditor(
                keyword,
                R.string.moments_automation_content_keywords,
                R.string.moments_automation_keyword_unrestricted,
            ),
            AutomationRuleEditor(
                key = contentType,
                titleRes = R.string.moments_automation_content_type,
                summary = {
                    if (it.enabled) {
                        pluralStringResource(
                            R.plurals.moments_automation_selected_type_count,
                            it.typeIds.size,
                            it.typeIds.size,
                        )
                    } else {
                        stringResource(R.string.moments_automation_type_unrestricted)
                    }
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                summaryExtra = { rule ->
                    if (rule.enabled) {
                        AutomationMiniChips(
                            MomentsContentType.entries
                                .filter { it.typeId in rule.typeIds }
                                .map { stringResource(it.nameRes) },
                        )
                    }
                },
                sheet = { _, value, onChange ->
                    AutomationChipSet(
                        options =
                            MomentsContentType.entries.map {
                                it.typeId to stringResource(it.nameRes)
                            },
                        selected = value.typeIds,
                        onChange = { onChange(value.copy(typeIds = it)) },
                    )
                },
            ),
            AutomationRuleEditor(
                key = maximumAge,
                titleRes = R.string.moments_automation_maximum_age,
                summary = {
                    if (it.enabled) {
                        stringResource(
                            R.string.moments_automation_maximum_age_value,
                            it.maximumHours.ifBlank { "0" },
                        )
                    } else {
                        stringResource(R.string.moments_automation_age_unrestricted)
                    }
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                inline =
                    numberInline(
                        R.string.moments_automation_maximum_age_hours,
                        1..168,
                        1,
                        R.string.automation_unit_hours,
                        get = { it.maximumHours.toLongOrNull() ?: 0L },
                        set = { v, n -> v.copy(maximumHours = n.toString()) },
                        maxExact = 999_999L,
                    ),
            ),
        )

    override val sections =
        listOf(
            AutomationSection(
                R.string.moments_automation_section_processing,
                listOfNotNull(process, action.takeIf { includeAction }, mode),
            ),
            AutomationSection(R.string.moments_automation_section_pace, listOf(interval)),
            AutomationSection(
                R.string.moments_automation_section_filters,
                listOf(timeRange, keyword, contentType, maximumAge),
            ),
        )

    override fun validate(rules: AutomationRules, keyIds: Set<String>?): String? {
        val interval = rules[interval]
        if (validates(keyIds, this.interval) && interval.enabled) {
            val value =
                interval.milliseconds.toLongOrNull()
                    ?: return localizedAutomationString(
                        R.string.moments_automation_invalid_interval,
                    )
            if (value !in 0L..MAX_ACTION_DELAY_MS) {
                return dev.ujhhgtg.wekit.features.items.payment.localizedPaymentQuantityString(
                    R.plurals.moments_automation_interval_too_large,
                    MAX_ACTION_DELAY_MS.toInt(),
                    MAX_ACTION_DELAY_MS,
                )
            }
        }
        if (validates(keyIds, keyword)) {
            rules[keyword]
                .validationError(
                    localizedAutomationString(R.string.moments_automation_content_keywords),
                )
                ?.let {
                    return it
                }
        }
        if (
            validates(keyIds, contentType) &&
                rules[contentType].let { it.enabled && it.typeIds.isEmpty() }
        ) {
            return localizedAutomationString(R.string.moments_automation_select_type)
        }
        if (
            validates(keyIds, maximumAge) &&
                rules[maximumAge].let { it.enabled && it.maximumHours.toLongOrNull() == null }
        ) {
            return localizedAutomationString(R.string.moments_automation_invalid_maximum_age)
        }
        return null
    }

    companion object {
        private val actionCodec =
            object : AutomationValueCodec<MomentActionRule> {
                override fun write(value: MomentActionRule, out: AutomationFieldWriter) {
                    out.put("enabled", value.enabled)
                    out.put("action", value.action)
                }

                override fun read(input: AutomationFieldReader) =
                    MomentActionRule(input.boolean("enabled"), input.enum("action"))
            }

        private val modeCodec =
            object : AutomationValueCodec<MomentModeRule> {
                override fun write(value: MomentModeRule, out: AutomationFieldWriter) {
                    out.put("enabled", value.enabled)
                    out.put("mode", value.mode)
                }

                override fun read(input: AutomationFieldReader) =
                    MomentModeRule(input.boolean("enabled"), input.enum("mode"))
            }

        private val intervalCodec =
            object : AutomationValueCodec<MomentIntervalRule> {
                override fun write(value: MomentIntervalRule, out: AutomationFieldWriter) {
                    out.put("enabled", value.enabled)
                    out.put("milliseconds", value.milliseconds)
                }

                override fun read(input: AutomationFieldReader) =
                    MomentIntervalRule(input.boolean("enabled"), input.string("milliseconds"))
            }

        private val contentTypeCodec =
            object : AutomationValueCodec<MomentTypeRule> {
                override fun write(value: MomentTypeRule, out: AutomationFieldWriter) {
                    out.put("enabled", value.enabled)
                    out.putList("typeIds", value.typeIds.sorted().map(Int::toString))
                }

                override fun read(input: AutomationFieldReader) =
                    MomentTypeRule(
                        input.boolean("enabled"),
                        input.list("typeIds").map(String::toInt).toSet(),
                    )
            }

        private val ageCodec =
            object : AutomationValueCodec<MomentAgeRule> {
                override fun write(value: MomentAgeRule, out: AutomationFieldWriter) {
                    out.put("enabled", value.enabled)
                    out.put("maximumHours", value.maximumHours)
                }

                override fun read(input: AutomationFieldReader) =
                    MomentAgeRule(input.boolean("enabled"), input.string("maximumHours"))
            }

        val Like =
            MomentsAutomation(
                "MOMENTS_LIKE",
                R.string.feature_auto_like_moments_name,
                includeAction = true,
            )
        val Repost =
            MomentsAutomation(
                "MOMENTS_REPOST",
                R.string.feature_auto_repost_moments_name,
                includeAction = false,
            )
    }
}
