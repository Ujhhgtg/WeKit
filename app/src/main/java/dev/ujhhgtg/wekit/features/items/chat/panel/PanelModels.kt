package dev.ujhhgtg.wekit.features.items.chat.panel

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

enum class PanelSource {
    LOCAL,
    RECENT,
    SHARED,
    ONLINE,
    IMPORTED,
}

@Serializable
data class StickerItem(
    val id: String,
    val title: String = "",
    val customTitle: String? = null,
    val localPath: String? = null,
    val remoteObjectId: String? = null,
    val thumbnailUrl: String? = null,
    val imageUrl: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val packId: String = "",
    val sendCount: Long = 0,
    val lastSentAt: Long = 0,
)

@Serializable
data class StickerPack(
    val id: String,
    val title: String,
    val cover: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val onlineSourcePackId: String? = null,
    val order: Int = 0,
    val itemCount: Int = 0,
    val badge: String? = null,
    val uploadTime: Long = 0,
    val downloadCount: Int = 0,
    val items: List<StickerItem> = emptyList(),
)

@Serializable
data class VoiceItem(
    val id: String,
    val title: String,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val remoteObjectId: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val packId: String = "",
    val durationMs: Long = 0,
    val format: String = "",
    val sendCount: Long = 0,
    val lastSentAt: Long = 0,
    val isContainer: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class VoicePack(
    val id: String,
    val title: String,
    val cover: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val order: Int = 0,
    val itemCount: Int = 0,
    val badge: String? = null,
    val items: List<VoiceItem> = emptyList(),
)

@Serializable
data class CloneVoice(
    val id: String,
    val name: String,
    val fileName: String,
)

data class CloneExample(
    val group: String,
    val fileName: String,
) {
    val title: String get() = fileName.substringBeforeLast('.').ifBlank { fileName }
}

data class VoiceProviderPage(
    val items: List<VoiceItem>,
    val page: Int,
    val hasMore: Boolean,
)

data class VoicePreview(
    val path: String,
    val temporary: Boolean,
)

sealed interface PanelUiText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : PanelUiText

    data class Raw(val value: String) : PanelUiText
}

fun panelUiText(@StringRes id: Int, vararg args: Any): PanelUiText =
    PanelUiText.Resource(id, args.toList())

fun Throwable.toPanelUiText(@StringRes fallbackId: Int, vararg fallbackArgs: Any): PanelUiText =
    message?.let(PanelUiText::Raw) ?: panelUiText(fallbackId, *fallbackArgs)

sealed interface PanelUiState<out T> {
    data object Loading : PanelUiState<Nothing>
    data class Content<T>(val value: T) : PanelUiState<T>
    data class Empty(val message: PanelUiText) : PanelUiState<Nothing>
    data class Error(val message: PanelUiText) : PanelUiState<Nothing>
}

enum class StickerDestination {
    RECENT,
    SEARCH,
    PACKS,
    ONLINE,
    ONLINE_SEARCH,
    SETTINGS,
}

enum class StickerPackLayout {
    TABS,
    GRID,
    LIST,
}

enum class VoicePackLayout {
    TABS,
    LIST,
}

enum class LocalSortMode {
    NAME,
    MODIFIED,
    RECENT,
    FREQUENT,
    CUSTOM;

    fun next(): LocalSortMode = entries[(ordinal + 1) % entries.size]
}

enum class VoiceDestination {
    RECENT,
    SEARCH,
    LOCAL,
    TTS,
    ONLINE,
    ONLINE_SEARCH,
    SHARED,
    SETTINGS,
}

const val RECENT_PACK_ID = "!!recent:data_!!"
