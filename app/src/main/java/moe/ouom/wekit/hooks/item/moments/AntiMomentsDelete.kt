package moe.ouom.wekit.hooks.item.moments

import android.content.ContentValues
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeDatabaseListener
import moe.ouom.wekit.utils.WeProtoData
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(
    path = "朋友圈/拦截朋友圈删除",
    desc = "移除删除标志并注入 '[拦截删除]' 标记"
)
object AntiMomentsDelete : BaseSwitchFunctionHookItem(), WeDatabaseListener.IUpdateListener {

    private const val LOG_TAG = "AntiMomentsDelete"
    private const val TBL_SNS_INFO = "SnsInfo"
    private const val DEFAULT_WATERMARK = "[拦截删除]"

    override fun onUpdate(table: String, values: ContentValues): Boolean {
        if (!isEnabled) return false

        try {
            when (table) {
                TBL_SNS_INFO -> handleSnsRecord(values)
            }
        } catch (ex: Throwable) {
            WeLogger.e(LOG_TAG, "拦截处理异常", ex)
        }
        return false
    }

    override fun entry(classLoader: ClassLoader) {
        WeDatabaseListener.addListener(this)
        WeLogger.i(LOG_TAG, "服务已启动 | 标记文本：'$DEFAULT_WATERMARK'")
    }

    override fun unload(classLoader: ClassLoader) {
        WeDatabaseListener.removeListener(this)
        WeLogger.i(LOG_TAG, "服务已停止")
    }

    private fun handleSnsRecord(values: ContentValues) {
        val typeVal = (values.get("type") as? Int) ?: return
        val sourceVal = (values.get("sourceType") as? Int) ?: return

        if (!MomentsContentType.allTypeIds.contains(typeVal)) return
        if (sourceVal != 0) return

        val kindName = MomentsContentType.fromId(typeVal)?.displayName ?: "Unknown[$typeVal]"
        WeLogger.d(LOG_TAG, "捕获删除信号 -> $kindName ($typeVal)")

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
                    WeLogger.i(LOG_TAG, ">> 拦截成功：[$kindName] 已注入标记")
                }
            } catch (e: Exception) {
                WeLogger.e(LOG_TAG, "朋友圈 Protobuf 处理失败", e)
            }
        }
    }

    private fun appendWatermark(proto: WeProtoData, fieldNumber: Int): Boolean {
        try {
            val json = proto.toJSON()
            val key = fieldNumber.toString()
            WeLogger.d(LOG_TAG, json.toString())

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
            WeLogger.e(LOG_TAG, "注入标记失败", e)
        }
        return false
    }
}