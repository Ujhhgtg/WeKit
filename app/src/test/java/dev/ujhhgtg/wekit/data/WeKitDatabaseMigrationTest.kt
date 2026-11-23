package dev.ujhhgtg.wekit.data

import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeKitDatabaseMigrationTest {
    @Test
    fun `migration 21 to 22 preserves current rows and empty completed collections`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { sql ->
                WeKitDatabase.migration17To18Sql.forEach(sql::execute)
                WeKitDatabase.migration20To21Sql.forEach(sql::execute)
                sql.execute(WeKitDatabase.migration21To22Sql.first())
                sql.execute("INSERT INTO structured_store_state (domainKey,dataVersion,status,sourceKind,exportable,completedAt) VALUES ('moments/custom_bottom_details',1,'READY','document',1,123), ('chat/groups',1,'READY','document',1,124), ('feature_flags/overrides',1,'FAILED','document',1,NULL)")
                sql.execute("INSERT INTO moment_custom_details VALUES ('123', 'edited after migration')")
                sql.execute("INSERT INTO documents VALUES ('moments','custom_bottom_details','stale JSON',1,1,1), ('chat','groups','stale group list',1,1,1), ('feature_flags','overrides','broken JSON',1,1,1)")

                WeKitDatabase.migration21To22Sql.forEach(sql::execute)

                assertEquals(1, sql.count("moment_custom_details", "snsId = '123' AND text = 'edited after migration'"))
                assertEquals(0, sql.count("conversation_groups"))
                assertEquals(2, sql.count("documents", "namespace = 'migration' AND content = 'completed' AND `key` IN ('json-tables-v1/moments/custom_bottom_details','json-tables-v1/chat/groups')"))
                assertEquals(1, sql.count("documents", "namespace = 'migration' AND `key` = 'json-tables-v1/moments/custom_bottom_details' AND updatedAt = 123"))
                assertEquals(0, sql.count("documents", "namespace = 'migration' AND `key` = 'json-tables-v1/feature_flags/overrides'"))
                assertEquals(1, sql.count("documents", "namespace = 'feature_flags' AND content = 'broken JSON'"))
                assertEquals(1, sql.count("documents", "namespace = 'moments' AND content = 'stale JSON'"))
                assertFalse(sql.tableExists("structured_store_state"))
            }
        }
    }

    @Test
    fun `migration 21 to 22 also accepts the simplified schema without changing its markers`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { sql ->
                WeKitDatabase.migration17To18Sql.forEach(sql::execute)
                WeKitDatabase.migration20To21Sql.forEach(sql::execute)
                sql.execute("INSERT INTO documents VALUES ('migration','json-tables-v1/chat/groups','completed',1,456,1)")
                sql.execute("INSERT INTO real_name_scan_progress VALUES ('member', 42)")

                WeKitDatabase.migration21To22Sql.forEach(sql::execute)

                assertEquals(1, sql.count("documents"))
                assertEquals(1, sql.count("documents", "updatedAt = 456 AND content = 'completed'"))
                assertEquals(1, sql.count("real_name_scan_progress", "wxId = 'member' AND resumeIndex = 42"))
                assertFalse(sql.tableExists("structured_store_state"))
            }
        }
    }

    @Test
    fun `migration 21 to 22 turns omitted backup data into defaults instead of legacy preferences`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { sql ->
                WeKitDatabase.migration17To18Sql.forEach(sql::execute)
                WeKitDatabase.migration20To21Sql.forEach(sql::execute)
                sql.execute(WeKitDatabase.migration21To22Sql.first())
                sql.execute("INSERT INTO structured_store_state (domainKey,dataVersion,status,sourceKind,exportable) VALUES ('json/RedPacketSettings',1,'RESET','export-redacted',0), ('chat/groups',1,'RESET','export-redacted',0), ('chat/folders',1,'RESET','export-redacted',0)")
                sql.execute("INSERT INTO documents VALUES ('json','RedPacketSettings','stale private configuration',1,1,0)")

                WeKitDatabase.migration21To22Sql.forEach(sql::execute)

                assertEquals(1, sql.count("documents", "namespace = 'json' AND `key` = 'RedPacketSettings' AND content = '{}' AND exportable = 1"))
                assertEquals(1, sql.count("documents", "namespace = 'chat' AND `key` = 'folders' AND content = '[]'"))
                sql.executeQuery("SELECT content FROM documents WHERE namespace = 'chat' AND `key` = 'groups'").use { rows ->
                    assertTrue(rows.next())
                    val groups = dev.ujhhgtg.wekit.data.structured.LegacyConversationCollections.groups(rows.getString(1)).items
                    assertEquals(5, groups.size)
                    assertEquals(5, groups.map { it.id }.distinct().size)
                }
                // The normal JSON importer still needs to write the defaults to the business tables.
                assertEquals(0, sql.count("documents", "namespace = 'migration'"))
                assertFalse(sql.tableExists("structured_store_state"))
            }
        }
    }

    @Test
    fun `migration 12 to 13 preserves conversation rows and removes workspace state`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, systemPromptId TEXT, workspaceId TEXT, modelId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, favorite INTEGER NOT NULL, promptTokens INTEGER, completionTokens INTEGER, totalTokens INTEGER, contextWindow INTEGER)")
                statement.execute("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, content TEXT NOT NULL)")
                statement.execute("CREATE TABLE tool_calls (id TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, resultJson TEXT)")
                statement.execute("CREATE TABLE workspaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                statement.execute("CREATE TABLE settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                statement.execute("CREATE TABLE tool_permissions (providerId TEXT NOT NULL, toolName TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(providerId, toolName))")
                statement.execute("INSERT INTO sessions VALUES ('session', 'Title', NULL, 'workspace', 'model', 1, 2, 1, 3, 4, 7, 8192)")
                statement.execute("INSERT INTO messages VALUES ('message', 'session', 'kept')")
                statement.execute("INSERT INTO tool_calls VALUES ('call', 'message', 'kept')")
                statement.execute("INSERT INTO workspaces VALUES ('workspace', 'old-files-stay-on-disk')")
                statement.execute("INSERT INTO settings VALUES ('memory_enabled', 'true'), ('default_workspace_id', 'workspace'), ('default_model_id', 'model')")
                statement.execute("INSERT INTO tool_permissions VALUES ('builtin-fs', 'read_file', 'ENABLED'), ('builtin-fs', 'load_skill', 'ENABLED'), ('mcp', 'read_file', 'MANUAL_APPROVAL')")
                WeKitDatabase.migration12To13Sql.forEach(statement::execute)
            }

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT linuxEnvironmentId, lastEffectiveLinuxEnvironmentId, title FROM sessions WHERE id = 'session'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(null, rows.getString(1))
                    assertEquals(null, rows.getString(2))
                    assertEquals("Title", rows.getString(3))
                }
                assertEquals(1, statement.count("messages"))
                assertEquals(1, statement.count("tool_calls"))
                assertEquals(0, statement.count("settings", "`key` IN ('memory_enabled', 'default_workspace_id')"))
                assertEquals(1, statement.count("settings", "`key` = 'default_model_id'"))
                assertEquals(0, statement.count("tool_permissions", "providerId = 'builtin-fs' AND toolName = 'read_file'"))
                assertEquals(1, statement.count("tool_permissions", "providerId = 'builtin-fs' AND toolName = 'load_skill'"))
                assertEquals(1, statement.count("tool_permissions", "providerId = 'mcp' AND toolName = 'read_file'"))
                assertFalse(statement.tableExists("workspaces"))
                assertTrue(statement.tableExists("linux_environments"))
            }
        }
    }

    @Test
    fun `migration 13 to 14 adds independent bridge audit storage`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                WeKitDatabase.migration13To14Sql.forEach(statement::execute)
                statement.execute("INSERT INTO bridge_tool_audits VALUES ('audit', 'session', 'native', 'call', 'builtin', 'read_only', '{}', 'AUTO_ALLOWED', 'SUCCEEDED', 'result', 1)")
                statement.execute("INSERT INTO bridge_tool_audits VALUES ('cancelled', 'session', 'native', NULL, 'builtin', 'read_only', '{}', NULL, 'CANCELLED', 'revoked', 2)")
                assertEquals(2, statement.count("bridge_tool_audits"))
                assertTrue(statement.indexExists("index_bridge_tool_audits_sessionId"))
                assertTrue(statement.indexExists("index_bridge_tool_audits_environmentId"))
            }
        }
    }

    @Test
    fun `migration 14 to 15 adds session permission level and drops per-tool permissions`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, modelId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                statement.execute("CREATE TABLE tool_permissions (providerId TEXT NOT NULL, toolName TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(providerId, toolName))")
                statement.execute("INSERT INTO sessions VALUES ('session', 'Title', 'model', 1, 2)")
                statement.execute("INSERT INTO tool_permissions VALUES ('builtin-fs', 'read_file', 'ENABLED')")
                WeKitDatabase.migration14To15Sql.forEach(statement::execute)
            }

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT permissionLevel FROM sessions WHERE id = 'session'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(null, rows.getString(1))
                }
                assertFalse(statement.tableExists("tool_permissions"))
            }
        }
    }

    @Test
    fun `migration 15 to 16 removes local models without losing conversations or remote configuration`() {
        for (keepRemoteDefaults in listOf(false, true)) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE model_providers (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL, baseUrl TEXT NOT NULL, apiKey TEXT NOT NULL)")
                    statement.execute("CREATE TABLE models (id TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, modelIdRemote TEXT NOT NULL)")
                    statement.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, modelId TEXT, contextWindow INTEGER)")
                    statement.execute("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, content TEXT NOT NULL)")
                    statement.execute("CREATE TABLE tool_calls (id TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, resultJson TEXT)")
                    statement.execute("CREATE TABLE settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("INSERT INTO model_providers VALUES ('local-llama', 'LOCAL_LLAMA', '', '', ''), ('imported-local', 'LOCAL_LLAMA', 'Old local', '', ''), ('remote', 'OPENAI_RESPONSES', 'Remote', 'https://example.com/v1', 'preserved-key')")
                    statement.execute("INSERT INTO models VALUES ('local-model', 'local-llama', 'weights'), ('imported-model', 'imported-local', 'weights'), ('remote-model', 'remote', 'remote-id')")
                    statement.execute("INSERT INTO sessions VALUES ('local-session', 'Local history', 'local-model', 32768), ('imported-session', 'Imported history', 'imported-model', 16384), ('remote-session', 'Remote history', 'remote-model', 65536), ('default-session', 'Default history', NULL, NULL)")
                    statement.execute("INSERT INTO messages VALUES ('message', 'local-session', 'preserved conversation')")
                    statement.execute("INSERT INTO tool_calls VALUES ('call', 'message', 'preserved tool result')")
                    val defaultModel = if (keepRemoteDefaults) "remote-model" else "local-model"
                    val smallModel = if (keepRemoteDefaults) "remote-model" else "imported-model"
                    statement.execute("INSERT INTO settings VALUES ('default_model_id', '$defaultModel'), ('small_model_id', '$smallModel'), ('local_compute_backend', 'vulkan'), ('unrelated', 'local-model')")

                    WeKitDatabase.migration15To16Sql.forEach(statement::execute)

                    assertEquals(1, statement.count("model_providers"))
                    assertEquals(1, statement.count("models", "id = 'remote-model' AND modelIdRemote = 'remote-id'"))
                    assertEquals(1, statement.count("model_providers", "id = 'remote' AND type = 'OPENAI_RESPONSES' AND name = 'Remote' AND baseUrl = 'https://example.com/v1' AND apiKey = 'preserved-key'"))
                    assertEquals(4, statement.count("sessions"))
                    assertEquals(2, statement.count("sessions", "id IN ('local-session', 'imported-session') AND modelId IS NULL AND contextWindow IS NULL"))
                    assertEquals(1, statement.count("sessions", "id = 'remote-session' AND modelId = 'remote-model' AND contextWindow = 65536"))
                    assertEquals(1, statement.count("messages", "sessionId = 'local-session' AND content = 'preserved conversation'"))
                    assertEquals(1, statement.count("tool_calls", "messageId = 'message' AND resultJson = 'preserved tool result'"))
                    assertEquals(0, statement.count("settings", "`key` = 'local_compute_backend'"))
                    assertEquals(if (keepRemoteDefaults) 2 else 0, statement.count("settings", "`key` IN ('default_model_id', 'small_model_id')"))
                    assertEquals(1, statement.count("settings", "`key` = 'unrelated' AND value = 'local-model'"))
                }
            }
        }
    }

    @Test
    fun `migration 16 to 17 removes chroot configuration and keeps other environments and history`() {
        for (keepDefault in listOf(false, true)) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE linux_environments (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, rootfsPath TEXT, sshHost TEXT)")
                    statement.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, linuxEnvironmentId TEXT, lastEffectiveLinuxEnvironmentId TEXT)")
                    statement.execute("CREATE TABLE settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, content TEXT NOT NULL)")
                    statement.execute("CREATE TABLE tool_calls (id TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, resultJson TEXT)")
                    statement.execute("CREATE TABLE bridge_tool_audits (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, environmentId TEXT NOT NULL, result TEXT NOT NULL)")
                    statement.execute("INSERT INTO linux_environments VALUES ('retired', 'CHROOT', '/old/rootfs', NULL), ('proot', 'PROOT', '/proot/rootfs', NULL), ('ssh', 'SSH', NULL, 'example.com')")
                    statement.execute("INSERT INTO sessions VALUES ('old-session', 'Old history', 'retired', 'retired'), ('mixed-session', 'Mixed history', 'proot', 'retired'), ('remote-session', 'Remote history', 'ssh', 'ssh'), ('default-session', 'Default history', NULL, 'retired')")
                    val defaultId = if (keepDefault) "proot" else "retired"
                    statement.execute("INSERT INTO settings VALUES ('default_linux_environment_id', '$defaultId'), ('unrelated', 'retired')")
                    statement.execute("INSERT INTO messages VALUES ('message', 'old-session', 'preserved history')")
                    statement.execute("INSERT INTO tool_calls VALUES ('call', 'message', 'preserved result')")
                    statement.execute("INSERT INTO bridge_tool_audits VALUES ('audit', 'old-session', 'retired', 'preserved audit')")

                    WeKitDatabase.migration16To17Sql.forEach(statement::execute)

                    assertEquals(2, statement.count("linux_environments"))
                    assertEquals(1, statement.count("linux_environments", "id = 'proot' AND type = 'PROOT' AND rootfsPath = '/proot/rootfs'"))
                    assertEquals(1, statement.count("linux_environments", "id = 'ssh' AND type = 'SSH' AND sshHost = 'example.com'"))
                    assertEquals(4, statement.count("sessions"))
                    assertEquals(1, statement.count("sessions", "id = 'old-session' AND title = 'Old history' AND linuxEnvironmentId IS NULL AND lastEffectiveLinuxEnvironmentId IS NULL"))
                    assertEquals(1, statement.count("sessions", "id = 'mixed-session' AND linuxEnvironmentId = 'proot' AND lastEffectiveLinuxEnvironmentId IS NULL"))
                    assertEquals(1, statement.count("sessions", "id = 'remote-session' AND linuxEnvironmentId = 'ssh' AND lastEffectiveLinuxEnvironmentId = 'ssh'"))
                    assertEquals(1, statement.count("sessions", "id = 'default-session' AND linuxEnvironmentId IS NULL AND lastEffectiveLinuxEnvironmentId IS NULL"))
                    assertEquals(if (keepDefault) 1 else 0, statement.count("settings", "`key` = 'default_linux_environment_id' AND value = 'proot'"))
                    assertEquals(0, statement.count("settings", "`key` = 'default_linux_environment_id' AND value = 'retired'"))
                    assertEquals(1, statement.count("settings", "`key` = 'unrelated' AND value = 'retired'"))
                    assertEquals(1, statement.count("messages", "sessionId = 'old-session' AND content = 'preserved history'"))
                    assertEquals(1, statement.count("tool_calls", "messageId = 'message' AND resultJson = 'preserved result'"))
                    assertEquals(1, statement.count("bridge_tool_audits", "environmentId = 'retired' AND result = 'preserved audit'"))
                }
            }
        }
    }

    private fun java.sql.Statement.count(table: String, where: String = "1"): Int =
        executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { rows -> rows.next(); rows.getInt(1) }

    private fun java.sql.Statement.tableExists(name: String): Boolean =
        executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { it.next() }

    private fun java.sql.Statement.indexExists(name: String): Boolean =
        executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = '$name'").use { it.next() }
}
