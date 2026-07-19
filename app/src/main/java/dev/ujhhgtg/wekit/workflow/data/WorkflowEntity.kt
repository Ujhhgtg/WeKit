package dev.ujhhgtg.wekit.workflow.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Persisted representation of a [dev.ujhhgtg.wekit.workflow.model.Workflow].
 *
 * The full Workflow data class (with its nested sealed-interface node tree) is stored as a
 * single JSON column ([workflowJson]) to avoid maintaining a complex multi-table relational
 * schema for an arbitrarily-nested tree structure. Runtime lookups filter on the denormalized
 * [triggerType] and [enabled] columns.
 */
@Entity(
    tableName = "workflows",
    indices = [
        Index("triggerType"),
        Index("enabled"),
    ],
)
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    /**
     * Full JSON-serialized [dev.ujhhgtg.wekit.workflow.model.Workflow]. Includes all nodes,
     * the trigger, and the enabled flag (for atomic export/import round-trips). The top-level
     * [enabled] column is the authoritative switch; [workflowJson]'s embedded `enabled` is
     * ignored on read but kept for correct import behavior.
     */
    val workflowJson: String,
    /**
     * Denormalized trigger discriminator for efficient filtering, e.g. `"NewMessage"`.
     * Null for manually-triggered workflows.
     */
    val triggerType: String?,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
