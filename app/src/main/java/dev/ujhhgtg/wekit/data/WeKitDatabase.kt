package dev.ujhhgtg.wekit.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.ujhhgtg.wekit.data.dao.AssetDao
import dev.ujhhgtg.wekit.data.dao.AutomationDao
import dev.ujhhgtg.wekit.data.dao.ConversationCollectionDao
import dev.ujhhgtg.wekit.data.dao.SimpleStructuredDao
import dev.ujhhgtg.wekit.data.entity.ConversationGroupEntity
import dev.ujhhgtg.wekit.data.entity.ConversationGroupMemberEntity
import dev.ujhhgtg.wekit.data.entity.ConversationFolderEntity
import dev.ujhhgtg.wekit.data.entity.ConversationFolderMemberEntity
import dev.ujhhgtg.wekit.data.entity.MomentCustomDetailEntity
import dev.ujhhgtg.wekit.data.entity.FeatureFlagOverrideEntity
import dev.ujhhgtg.wekit.data.entity.ContactRealNamePartEntity
import dev.ujhhgtg.wekit.data.entity.RealNameScanProgressEntity
import dev.ujhhgtg.wekit.data.entity.RedPacketRuleEntity
import dev.ujhhgtg.wekit.data.entity.RedPacketKeywordEntity
import dev.ujhhgtg.wekit.data.entity.TransferRuleEntity
import dev.ujhhgtg.wekit.data.entity.TransferKeywordEntity
import dev.ujhhgtg.wekit.data.entity.MomentAutomationRuleEntity
import dev.ujhhgtg.wekit.data.entity.MomentAutomationKeywordEntity
import dev.ujhhgtg.wekit.data.entity.MomentAutomationContentTypeEntity
import dev.ujhhgtg.wekit.data.entity.collectionSchemaSql
import dev.ujhhgtg.wekit.data.entity.simpleStructuredSchemaSql
import dev.ujhhgtg.wekit.data.structured.automationSchemaSql
import dev.ujhhgtg.wekit.agent.data.dao.BridgeToolAuditDao
import dev.ujhhgtg.wekit.agent.data.dao.ConditionalPromptDao
import dev.ujhhgtg.wekit.data.dao.DocumentDao
import dev.ujhhgtg.wekit.data.dao.ExtensionInstallDao
import dev.ujhhgtg.wekit.agent.data.dao.ExternalServiceDao
import dev.ujhhgtg.wekit.agent.data.dao.LinuxEnvironmentDao
import dev.ujhhgtg.wekit.data.dao.ManagedDataDao
import dev.ujhhgtg.wekit.agent.data.dao.MessageDao
import dev.ujhhgtg.wekit.agent.data.dao.ModelDao
import dev.ujhhgtg.wekit.agent.data.dao.ModelProviderDao
import dev.ujhhgtg.wekit.agent.data.dao.PerTurnPromptDao
import dev.ujhhgtg.wekit.data.dao.PreferenceDao
import dev.ujhhgtg.wekit.agent.data.dao.PresetPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ProviderDao
import dev.ujhhgtg.wekit.data.dao.ScriptCatalogDao
import dev.ujhhgtg.wekit.agent.data.dao.SessionDao
import dev.ujhhgtg.wekit.agent.data.dao.SettingDao
import dev.ujhhgtg.wekit.agent.data.dao.SystemPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ToolCallDao
import dev.ujhhgtg.wekit.agent.data.dao.TriggerDao
import dev.ujhhgtg.wekit.data.entity.AssetBindingEntity
import dev.ujhhgtg.wekit.data.entity.AssetChunkEntity
import dev.ujhhgtg.wekit.data.entity.AssetEntity
import dev.ujhhgtg.wekit.agent.data.entity.BridgeToolAuditEntity
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.data.entity.DexCacheDescriptorEntity
import dev.ujhhgtg.wekit.data.entity.DexCacheEntryEntity
import dev.ujhhgtg.wekit.data.entity.DocumentEntity
import dev.ujhhgtg.wekit.data.entity.ExtensionInstallEntity
import dev.ujhhgtg.wekit.agent.data.entity.ExternalServiceEntity
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.data.entity.ManagedDataEntryEntity
import dev.ujhhgtg.wekit.agent.data.entity.MessageEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.data.entity.PreferenceEntryEntity
import dev.ujhhgtg.wekit.data.entity.PreferenceSetMemberEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.data.entity.ScriptCatalogEntity
import dev.ujhhgtg.wekit.agent.data.entity.SessionEntity
import dev.ujhhgtg.wekit.agent.data.entity.SettingEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolCallEntity
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.agent.data.WeAgentConverters
import dev.ujhhgtg.wekit.activity.settings.BackupCoordinator
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.LegacyPaths
import java.io.File

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        ProviderEntity::class,
        ModelProviderEntity::class,
        ModelEntity::class,
        SystemPromptEntity::class,
        PerTurnPromptEntity::class,
        ConditionalPromptEntity::class,
        PresetPromptEntity::class,
        LinuxEnvironmentEntity::class,
        SettingEntity::class,
        TriggerEntity::class,
        ExternalServiceEntity::class,
        BridgeToolAuditEntity::class,
        PreferenceEntryEntity::class,
        PreferenceSetMemberEntity::class,
        DocumentEntity::class,
        AssetEntity::class,
        AssetChunkEntity::class,
        AssetBindingEntity::class,
        ScriptCatalogEntity::class,
        ExtensionInstallEntity::class,
        ManagedDataEntryEntity::class,
        DexCacheEntryEntity::class,
        DexCacheDescriptorEntity::class,
        ConversationGroupEntity::class,
        ConversationGroupMemberEntity::class,
        ConversationFolderEntity::class,
        ConversationFolderMemberEntity::class,
        MomentCustomDetailEntity::class,
        FeatureFlagOverrideEntity::class,
        ContactRealNamePartEntity::class,
        RealNameScanProgressEntity::class,
        RedPacketRuleEntity::class,
        RedPacketKeywordEntity::class,
        TransferRuleEntity::class,
        TransferKeywordEntity::class,
        MomentAutomationRuleEntity::class,
        MomentAutomationKeywordEntity::class,
        MomentAutomationContentTypeEntity::class,
    ],
    version = 22,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 9, to = 10), // adds external_services table
        AutoMigration(from = 10, to = 11), // adds messages.reasoningSignature, tool_calls.providerSignature
    ],
)
@TypeConverters(WeAgentConverters::class)
abstract class WeKitDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun providerDao(): ProviderDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun perTurnPromptDao(): PerTurnPromptDao
    abstract fun conditionalPromptDao(): ConditionalPromptDao
    abstract fun presetPromptDao(): PresetPromptDao
    abstract fun linuxEnvironmentDao(): LinuxEnvironmentDao
    abstract fun settingDao(): SettingDao
    abstract fun triggerDao(): TriggerDao
    abstract fun externalServiceDao(): ExternalServiceDao
    abstract fun bridgeToolAuditDao(): BridgeToolAuditDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun documentDao(): DocumentDao
    abstract fun assetDao(): AssetDao
    abstract fun scriptCatalogDao(): ScriptCatalogDao
    abstract fun extensionInstallDao(): ExtensionInstallDao
    abstract fun managedDataDao(): ManagedDataDao
    abstract fun conversationCollectionDao(): ConversationCollectionDao
    abstract fun simpleStructuredDao(): SimpleStructuredDao
    abstract fun automationDao(): AutomationDao

    companion object {
        private const val TAG = "WeKitDatabase"

        const val FILE_NAME = "wekit.db"
        const val SCHEMA_VERSION = 22

        /** Location of the unified database after the path migration has completed. */
        val file: File
            get() = KnownPaths.moduleRoot.resolve(FILE_NAME).toFile()

        init {
            BackupCoordinator.beforeDatabaseReplace = { close() }
        }

        @Volatile
        private var INSTANCE: WeKitDatabase? = null

        val instance: WeKitDatabase
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: build().also { INSTANCE = it }
            }

        fun close() = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }

        // 11 → 12: WEKIT_ROUTER enum value removed from ModelProviderType.
        // Any stored provider row with that type is now unreadable; delete them so the
        // converter no longer encounters an unknown enum name on startup.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove models that referenced the now-deleted provider first to avoid
                // dangling providerId foreign keys, then drop the providers themselves.
                db.execSQL(
                    "DELETE FROM models WHERE providerId IN " +
                            "(SELECT id FROM model_providers WHERE type = 'WEKIT_ROUTER')"
                )
                db.execSQL("DELETE FROM model_providers WHERE type = 'WEKIT_ROUTER'")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration12To13Sql.forEach(db::execSQL)
            }
        }

        val migration12To13Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `linux_environments` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `workingDirectory` TEXT NOT NULL, `environmentVariablesJson` TEXT NOT NULL, `rootfsPath` TEXT, `rootfsContentVersion` TEXT, `createdAt` INTEGER, `sshHost` TEXT, `sshPort` INTEGER, `sshUsername` TEXT, `sshAuthenticationType` TEXT, `sshCredentialCiphertext` BLOB, `sshCredentialIv` BLOB, `sshCredentialReference` TEXT, `sshHostKeyAlgorithm` TEXT, `sshHostKeyFingerprint` TEXT, `bridgePath` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE `sessions_new` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `systemPromptId` TEXT, `linuxEnvironmentId` TEXT, `lastEffectiveLinuxEnvironmentId` TEXT, `modelId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `favorite` INTEGER NOT NULL, `promptTokens` INTEGER, `completionTokens` INTEGER, `totalTokens` INTEGER, `contextWindow` INTEGER, PRIMARY KEY(`id`))",
            "INSERT INTO `sessions_new` (`id`, `title`, `systemPromptId`, `linuxEnvironmentId`, `lastEffectiveLinuxEnvironmentId`, `modelId`, `createdAt`, `updatedAt`, `favorite`, `promptTokens`, `completionTokens`, `totalTokens`, `contextWindow`) SELECT `id`, `title`, `systemPromptId`, NULL, NULL, `modelId`, `createdAt`, `updatedAt`, `favorite`, `promptTokens`, `completionTokens`, `totalTokens`, `contextWindow` FROM `sessions`",
            "DROP TABLE `sessions`",
            "ALTER TABLE `sessions_new` RENAME TO `sessions`",
            "DROP TABLE `workspaces`",
            "DELETE FROM `settings` WHERE `key` IN ('memory_enabled', 'default_workspace_id')",
            "DELETE FROM `tool_permissions` WHERE `providerId` = 'builtin-fs' AND `toolName` IN ('read_file', 'list_dir', 'search_files', 'write_file', 'append_file', 'delete_file', 'move_file')",
        )

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration13To14Sql.forEach(db::execSQL)
            }
        }

        val migration13To14Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `bridge_tool_audits` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `environmentId` TEXT NOT NULL, `parentToolCallId` TEXT, `providerId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argumentsJson` TEXT NOT NULL, `approvalStatus` TEXT, `executionOutcome` TEXT NOT NULL, `result` TEXT NOT NULL, `executedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_bridge_tool_audits_sessionId` ON `bridge_tool_audits` (`sessionId`)",
            "CREATE INDEX IF NOT EXISTS `index_bridge_tool_audits_environmentId` ON `bridge_tool_audits` (`environmentId`)",
        )

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration14To15Sql.forEach(db::execSQL)
            }
        }

        // 14 → 15: per-tool permission rows are replaced by a session-level permission level
        // (sessions.permissionLevel). The tool_permissions table is dropped outright — the old
        // per-tool modes have no equivalent under the level model.
        val migration14To15Sql = listOf(
            "ALTER TABLE `sessions` ADD COLUMN `permissionLevel` TEXT",
            "DROP TABLE `tool_permissions`",
        )

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration15To16Sql.forEach(db::execSQL)
            }
        }

        // Remove the retired local provider before Room decodes provider types. Sessions and
        // messages remain; affected model selections fall back to the remaining remote models.
        val migration15To16Sql = listOf(
            "UPDATE sessions SET modelId = NULL, contextWindow = NULL WHERE modelId IN " +
                    "(SELECT id FROM models WHERE providerId = 'local-llama' OR providerId IN " +
                    "(SELECT id FROM model_providers WHERE type = 'LOCAL_LLAMA'))",
            "DELETE FROM settings WHERE `key` = 'local_compute_backend' OR " +
                    "(`key` IN ('default_model_id', 'small_model_id') AND value IN " +
                    "(SELECT id FROM models WHERE providerId = 'local-llama' OR providerId IN " +
                    "(SELECT id FROM model_providers WHERE type = 'LOCAL_LLAMA')))",
            "DELETE FROM models WHERE providerId = 'local-llama' OR providerId IN " +
                    "(SELECT id FROM model_providers WHERE type = 'LOCAL_LLAMA')",
            "DELETE FROM model_providers WHERE id = 'local-llama' OR type = 'LOCAL_LLAMA'",
        )

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration16To17Sql.forEach(db::execSQL)
            }
        }

        // Drop the retired environment type before Room decodes it. Keep conversation/audit
        // history and the old rootfs files; only obsolete configuration and bindings are removed.
        val migration16To17Sql = listOf(
            "UPDATE sessions SET linuxEnvironmentId = NULL WHERE linuxEnvironmentId IN " +
                    "(SELECT id FROM linux_environments WHERE type = 'CHROOT')",
            "UPDATE sessions SET lastEffectiveLinuxEnvironmentId = NULL WHERE lastEffectiveLinuxEnvironmentId IN " +
                    "(SELECT id FROM linux_environments WHERE type = 'CHROOT')",
            "DELETE FROM settings WHERE `key` = 'default_linux_environment_id' AND value IN " +
                    "(SELECT id FROM linux_environments WHERE type = 'CHROOT')",
            "DELETE FROM linux_environments WHERE type = 'CHROOT'",
        )

        /**
         * 17 → 18 adds the unified WeKit storage catalog.  These tables intentionally have no
         * foreign keys: file-backed content may be imported in a later phase and a missing file
         * must be reported instead of making Room silently delete its index row.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration17To18Sql.forEach(db::execSQL)
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `linux_environments` ADD COLUMN `sshPassword` TEXT")
                db.execSQL("ALTER TABLE `linux_environments` ADD COLUMN `sshPrivateKey` TEXT")
                db.execSQL("ALTER TABLE `linux_environments` ADD COLUMN `sshPrivateKeyPassphrase` TEXT")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `dex_cache_entries` (`hostVersion` TEXT NOT NULL, `technicalId` TEXT NOT NULL, `methodHash` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`hostVersion`, `technicalId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dex_cache_entries_hostVersion` ON `dex_cache_entries` (`hostVersion`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `dex_cache_descriptors` (`hostVersion` TEXT NOT NULL, `technicalId` TEXT NOT NULL, `descriptorKey` TEXT NOT NULL, `descriptorValue` TEXT NOT NULL, PRIMARY KEY(`hostVersion`, `technicalId`, `descriptorKey`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dex_cache_descriptors_hostVersion_technicalId` ON `dex_cache_descriptors` (`hostVersion`, `technicalId`)")
            }
        }

        // Content is imported after legacy files arrive, and also on fresh schema creation.
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration20To21Sql.forEach(db::execSQL)
            }
        }

        val migration20To21Sql = collectionSchemaSql +
                simpleStructuredSchemaSql + automationSchemaSql

        // Two schema-21 builds existed: with and without the retired state table. Promote
        // completed imports before removing it, so current rows (including empty collections)
        // remain authoritative instead of being overwritten by their old JSON on startup.
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration21To22Sql.forEach(db::execSQL)
            }
        }

        val migration21To22Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `structured_store_state` (`domainKey` TEXT NOT NULL, `dataVersion` INTEGER NOT NULL, `status` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceHash` TEXT, `exportable` INTEGER NOT NULL, `completedAt` INTEGER, `errorCode` TEXT, `errorSummary` TEXT, PRIMARY KEY(`domainKey`))",
            "INSERT OR IGNORE INTO documents (namespace, `key`, content, formatVersion, updatedAt, exportable) " +
                "SELECT 'migration', '${JsonDataMigration.MARKER_PREFIX}' || domainKey, 'completed', 1, COALESCE(completedAt, 0), 1 " +
                "FROM structured_store_state WHERE dataVersion = 1 AND status = 'READY'",
            // Old filtered backups used RESET for omitted data. Supply ordinary default JSON
            // for the existing importer; do not fall back to stale preferences or add runtime states.
            "INSERT OR REPLACE INTO documents (namespace, `key`, content, formatVersion, updatedAt, exportable) " +
                "SELECT substr(domainKey, 1, instr(domainKey, '/') - 1), substr(domainKey, instr(domainKey, '/') + 1), " +
                "CASE domainKey " +
                "WHEN 'chat/groups' THEN '[{\"id\":\"wekit_group_all\"},{\"id\":\"wekit_group_default_unread\",\"type\":\"PRESET_UNREAD\",\"builtInLabel\":\"UNREAD\"},{\"id\":\"wekit_group_default_groups\",\"type\":\"PRESET_GROUPS\",\"builtInLabel\":\"GROUPS\"},{\"id\":\"wekit_group_default_friends\",\"type\":\"PRESET_FRIENDS\",\"builtInLabel\":\"FRIENDS\"},{\"id\":\"wekit_group_default_officials\",\"type\":\"PRESET_OFFICIALS\",\"builtInLabel\":\"OFFICIALS\"}]' " +
                "WHEN 'chat/folders' THEN '[]' WHEN 'feature_flags/overrides' THEN '[]' ELSE '{}' END, 1, 0, 1 " +
                "FROM structured_store_state WHERE dataVersion = 1 AND status = 'RESET' " +
                "AND NOT EXISTS (SELECT 1 FROM documents WHERE namespace = 'migration' AND `key` = '${JsonDataMigration.MARKER_PREFIX}' || domainKey)",
            "DROP TABLE structured_store_state",
        )

        val migration17To18Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `preference_entries` (`namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `valueType` TEXT NOT NULL, `valueText` TEXT, `valueLong` INTEGER, `valueDouble` REAL, `valueBlob` BLOB, `encodingVersion` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `exportable` INTEGER NOT NULL, PRIMARY KEY(`namespace`, `key`))",
            "CREATE INDEX IF NOT EXISTS `index_preference_entries_namespace` ON `preference_entries` (`namespace`)",
            "CREATE INDEX IF NOT EXISTS `index_preference_entries_updatedAt` ON `preference_entries` (`updatedAt`)",
            "CREATE TABLE IF NOT EXISTS `preference_set_members` (`namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `member` TEXT NOT NULL, PRIMARY KEY(`namespace`, `key`, `member`))",
            "CREATE INDEX IF NOT EXISTS `index_preference_set_members_namespace_key` ON `preference_set_members` (`namespace`, `key`)",
            "CREATE TABLE IF NOT EXISTS `documents` (`namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `content` TEXT NOT NULL, `formatVersion` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `exportable` INTEGER NOT NULL, PRIMARY KEY(`namespace`, `key`))",
            "CREATE INDEX IF NOT EXISTS `index_documents_namespace` ON `documents` (`namespace`)",
            "CREATE INDEX IF NOT EXISTS `index_documents_updatedAt` ON `documents` (`updatedAt`)",
            "CREATE TABLE IF NOT EXISTS `assets` (`assetId` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `metadataJson` TEXT, `exportable` INTEGER NOT NULL, PRIMARY KEY(`assetId`))",
            "CREATE INDEX IF NOT EXISTS `index_assets_sha256` ON `assets` (`sha256`)",
            "CREATE INDEX IF NOT EXISTS `index_assets_createdAt` ON `assets` (`createdAt`)",
            "CREATE TABLE IF NOT EXISTS `asset_chunks` (`assetId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `bytes` BLOB NOT NULL, PRIMARY KEY(`assetId`, `ordinal`))",
            "CREATE INDEX IF NOT EXISTS `index_asset_chunks_assetId` ON `asset_chunks` (`assetId`)",
            "CREATE TABLE IF NOT EXISTS `asset_bindings` (`owner` TEXT NOT NULL, `slot` TEXT NOT NULL, `assetId` TEXT NOT NULL, PRIMARY KEY(`owner`, `slot`))",
            "CREATE INDEX IF NOT EXISTS `index_asset_bindings_assetId` ON `asset_bindings` (`assetId`)",
            "CREATE TABLE IF NOT EXISTS `script_catalog` (`scriptId` TEXT NOT NULL, `kind` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `entryPoint` TEXT, `manifestJson` TEXT, `contentHash` TEXT NOT NULL, `version` TEXT, `enabled` INTEGER NOT NULL, `trusted` INTEGER NOT NULL, `configSummary` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`scriptId`))",
            "CREATE INDEX IF NOT EXISTS `index_script_catalog_kind` ON `script_catalog` (`kind`)",
            "CREATE INDEX IF NOT EXISTS `index_script_catalog_relativePath` ON `script_catalog` (`relativePath`)",
            "CREATE INDEX IF NOT EXISTS `index_script_catalog_enabled` ON `script_catalog` (`enabled`)",
            "CREATE TABLE IF NOT EXISTS `extension_installs` (`extensionId` TEXT NOT NULL, `version` TEXT NOT NULL, `abi` TEXT NOT NULL, `manifestJson` TEXT, `contentHash` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `selected` INTEGER NOT NULL, `mounted` INTEGER NOT NULL, `configJson` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`extensionId`, `version`, `abi`))",
            "CREATE INDEX IF NOT EXISTS `index_extension_installs_extensionId` ON `extension_installs` (`extensionId`)",
            "CREATE INDEX IF NOT EXISTS `index_extension_installs_selected` ON `extension_installs` (`selected`)",
            "CREATE TABLE IF NOT EXISTS `managed_data_entries` (`pluginId` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, `modifiedAt` INTEGER NOT NULL, PRIMARY KEY(`pluginId`, `relativePath`))",
            "CREATE INDEX IF NOT EXISTS `index_managed_data_entries_pluginId` ON `managed_data_entries` (`pluginId`)",
            "CREATE INDEX IF NOT EXISTS `index_managed_data_entries_contentHash` ON `managed_data_entries` (`contentHash`)",
        )

        private fun build(): WeKitDatabase {
            val external = LegacyPaths.externalModuleRoot.resolve("agent/weagent.db").toFile()
            val oldUnified = KnownPaths.moduleRoot.resolve("wekit.db").toFile()
            val oldPrivate = LegacyPaths.privateWeAgentDatabase.toFile()
            val movedPrivate = KnownPaths.moduleRoot.resolve("agent/weagent.db").toFile()
            val source = when {
                oldUnified.isFile -> oldUnified
                else -> listOf(external, oldPrivate, movedPrivate)
                    .filter(File::isFile)
                    .maxByOrNull(File::lastModified)
            }
            val destination = KnownPaths.moduleRoot.resolve("wekit.db").toFile()
            val sourceFile = source ?: external
            val relocator = LegacyDatabaseRelocator(sourceFile, destination) { sourceFile ->
                val sourceDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                    sourceFile.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                )
                sourceDb.use { sourceDb ->
                    // A legacy database may still have committed pages in its WAL. Checkpoint it
                    // before copying the main file so the destination is a complete snapshot. A
                    // busy result means a reader still owns the WAL; abort relocation and keep
                    // using the untouched source instead of silently losing those pages.
                    sourceDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        check(cursor.moveToFirst()) { "wal_checkpoint returned no result" }
                        val busy = cursor.getInt(0)
                        val frames = cursor.getInt(1)
                        val checkpointed = cursor.getInt(2)
                        check(busy == 0 && frames == checkpointed) {
                            "legacy database WAL checkpoint was incomplete (busy=$busy, frames=$frames, checkpointed=$checkpointed)"
                        }
                    }
                }
            }
            // Keep the lock through Room's first open/migration. Without this, a second WeChat
            // process can observe the freshly moved file while the first process is still opening
            // it; if Room then fails and rolls back, the second process would hold a now-unlinked
            // database handle and the next startup could lose the only migrated copy.
            return relocator.withExclusiveLock {
                val prepared = relocator.prepareLocked()
                if (prepared.externalFallback) {
                    val failure = prepared.failure
                    if (failure == null) {
                        WeLogger.e(TAG, "private storage migration failed; external database was not used")
                    } else {
                        WeLogger.e(TAG, "private storage migration failed; external database was not used", failure)
                    }
                    throw IllegalStateException("Unable to migrate the legacy WeAgent database into the unified database", failure)
                }
                val database = buildAt(prepared.file, JournalMode.WRITE_AHEAD_LOGGING)
                return@withExclusiveLock try {
                    database.openHelper.writableDatabase
                    rewriteMovedEnvironmentPaths(database)
                    relocator.commit(prepared)
                    database
                } catch (t: Throwable) {
                    WeLogger.e(TAG, "migrated database failed to open; rolling back", t)
                    runCatching { database.close() }
                    relocator.rollbackLocked(prepared)
                    throw IllegalStateException("Unable to open the unified WeKit database", t)
                }
            }
        }

        private fun rewriteMovedEnvironmentPaths(database: WeKitDatabase) {
            val oldPrefix = LegacyPaths.privateAgentRoot.toFile().absolutePath + "/"
            val newPrefix = KnownPaths.moduleRoot.resolve("agent").toFile().absolutePath + "/"
            val db = database.openHelper.writableDatabase
            db.execSQL(
                "UPDATE linux_environments SET rootfsPath = REPLACE(rootfsPath, ?, ?) WHERE rootfsPath LIKE ?",
                arrayOf(oldPrefix, newPrefix, "$oldPrefix%"),
            )
            db.execSQL(
                "UPDATE linux_environments SET bridgePath = REPLACE(bridgePath, ?, ?) WHERE bridgePath LIKE ?",
                arrayOf(oldPrefix, newPrefix, "$oldPrefix%"),
            )
        }

        private fun buildAt(
            dbFile: File,
            journalMode: JournalMode,
        ): WeKitDatabase = Room.databaseBuilder(
            HostInfo.application,
            WeKitDatabase::class.java,
            dbFile.toString()
        )
            .setJournalMode(journalMode)
            .addMigrations(MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
            // Destructive fallback is scoped to the pre-release schemas (1–8) only, which no
            // migration path was ever written for. From 9 onwards every step must have a
            // migration: a missing one then fails loudly at open time instead of silently
            // wiping every session, prompt, trigger and model provider (API keys
            // included). If you bump `version`, add the matching migration — do NOT widen this
            // list.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7, 8)
            .build()
    }
}
