package dev.ujhhgtg.wekit.data.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "moment_custom_details")
data class MomentCustomDetailEntity(
    @PrimaryKey val snsId: String,
    val text: String,
)

@Serializable
@Entity(tableName = "feature_flag_overrides")
data class FeatureFlagOverrideEntity(
    @PrimaryKey val runtimeKey: String,
    val internalType: String,
    val rawValue: String,
) {
    @get:Ignore
    val value: Any
        get() = when (internalType) {
            "i" -> rawValue.toInt()
            "f" -> rawValue.toFloat()
            "l" -> rawValue.toLong()
            "s" -> rawValue
            else -> error("Unknown override type: $internalType")
        }
}

@Entity(tableName = "contact_real_name_parts", primaryKeys = ["wxId", "kind"])
data class ContactRealNamePartEntity(
    val wxId: String,
    val kind: String,
    val value: String,
)

@Entity(tableName = "real_name_scan_progress")
data class RealNameScanProgressEntity(
    @PrimaryKey val wxId: String,
    val resumeIndex: Int,
)

val simpleStructuredSchemaSql = listOf(
    "CREATE TABLE IF NOT EXISTS `moment_custom_details` (`snsId` TEXT NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`snsId`))",
    "CREATE TABLE IF NOT EXISTS `feature_flag_overrides` (`runtimeKey` TEXT NOT NULL, `internalType` TEXT NOT NULL, `rawValue` TEXT NOT NULL, PRIMARY KEY(`runtimeKey`))",
    "CREATE TABLE IF NOT EXISTS `contact_real_name_parts` (`wxId` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`wxId`, `kind`))",
    "CREATE TABLE IF NOT EXISTS `real_name_scan_progress` (`wxId` TEXT NOT NULL, `resumeIndex` INTEGER NOT NULL, PRIMARY KEY(`wxId`))",
)
