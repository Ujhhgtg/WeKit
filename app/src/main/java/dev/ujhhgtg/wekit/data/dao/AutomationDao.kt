package dev.ujhhgtg.wekit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.ujhhgtg.wekit.data.entity.*
import dev.ujhhgtg.wekit.data.structured.*
import dev.ujhhgtg.wekit.features.items.moments.MomentAutomationOverrides
import dev.ujhhgtg.wekit.features.items.moments.MomentAutomationRuleSet
import dev.ujhhgtg.wekit.features.items.moments.StoredMomentAutomationConfig
import dev.ujhhgtg.wekit.features.items.payment.RedPacketSettings
import dev.ujhhgtg.wekit.features.items.payment.TransferSettings

@Dao
abstract class AutomationDao {
    @Query("SELECT * FROM red_packet_rules ORDER BY scopeType, talkerId, memberId")
    abstract suspend fun readRedPacketRules(): List<RedPacketRuleEntity>

    @Upsert
    abstract suspend fun upsert(row: RedPacketRuleEntity)

    @Query("DELETE FROM red_packet_rules WHERE scopeType = :scopeType AND talkerId = :talkerId AND memberId = :memberId")
    abstract suspend fun deleteRedPacketRule(scopeType: String, talkerId: String, memberId: String)

    @Query("SELECT * FROM transfer_rules ORDER BY scopeType, talkerId, memberId")
    abstract suspend fun readTransferRules(): List<TransferRuleEntity>

    @Upsert
    abstract suspend fun upsert(row: TransferRuleEntity)

    @Query("DELETE FROM transfer_rules WHERE scopeType = :scopeType AND talkerId = :talkerId AND memberId = :memberId")
    abstract suspend fun deleteTransferRule(scopeType: String, talkerId: String, memberId: String)

    @Query("SELECT * FROM moment_automation_rules WHERE featureKind = :featureKind ORDER BY featureKind, scopeType, ownerId")
    abstract suspend fun readMomentAutomationRules(featureKind: String): List<MomentAutomationRuleEntity>

    @Upsert
    abstract suspend fun upsert(row: MomentAutomationRuleEntity)

    @Query("DELETE FROM moment_automation_rules WHERE featureKind = :featureKind AND scopeType = :scopeType AND ownerId = :ownerId")
    abstract suspend fun deleteMomentAutomationRule(featureKind: String, scopeType: String, ownerId: String)

    @Query("SELECT * FROM red_packet_keywords ORDER BY scopeType, talkerId, memberId, ruleKind, position")
    abstract suspend fun readRedPacketKeywords(): List<RedPacketKeywordEntity>

    @Insert
    abstract suspend fun insertRedPacketKeywords(rows: List<RedPacketKeywordEntity>)

    @Query("DELETE FROM red_packet_keywords WHERE scopeType = :scopeType AND talkerId = :talkerId AND memberId = :memberId AND ruleKind = :ruleKind")
    abstract suspend fun deleteRedPacketKeywords(scopeType: String, talkerId: String, memberId: String, ruleKind: String)

    @Query("SELECT * FROM transfer_keywords ORDER BY scopeType, talkerId, memberId, position")
    abstract suspend fun readTransferKeywords(): List<TransferKeywordEntity>

    @Insert
    abstract suspend fun insertTransferKeywords(rows: List<TransferKeywordEntity>)

    @Query("DELETE FROM transfer_keywords WHERE scopeType = :scopeType AND talkerId = :talkerId AND memberId = :memberId")
    abstract suspend fun deleteTransferKeywords(scopeType: String, talkerId: String, memberId: String)

    @Query("SELECT * FROM moment_automation_keywords WHERE featureKind = :featureKind ORDER BY featureKind, scopeType, ownerId, position")
    abstract suspend fun readMomentAutomationKeywords(featureKind: String): List<MomentAutomationKeywordEntity>

    @Insert
    abstract suspend fun insertMomentAutomationKeywords(rows: List<MomentAutomationKeywordEntity>)

    @Query("DELETE FROM moment_automation_keywords WHERE featureKind = :featureKind AND scopeType = :scopeType AND ownerId = :ownerId")
    abstract suspend fun deleteMomentAutomationKeywords(featureKind: String, scopeType: String, ownerId: String)

    @Query("SELECT * FROM moment_automation_content_types WHERE featureKind = :featureKind ORDER BY featureKind, scopeType, ownerId, typeId")
    abstract suspend fun readMomentAutomationContentTypes(featureKind: String): List<MomentAutomationContentTypeEntity>

