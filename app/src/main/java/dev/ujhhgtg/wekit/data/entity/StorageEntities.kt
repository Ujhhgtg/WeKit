package dev.ujhhgtg.wekit.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A typed value migrated from WeKit's legacy preference stores.
 *
 * Values are deliberately represented as a small set of nullable columns instead of a
 * polymorphic blob.  This keeps the database exportable without depending on the implementation
 * of MMKV or Java serialization.  [valueType] identifies which column is populated and is kept
 * as a string so unknown legacy types can be preserved during a migration.
 */
@Entity(
    tableName = "preference_entries",
    primaryKeys = ["namespace", "key"],
    indices = [Index("namespace"), Index("updatedAt")],
)
data class PreferenceEntryEntity(
    val namespace: String,
    val key: String,
    val valueType: String,
    val valueText: String? = null,
    val valueLong: Long? = null,
    val valueDouble: Double? = null,
    val valueBlob: ByteArray? = null,
    val encodingVersion: Int = 1,
    val revision: Long = 0,
    val updatedAt: Long,
    val exportable: Boolean = true,
)

/** A normalized member index for legacy StringSet preferences. */
@Entity(
    tableName = "preference_set_members",
    primaryKeys = ["namespace", "key", "member"],
    indices = [Index("namespace", "key")],
)
data class PreferenceSetMemberEntity(
    val namespace: String,
    val key: String,
    val member: String,
)

/** Versioned JSON/document storage for settings that do not have a stable relational schema yet. */
@Entity(
    tableName = "documents",
    primaryKeys = ["namespace", "key"],
    indices = [Index("namespace"), Index("updatedAt")],
)
data class DocumentEntity(
    val namespace: String,
    val key: String,
    val content: String,
    val formatVersion: Int = 1,
    val updatedAt: Long,
    val exportable: Boolean = true,
)

/** Metadata for a user-owned asset. The bytes live in [AssetChunkEntity]. */
@Entity(
    tableName = "assets",
    indices = [Index(value = ["sha256"]), Index(value = ["createdAt"])],
)
data class AssetEntity(
    @androidx.room.PrimaryKey val assetId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Long,
    val metadataJson: String? = null,
    val exportable: Boolean = true,
)

/** Fixed-size chunks keep large media out of a single Room object. */
@Entity(
    tableName = "asset_chunks",
    primaryKeys = ["assetId", "ordinal"],
    indices = [Index("assetId")],
)
data class AssetChunkEntity(
    val assetId: String,
    val ordinal: Int,
    val bytes: ByteArray,
)

/** A stable feature/slot reference to an asset (for example, a custom avatar). */
@Entity(
    tableName = "asset_bindings",
    primaryKeys = ["owner", "slot"],
    indices = [Index("assetId")],
)
data class AssetBindingEntity(
    val owner: String,
    val slot: String,
    val assetId: String,
)

/**
 * Index for a script directory. Script source and companion files remain files and are never
 * copied into a BLOB column; [relativePath] is relative to the managed WeKit root.
 */
@Entity(
    tableName = "script_catalog",
    indices = [Index("kind"), Index("relativePath"), Index("enabled")],
)
data class ScriptCatalogEntity(
    @androidx.room.PrimaryKey val scriptId: String,
    val kind: String,
    val relativePath: String,
    val entryPoint: String? = null,
    val manifestJson: String? = null,
    val contentHash: String,
    val version: String? = null,
    val enabled: Boolean = true,
    val trusted: Boolean = false,
    val configSummary: String? = null,
    val updatedAt: Long,
)

/** Index for an extension package whose APK/SO/DEX/rootfs bytes stay in the managed files tree. */
@Entity(
    tableName = "extension_installs",
    primaryKeys = ["extensionId", "version", "abi"],
    indices = [Index("extensionId"), Index("selected")],
)
data class ExtensionInstallEntity(
    val extensionId: String,
    val version: String,
    val abi: String,
    val manifestJson: String? = null,
    val contentHash: String,
    val relativePath: String,
    val selected: Boolean = false,
    val mounted: Boolean = false,
    val configJson: String? = null,
    val updatedAt: Long,
)

/** Hash/size index for Python plugin data files; file contents stay in the managed tree. */
@Entity(
    tableName = "managed_data_entries",
    primaryKeys = ["pluginId", "relativePath"],
    indices = [Index("pluginId"), Index("contentHash")],
)
data class ManagedDataEntryEntity(
    val pluginId: String,
    val relativePath: String,
    val sizeBytes: Long,
    val contentHash: String,
    val modifiedAt: Long,
)

/** Host-versioned Dex descriptors kept in the shared Room database. */
@Entity(
    tableName = "dex_cache_entries",
    primaryKeys = ["hostVersion", "technicalId"],
    indices = [Index("hostVersion")],
)
data class DexCacheEntryEntity(
    val hostVersion: String,
    val technicalId: String,
    val methodHash: String,
    val timestamp: Long,
)

@Entity(
    tableName = "dex_cache_descriptors",
    primaryKeys = ["hostVersion", "technicalId", "descriptorKey"],
    indices = [Index("hostVersion", "technicalId")],
)
data class DexCacheDescriptorEntity(
    val hostVersion: String,
    val technicalId: String,
    val descriptorKey: String,
    val descriptorValue: String,
)
