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
import dev.ujhhgtg.wekit.agent.data.dao.ModelDao
import dev.ujhhgtg.wekit.agent.data.dao.ModelProviderDao
import dev.ujhhgtg.wekit.agent.data.dao.PerTurnPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.PresetPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ProviderDao
import dev.ujhhgtg.wekit.agent.data.dao.SessionDao
import dev.ujhhgtg.wekit.agent.data.dao.SettingDao
import dev.ujhhgtg.wekit.agent.data.dao.SystemPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ToolCallDao
import dev.ujhhgtg.wekit.agent.data.dao.ToolPermissionDao
import dev.ujhhgtg.wekit.agent.data.dao.TriggerDao
import dev.ujhhgtg.wekit.agent.data.dao.WorkspaceDao
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ExternalServiceEntity
import dev.ujhhgtg.wekit.agent.data.entity.MessageEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.SessionEntity
import dev.ujhhgtg.wekit.agent.data.entity.SettingEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolCallEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolPermissionEntity
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.agent.data.entity.WorkspaceEntity
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        ProviderEntity::class,
        ToolPermissionEntity::class,
        ModelProviderEntity::class,
        ModelEntity::class,
        SystemPromptEntity::class,
        PerTurnPromptEntity::class,
        ConditionalPromptEntity::class,
        PresetPromptEntity::class,
        WorkspaceEntity::class,
        SettingEntity::class,
        TriggerEntity::class,
        ExternalServiceEntity::class,
    ],
    version = 12,
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
    abstract fun toolPermissionDao(): ToolPermissionDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun perTurnPromptDao(): PerTurnPromptDao
    abstract fun conditionalPromptDao(): ConditionalPromptDao
    abstract fun presetPromptDao(): PresetPromptDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun settingDao(): SettingDao
    abstract fun triggerDao(): TriggerDao
    abstract fun externalServiceDao(): ExternalServiceDao

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
            // WAL uses mmap'd -shm/-wal sidecars that misbehave on FUSE-emulated
            // external storage (moduleData lives on /sdcard); TRUNCATE is safe there.
            .setJournalMode(journalMode)
            .addMigrations(MIGRATION_11_12)
            // Destructive fallback is scoped to the pre-release schemas (1–8) only, which no
            // migration path was ever written for. From 9 onwards every step must have a
            // migration: a missing one then fails loudly at open time instead of silently
            // wiping every session, prompt, workspace, trigger and model provider (API keys
            // included). If you bump `version`, add the matching migration — do NOT widen this
            // list.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7, 8)
            .build()
    }
}
