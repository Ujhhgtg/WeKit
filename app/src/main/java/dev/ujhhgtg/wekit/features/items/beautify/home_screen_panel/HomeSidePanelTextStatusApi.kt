package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Method

internal class HomeSidePanelTextStatusApi(
    private val serviceClass: Class<*>,
    private val storageAccessor: Method,
    private val latestStatusMethod: Method,
    private val recordClass: Class<*>,
) : HomeSidePanelTextStatusReader {

    private val serviceInstance by lazy {
        serviceClass.reflekt().firstField {
            type = serviceClass
            modifiers(Modifiers.STATIC)
        }.getStatic()!!
    }

    override fun read(wxId: String): HomeSidePanelStatusUiState {
        return runCatching {
            val storage = storageAccessor.invoke(serviceInstance)!!
            val value = latestStatusMethod.invoke(storage, wxId) ?: return HomeSidePanelStatusUiState.NoStatus
            val record = unwrapStatusRecord(value)
            val state = mapStatusRecord(
                StatusRecordValues(
                    statusId = record.reflekt().getField("field_StatusID", true) as String?,
                    description = record.reflekt().getField("field_Description", true) as String?,
                    iconId = record.reflekt().getField("field_IconID", true) as String?,
                    expireTime = (record.reflekt().getField("field_ExpireTime", true) as Number).toLong(),
                    emojiInfo = record.reflekt().getField("field_EmojiInfo", true) as ByteArray?,
                ),
            )
            enrichEmojiUrls(state)
        }.getOrElse { throwable ->
            WeLogger.e(TAG, "failed to read current TextStatus", throwable)
            HomeSidePanelStatusUiState.Error("获取失败")
        }
    }

    private fun unwrapStatusRecord(value: Any): Any {
        if (recordClass.isInstance(value)) return value
        return value.reflekt().fields { superclass = true }
            .first { recordClass.isAssignableFrom(it.type) }
            .get()!!
    }

    private fun enrichEmojiUrls(state: HomeSidePanelStatusUiState): HomeSidePanelStatusUiState {
        if (state !is HomeSidePanelStatusUiState.Ready) return state
        val emoji = state.status.emoji ?: return state
        val md5 = emoji.md5 ?: return state
        if (!emoji.url.isNullOrBlank() || !emoji.thumbUrl.isNullOrBlank()) return state

        val fallback = runCatching {
            WeServiceApi.getEmojiInfoByMd5(md5).reflekt().let { info ->
                val thumbUrl = info.getField("field_thumbUrl", true) as String?
                val cdnUrl = info.getField("field_cdnUrl", true) as String?
                emoji.copy(
                    url = cdnUrl?.ifBlank { null },
                    thumbUrl = thumbUrl?.ifBlank { null },
                )
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to resolve TextStatus emoji URL for $md5", it)
        }.getOrNull() ?: return state

        return HomeSidePanelStatusUiState.Ready(state.status.copy(emoji = fallback))
    }

    private companion object {
        const val TAG = "HomeSidePanelTextStatusApi"
    }
}
