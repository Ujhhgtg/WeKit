package dev.ujhhgtg.wekit.agent.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ExternalServiceEntity
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.SettingEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelProviderDao {
    @Query("SELECT * FROM model_providers ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ModelProviderEntity>>

    @Query("SELECT * FROM model_providers WHERE id = :id")
    suspend fun getById(id: String): ModelProviderEntity?

    @Upsert
    suspend fun upsert(provider: ModelProviderEntity)

    @Query("DELETE FROM model_providers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY displayName COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY displayName COLLATE NOCASE, id")
    fun observeForProvider(providerId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY displayName COLLATE NOCASE, id")
    suspend fun getForProviderOnce(providerId: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models ORDER BY displayName COLLATE NOCASE, id LIMIT 1")
    suspend fun first(): ModelEntity?

    @Query("SELECT * FROM models ORDER BY displayName COLLATE NOCASE, id")
    suspend fun getAllOnce(): List<ModelEntity>

    @Upsert
    suspend fun upsert(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM models WHERE providerId = :providerId")
    suspend fun deleteForProvider(providerId: String)
}

@Dao
interface SystemPromptDao {
    @Query("SELECT * FROM system_prompts ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<SystemPromptEntity>>

    @Query("SELECT * FROM system_prompts ORDER BY name COLLATE NOCASE, id")
    suspend fun getAllOnce(): List<SystemPromptEntity>

    @Query("SELECT * FROM system_prompts WHERE id = :id")
    suspend fun getById(id: String): SystemPromptEntity?

    @Upsert
    suspend fun upsert(prompt: SystemPromptEntity)

    @Query("DELETE FROM system_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PerTurnPromptDao {
    @Query("SELECT * FROM per_turn_prompts ORDER BY title COLLATE NOCASE, id")
    fun observeAll(): Flow<List<PerTurnPromptEntity>>

    @Query("SELECT * FROM per_turn_prompts WHERE enabled = 1")
    suspend fun getEnabled(): List<PerTurnPromptEntity>

    @Upsert
    suspend fun upsert(prompt: PerTurnPromptEntity)

    @Query("DELETE FROM per_turn_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ConditionalPromptDao {
    @Query("SELECT * FROM conditional_prompts ORDER BY id")
    fun observeAll(): Flow<List<ConditionalPromptEntity>>

    @Query("SELECT * FROM conditional_prompts WHERE enabled = 1")
    suspend fun getEnabled(): List<ConditionalPromptEntity>

    @Upsert
    suspend fun upsert(prompt: ConditionalPromptEntity)

    @Query("DELETE FROM conditional_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PresetPromptDao {
    @Query("SELECT * FROM preset_prompts ORDER BY title COLLATE NOCASE, id")
    fun observeAll(): Flow<List<PresetPromptEntity>>

    @Query("SELECT * FROM preset_prompts ORDER BY title COLLATE NOCASE, id")
    suspend fun getAllOnce(): List<PresetPromptEntity>

    @Upsert
    suspend fun upsert(preset: PresetPromptEntity)

    @Query("DELETE FROM preset_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface LinuxEnvironmentDao {
    @Query("SELECT * FROM linux_environments ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<LinuxEnvironmentEntity>>

    @Query("SELECT * FROM linux_environments ORDER BY name COLLATE NOCASE, id")
    suspend fun getAllOnce(): List<LinuxEnvironmentEntity>

    @Query("SELECT * FROM linux_environments WHERE id = :id")
    suspend fun getById(id: String): LinuxEnvironmentEntity?

    @Query("SELECT * FROM linux_environments WHERE id = :id")
    fun observeById(id: String): Flow<LinuxEnvironmentEntity?>

    @Upsert
    suspend fun upsert(environment: LinuxEnvironmentEntity)

    @Query("DELETE FROM linux_environments WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM settings WHERE key = :key")
    fun observeValue(key: String): Flow<String?>

    @Upsert
    suspend fun upsert(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}

@Dao
interface ExternalServiceDao {
    @Query("SELECT * FROM external_services")
    fun observeAll(): Flow<List<ExternalServiceEntity>>

    @Query("SELECT apiKey FROM external_services WHERE serviceId = :serviceId")
    suspend fun getApiKey(serviceId: String): String?

    @Upsert
    suspend fun upsert(service: ExternalServiceEntity)

    @Query("DELETE FROM external_services WHERE serviceId = :serviceId")
    suspend fun deleteById(serviceId: String)
}
