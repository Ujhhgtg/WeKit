package dev.ujhhgtg.wekit.workflow.data

import dev.ujhhgtg.wekit.agent.data.WeAgentDatabase
import dev.ujhhgtg.wekit.workflow.model.Workflow
import dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Single source of truth for [Workflow] persistence.
 *
 * Serialization/deserialization between [Workflow] (domain model) and [WorkflowEntity]
 * (Room row) is centralized here; callers work exclusively with [Workflow] objects.
 */
object WorkflowRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val dao: WorkflowDao
        get() = WeAgentDatabase.instance.workflowDao()

    // ── Observation ──────────────────────────────────────────────────────────

    fun observeAll(): Flow<List<Workflow>> =
        dao.observeAll().map { entities -> entities.mapNotNull { it.toWorkflow() } }

    // ── Reads ─────────────────────────────────────────────────────────────────

    suspend fun getAll(): List<Workflow> = dao.getAllOnce().mapNotNull { it.toWorkflow() }

    suspend fun getEnabled(): List<Workflow> = dao.getEnabled().mapNotNull { it.toWorkflow() }

    suspend fun getEnabledForTrigger(triggerType: String): List<Workflow> =
        dao.getEnabledByTriggerType(triggerType).mapNotNull { it.toWorkflow() }

    suspend fun getById(id: String): Workflow? = dao.getById(id)?.toWorkflow()

    // ── Writes ────────────────────────────────────────────────────────────────

    suspend fun upsert(workflow: Workflow) {
        val now = Instant.now()
        val existing = dao.getById(workflow.id)
        dao.upsert(workflow.toEntity(
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        ))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) =
        dao.setEnabled(id, enabled)

    suspend fun delete(id: String) = dao.deleteById(id)

    // ── Serialization helpers ─────────────────────────────────────────────────

    private fun Workflow.toEntity(createdAt: Instant, updatedAt: Instant): WorkflowEntity {
        val triggerType = when (trigger) {
            is WorkflowTrigger.NewMessage   -> "NewMessage"
            is WorkflowTrigger.NewMoment    -> "NewMoment"
            is WorkflowTrigger.NewTransfer  -> "NewTransfer"
            is WorkflowTrigger.NewRedPacket -> "NewRedPacket"
            is WorkflowTrigger.Schedule     -> "Schedule"
            null                            -> null
        }
        return WorkflowEntity(
            id = id,
            name = name,
            description = description,
            workflowJson = json.encodeToString(this),
            triggerType = triggerType,
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun WorkflowEntity.toWorkflow(): Workflow? =
        runCatching { json.decodeFromString<Workflow>(workflowJson) }.getOrNull()
            ?.copy(enabled = enabled) // authoritative enabled flag from the column
}
