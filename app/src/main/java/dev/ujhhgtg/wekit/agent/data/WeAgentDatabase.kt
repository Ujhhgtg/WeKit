package dev.ujhhgtg.wekit.agent.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.ujhhgtg.wekit.agent.data.dao.ConditionalPromptDao
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
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe

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
    ],
    version = 8,
    exportSchema = true,
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

    companion object {
        @Volatile
        private var INSTANCE: WeAgentDatabase? = null

        val instance: WeAgentDatabase
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: build().also { INSTANCE = it }
            }

        /**
         * v3 → v4: adds the nullable `maxTokens` column to `models`. Additive, so existing models,
         * provider API keys, and chat history are preserved (unlike the destructive fallback, which
         * wipes everything). [fallbackToDestructiveMigration] stays as the last-resort safety net.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `maxTokens` INTEGER")
            }
        }

        /**
         * v4 → v5: adds the `supportsVision` flag to `models` (gates the `ui-screenshot` tool).
         * Additive & non-destructive, so existing models/keys/history survive.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `supportsVision` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: adds the `favorite` flag to `sessions` and creates the `triggers` table (WeAgent
         * trigger system). Additive & non-destructive. The column list / types / NOT NULL + DEFAULT
         * constraints below must match what Room generates for [TriggerEntity] and [SessionEntity],
         * or Room's post-migration schema validation will fail.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `triggers` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `promptTemplate` TEXT NOT NULL,
                        `scheduleKind` TEXT,
                        `intervalSeconds` INTEGER,
                        `cronExpr` TEXT,
                        `dailyMinuteOfDay` INTEGER,
                        `atEpochMillis` INTEGER,
                        `conditionsJson` TEXT,
                        `bufferDebounceMillis` INTEGER NOT NULL DEFAULT 3000,
                        `bufferMaxEvents` INTEGER NOT NULL DEFAULT 20,
                        `bufferMaxWaitMillis` INTEGER NOT NULL DEFAULT 30000,
                        `filterOwnEvents` INTEGER NOT NULL DEFAULT 1,
                        `cooldownMillis` INTEGER NOT NULL DEFAULT 0,
                        `lastFiredAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_triggers_sessionId` ON `triggers` (`sessionId`)")
            }
        }

        /**
         * v6 → v7: makes `sessions.modelId` nullable so null = "默认" (follow the settings default
         * model at turn time, mirroring `systemPromptId`/`workspaceId`). SQLite can't relax a NOT NULL
         * constraint in place, so recreate the table and copy rows over. Non-destructive: all session
         * rows + their bound ids are preserved (existing non-null model ids stay as explicit bindings).
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `sessions_new` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `systemPromptId` TEXT,
                        `workspaceId` TEXT,
                        `modelId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `favorite` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `sessions_new` (`id`, `title`, `systemPromptId`, `workspaceId`, `modelId`, `createdAt`, `updatedAt`, `favorite`)
                    SELECT `id`, `title`, `systemPromptId`, `workspaceId`, `modelId`, `createdAt`, `updatedAt`, `favorite` FROM `sessions`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
            }
        }

        /**
         * v7 → v8: adds the nullable `reasoning` column to `messages` so assistant "思考过程" survives
         * a reload (previously it was streamed to the UI but never persisted). Additive & non-destructive.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT")
            }
        }

        private fun build(): WeAgentDatabase {
            val dbFile = KnownPaths.moduleData
                .resolve("agent")
                .createDirsSafe()
                .resolve("weagent.db")
            return Room.databaseBuilder(
                HostInfo.application,
                WeAgentDatabase::class.java,
                dbFile.toString())
                // WAL uses mmap'd -shm/-wal sidecars that misbehave on FUSE-emulated
                // external storage (moduleData lives on /sdcard); TRUNCATE is safe there.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
