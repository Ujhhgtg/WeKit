package dev.ujhhgtg.wekit.agent.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.ujhhgtg.wekit.agent.data.entity.MessageEntity
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.SessionEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolCallEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolPermissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY favorite DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: java.time.Instant)

    @Query("UPDATE sessions SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getForSession(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_calls WHERE messageId = :messageId")
    suspend fun getForMessage(messageId: String): List<ToolCallEntity>

    @Query("SELECT * FROM tool_calls WHERE id = :id")
    suspend fun getById(id: String): ToolCallEntity?

    @Upsert
    suspend fun upsert(toolCall: ToolCallEntity)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE enabled = 1")
    suspend fun getEnabled(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Upsert
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ToolPermissionDao {
    @Query("SELECT * FROM tool_permissions")
    fun observeAll(): Flow<List<ToolPermissionEntity>>

    @Query("SELECT * FROM tool_permissions")
    suspend fun getAll(): List<ToolPermissionEntity>

    @Query("SELECT * FROM tool_permissions WHERE providerId = :providerId")
    suspend fun getForProvider(providerId: String): List<ToolPermissionEntity>

    @Query("SELECT mode FROM tool_permissions WHERE providerId = :providerId AND toolName = :toolName")
    suspend fun getMode(providerId: String, toolName: String): dev.ujhhgtg.wekit.agent.tool.ToolMode?

    @Upsert
    suspend fun upsert(permission: ToolPermissionEntity)

    @Upsert
    suspend fun upsertAll(permissions: List<ToolPermissionEntity>)

    /** Seed factory defaults only for tools not already present (never clobber user overrides). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(permissions: List<ToolPermissionEntity>)

    @Query("DELETE FROM tool_permissions WHERE providerId = :providerId")
    suspend fun deleteForProvider(providerId: String)
}
