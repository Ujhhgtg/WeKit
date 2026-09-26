package dev.ujhhgtg.wekit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.ujhhgtg.wekit.data.entity.AutomationScopeEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopeFieldEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopeListValueEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopePresetEntity

/** All rows of one automation feature, decoded by [dev.ujhhgtg.wekit.features.automation.AutomationStore]. */
class AutomationFeatureRows(
    val scopes: List<AutomationScopeEntity>,
    val fields: List<AutomationScopeFieldEntity>,
    val listValues: List<AutomationScopeListValueEntity>,
    val presets: List<AutomationScopePresetEntity>,
)

/** One scope's complete replacement content. */
class AutomationScopeRows(
    val scope: AutomationScopeEntity,
    val fields: List<AutomationScopeFieldEntity>,
    val listValues: List<AutomationScopeListValueEntity>,
    val presets: List<AutomationScopePresetEntity>,
)

@Dao
abstract class AutomationDao {
    @Query("SELECT * FROM automation_scopes WHERE feature = :feature ORDER BY scopeType, position, scopeId, memberId")
    abstract suspend fun readScopes(feature: String): List<AutomationScopeEntity>

    @Query("SELECT * FROM automation_scope_fields WHERE feature = :feature")
    abstract suspend fun readFields(feature: String): List<AutomationScopeFieldEntity>

    @Query("SELECT * FROM automation_scope_list_values WHERE feature = :feature ORDER BY position")
    abstract suspend fun readListValues(feature: String): List<AutomationScopeListValueEntity>

    @Query("SELECT * FROM automation_scope_presets WHERE feature = :feature ORDER BY position")
    abstract suspend fun readPresetRefs(feature: String): List<AutomationScopePresetEntity>

    @Upsert
    abstract suspend fun upsertScope(row: AutomationScopeEntity)

    @Insert
    abstract suspend fun insertFields(rows: List<AutomationScopeFieldEntity>)

    @Insert
    abstract suspend fun insertListValues(rows: List<AutomationScopeListValueEntity>)

    @Insert
    abstract suspend fun insertPresetRefs(rows: List<AutomationScopePresetEntity>)

    @Query("DELETE FROM automation_scopes WHERE feature = :feature AND scopeType = :scopeType AND scopeId = :scopeId AND memberId = :memberId")
    abstract suspend fun deleteScope(feature: String, scopeType: String, scopeId: String, memberId: String)

    @Query("DELETE FROM automation_scope_fields WHERE feature = :feature AND scopeType = :scopeType AND scopeId = :scopeId AND memberId = :memberId")
    abstract suspend fun deleteFields(feature: String, scopeType: String, scopeId: String, memberId: String)

    @Query("DELETE FROM automation_scope_list_values WHERE feature = :feature AND scopeType = :scopeType AND scopeId = :scopeId AND memberId = :memberId")
    abstract suspend fun deleteListValues(feature: String, scopeType: String, scopeId: String, memberId: String)

    @Query("DELETE FROM automation_scope_presets WHERE feature = :feature AND scopeType = :scopeType AND scopeId = :scopeId AND memberId = :memberId")
    abstract suspend fun deletePresetRefs(feature: String, scopeType: String, scopeId: String, memberId: String)

    @Query("DELETE FROM automation_scope_presets WHERE feature = :feature AND presetId = :presetId")
    abstract suspend fun deleteReferencesTo(feature: String, presetId: String)

    @Query("UPDATE automation_scopes SET position = :position WHERE feature = :feature AND scopeType = :scopeType AND scopeId = :scopeId AND memberId = ''")
    abstract suspend fun updatePosition(feature: String, scopeType: String, scopeId: String, position: Int)

    @Transaction
    open suspend fun readFeature(feature: String) = AutomationFeatureRows(
        scopes = readScopes(feature),
        fields = readFields(feature),
        listValues = readListValues(feature),
        presets = readPresetRefs(feature),
    )

    /** Replaces one scope, or deletes it when [keepScope] is false. */
    @Transaction
    open suspend fun replaceScope(rows: AutomationScopeRows, keepScope: Boolean) {
        val s = rows.scope
        if (!keepScope) {
            deleteScope(s.feature, s.scopeType, s.scopeId, s.memberId)
            if (s.scopeType == "PRESET") deleteReferencesTo(s.feature, s.scopeId)
            return
        }
        upsertScope(s)
        deleteFields(s.feature, s.scopeType, s.scopeId, s.memberId)
        deleteListValues(s.feature, s.scopeType, s.scopeId, s.memberId)
        deletePresetRefs(s.feature, s.scopeType, s.scopeId, s.memberId)
        insertFields(rows.fields)
        insertListValues(rows.listValues)
        insertPresetRefs(rows.presets)
    }

    /** Applies several scope replacements atomically (batch editor, bulk block). */
    @Transaction
    open suspend fun replaceScopes(rows: List<Pair<AutomationScopeRows, Boolean>>) {
        rows.forEach { (scope, keep) -> replaceScope(scope, keep) }
    }

    @Transaction
    open suspend fun reorder(feature: String, scopeType: String, orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> updatePosition(feature, scopeType, id, index) }
    }
}
