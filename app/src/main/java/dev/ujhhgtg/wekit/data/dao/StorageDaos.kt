package dev.ujhhgtg.wekit.data.dao

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.ujhhgtg.wekit.data.entity.AssetBindingEntity
import dev.ujhhgtg.wekit.data.entity.AssetChunkEntity
import dev.ujhhgtg.wekit.data.entity.AssetEntity
import dev.ujhhgtg.wekit.data.entity.DocumentEntity
import dev.ujhhgtg.wekit.data.entity.ExtensionInstallEntity
import dev.ujhhgtg.wekit.data.entity.ManagedDataEntryEntity
import dev.ujhhgtg.wekit.data.entity.PreferenceEntryEntity
import dev.ujhhgtg.wekit.data.entity.PreferenceSetMemberEntity
import dev.ujhhgtg.wekit.data.entity.ScriptCatalogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preference_entries WHERE namespace = :namespace ORDER BY key")
    fun observeNamespace(namespace: String): Flow<List<PreferenceEntryEntity>>

    @Query("SELECT * FROM preference_entries WHERE namespace = :namespace AND key = :key")
    suspend fun get(namespace: String, key: String): PreferenceEntryEntity?

    @Upsert
    suspend fun upsert(entry: PreferenceEntryEntity)

    @Query("DELETE FROM preference_entries WHERE namespace = :namespace AND key = :key")
    suspend fun delete(namespace: String, key: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceSetMembers(members: List<PreferenceSetMemberEntity>)

    @Query("SELECT member FROM preference_set_members WHERE namespace = :namespace AND key = :key ORDER BY member")
    suspend fun getSetMembers(namespace: String, key: String): List<String>

    @Query("DELETE FROM preference_set_members WHERE namespace = :namespace AND key = :key")
    suspend fun deleteSetMembers(namespace: String, key: String)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE namespace = :namespace ORDER BY key")
    fun observeNamespace(namespace: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE namespace = :namespace AND key = :key")
    suspend fun get(namespace: String, key: String): DocumentEntity?

    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE namespace = :namespace AND key = :key")
    suspend fun delete(namespace: String, key: String)
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE assetId = :assetId")
    suspend fun get(assetId: String): AssetEntity?

    @Query("SELECT * FROM assets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AssetEntity>>

    @Upsert
    suspend fun upsert(asset: AssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<AssetChunkEntity>)

    /**
     * Opens a cursor over chunks for streaming large assets. Callers must close the returned
     * cursor; unlike [getChunks], this does not materialize the complete asset in memory.
     */
    @Query("SELECT ordinal, bytes FROM asset_chunks WHERE assetId = :assetId ORDER BY ordinal")
    fun openChunkCursor(assetId: String): Cursor

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: AssetChunkEntity)

    @Query("SELECT bytes FROM asset_chunks WHERE assetId = :assetId ORDER BY ordinal")
    suspend fun getChunks(assetId: String): List<ByteArray>

    @Query("SELECT COUNT(*) FROM asset_chunks WHERE assetId = :assetId")
    suspend fun countChunks(assetId: String): Int

    @Query("DELETE FROM asset_chunks WHERE assetId = :assetId")
    suspend fun deleteChunks(assetId: String)

    @Query("DELETE FROM assets WHERE assetId = :assetId")
    suspend fun delete(assetId: String)

    @Upsert
    suspend fun bind(binding: AssetBindingEntity)

    @Query("SELECT * FROM asset_bindings WHERE owner = :owner ORDER BY slot")
    suspend fun getBindings(owner: String): List<AssetBindingEntity>

    @Query("SELECT * FROM asset_bindings WHERE owner = :owner AND slot = :slot")
    suspend fun getBinding(owner: String, slot: String): AssetBindingEntity?

    @Query("SELECT COUNT(*) FROM asset_bindings WHERE assetId = :assetId")
    suspend fun countBindings(assetId: String): Int

    @Query("DELETE FROM asset_bindings WHERE owner = :owner AND slot = :slot")
    suspend fun unbind(owner: String, slot: String)
}

@Dao
interface ScriptCatalogDao {
    @Query("SELECT * FROM script_catalog ORDER BY kind, relativePath")
    fun observeAll(): Flow<List<ScriptCatalogEntity>>

    @Query("SELECT * FROM script_catalog WHERE scriptId = :scriptId")
    suspend fun get(scriptId: String): ScriptCatalogEntity?

    @Query("SELECT * FROM script_catalog WHERE kind = :kind ORDER BY relativePath")
    suspend fun getByKind(kind: String): List<ScriptCatalogEntity>

    @Upsert
    suspend fun upsert(script: ScriptCatalogEntity)

    @Query("DELETE FROM script_catalog WHERE scriptId = :scriptId")
    suspend fun delete(scriptId: String)
}

@Dao
interface ExtensionInstallDao {
    @Query("SELECT * FROM extension_installs ORDER BY extensionId, version, abi")
    fun observeAll(): Flow<List<ExtensionInstallEntity>>

    @Query("SELECT * FROM extension_installs WHERE extensionId = :extensionId ORDER BY version DESC, abi")
    suspend fun getForExtension(extensionId: String): List<ExtensionInstallEntity>

    @Upsert
    suspend fun upsert(extension: ExtensionInstallEntity)

    @Query("DELETE FROM extension_installs WHERE extensionId = :extensionId AND version = :version AND abi = :abi")
    suspend fun delete(extensionId: String, version: String, abi: String)
}

@Dao
interface ManagedDataDao {
    @Query("SELECT * FROM managed_data_entries WHERE pluginId = :pluginId ORDER BY relativePath")
    fun observeForPlugin(pluginId: String): Flow<List<ManagedDataEntryEntity>>

    @Upsert
    suspend fun upsert(entry: ManagedDataEntryEntity)

    @Query("DELETE FROM managed_data_entries WHERE pluginId = :pluginId AND relativePath = :relativePath")
    suspend fun delete(pluginId: String, relativePath: String)
}
