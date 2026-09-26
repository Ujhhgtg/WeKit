package dev.ujhhgtg.wekit.data.structured

import dev.ujhhgtg.wekit.data.dao.AutomationFeatureRows
import dev.ujhhgtg.wekit.data.entity.AutomationScopeEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopeFieldEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopeListValueEntity
import dev.ujhhgtg.wekit.data.entity.AutomationScopePresetEntity
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.automation.AutomationReplyStep
import dev.ujhhgtg.wekit.features.automation.AutomationReplyStepKind
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationSnapshot
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.decodeAutomationRows
import dev.ujhhgtg.wekit.features.items.moments.MomentAutomationAction
import dev.ujhhgtg.wekit.features.items.moments.MomentsAutomation
import dev.ujhhgtg.wekit.features.items.payment.RedPacketAutomation
import dev.ujhhgtg.wekit.features.items.payment.TransferAutomation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

class AutomationMigrationTest {
    @Test
    fun `migration 23 to 24 moves every legacy rule into the generic tables`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                legacyAutomationSchemaSql.forEach(statement::execute)
                statement.execute(
                    "INSERT INTO red_packet_rules VALUES ('GLOBAL', '', '', 1, 0, 'CLICK', 1, 60, 120, 1, 'STRING_LIST', '', 0, " +
                            "NULL, NULL, NULL, NULL, 1, '500', '300', 0, 1, 'thanks \$amount')"
                )
                statement.execute(
                    "INSERT INTO red_packet_rules (scopeType, talkerId, memberId, grabEnabled) VALUES ('CONTACT', 'wxid_a', '', 0)"
                )
                statement.execute(
                    "INSERT INTO red_packet_rules (scopeType, talkerId, memberId, replyEnabled, replyText) " +
                            "VALUES ('GROUP_MEMBER', 'g@chatroom', 'm1', 1, '')"
                )
                statement.execute(
                    "INSERT INTO red_packet_keywords VALUES ('GLOBAL', '', '', 'KEYWORD', 1, 'b'), ('GLOBAL', '', '', 'KEYWORD', 0, 'a'), " +
                            "('CONTACT', 'wxid_a', '', 'SKIP_KEYWORD', 0, 'orphan')"
                )
                statement.execute(
                    "INSERT INTO transfer_rules (scopeType, talkerId, memberId, amountEnabled, amountMinimumYuan, amountMaximumYuan, " +
                            "memoKeywordEnabled, memoKeywordMode, memoKeywordRegex, memoKeywordIgnoreCase) " +
                            "VALUES ('GLOBAL', '', '', 1, '1.00', '', 1, 'EXACT', '', 1)"
                )
                statement.execute("INSERT INTO transfer_keywords VALUES ('GLOBAL', '', '', 0, 'rent')")
                statement.execute(
                    "INSERT INTO moment_automation_rules (featureKind, scopeType, ownerId, actionEnabled, actionValue, contentTypeEnabled) " +
                            "VALUES ('LIKE', 'GLOBAL', '', 1, 'UNLIKE', 1), ('REPOST', 'GLOBAL', '', 1, 'LIKE', NULL)"
                )
                statement.execute(
                    "INSERT INTO moment_automation_content_types VALUES ('LIKE', 'GLOBAL', '', 3), ('LIKE', 'GLOBAL', '', 1)"
                )

                migration23To24Sql.forEach(statement::execute)

