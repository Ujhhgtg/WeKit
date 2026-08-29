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
import dev.ujhhgtg.wekit.agent.data.entity.BridgeToolAuditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY favorite DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions")
    suspend fun getAllOnce(): List<SessionEntity>

    @Query("SELECT linuxEnvironmentId FROM sessions WHERE id = :id")
    fun observeLinuxEnvironmentId(id: String): Flow<String?>

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: java.time.Instant)

    @Query("UPDATE sessions SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query(
        "UPDATE sessions SET promptTokens = :promptTokens, completionTokens = :completionTokens, " +
                "totalTokens = :totalTokens WHERE id = :id"
    )
    suspend fun updateUsage(id: String, promptTokens: Int?, completionTokens: Int?, totalTokens: Int?)

    @Query("UPDATE sessions SET contextWindow = :contextWindow WHERE id = :id")
    suspend fun updateContextWindow(id: String, contextWindow: Int?)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sessions SET modelId = NULL WHERE modelId IN (:modelIds)")
    suspend fun clearModelBindings(modelIds: List<String>)

    @Query("UPDATE sessions SET systemPromptId = NULL WHERE systemPromptId = :promptId")
    suspend fun clearSystemPromptBindings(promptId: String)

    @Query("UPDATE sessions SET linuxEnvironmentId = NULL WHERE linuxEnvironmentId = :environmentId")
    suspend fun clearLinuxEnvironmentBindings(environmentId: String)

    @Query("UPDATE sessions SET lastEffectiveLinuxEnvironmentId = :environmentId WHERE id = :id")
    suspend fun setLastEffectiveLinuxEnvironmentId(id: String, environmentId: String)

    @Query("UPDATE sessions SET linuxEnvironmentId = :bindingId, lastEffectiveLinuxEnvironmentId = :effectiveId WHERE id = :id")
    suspend fun transitionLinuxEnvironment(id: String, bindingId: String?, effectiveId: String)
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

    /**
     * Deletes all messages in [sessionId] whose [createdAt] is >= [fromTimestamp]. Used by
     * [WeAgentRepository.sanitizeSessionHistory] to remove trailing incomplete assistant turns.
     */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND createdAt >= :fromTimestamp")
    suspend fun deleteFromTimestamp(sessionId: String, fromTimestamp: java.time.Instant)

    /**
     * Deletes all messages in [sessionId] whose [createdAt] is strictly after [afterTimestamp].
     * Used by [WeAgentRepository.truncateToMessage] (回到此处).
     */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND createdAt > :afterTimestamp")
    suspend fun deleteAfterTimestamp(sessionId: String, afterTimestamp: java.time.Instant)

    /** Returns all messages in [sessionId] up to and including [upToTimestamp], oldest-first. */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND createdAt <= :upToTimestamp ORDER BY createdAt ASC")
    suspend fun getUpToTimestamp(sessionId: String, upToTimestamp: java.time.Instant): List<MessageEntity>
}

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_calls WHERE messageId = :messageId ORDER BY rowid ASC")
    suspend fun getForMessage(messageId: String): List<ToolCallEntity>

    @Query("SELECT * FROM tool_calls WHERE id = :id")
    suspend fun getById(id: String): ToolCallEntity?

    @Upsert
    suspend fun upsert(toolCall: ToolCallEntity)

    /**
     * Deletes all tool_call rows whose parent message belongs to [sessionId] and has a
     * [createdAt] strictly after [afterTimestamp]. The subquery runs before the outer DELETE, so
     * the messages rows still exist when this query fires. Used by [WeAgentRepository.truncateToMessage].
     */
    @Query("DELETE FROM tool_calls WHERE messageId IN (SELECT id FROM messages WHERE sessionId = :sessionId AND createdAt > :afterTimestamp)")
    suspend fun deleteForMessagesAfter(sessionId: String, afterTimestamp: java.time.Instant)

    /**
     * Deletes all tool_call rows for a given [messageId]. Used by
     * [WeAgentRepository.sanitizeSessionHistory] to clean up pending tool-call children of a
     * deleted assistant message.
     */
    @Query("DELETE FROM tool_calls WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)
}

@Dao
interface BridgeToolAuditDao {
    @Insert
    suspend fun insert(entry: BridgeToolAuditEntity)

    @Query("SELECT * FROM bridge_tool_audits WHERE sessionId = :sessionId ORDER BY executedAt ASC")
    suspend fun getForSession(sessionId: String): List<BridgeToolAuditEntity>
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY name COLLATE NOCASE, id")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE enabled = 1 ORDER BY name COLLATE NOCASE, id")
    suspend fun getEnabled(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Upsert
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: String)
}
