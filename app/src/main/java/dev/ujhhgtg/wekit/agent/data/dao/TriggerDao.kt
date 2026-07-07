package dev.ujhhgtg.wekit.agent.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface TriggerDao {
    @Query("SELECT * FROM triggers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TriggerEntity>>

    @Query("SELECT * FROM triggers ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<TriggerEntity>

    @Query("SELECT * FROM triggers WHERE enabled = 1")
    suspend fun getEnabled(): List<TriggerEntity>

    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun getById(id: String): TriggerEntity?

    @Query("SELECT * FROM triggers WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: String): List<TriggerEntity>

    @Upsert
    suspend fun upsert(trigger: TriggerEntity)

    @Query("UPDATE triggers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE triggers SET lastFiredAt = :firedAt WHERE id = :id")
    suspend fun setLastFiredAt(id: String, firedAt: Instant)

    @Query("DELETE FROM triggers WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Cascade helper: SESSION-scoped triggers are removed when their session is deleted. */
    @Query("DELETE FROM triggers WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
