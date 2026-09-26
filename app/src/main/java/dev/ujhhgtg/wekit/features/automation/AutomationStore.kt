package dev.ujhhgtg.wekit.features.automation

import android.os.SystemClock
import dev.ujhhgtg.wekit.data.WeKitDatabase
import dev.ujhhgtg.wekit.data.dao.AutomationFeatureRows
import dev.ujhhgtg.wekit.data.dao.AutomationScopeRows
import dev.ujhhgtg.wekit.data.entity.AutomationScopeEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopeFieldEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopeListValueEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopePresetEntity
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/** One stored scope: its own overrides plus ordered preset references. */
data class AutomationStoredScope(
    val scope: AutomationScope,
    val values: AutomationRuleValues = AutomationRuleValues(),
    val presets: List<String> = emptyList(),
    val name: String? = null,
    val position: Int = 0,
) {
    /** A scope with no content is deleted on save (except named PRESET/ITEM scopes). */
    val isBlank: Boolean
        get() = values.isEmpty() && presets.isEmpty() &&
                scope.type != AutomationScopeType.PRESET && scope.type != AutomationScopeType.ITEM &&
                scope.type != AutomationScopeType.GLOBAL
}

class AutomationSnapshot(private val spec: AutomationSpec, val scopes: Map<AutomationScope, AutomationStoredScope>) {
    operator fun get(scope: AutomationScope): AutomationStoredScope = scopes[scope] ?: AutomationStoredScope(scope)

    fun ofType(type: AutomationScopeType): List<AutomationStoredScope> =
        scopes.values.filter { it.scope.type == type }.sortedWith(compareBy({ it.position }, { it.scope.scopeId }))

    fun membersOf(groupId: String): List<AutomationStoredScope> =
        scopes.values.filter { it.scope.type == AutomationScopeType.GROUP_MEMBER && it.scope.scopeId == groupId }

    /** Scopes whose rules apply before [scope]'s own presets and values, most general first. */
    fun ancestors(scope: AutomationScope): List<AutomationScope> = when (scope.type) {
        AutomationScopeType.GLOBAL, AutomationScopeType.ITEM -> emptyList()
        AutomationScopeType.CATEGORY, AutomationScopeType.PRESET -> listOf(AutomationScope.Global)
        AutomationScopeType.CONTACT -> buildList {
            add(AutomationScope.Global)
            if (AutomationScopeType.CATEGORY in spec.scopeTypes) {
                add(AutomationScope.category(AutomationTalkerCategory.of(scope.scopeId)))
            }
        }

        AutomationScopeType.GROUP_MEMBER -> ancestors(AutomationScope.contact(scope.scopeId)) +
                AutomationScope.contact(scope.scopeId)
    }

    /** Rules inherited by [scope], i.e. everything except its own presets and values. */
    fun parentRules(scope: AutomationScope): AutomationRules =
        ancestors(scope).fold(AutomationRules.Defaults) { rules, s -> applyScope(rules, s) }

    /** Rules effective at [scope] itself (its presets and values applied). */
    fun effectiveRules(scope: AutomationScope): AutomationRules = applyScope(parentRules(scope), scope)

    private fun applyScope(rules: AutomationRules, scope: AutomationScope, presetsOverride: List<String>? = null): AutomationRules {
        val stored = scopes[scope] ?: return rules
        val withPresets = (presetsOverride ?: stored.presets).fold(rules) { acc, presetId ->
            acc.overlay(scopes[AutomationScope.preset(presetId)]?.values)
        }
        return withPresets.overlay(stored.values)
    }

    private fun chainFor(talker: String?, sender: String?): List<AutomationScope> = buildList {
        add(AutomationScope.Global)
        if (talker.isNullOrEmpty()) return@buildList
        val category = AutomationTalkerCategory.of(talker)
        if (AutomationScopeType.CATEGORY in spec.scopeTypes) add(AutomationScope.category(category))
        if (AutomationScopeType.CONTACT in spec.scopeTypes) add(AutomationScope.contact(talker))
        if (AutomationScopeType.GROUP_MEMBER in spec.scopeTypes &&
            category == AutomationTalkerCategory.GROUP && !sender.isNullOrEmpty()
        ) add(AutomationScope.member(talker, sender))
    }

    fun resolve(talker: String?, sender: String? = null): AutomationRules =
        chainFor(talker, sender).fold(AutomationRules.Defaults) { rules, scope -> applyScope(rules, scope) }

    /**
     * One resolution per preset referenced by the most specific scope that references several
     * presets, so callers can evaluate "any preset matches" instead of "presets merged in order".
     */
    fun resolveVariants(talker: String?, sender: String? = null): List<AutomationRules> {
        val chain = chainFor(talker, sender)
        val branchIndex = chain.indexOfLast { (scopes[it]?.presets?.size ?: 0) > 1 }
        if (branchIndex < 0) return listOf(resolve(talker, sender))
        val branchScope = chain[branchIndex]
        return scopes[branchScope]!!.presets.map { presetId ->
            chain.foldIndexed(AutomationRules.Defaults) { index, rules, scope ->
                applyScope(rules, scope, presetsOverride = if (index == branchIndex) listOf(presetId) else null)
            }
        }
    }
}

class AutomationStore(private val spec: AutomationSpec) {
    private companion object {
        const val FOREIGN_PROCESS_TTL_MS = 3000L
    }

