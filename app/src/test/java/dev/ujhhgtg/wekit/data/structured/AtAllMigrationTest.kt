package dev.ujhhgtg.wekit.data.structured

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Statement

class AtAllMigrationTest {
    private fun migrate(setup: Statement.() -> Unit, check: Statement.() -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { s ->
                s.execute("CREATE TABLE `preference_entries` (`namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `valueType` TEXT NOT NULL, `valueText` TEXT, `valueLong` INTEGER, `valueDouble` REAL, `valueBlob` BLOB, `encodingVersion` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `exportable` INTEGER NOT NULL, PRIMARY KEY(`namespace`, `key`))")
                s.execute("CREATE TABLE `preference_set_members` (`namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `member` TEXT NOT NULL, PRIMARY KEY(`namespace`, `key`, `member`))")
                automationSchemaSql.forEach(s::execute)
                s.setup()
                migration24To25Sql.forEach(s::execute)
                s.check()
            }
        }
    }

    private fun Statement.pref(key: String, value: Long) =
        execute("INSERT INTO preference_entries VALUES ('wekit_prefs', '$key', 'bool', NULL, $value, NULL, NULL, 1, 1, 0, 1)")

    private fun Statement.members(key: String, vararg ids: String) =
        ids.forEach { execute("INSERT INTO preference_set_members VALUES ('wekit_prefs', '$key', '$it')") }

    private fun Statement.value(sql: String): String? = executeQuery(sql).use { if (it.next()) it.getString(1) else null }

    private fun Statement.mention(type: String, id: String) = value(
        "SELECT value FROM automation_scope_fields WHERE feature = 'CUSTOM_NOTIFICATIONS' AND scopeType = '$type' " +
                "AND scopeId = '$id' AND ruleKey = 'mentions' AND field = 'blockAtAll'"
    )

    @Test
    fun `blacklist groups block at-all and the feature is enabled without taking over`() = migrate({
        pref("屏蔽群聊@所有人", 1)
        members("block_at_all_notifications_blacklist", "a@chatroom", "b@chatroom")
        members("block_at_all_notifications_whitelist", "c@chatroom")
    }) {
        assertEquals("true", mention("CONTACT", "a@chatroom"))
        assertEquals("true", mention("CONTACT", "b@chatroom"))
        assertEquals(null, mention("CONTACT", "c@chatroom"))
        assertEquals(null, mention("CATEGORY", "GROUP"))
        assertEquals("false", value("SELECT value FROM automation_scope_fields WHERE ruleKey = 'takeOver'"))
        assertEquals("1", value("SELECT valueLong FROM preference_entries WHERE `key` = '自定义通知'"))
        assertEquals("0", value("SELECT COUNT(*) FROM preference_set_members"))
        assertEquals("1", value("SELECT COUNT(*) FROM preference_entries"))
    }

    @Test
    fun `whitelist blocks every group except the listed ones`() = migrate({
        pref("屏蔽群聊@所有人", 1)
        pref("block_at_all_notifications_use_whitelist", 1)
        members("block_at_all_notifications_whitelist", "c@chatroom")
    }) {
        assertEquals("true", mention("CATEGORY", "GROUP"))
        assertEquals("false", mention("CONTACT", "c@chatroom"))
    }

    @Test
    fun `disabled legacy feature or notifications evolved leave the feature off`() {
        migrate({ members("block_at_all_notifications_blacklist", "a@chatroom") }) {
            assertEquals("0", value("SELECT COUNT(*) FROM automation_scopes"))
            assertEquals("0", value("SELECT COUNT(*) FROM preference_set_members"))
        }
        migrate({
            pref("屏蔽群聊@所有人", 1)
            pref("通知进化", 1)
        }) {
            assertEquals(null, value("SELECT valueLong FROM preference_entries WHERE `key` = '自定义通知'"))
        }
    }
}
