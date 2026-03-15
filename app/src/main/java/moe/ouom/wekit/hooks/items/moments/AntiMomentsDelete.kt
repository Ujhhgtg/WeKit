package moe.ouom.wekit.hooks.items.moments

import android.content.ContentValues
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.WeDatabaseListenerApi
import moe.ouom.wekit.hooks.api.net.WeProtoData
import moe.ouom.wekit.utils.logging.WeLogger

@HookItem(
    path = "朋友圈/拦截朋友圈删除",
    desc = "移除删除标志并注入 '[拦截删除]' 标记"
)
object AntiMomentsDelete : SwitchHookItem(), WeDatabaseListenerApi.IUpdateListener {

    private val TAG = nameof(AntiMomentsDelete)
    private const val TBL_SNS_INFO = "SnsInfo"
    private const val DEFAULT_WATERMARK = "[拦截删除]"

    override fun onUpdate(table: String, values: ContentValues): Boolean {
        if (!isEnabled) return false

        try {
            when (table) {
                TBL_SNS_INFO -> handleSnsRecord(values)
            }
        } catch (ex: Throwable) {
            WeLogger.e(TAG, "拦截处理异常", ex)
        }
        return false
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    private fun handleSnsRecord(values: ContentValues) {
        val typeVal = (values.get("type") as? Int) ?: return
        val sourceVal = (values.get("sourceType") as? Int) ?: return

        if (!MomentsContentType.allTypeIds.contains(typeVal)) return
        if (sourceVal != 0) return

        val kindName = MomentsContentType.fromId(typeVal)?.displayName ?: "Unknown[$typeVal]"

        // 移除来源
        values.remove("sourceType")

        // 注入水印
        val contentBytes = values.getAsByteArray("content")
        if (contentBytes != null) {
            try {
                val proto = WeProtoData()
                proto.fromMessageBytes(contentBytes)

                if (appendWatermark(proto, 5)) {
                    values.put("content", proto.toMessageBytes())
                    WeLogger.i(TAG, ">> 拦截成功：[$kindName] 已注入标记")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "朋友圈 Protobuf 处理失败", e)
            }
        }
    }

    private fun appendWatermark(proto: WeProtoData, fieldNumber: Int): Boolean {
        try {
            val json = proto.toJsonObject()
            val key = fieldNumber.toString()
            WeLogger.d(TAG, json.toString())

            if (!json.has(key)) return false

            val currentVal = json.get(key)

            if (currentVal is String) {
                if (currentVal.contains(DEFAULT_WATERMARK)) {
                    return false
                }
                val newVal = "$DEFAULT_WATERMARK $currentVal "
                proto.setLenUtf8(fieldNumber, 0, newVal)
                return true
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "注入标记失败", e)
        }
        return false
    }
}