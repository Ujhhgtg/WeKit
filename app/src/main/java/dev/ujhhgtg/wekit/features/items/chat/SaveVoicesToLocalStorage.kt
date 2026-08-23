package dev.ujhhgtg.wekit.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.DownloadIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SaveVoicesToLocalStorage : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "语音保存到本地"
    override val nameRes = R.string.feature_save_voices_to_local_storage_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_save_voices_to_local_storage_description

    private const val TAG = "SaveVoicesToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777003,
                localizedChatString(R.string.chat_action_save_locally),
                DownloadIcon,
                MaterialSymbols.Outlined.Download,
                { msgInfo -> msgInfo.typeCode == MessageType.VOICE.code },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgs ->
                        msgs.isNotEmpty() && msgs.all { it.typeCode == MessageType.VOICE.code }
                    },
                    onClick = { view, _, msgs ->
                        CoroutineScope(Dispatchers.IO).launch {
                            var succeeded = 0
                            msgs.forEach { if (saveVoice(it) != null) succeeded++ }
                            showToastSuspend(
                                view.context,
                                view.context.localizedChatQuantity(
                                    R.plurals.chat_voices_saved_locally,
                                    msgs.size,
                                    succeeded,
                                    msgs.size,
                                ),
                            )
                        }
                    },
                )
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val path = saveVoice(msgInfo)
                    showToastSuspend(
                        view.context,
                        path?.let { view.context.localizedChatString(R.string.chat_voice_saved_to, it) }
                            ?: view.context.localizedChatString(R.string.chat_voice_save_failed),
                    )
                }
            }
        )
    }

    private suspend fun saveVoice(msgInfo: MessageInfo): String? {
        val encPath = msgInfo.imagePath ?: run {
            WeLogger.e(TAG, "voice imagePath is null")
            return null
        }
        return WeMessageApi.saveVoiceByEncPath(encPath) ?: run {
            WeLogger.e(TAG, "failed to save voice encPath=$encPath")
            null
        }
    }
}