    @Insert
    abstract suspend fun insertMomentAutomationContentTypes(rows: List<MomentAutomationContentTypeEntity>)

    @Query("DELETE FROM moment_automation_content_types WHERE featureKind = :featureKind AND scopeType = :scopeType AND ownerId = :ownerId")
    abstract suspend fun deleteMomentAutomationContentTypes(featureKind: String, scopeType: String, ownerId: String)

    @Transaction
    open suspend fun getRedPacketConfig(): RedPacketSettings.StoredConfig {
        val keywords = readRedPacketKeywords().groupBy { Triple(it.scopeType, it.talkerId, it.memberId) }
        val contacts = linkedMapOf<String, RedPacketSettings.RuleOverrides>()
        val groups = linkedMapOf<String, MutableMap<String, RedPacketSettings.RuleOverrides>>()
        var global: RedPacketSettings.RuleSet? = null
        readRedPacketRules().forEach { row ->
            val value = row.toOverrides(keywords[Triple(row.scopeType, row.talkerId, row.memberId)].orEmpty())
            when (row.scopeType) {
                "GLOBAL" -> global = value.asGlobalRules()
                "CONTACT" -> contacts[row.talkerId] = value
                "GROUP_MEMBER" -> groups.getOrPut(row.talkerId) { linkedMapOf() }[row.memberId] = value
            }
        }
        return RedPacketSettings.StoredConfig(
            global = requireNotNull(global) { "Global RedPacket rule is missing" },
            contacts = contacts,
            groupMembers = groups,
        )
    }

    @Transaction
    open suspend fun insertRedPacketConfig(value: RedPacketSettings.StoredConfig) {
        saveRedPacketRule("GLOBAL", "", "", value.global.asOverrides())
        value.contacts.forEach { (id, rules) -> saveRedPacketRule("CONTACT", id, "", rules) }
        value.groupMembers.forEach { (group, members) ->
            members.forEach { (member, rules) -> saveRedPacketRule("GROUP_MEMBER", group, member, rules) }
        }
    }

    @Transaction
    open suspend fun putRedPacketRule(
        scopeType: String,
        talkerId: String,
        memberId: String,
        rules: RedPacketSettings.RuleOverrides,
    ) {
        validatePaymentScope(scopeType, talkerId, memberId)
        if (scopeType != "GLOBAL" && rules.isEmpty()) {
            deleteRedPacketRule(scopeType, talkerId, memberId)
        } else {
            saveRedPacketRule(scopeType, talkerId, memberId, rules)
        }
    }

    private suspend fun saveRedPacketRule(
        scopeType: String,
        talkerId: String,
        memberId: String,
        rules: RedPacketSettings.RuleOverrides,
    ) {
        upsert(rules.toEntity(scopeType, talkerId, memberId))
        deleteRedPacketKeywords(scopeType, talkerId, memberId, "KEYWORD")
        deleteRedPacketKeywords(scopeType, talkerId, memberId, "SKIP_KEYWORD")
        insertRedPacketKeywords(rules.keyword?.strings.orEmpty().mapIndexed { index, text ->
            RedPacketKeywordEntity(scopeType, talkerId, memberId, "KEYWORD", index, text)
        } + rules.skipKeyword?.strings.orEmpty().mapIndexed { index, text ->
            RedPacketKeywordEntity(scopeType, talkerId, memberId, "SKIP_KEYWORD", index, text)
        })
    }

    @Transaction
    open suspend fun getTransferConfig(): TransferSettings.StoredConfig {
        val keywords = readTransferKeywords().groupBy { Triple(it.scopeType, it.talkerId, it.memberId) }
        val contacts = linkedMapOf<String, TransferSettings.RuleOverrides>()
        val groups = linkedMapOf<String, MutableMap<String, TransferSettings.RuleOverrides>>()
        var global: TransferSettings.RuleSet? = null
        readTransferRules().forEach { row ->
            val value = row.toOverrides(keywords[Triple(row.scopeType, row.talkerId, row.memberId)].orEmpty())
            when (row.scopeType) {
                "GLOBAL" -> global = value.asGlobalRules()
                "CONTACT" -> contacts[row.talkerId] = value
                "GROUP_MEMBER" -> groups.getOrPut(row.talkerId) { linkedMapOf() }[row.memberId] = value
            }
        }
        return TransferSettings.StoredConfig(
            global = requireNotNull(global) { "Global Transfer rule is missing" },
            contacts = contacts,
            groupMembers = groups,
        )
    }

