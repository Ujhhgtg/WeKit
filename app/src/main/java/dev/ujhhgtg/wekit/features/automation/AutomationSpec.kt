package dev.ujhhgtg.wekit.features.automation

import kotlinx.serialization.Serializable

/** A named rule of an automation feature with its storage codec and built-in default. */
class AutomationRuleKey<T : Any>(
    val id: String,
    val codec: AutomationValueCodec<T>,
    val default: T,
)

enum class AutomationScopeType {
    GLOBAL,

    /** [AutomationScope.scopeId] is an [AutomationTalkerCategory] name. */
    CATEGORY,
    CONTACT,

    /** [AutomationScope.scopeId] is the group id, [AutomationScope.memberId] the member wxid. */
    GROUP_MEMBER,

    /** A named, reusable partial rule set that other scopes reference. */
    PRESET,

    /** An entry of an ordered, named list (auto-reply rules, friend triggers, ...). */
    ITEM,
}

@Serializable
data class AutomationScope(
    val type: AutomationScopeType,
    val scopeId: String = "",
    val memberId: String = "",
) {
    init {
        require(
            when (type) {
                AutomationScopeType.GLOBAL -> scopeId.isEmpty() && memberId.isEmpty()
                AutomationScopeType.CATEGORY ->
                    AutomationTalkerCategory.entries.any { it.name == scopeId } && memberId.isEmpty()
                AutomationScopeType.GROUP_MEMBER ->
                    AutomationTalkerCategory.of(scopeId) == AutomationTalkerCategory.GROUP && memberId.isNotEmpty()
                AutomationScopeType.CONTACT, AutomationScopeType.PRESET, AutomationScopeType.ITEM ->
                    scopeId.isNotEmpty() && memberId.isEmpty()
            }
        ) { "Invalid automation scope $type/$scopeId/$memberId" }
    }

    val category: AutomationTalkerCategory?
        get() = if (type == AutomationScopeType.CATEGORY) AutomationTalkerCategory.valueOf(scopeId) else null

    companion object {
        val Global = AutomationScope(AutomationScopeType.GLOBAL)
        fun category(category: AutomationTalkerCategory) = AutomationScope(AutomationScopeType.CATEGORY, category.name)
        fun contact(wxId: String) = AutomationScope(AutomationScopeType.CONTACT, wxId)
        fun member(groupId: String, memberId: String) = AutomationScope(AutomationScopeType.GROUP_MEMBER, groupId, memberId)
        fun preset(id: String) = AutomationScope(AutomationScopeType.PRESET, id)
        fun item(id: String) = AutomationScope(AutomationScopeType.ITEM, id)
    }
}

/** A partial set of rule values: absent keys inherit from the parent scope. */
class AutomationRuleValues(val values: Map<String, Any> = emptyMap()) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: AutomationRuleKey<T>): T? = values[key.id] as T?

    operator fun contains(key: AutomationRuleKey<*>): Boolean = key.id in values

    fun <T : Any> with(key: AutomationRuleKey<T>, value: T) = AutomationRuleValues(values + (key.id to value))
    fun without(key: AutomationRuleKey<*>) = AutomationRuleValues(values - key.id)

    val keyIds: Set<String> get() = values.keys
    val size: Int get() = values.size
    fun isEmpty(): Boolean = values.isEmpty()

    override fun equals(other: Any?): Boolean = other is AutomationRuleValues && other.values == values
    override fun hashCode(): Int = values.hashCode()
}

/** Fully resolved rules: every key has a value, falling back to [AutomationRuleKey.default]. */
class AutomationRules(private val values: Map<String, Any>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: AutomationRuleKey<T>): T = values[key.id] as T? ?: key.default

    fun overlay(overrides: AutomationRuleValues?): AutomationRules =
        if (overrides == null || overrides.isEmpty()) this else AutomationRules(values + overrides.values)

    /** Copies the effective value of [key] into [overrides] (activating an override in the editor). */
    fun <T : Any> copyInto(key: AutomationRuleKey<T>, overrides: AutomationRuleValues) = overrides.with(key, get(key))

    companion object {
        val Defaults = AutomationRules(emptyMap())
    }
}

/**
 * Describes one automation feature's rules. The same description drives resolution, storage and
 * the generic scope editor in [dev.ujhhgtg.wekit.activity.automation.AutomationSettingsActivity].
 */
abstract class AutomationSpec(val id: String) {
    abstract val keys: List<AutomationRuleKey<*>>

    /** Scope kinds the feature resolves and offers in the settings UI. */
    abstract val scopeTypes: Set<AutomationScopeType>

    /** Which categories get a CATEGORY scope (when [scopeTypes] contains it). */
    open val categories: List<AutomationTalkerCategory> = AutomationTalkerCategory.entries

    /** Contacts offered for CONTACT scopes. */
    open val contactKinds: Set<AutomationTalkerCategory> =
        setOf(AutomationTalkerCategory.PRIVATE, AutomationTalkerCategory.GROUP)

    val store by lazy { AutomationStore(this) }

    // ---- settings UI -------------------------------------------------------------------------

    /** Title of the settings Activity. */
    abstract val titleRes: Int

    /** Summary under the GLOBAL row on the home screen. */
    open val globalSummaryRes: Int = dev.ujhhgtg.wekit.R.string.automation_global_settings_summary

    abstract val editors: List<dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor<*>>

    /** Title of the user-managed ITEM list; null when the feature has no such list. */
    open val itemsTitleRes: Int? = null

    /** Fixed, non-deletable ITEM scopes (id to title) listed on the home screen. */
    open val fixedItems: List<Pair<String, Int>> = emptyList()

    /** Default name of a newly created ITEM (with its 1-based index). */
    open val newItemNameRes: Int = dev.ujhhgtg.wekit.R.string.automation_new_rule_name

    fun key(id: String): AutomationRuleKey<*> = keys.first { it.id == id }

    /** Returns a user-facing error for [rules], validating only [keyIds] when given. */
    open fun validate(rules: AutomationRules, keyIds: Set<String>?): String? = null

    protected fun validates(keyIds: Set<String>?, key: AutomationRuleKey<*>) = keyIds == null || key.id in keyIds
}
