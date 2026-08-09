package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Folder
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.panel.RECENT_PACK_ID
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerPanelRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.VoicePanelRepository
import dev.ujhhgtg.wekit.ui.panel.PanelPackChoice
import dev.ujhhgtg.wekit.ui.panel.showPanelPackPicker
import dev.ujhhgtg.wekit.ui.utils.DownloadIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.name

@Feature(
    id = "保存到面板",
    nameRes = "feature_save_to_panel_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_save_to_panel_description",
)
object SaveToPanel : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "SaveToPanel"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = 777024,
            text = "保存到面板",
            drawable = DownloadIcon,
            imageVector = MaterialSymbols.Outlined.Download,
            isSupported = ::isSupportedMessage,
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                isSupported = { messages ->
                    messages.isNotEmpty() && messages.all(::isSupportedMessage)
                },
                onClick = { view, _, messages ->
                    beginSave(view.context, messages)
                },
            ),
        ) { view, _, message ->
            beginSave(view.context, listOf(message))
        },
    )

    private fun isSupportedMessage(message: MessageInfo): Boolean =
        message.type?.isSticker == true || message.type == MessageType.VOICE

    private class PendingSave(
        val context: Context,
        val messages: List<MessageInfo>,
    ) {
        val stickerMessages = messages.filter { it.type?.isSticker == true }
        val voiceMessages = messages.filter { it.type == MessageType.VOICE }
        var stickerPackId: String? = null
        var voicePackId: String? = null
    }

    private fun beginSave(context: Context, messages: List<MessageInfo>) {
        val pending = PendingSave(context, messages)
        chooseNextPack(pending)
    }

    /** Runs the two pack choices in order, then saves the original message list by type. */
    private fun chooseNextPack(pending: PendingSave) {
        when {
            pending.stickerMessages.isNotEmpty() && pending.stickerPackId == null ->
                chooseStickerPack(pending)

            pending.voiceMessages.isNotEmpty() && pending.voicePackId == null ->
                chooseVoicePack(pending)

            else -> saveMessages(pending)
        }
    }

    private fun chooseStickerPack(pending: PendingSave) {
        CoroutineScope(Dispatchers.Main).launch {
            val packs = withContext(Dispatchers.IO) { StickerPanelRepository.loadPacks() }
            showPanelPackPicker(
                context = pending.context,
                title = "选择表情包",
                createLabel = "新建表情包",
                itemCountLabel = { "$it 张表情" },
                packIcon = MaterialSymbols.Outlined.Folder,
                packs = packs.map { PanelPackChoice(it.id, it.title, it.itemCount) },
                onCreatePack = { name ->
                    withContext(Dispatchers.IO) { StickerPanelRepository.createPack(name) }
                },
                onSelect = { packId ->
                    pending.stickerPackId = packId
                    chooseNextPack(pending)
                },
            )
        }
    }

    private fun chooseVoicePack(pending: PendingSave) {
        CoroutineScope(Dispatchers.Main).launch {
            val packs = withContext(Dispatchers.IO) {
                VoicePanelRepository.loadPacks().filter { it.id != RECENT_PACK_ID }
            }
            showPanelPackPicker(
                context = pending.context,
                title = "选择语音包",
                createLabel = "新建语音包",
                itemCountLabel = { "$it 条语音" },
                packIcon = MaterialSymbols.Outlined.Folder,
                packs = packs.map { PanelPackChoice(it.id, it.title, it.itemCount) },
                onCreatePack = { name ->
                    withContext(Dispatchers.IO) { VoicePanelRepository.createPack(name) }
                },
                onSelect = { packId ->
                    pending.voicePackId = packId
                    chooseNextPack(pending)
                },
            )
        }
    }

    private fun saveMessages(pending: PendingSave) {
        CoroutineScope(Dispatchers.IO).launch {
            var succeeded = 0
            pending.messages.forEach { message ->
                val saved = when {
                    message.type?.isSticker == true ->
                        pending.stickerPackId?.let { saveSticker(message, it) } == true

                    message.type == MessageType.VOICE ->
                        pending.voicePackId?.let { saveVoice(message, it) } == true

                    else -> false
                }
                if (saved) succeeded++
            }
            showToastSuspend(pending.context, summary(pending, succeeded))
        }
    }

    private fun summary(pending: PendingSave, succeeded: Int): String {
        if (pending.messages.size == 1) {
            return when {
                succeeded == 1 && pending.stickerPackId != null ->
                    "已保存贴纸到「${pending.stickerPackId}」"

                succeeded == 1 && pending.voicePackId != null ->
                    "已保存语音到「${pending.voicePackId}」"

                pending.stickerMessages.isNotEmpty() -> "贴纸保存失败! 查看日志以了解错误详情"
                else -> "语音保存失败! 查看日志以了解错误详情"
            }
        }
        if (pending.stickerMessages.isNotEmpty() && pending.voiceMessages.isNotEmpty()) {
            return "已保存 $succeeded/${pending.messages.size} 条消息到面板"
        }
        return if (pending.stickerMessages.isNotEmpty()) {
            "已保存 $succeeded/${pending.messages.size} 张贴纸到「${pending.stickerPackId}」"
        } else {
            "已保存 $succeeded/${pending.messages.size} 条语音到「${pending.voicePackId}」"
        }
    }

    private fun saveSticker(message: MessageInfo, packId: String): Boolean {
        val cachedPath = WeMessageApi.cacheAndSaveSticker(message.serverId) ?: run {
            WeLogger.e(TAG, "cacheAndSaveSticker failed for svrId=${message.serverId}")
            return false
        }
        val source = cachedPath.asPath
        return runCatching {
            Files.newInputStream(source).use { input ->
                StickerPanelRepository.importSticker(packId, source.name, input).getOrThrow()
            }
            true
        }.getOrElse { error ->
            WeLogger.e(TAG, "save sticker failed: pack=$packId", error)
            false
        }
    }

    private fun saveVoice(message: MessageInfo, packId: String): Boolean {
        val encPath = message.imagePath ?: run {
            WeLogger.e(TAG, "voice imagePath is null")
            return false
        }
        val mp3Path = WeMessageApi.saveVoiceByEncPath(encPath) ?: run {
            WeLogger.e(TAG, "saveVoiceByEncPath failed for encPath=$encPath")
            return false
        }
        val source = mp3Path.asPath
        return runCatching {
            Files.newInputStream(source).use { input ->
                VoicePanelRepository.importVoice(packId, source.name, input).getOrThrow()
            }
            true
        }.getOrElse { error ->
            WeLogger.e(TAG, "save voice failed: pack=$packId", error)
            false
        }
    }
}
