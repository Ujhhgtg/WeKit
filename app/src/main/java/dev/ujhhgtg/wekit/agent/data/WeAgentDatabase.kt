package dev.ujhhgtg.wekit.agent.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import dev.ujhhgtg.wekit.agent.data.dao.WorkspaceDao
import dev.ujhhgtg.wekit.workflow.data.WorkflowDao
import dev.ujhhgtg.wekit.workflow.data.WorkflowEntity
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
        ExternalServiceEntity::class,
        WorkflowEntity::class,
    ],
    version = 13,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 9, to = 10),  // adds external_services table
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
    abstract fun externalServiceDao(): ExternalServiceDao
    abstract fun workflowDao(): WorkflowDao

    companion object {
        @Volatile
        private var INSTANCE: WeAgentDatabase? = null

        val instance: WeAgentDatabase
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: build().also { INSTANCE = it }
            }

        // 11 → 12: WEKIT_ROUTER enum value removed from ModelProviderType.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM models WHERE providerId IN " +
                            "(SELECT id FROM model_providers WHERE type = 'WEKIT_ROUTER')"
                )
                db.execSQL("DELETE FROM model_providers WHERE type = 'WEKIT_ROUTER'")
            }
        }

        // 12 → 13: Trigger system replaced by the Workflow system.
        //   - DROP triggers table (users must recreate via the new Workflow UI)
        //   - CREATE workflows table
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS triggers")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflows (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        workflowJson TEXT NOT NULL,
                        triggerType TEXT,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflows_triggerType ON workflows (triggerType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflows_enabled ON workflows (enabled)")
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
                dbFile.toString()
            )
                // WAL uses mmap'd -shm/-wal sidecars that misbehave on FUSE-emulated
                // external storage (moduleData lives on /sdcard); TRUNCATE is safe there.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_11_12, MIGRATION_12_13)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