    @Transaction
    open suspend fun insertTransferConfig(value: TransferSettings.StoredConfig) {
        saveTransferRule("GLOBAL", "", "", value.global.asOverrides())
        value.contacts.forEach { (id, rules) -> saveTransferRule("CONTACT", id, "", rules) }
        value.groupMembers.forEach { (group, members) ->
            members.forEach { (member, rules) -> saveTransferRule("GROUP_MEMBER", group, member, rules) }
        }
    }

    @Transaction
    open suspend fun putTransferRule(
        scopeType: String,
        talkerId: String,
        memberId: String,
        rules: TransferSettings.RuleOverrides,
    ) {
        validatePaymentScope(scopeType, talkerId, memberId)
        if (scopeType != "GLOBAL" && rules.isEmpty()) {
            deleteTransferRule(scopeType, talkerId, memberId)
        } else {
            saveTransferRule(scopeType, talkerId, memberId, rules)
        }
    }

    private suspend fun saveTransferRule(
        scopeType: String,
        talkerId: String,
        memberId: String,
        rules: TransferSettings.RuleOverrides,
    ) {
        upsert(rules.toEntity(scopeType, talkerId, memberId))
        deleteTransferKeywords(scopeType, talkerId, memberId)
        insertTransferKeywords(rules.memoKeyword?.strings.orEmpty().mapIndexed { index, text ->
            TransferKeywordEntity(scopeType, talkerId, memberId, index, text)
        })
    }

    @Transaction
    open suspend fun getMomentAutomationConfig(featureKind: String): StoredMomentAutomationConfig {
        val keywords = readMomentAutomationKeywords(featureKind).groupBy { it.scopeType to it.ownerId }
        val types = readMomentAutomationContentTypes(featureKind).groupBy { it.scopeType to it.ownerId }
        val contacts = linkedMapOf<String, MomentAutomationOverrides>()
        var global: MomentAutomationRuleSet? = null
        readMomentAutomationRules(featureKind).forEach { row ->
            val key = row.scopeType to row.ownerId
            val value = row.toOverrides(keywords[key].orEmpty(), types[key].orEmpty())
            when (row.scopeType) {
                "GLOBAL" -> global = value.asGlobalRules()
                "CONTACT" -> contacts[row.ownerId] = value
            }
        }
        return StoredMomentAutomationConfig(
            global = requireNotNull(global) { "Global moment automation rule is missing" },
            contacts = contacts,
        )
    }

    @Transaction
    open suspend fun insertMomentAutomationConfig(featureKind: String, value: StoredMomentAutomationConfig) {
        saveMomentAutomationRule(featureKind, "GLOBAL", "", value.global.asOverrides())
        value.contacts.forEach { (id, rules) -> saveMomentAutomationRule(featureKind, "CONTACT", id, rules) }
    }

    @Transaction
    open suspend fun putMomentAutomationRule(
        featureKind: String,
        scopeType: String,
        ownerId: String,
        rules: MomentAutomationOverrides,
    ) {
        validateMomentScope(featureKind, scopeType, ownerId)
        // REPOST hides action in the editor, but historical action overrides remain data.
        if (scopeType != "GLOBAL" && rules.isEmpty(includeAction = true)) {
            deleteMomentAutomationRule(featureKind, scopeType, ownerId)
        } else {
            saveMomentAutomationRule(featureKind, scopeType, ownerId, rules)
        }
    }

    private suspend fun saveMomentAutomationRule(
        featureKind: String,
        scopeType: String,
        ownerId: String,
        rules: MomentAutomationOverrides,
    ) {
        upsert(rules.toEntity(featureKind, scopeType, ownerId))
        deleteMomentAutomationKeywords(featureKind, scopeType, ownerId)
        insertMomentAutomationKeywords(rules.keyword?.strings.orEmpty().mapIndexed { index, text ->
            MomentAutomationKeywordEntity(featureKind, scopeType, ownerId, index, text)
        })
        deleteMomentAutomationContentTypes(featureKind, scopeType, ownerId)
        insertMomentAutomationContentTypes(rules.contentType?.typeIds.orEmpty().map {
            MomentAutomationContentTypeEntity(featureKind, scopeType, ownerId, it)
        })
    }
}
