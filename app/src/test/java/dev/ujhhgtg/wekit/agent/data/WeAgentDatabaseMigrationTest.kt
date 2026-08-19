package dev.ujhhgtg.wekit.agent.data

import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeAgentDatabaseMigrationTest {
    @Test
    fun `migration 12 to 13 preserves conversation rows and removes workspace state`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, systemPromptId TEXT, workspaceId TEXT, modelId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, favorite INTEGER NOT NULL, promptTokens INTEGER, completionTokens INTEGER, totalTokens INTEGER, contextWindow INTEGER)")
                statement.execute("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, content TEXT NOT NULL)")
                statement.execute("CREATE TABLE tool_calls (id TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, resultJson TEXT)")
                statement.execute("CREATE TABLE workspaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                statement.execute("CREATE TABLE settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                statement.execute("INSERT INTO sessions VALUES ('session', 'Title', NULL, 'workspace', 'model', 1, 2, 1, 3, 4, 7, 8192)")
                statement.execute("INSERT INTO messages VALUES ('message', 'session', 'kept')")
                statement.execute("INSERT INTO tool_calls VALUES ('call', 'message', 'kept')")
                statement.execute("INSERT INTO workspaces VALUES ('workspace', 'old-files-stay-on-disk')")
                statement.execute("INSERT INTO settings VALUES ('memory_enabled', 'true'), ('default_workspace_id', 'workspace'), ('default_model_id', 'model')")
                WeAgentDatabase.migration12To13Sql.forEach(statement::execute)
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
                assertFalse(statement.tableExists("workspaces"))
                assertTrue(statement.tableExists("linux_environments"))
            }
        }
    }

    @Test
    fun `migration 13 to 14 adds independent bridge audit storage`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                WeAgentDatabase.migration13To14Sql.forEach(statement::execute)
                statement.execute("INSERT INTO bridge_tool_audits VALUES ('audit', 'session', 'native', 'call', 'builtin', 'read_only', '{}', 'AUTO_ALLOWED', 'SUCCEEDED', 'result', 1)")
                statement.execute("INSERT INTO bridge_tool_audits VALUES ('cancelled', 'session', 'native', NULL, 'builtin', 'read_only', '{}', NULL, 'CANCELLED', 'revoked', 2)")
                assertEquals(2, statement.count("bridge_tool_audits"))
                assertTrue(statement.indexExists("index_bridge_tool_audits_sessionId"))
                assertTrue(statement.indexExists("index_bridge_tool_audits_environmentId"))
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
