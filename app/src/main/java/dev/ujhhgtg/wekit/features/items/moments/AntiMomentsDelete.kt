package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.net.WeProtoData
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

object AntiMomentsDelete : SwitchFeature(), WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "朋友圈防撤回"
    override val nameRes = R.string.feature_anti_moments_delete_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_anti_moments_delete_description

    private const val TAG = "AntiMomentsDelete"
    private const val TBL_SNS_INFO = "SnsInfo"
    const val INTERCEPT_MARKER = "[拦截删除]"

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        try {
            when (table) {
                TBL_SNS_INFO -> handleSnsRecord(values)
            }
        } catch (ex: Throwable) {
            WeLogger.e(TAG, "拦截处理异常", ex)
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    private fun handleSnsRecord(values: ContentValues) {
        val typeVal = values.get("type") as? Int ?: return
        val sourceVal = values.get("sourceType") as? Int ?: return

        if (!MomentsContentType.allTypeIds.contains(typeVal)) return
        if (sourceVal != 0) return

        val kindName = MomentsContentType.fromId(typeVal)?.name ?: "Unknown[$typeVal]"

        // 移除来源
        values.remove("sourceType")

        // 注入水印
        val contentBytes = values.getAsByteArray("content")
        if (contentBytes != null) {
            try {
                val proto = WeProtoData.fromMessageBytes(contentBytes)

                if (appendWatermark(proto, 5)) {
                    values.put("content", proto.toMessageBytes())
                    WeLogger.i(TAG, "intercepted: [$kindName], marker injected")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to handle moments protobuf", e)
            }
        }
    }

    private fun appendWatermark(proto: WeProtoData, fieldNumber: Int): Boolean {
        try {
            val json = proto.toJsonObject()
            val key = fieldNumber.toString()

            if (!json.has(key)) return false

            val currentVal = json.get(key)

            if (currentVal is String) {
                if (currentVal.contains(INTERCEPT_MARKER)) {
                    return false
                }
                val newVal = "$INTERCEPT_MARKER $currentVal "
                proto.setLenUtf8(fieldNumber, 0, newVal)
                return true
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "注入标记失败", e)
        }
        return false
    }
}
