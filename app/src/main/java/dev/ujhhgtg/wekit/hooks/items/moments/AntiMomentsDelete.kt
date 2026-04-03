package dev.ujhhgtg.wekit.hooks.items.moments

import android.content.ContentValues
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.net.WeProtoData
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(
    path = "朋友圈/拦截朋友圈删除",
    desc = "拦截他人朋友圈删除并添加标记"
)
object AntiMomentsDelete : SwitchHookItem(), WeDatabaseListenerApi.IUpdateListener {

    private val TAG = nameOf(AntiMomentsDelete)
    private const val TBL_SNS_INFO = "SnsInfo"
    private const val DEFAULT_MARK = "[拦截删除]"

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
                val proto = WeProtoData.fromMessageBytes(contentBytes)

                if (appendWatermark(proto, 5)) {
                    values.put("content", proto.toMessageBytes())
                    WeLogger.i(TAG, "拦截成功：[$kindName] 已注入标记")
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
                if (currentVal.contains(DEFAULT_MARK)) {
                    return false
                }
                val newVal = "$DEFAULT_MARK $currentVal "
                proto.setLenUtf8(fieldNumber, 0, newVal)
                return true
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "注入标记失败", e)
        }
        return false
    }
}
