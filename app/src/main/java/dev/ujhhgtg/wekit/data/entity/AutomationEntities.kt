package dev.ujhhgtg.wekit.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/** Nullable enabled columns represent inheritance of an entire rule group. */
@Entity(
    tableName = "red_packet_rules",
    primaryKeys = ["scopeType", "talkerId", "memberId"],
)
data class RedPacketRuleEntity(
    val scopeType: String,
    val talkerId: String,
    val memberId: String,
    val grabEnabled: Boolean? = null,
    val grabSelfEnabled: Boolean? = null,
    val receiveMode: String? = null,
    val timeEnabled: Boolean? = null,
    val timeStartMinute: Int? = null,
    val timeEndMinute: Int? = null,
    val keywordEnabled: Boolean? = null,
    val keywordMode: String? = null,
    val keywordRegex: String? = null,
    val keywordIgnoreCase: Boolean? = null,
    val skipKeywordEnabled: Boolean? = null,
    val skipKeywordMode: String? = null,
    val skipKeywordRegex: String? = null,
    val skipKeywordIgnoreCase: Boolean? = null,
    val delayEnabled: Boolean? = null,
    val delayBaseMs: String? = null,
    val delayRandomRangeMs: String? = null,
    val notificationEnabled: Boolean? = null,
    val replyEnabled: Boolean? = null,
    val replyText: String? = null,
)

@Entity(
    tableName = "transfer_rules",
    primaryKeys = ["scopeType", "talkerId", "memberId"],
)
data class TransferRuleEntity(
    val scopeType: String,
    val talkerId: String,
    val memberId: String,
    val acceptEnabled: Boolean? = null,
    val timeEnabled: Boolean? = null,
    val timeStartMinute: Int? = null,
    val timeEndMinute: Int? = null,
    val amountEnabled: Boolean? = null,
    val amountMinimumYuan: String? = null,
    val amountMaximumYuan: String? = null,
    val memoKeywordEnabled: Boolean? = null,
    val memoKeywordMode: String? = null,
    val memoKeywordRegex: String? = null,
    val memoKeywordIgnoreCase: Boolean? = null,
    val delayEnabled: Boolean? = null,
    val delayBaseMs: String? = null,
    val delayRandomRangeMs: String? = null,
    val notificationEnabled: Boolean? = null,
    val replyEnabled: Boolean? = null,
    val replyText: String? = null,
)

@Entity(
    tableName = "moment_automation_rules",
    primaryKeys = ["featureKind", "scopeType", "ownerId"],
)
data class MomentAutomationRuleEntity(
    val featureKind: String,
    val scopeType: String,
    val ownerId: String,
    val processEnabled: Boolean? = null,
    val actionEnabled: Boolean? = null,
    val actionValue: String? = null,
    val modeEnabled: Boolean? = null,
    val modeValue: String? = null,
    val intervalEnabled: Boolean? = null,
    val intervalMilliseconds: String? = null,
    val timeEnabled: Boolean? = null,
    val timeStartMinute: Int? = null,
    val timeEndMinute: Int? = null,
    val keywordEnabled: Boolean? = null,
    val keywordMode: String? = null,
    val keywordRegex: String? = null,
    val keywordIgnoreCase: Boolean? = null,
    val contentTypeEnabled: Boolean? = null,
    val maximumAgeEnabled: Boolean? = null,
    val maximumAgeHours: String? = null,
)

@Entity(
    tableName = "red_packet_keywords",
    primaryKeys = ["scopeType", "talkerId", "memberId", "ruleKind", "position"],
    foreignKeys = [ForeignKey(
        entity = RedPacketRuleEntity::class,
        parentColumns = ["scopeType", "talkerId", "memberId"],
        childColumns = ["scopeType", "talkerId", "memberId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class RedPacketKeywordEntity(
    val scopeType: String,
    val talkerId: String,
    val memberId: String,
    val ruleKind: String,
    val position: Int,
    val text: String,
)

@Entity(
    tableName = "transfer_keywords",
    primaryKeys = ["scopeType", "talkerId", "memberId", "position"],
    foreignKeys = [ForeignKey(
        entity = TransferRuleEntity::class,
        parentColumns = ["scopeType", "talkerId", "memberId"],
        childColumns = ["scopeType", "talkerId", "memberId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TransferKeywordEntity(
    val scopeType: String,
    val talkerId: String,
    val memberId: String,
    val position: Int,
    val text: String,
)

@Entity(
    tableName = "moment_automation_keywords",
    primaryKeys = ["featureKind", "scopeType", "ownerId", "position"],
    foreignKeys = [ForeignKey(
        entity = MomentAutomationRuleEntity::class,
        parentColumns = ["featureKind", "scopeType", "ownerId"],
        childColumns = ["featureKind", "scopeType", "ownerId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MomentAutomationKeywordEntity(
    val featureKind: String,
    val scopeType: String,
    val ownerId: String,
    val position: Int,
    val text: String,
)

@Entity(
    tableName = "moment_automation_content_types",
    primaryKeys = ["featureKind", "scopeType", "ownerId", "typeId"],
    foreignKeys = [ForeignKey(
        entity = MomentAutomationRuleEntity::class,
        parentColumns = ["featureKind", "scopeType", "ownerId"],
        childColumns = ["featureKind", "scopeType", "ownerId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MomentAutomationContentTypeEntity(
    val featureKind: String,
    val scopeType: String,
    val ownerId: String,
    val typeId: Int,
)
