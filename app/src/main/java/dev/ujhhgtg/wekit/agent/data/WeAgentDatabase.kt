package dev.ujhhgtg.wekit.agent.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import dev.ujhhgtg.wekit.agent.data.dao.ConditionalPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ExternalServiceDao
import dev.ujhhgtg.wekit.agent.data.dao.MessageDao
import dev.ujhhgtg.wekit.agent.data.dao.LinuxEnvironmentDao
import dev.ujhhgtg.wekit.agent.data.dao.ModelDao
import dev.ujhhgtg.wekit.agent.data.dao.ModelProviderDao
import dev.ujhhgtg.wekit.agent.data.dao.PerTurnPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.PresetPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ProviderDao
import dev.ujhhgtg.wekit.agent.data.dao.SessionDao
import dev.ujhhgtg.wekit.agent.data.dao.SettingDao
import dev.ujhhgtg.wekit.agent.data.dao.SystemPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ToolCallDao
import dev.ujhhgtg.wekit.agent.data.dao.TriggerDao
import dev.ujhhgtg.wekit.agent.data.dao.BridgeToolAuditDao
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ExternalServiceEntity
import dev.ujhhgtg.wekit.agent.data.entity.MessageEntity
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.SessionEntity
import dev.ujhhgtg.wekit.agent.data.entity.SettingEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolCallEntity
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.agent.data.entity.BridgeToolAuditEntity
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths

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
    ],
    version = 15,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 9, to = 10), // adds external_services table
        AutoMigration(from = 10, to = 11), // adds messages.reasoningSignature, tool_calls.providerSignature
    ],
)
@TypeConverters(WeAgentConverters::class)
abstract class WeAgentDatabase : RoomDatabase() {
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

    companion object {
        private const val TAG = "WeAgentDatabase"

        @Volatile
        private var INSTANCE: WeAgentDatabase? = null

        val instance: WeAgentDatabase
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: build().also { INSTANCE = it }
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

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration12To13Sql.forEach(db::execSQL)
            }
        }

        internal val migration12To13Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `linux_environments` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `workingDirectory` TEXT NOT NULL, `environmentVariablesJson` TEXT NOT NULL, `rootfsPath` TEXT, `rootfsContentVersion` TEXT, `createdAt` INTEGER, `sshHost` TEXT, `sshPort` INTEGER, `sshUsername` TEXT, `sshAuthenticationType` TEXT, `sshCredentialCiphertext` BLOB, `sshCredentialIv` BLOB, `sshCredentialReference` TEXT, `sshHostKeyAlgorithm` TEXT, `sshHostKeyFingerprint` TEXT, `bridgePath` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE `sessions_new` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `systemPromptId` TEXT, `linuxEnvironmentId` TEXT, `lastEffectiveLinuxEnvironmentId` TEXT, `modelId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `favorite` INTEGER NOT NULL, `promptTokens` INTEGER, `completionTokens` INTEGER, `totalTokens` INTEGER, `contextWindow` INTEGER, PRIMARY KEY(`id`))",
            "INSERT INTO `sessions_new` (`id`, `title`, `systemPromptId`, `linuxEnvironmentId`, `lastEffectiveLinuxEnvironmentId`, `modelId`, `createdAt`, `updatedAt`, `favorite`, `promptTokens`, `completionTokens`, `totalTokens`, `contextWindow`) SELECT `id`, `title`, `systemPromptId`, NULL, NULL, `modelId`, `createdAt`, `updatedAt`, `favorite`, `promptTokens`, `completionTokens`, `totalTokens`, `contextWindow` FROM `sessions`",
            "DROP TABLE `sessions`",
            "ALTER TABLE `sessions_new` RENAME TO `sessions`",
            "DROP TABLE `workspaces`",
            "DELETE FROM `settings` WHERE `key` IN ('memory_enabled', 'default_workspace_id')",
            "DELETE FROM `tool_permissions` WHERE `providerId` = 'builtin-fs' AND `toolName` IN ('read_file', 'list_dir', 'search_files', 'write_file', 'append_file', 'delete_file', 'move_file')",
        )

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration13To14Sql.forEach(db::execSQL)
            }
        }

        internal val migration13To14Sql = listOf(
            "CREATE TABLE IF NOT EXISTS `bridge_tool_audits` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `environmentId` TEXT NOT NULL, `parentToolCallId` TEXT, `providerId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argumentsJson` TEXT NOT NULL, `approvalStatus` TEXT, `executionOutcome` TEXT NOT NULL, `result` TEXT NOT NULL, `executedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_bridge_tool_audits_sessionId` ON `bridge_tool_audits` (`sessionId`)",
            "CREATE INDEX IF NOT EXISTS `index_bridge_tool_audits_environmentId` ON `bridge_tool_audits` (`environmentId`)",
        )

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration14To15Sql.forEach(db::execSQL)
            }
        }

        // 14 → 15: per-tool permission rows are replaced by a session-level permission level
        // (sessions.permissionLevel). The tool_permissions table is dropped outright — the old
        // per-tool modes have no equivalent under the level model.
        internal val migration14To15Sql = listOf(
            "ALTER TABLE `sessions` ADD COLUMN `permissionLevel` TEXT",
            "DROP TABLE `tool_permissions`",
        )

        private fun build(): WeAgentDatabase {
            val external = KnownPaths.moduleData.resolve("agent/weagent.db").toFile()
            val private = File(HostInfo.application.filesDir, "wekit-agent/weagent.db")
            val relocator = WeAgentDatabaseRelocator(external, private) { source ->
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    source.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                ).close()
            }
            val prepared = relocator.prepare()
            if (prepared.externalFallback) {
                val failure = prepared.failure
                if (failure == null) WeLogger.w(TAG, "private storage migration failed; staying on external storage")
                else WeLogger.w(TAG, "private storage migration failed; staying on external storage", failure)
                return buildAt(prepared.file, JournalMode.TRUNCATE)
            }
            if (!prepared.migratedNow) return buildAt(prepared.file, JournalMode.WRITE_AHEAD_LOGGING)
            val database = buildAt(prepared.file, JournalMode.WRITE_AHEAD_LOGGING)
            return try {
                database.openHelper.writableDatabase
                relocator.commit(prepared)
                database
            } catch (t: Throwable) {
                WeLogger.e(TAG, "migrated database failed to open; rolling back to external storage", t)
                runCatching { database.close() }
                relocator.rollback(prepared)
                buildAt(external, JournalMode.TRUNCATE)
            }
        }

        private fun buildAt(
            dbFile: File,
            journalMode: JournalMode,
        ): WeAgentDatabase = Room.databaseBuilder(
            HostInfo.application,
            WeAgentDatabase::class.java,
            dbFile.toString()
        )
            // TRUNCATE is only used for the external-fallback path: WAL uses mmap'd
            // -shm/-wal sidecars that misbehave on FUSE-emulated external storage
            // (moduleData lives on /sdcard). Private storage always uses WAL.
            .setJournalMode(journalMode)
            .addMigrations(MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
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
