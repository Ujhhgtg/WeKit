package dev.ujhhgtg.wekit.workflow.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface WorkflowDao {

    @Query("SELECT * FROM workflows ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabled(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE enabled = 1 AND triggerType = :type ORDER BY createdAt DESC")
    suspend fun getEnabledByTriggerType(type: String): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getById(id: String): WorkflowEntity?

    @Upsert
    suspend fun upsert(workflow: WorkflowEntity)

    @Query("UPDATE workflows SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Instant = Instant.now())

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM workflows")
    suspend fun count(): Int
}
