package dev.ujhhgtg.wekit.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/*
 * Generic storage for every automation feature (see dev.ujhhgtg.wekit.features.automation).
 * A scope row owns its rule fields; a rule is present iff it has at least one scalar field, and an
 * absent rule inherits from the parent scope.
 */

@Entity(
    tableName = "automation_scopes",
    primaryKeys = ["feature", "scopeType", "scopeId", "memberId"],
)
data class AutomationScopeEntity(
    val feature: String,
    val scopeType: String,
    val scopeId: String,
    val memberId: String,
    /** Display name of PRESET / ITEM scopes. */
    val name: String?,
    /** Order of ITEM scopes (and presets in lists). */
    val position: Int,
)

@Entity(
    tableName = "automation_scope_fields",
    primaryKeys = ["feature", "scopeType", "scopeId", "memberId", "ruleKey", "field"],
    foreignKeys = [ForeignKey(
        entity = AutomationScopeEntity::class,
        parentColumns = ["feature", "scopeType", "scopeId", "memberId"],
        childColumns = ["feature", "scopeType", "scopeId", "memberId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AutomationScopeFieldEntity(
    val feature: String,
    val scopeType: String,
    val scopeId: String,
    val memberId: String,
    val ruleKey: String,
    val field: String,
    val value: String,
)

@Entity(
    tableName = "automation_scope_list_values",
    primaryKeys = ["feature", "scopeType", "scopeId", "memberId", "ruleKey", "field", "position"],
    foreignKeys = [ForeignKey(
        entity = AutomationScopeEntity::class,
        parentColumns = ["feature", "scopeType", "scopeId", "memberId"],
        childColumns = ["feature", "scopeType", "scopeId", "memberId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AutomationScopeListValueEntity(
    val feature: String,
    val scopeType: String,
    val scopeId: String,
    val memberId: String,
    val ruleKey: String,
    val field: String,
    val position: Int,
    val value: String,
)

/** Ordered PRESET references of a scope; applied before the scope's own values. */
@Entity(
    tableName = "automation_scope_presets",
    primaryKeys = ["feature", "scopeType", "scopeId", "memberId", "position"],
    foreignKeys = [ForeignKey(
        entity = AutomationScopeEntity::class,
        parentColumns = ["feature", "scopeType", "scopeId", "memberId"],
        childColumns = ["feature", "scopeType", "scopeId", "memberId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AutomationScopePresetEntity(
    val feature: String,
    val scopeType: String,
    val scopeId: String,
    val memberId: String,
    val position: Int,
    val presetId: String,
)
