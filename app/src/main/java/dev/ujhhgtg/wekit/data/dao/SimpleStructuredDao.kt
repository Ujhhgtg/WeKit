package dev.ujhhgtg.wekit.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.ujhhgtg.wekit.data.entity.ContactRealNamePartEntity
import dev.ujhhgtg.wekit.data.entity.FeatureFlagOverrideEntity
import dev.ujhhgtg.wekit.data.entity.MomentCustomDetailEntity
import dev.ujhhgtg.wekit.data.entity.RealNameScanProgressEntity

@Dao
interface SimpleStructuredDao {
    @Query("SELECT * FROM moment_custom_details")
    suspend fun getCustomDetails(): List<MomentCustomDetailEntity>

    @Upsert
    suspend fun putCustomDetail(value: MomentCustomDetailEntity)

    @Query("DELETE FROM moment_custom_details WHERE snsId = :snsId")
    suspend fun removeCustomDetail(snsId: String)

    @Query("SELECT * FROM feature_flag_overrides")
    suspend fun getFlagOverrides(): List<FeatureFlagOverrideEntity>

    @Upsert
    suspend fun putFlagOverride(value: FeatureFlagOverrideEntity)

    @Query("DELETE FROM feature_flag_overrides WHERE runtimeKey = :runtimeKey")
    suspend fun removeFlagOverride(runtimeKey: String)

    @Query("SELECT * FROM contact_real_name_parts WHERE kind = :kind")
    suspend fun getRealNameParts(kind: String): List<ContactRealNamePartEntity>

    @Upsert
    suspend fun putRealNamePart(value: ContactRealNamePartEntity)

    @Query("SELECT * FROM real_name_scan_progress")
    suspend fun getScanProgress(): List<RealNameScanProgressEntity>

    @Upsert
    suspend fun putScanProgress(value: RealNameScanProgressEntity)

    @Query("DELETE FROM real_name_scan_progress WHERE wxId = :wxId")
    suspend fun removeScanProgress(wxId: String)

    @Transaction
    suspend fun recordFirstNameAndClearProgress(wxId: String, value: String) {
        putRealNamePart(ContactRealNamePartEntity(wxId, "FIRST", value))
        removeScanProgress(wxId)
    }
}