    @Volatile
    private var cached: AutomationSnapshot? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var loadedAt = 0L

    /**
     * Loads (once per change) the stored configuration; safe to call on any thread. Edits made
     * in another process (settings run in the main process, notifications also in :push) can't
     * invalidate this cache, so outside the main process it expires after [FOREIGN_PROCESS_TTL_MS].
     */
    fun snapshot(): AutomationSnapshot {
        cached?.let {
            if (TargetProcesses.isInMain || SystemClock.elapsedRealtime() - loadedAt < FOREIGN_PROCESS_TTL_MS) return it
        }
        return runBlocking(Dispatchers.IO) { load() }
    }

    fun resolve(talker: String?, sender: String? = null): AutomationRules = snapshot().resolve(talker, sender)

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    suspend fun save(scope: AutomationStoredScope) = saveAll(listOf(scope))

    suspend fun saveAll(scopes: List<AutomationStoredScope>) = mutate {
        WeKitDatabase.instance.automationDao().replaceScopes(scopes.map { encode(it) to !it.isBlank })
    }

    suspend fun delete(scope: AutomationScope) = mutate {
        WeKitDatabase.instance.automationDao().replaceScopes(listOf(encode(AutomationStoredScope(scope)) to false))
    }

    suspend fun reorder(type: AutomationScopeType, orderedIds: List<String>) = mutate {
        WeKitDatabase.instance.automationDao().reorder(spec.id, type.name, orderedIds)
    }

    private suspend fun mutate(block: suspend () -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                block()
            } finally {
                // Also clear on cancellation: the SQL transaction may already have committed.
                cached = null
            }
        }
        listeners.forEach { it() }
    }

    private suspend fun load(): AutomationSnapshot {
        val rows = WeKitDatabase.instance.automationDao().readFeature(spec.id)
        return decodeAutomationRows(spec, rows).also {
            cached = it
            loadedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun encode(stored: AutomationStoredScope): AutomationScopeRows {
        val s = stored.scope
        val fields = ArrayList<AutomationScopeFieldEntity>()
        val lists = ArrayList<AutomationScopeListValueEntity>()
        stored.values.values.forEach { (ruleKey, value) ->
            @Suppress("UNCHECKED_CAST")
            val key = spec.key(ruleKey) as AutomationRuleKey<Any>
            val writer = key.codec.encode(value)
            writer.scalars.forEach { (field, v) ->
                fields += AutomationScopeFieldEntity(spec.id, s.type.name, s.scopeId, s.memberId, ruleKey, field, v)
            }
            writer.lists.forEach { (field, list) ->
                list.forEachIndexed { index, v ->
                    lists += AutomationScopeListValueEntity(spec.id, s.type.name, s.scopeId, s.memberId, ruleKey, field, index, v)
                }
            }
        }
        return AutomationScopeRows(
            scope = AutomationScopeEntity(spec.id, s.type.name, s.scopeId, s.memberId, stored.name, stored.position),
            fields = fields,
            listValues = lists,
            presets = stored.presets.mapIndexed { index, id ->
                AutomationScopePresetEntity(spec.id, s.type.name, s.scopeId, s.memberId, index, id)
            },
        )
    }
}

private const val TAG = "AutomationStore"

/** Rebuilds a feature's configuration from its stored rows; unknown rule keys are skipped. */
fun decodeAutomationRows(spec: AutomationSpec, rows: AutomationFeatureRows): AutomationSnapshot {
    fun AutomationScopeEntity.toScope() = AutomationScope(AutomationScopeType.valueOf(scopeType), scopeId, memberId)
    fun keyOf(type: String, id: String, member: String) =
        AutomationScope(AutomationScopeType.valueOf(type), id, member)

    val fields = rows.fields.groupBy { keyOf(it.scopeType, it.scopeId, it.memberId) }
    val lists = rows.listValues.groupBy { keyOf(it.scopeType, it.scopeId, it.memberId) }
    val presets = rows.presets.groupBy { keyOf(it.scopeType, it.scopeId, it.memberId) }
    val result = LinkedHashMap<AutomationScope, AutomationStoredScope>()
    rows.scopes.forEach { row ->
        val scope = row.toScope()
        val scopeFields = fields[scope].orEmpty().groupBy { it.ruleKey }
        val scopeLists = lists[scope].orEmpty().groupBy { it.ruleKey }
        val values = LinkedHashMap<String, Any>()
        scopeFields.forEach { (ruleKey, ruleFields) ->
            val key = spec.keys.firstOrNull { it.id == ruleKey }
            if (key == null) {
                WeLogger.w(TAG, "${spec.id}: ignoring unknown stored rule '$ruleKey'")
                return@forEach
            }
            val reader = AutomationFieldReader(
                ruleFields.associate { it.field to it.value },
                scopeLists[ruleKey].orEmpty().groupBy { it.field }
                    .mapValues { (_, v) -> v.sortedBy { it.position }.map { it.value } },
            )
            values[ruleKey] = key.codec.read(reader)
        }
        result[scope] = AutomationStoredScope(
            scope = scope,
            values = AutomationRuleValues(values),
            presets = presets[scope].orEmpty().sortedBy { it.position }.map { it.presetId },
            name = row.name,
            position = row.position,
        )
    }
    return AutomationSnapshot(spec, result)
}