                statement.executeQuery("SELECT COUNT(*) FROM automation_scope_list_values WHERE ruleKey = 'skipKeyword'").use {
                    it.next()
                    assertEquals(0, it.getInt(1), "orphaned keyword rows of an inherited rule are dropped")
                }
                assertFalse(statement.tableExists("red_packet_rules"))
                assertFalse(statement.tableExists("moment_automation_content_types"))
            }

            val redPacket = connection.snapshot(RedPacketAutomation)
            val global = redPacket.resolve(null)
            assertEquals(RedPacketAutomation.ReceiveMode.CLICK, global[RedPacketAutomation.receiveMode])
            assertEquals(3600, global[RedPacketAutomation.timeRange].startSecond)
            assertEquals(listOf("a", "b"), global[RedPacketAutomation.keyword].strings)
            assertEquals(
                listOf(AutomationReplyStep(AutomationReplyStepKind.TEXT, "thanks \$amount")),
                global[RedPacketAutomation.reply].steps,
            )
            assertTrue(global[RedPacketAutomation.reply].enabled)

            val contact = redPacket[AutomationScope.contact("wxid_a")]
            assertEquals(setOf("grab"), contact.values.keyIds)
            assertFalse(redPacket.resolve("wxid_a")[RedPacketAutomation.grab].enabled)
            assertEquals(listOf("a", "b"), redPacket.resolve("wxid_a")[RedPacketAutomation.keyword].strings)

            val member = redPacket.resolve("g@chatroom", "m1")[RedPacketAutomation.reply]
            assertTrue(member.enabled)
            assertTrue(member.steps.isEmpty())

            val transfer = connection.snapshot(TransferAutomation).resolve(null)
            assertEquals("1.00", transfer[TransferAutomation.amountRange].minimumYuan)
            assertEquals(AutomationKeywordMode.EXACT, transfer[TransferAutomation.memoKeyword].mode)
            assertEquals(listOf("rent"), transfer[TransferAutomation.memoKeyword].strings)

            val like = connection.snapshot(MomentsAutomation.Like).resolve(null)
            assertEquals(MomentAutomationAction.UNLIKE, like[MomentsAutomation.Like.action].action)
            assertEquals(setOf(1, 3), like[MomentsAutomation.Like.contentType].typeIds)

            val repost = connection.snapshot(MomentsAutomation.Repost)[AutomationScope.Global]
            assertTrue(repost.values.isEmpty())
        }
    }

    @Test
    fun `migration 25 to 26 turns minute time ranges into seconds`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                automationSchemaSql.forEach(statement::execute)
                statement.execute("INSERT INTO automation_scopes VALUES ('${RedPacketAutomation.id}', 'GLOBAL', '', '', NULL, 0)")
                statement.execute(
                    "INSERT INTO automation_scope_fields VALUES ('${RedPacketAutomation.id}', 'GLOBAL', '', '', 'timeRange', 'enabled', 'true'), " +
                            "('${RedPacketAutomation.id}', 'GLOBAL', '', '', 'timeRange', 'startMinute', '1380'), " +
                            "('${RedPacketAutomation.id}', 'GLOBAL', '', '', 'timeRange', 'endMinute', '420')"
                )
                migration25To26Sql.forEach(statement::execute)
            }
            val range = connection.snapshot(RedPacketAutomation).resolve(null)[RedPacketAutomation.timeRange]
            assertEquals(23 * 3600, range.startSecond)
            assertEquals(7 * 3600, range.endSecond)
        }
    }

    private fun Connection.snapshot(spec: AutomationSpec): AutomationSnapshot = createStatement().use { s ->
        val feature = spec.id
        val scopes = s.rows("SELECT * FROM automation_scopes WHERE feature = '$feature'") {
            AutomationScopeEntity(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getInt(6))
        }
        val fields = s.rows("SELECT * FROM automation_scope_fields WHERE feature = '$feature'") {
            AutomationScopeFieldEntity(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7))
        }
        val lists = s.rows("SELECT * FROM automation_scope_list_values WHERE feature = '$feature'") {
            AutomationScopeListValueEntity(
                it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getInt(7), it.getString(8),
            )
        }
        val presets = s.rows("SELECT * FROM automation_scope_presets WHERE feature = '$feature'") {
            AutomationScopePresetEntity(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5), it.getString(6))
        }
        decodeAutomationRows(spec, AutomationFeatureRows(scopes, fields, lists, presets))
    }

    private fun <T> Statement.rows(sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        executeQuery(sql).use { rows -> buildList { while (rows.next()) add(map(rows)) } }

    private fun Statement.tableExists(name: String): Boolean =
        executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { it.next() }
}
