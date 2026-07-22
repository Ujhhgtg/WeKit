package dev.ujhhgtg.wekit.ui.panel

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Image_search
import com.composables.icons.materialsymbols.outlined.Manage_search
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Sort
import com.composables.icons.materialsymbols.outlined.Travel_explore
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Upload_file
import dev.ujhhgtg.wekit.features.items.chat.panel.LocalSortMode
import dev.ujhhgtg.wekit.features.items.chat.panel.PANEL_BULK_DOWNLOAD_CONCURRENCY
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSource
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelUiState
import dev.ujhhgtg.wekit.features.items.chat.panel.RECENT_PACK_ID
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerDestination
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerItem
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerPack
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerPackLayout
import dev.ujhhgtg.wekit.features.items.chat.panel.parallelForEachWithProgress
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerImportPhase
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerImportProgress
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerImportResult
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerPackRepository
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

data class StickerPanelActions(
    val reloadLocal: suspend () -> List<StickerPack> = { emptyList() },
    val importSticker: (
        packId: String,
        mode: StickerImportMode,
        onStarted: () -> Unit,
        onComplete: (Result<Unit>) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
    val importTelegramStickerSet: suspend (
        value: String,
        onProgress: suspend (TelegramStickerImportProgress) -> Unit,
    ) -> Result<TelegramStickerImportResult> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val createPack: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val renamePack: suspend (String, String) -> Result<Unit> = { _, _ -> Result.failure(UnsupportedOperationException()) },
    val deletePack: suspend (String) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val loadOnlinePacks: suspend () -> Result<List<StickerPack>> = { Result.success(emptyList()) },
    val loadMyUploads: suspend () -> Result<List<StickerPack>> = { Result.success(emptyList()) },
    val loadOnlineItems: suspend (StickerPack) -> Result<List<StickerItem>> = { Result.success(emptyList()) },
    val searchOnline: suspend (String) -> Result<List<StickerItem>> = { Result.success(emptyList()) },
    val pickSimilarityImage: ((Result<ByteArray>) -> Unit) -> Unit = {},
    val loadSimilarityImage: suspend (StickerItem) -> Result<ByteArray> = {
        Result.failure(UnsupportedOperationException())
    },
    val searchSimilar: suspend (ByteArray) -> Result<List<StickerItem>> = {
        Result.failure(UnsupportedOperationException())
    },
    val uploadPack: suspend (StickerPack, (Float) -> Unit) -> Result<String> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val setCustomTitle: suspend (String, String) -> Result<Unit> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val setPackCover: suspend (String) -> Result<Unit> = {
        Result.failure(UnsupportedOperationException())
    },
    val deleteSticker: suspend (String) -> Result<Unit> = {
        Result.failure(UnsupportedOperationException())
    },
    val deleteStickers: suspend (List<String>) -> Result<Int> = {
        Result.failure(UnsupportedOperationException())
    },
    val savePackOrder: suspend (List<String>) -> Result<Unit> = {
        Result.failure(UnsupportedOperationException())
    },
    val saveItemOrder: suspend (String, List<String>) -> Result<Unit> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val ensurePack: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val saveOnlineSticker: suspend (String, StickerItem) -> Result<Unit> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
)

enum class StickerImportMode {
    MULTIPLE_FILES,
    DIRECTORY,
    TELEGRAM,
}

private const val SIMILARITY_SEARCH_PRIVACY_MESSAGE =
    "所选图片的完整内容将上传至第三方 FunBox API。图片可能包含个人信息或其他隐私内容，确定继续吗？"

fun showStickerPanelSheet(
    context: Context,
    actions: StickerPanelActions = StickerPanelActions(),
    onSend: suspend (StickerItem) -> Result<Unit>,
) {
    showPanelDialog(context) {
        StickerPanelContent(
            actions = actions,
            onSend = onSend,
            onDismiss = ::dismiss,
        )
    }
}

private sealed interface StickerPrompt {
    data object CreatePack : StickerPrompt
    data class Import(val pack: StickerPack?) : StickerPrompt
    data class UploadPack(val pack: StickerPack) : StickerPrompt
    data class RenamePack(val pack: StickerPack) : StickerPrompt
    data class DeletePack(val pack: StickerPack) : StickerPrompt
    data class SetStickerTitle(val item: StickerItem) : StickerPrompt
    data class DeleteSticker(val item: StickerItem) : StickerPrompt
    data class DeleteStickers(val items: List<StickerItem>) : StickerPrompt
    data class ConfirmSimilaritySticker(val item: StickerItem) : StickerPrompt
    class ConfirmSimilarityBytes(val bytes: ByteArray) : StickerPrompt
}

private enum class StickerReorderTarget {
    PACKS,
    ITEMS,
}

@Composable
private fun StickerPanelContent(
    actions: StickerPanelActions,
    onSend: suspend (StickerItem) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localPacks by remember { mutableStateOf<List<StickerPack>>(emptyList()) }
    var localState by remember { mutableStateOf<PanelUiState<Unit>>(PanelUiState.Loading) }
    var destination by remember {
        mutableStateOf(
            StickerDestination.entries.firstOrNull { it.name == PanelSettings.stickerLastDestination }
                ?: StickerDestination.RECENT,
        )
    }
    var selectedPackId by remember {
        mutableStateOf<String?>(null)
    }
    var localPackDetailId by remember { mutableStateOf<String?>(null) }
    var localPackLayout by remember { mutableStateOf(PanelSettings.localStickerPackLayout) }
    var onlinePackLayout by remember { mutableStateOf(PanelSettings.onlineStickerPackLayout) }
    var wrapActions by remember { mutableStateOf(PanelSettings.wrapPanelActions) }
    var query by remember { mutableStateOf("") }
    var localPackFilterQuery by remember { mutableStateOf("") }
    var localPackFilterExpanded by remember { mutableStateOf(false) }
    var onlineQuery by remember { mutableStateOf("") }
    var onlinePackQuery by remember { mutableStateOf("") }
    var onlinePackSearchExpanded by remember { mutableStateOf(false) }
    var onlinePacksState by remember { mutableStateOf<PanelUiState<List<StickerPack>>>(PanelUiState.Loading) }
    var onlinePacksRequest by remember { mutableIntStateOf(0) }
    var myUploadsState by remember { mutableStateOf<PanelUiState<List<StickerPack>>>(PanelUiState.Loading) }
    var myUploadsRequest by remember { mutableIntStateOf(0) }
    var showingMyUploads by remember { mutableStateOf(false) }
    var selectedOnlinePackId by remember { mutableStateOf<String?>(null) }
    var onlineItemsState by remember { mutableStateOf<PanelUiState<List<StickerItem>>>(PanelUiState.Empty("选择一个在线表情包")) }
    var onlineItemsRequest by remember { mutableIntStateOf(0) }
    var searchState by remember { mutableStateOf<PanelUiState<List<StickerItem>>>(PanelUiState.Empty("输入关键词搜索在线表情")) }
    var searchRequest by remember { mutableIntStateOf(0) }
    var similaritySearchActive by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<StickerPrompt?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    var telegramNamePrompt by remember { mutableStateOf(false) }
    var telegramProgress by remember { mutableStateOf<TelegramStickerImportProgress?>(null) }
    var telegramImportJob by remember { mutableStateOf<Job?>(null) }
    var uploadProgress by remember { mutableStateOf<Float?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedStickerKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var quickSelectionBase by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlineSaveProgress by remember { mutableStateOf<PanelSaveProgress?>(null) }
    var onlineSaveJob by remember { mutableStateOf<Job?>(null) }
    var sending by remember { mutableStateOf(false) }
    var previewSticker by remember { mutableStateOf<StickerItem?>(null) }
    var recentMostUsed by remember { mutableStateOf(PanelSettings.stickerRecentSortMode == 1) }
    var onlineSortMode by remember { mutableIntStateOf(PanelSettings.onlineStickerSortMode.coerceIn(0, 2)) }
    var localPackSortMode by remember { mutableStateOf(PanelSettings.stickerPackSortMode) }
    var localItemSortMode by remember { mutableStateOf(PanelSettings.stickerItemSortMode) }
    var reorderTarget by remember { mutableStateOf<StickerReorderTarget?>(null) }
    var reorderPackId by remember { mutableStateOf<String?>(null) }
    var reorderKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var localRequest by remember { mutableIntStateOf(0) }
    val localPackGridState = rememberLazyGridState()
    val localPackListState = rememberLazyListState()
    val localItemGridState = rememberLazyGridState()
    val onlinePackGridState = rememberLazyGridState()
    val onlinePackListState = rememberLazyListState()
    val onlineItemGridState = rememberLazyGridState()

    fun refreshLocal() {
        val request = ++localRequest
        val showFullLoadingState = localState !is PanelUiState.Content
        if (showFullLoadingState) localState = PanelUiState.Loading
        scope.launch {
            try {
                val packs = withContext(Dispatchers.IO) { actions.reloadLocal() }
                if (request != localRequest) return@launch
                localPacks = packs
                localState = PanelUiState.Content(Unit)
                if (selectedPackId !in localPacks.map { it.id }) {
                    selectedPackId = localPacks.firstOrNull { it.id != RECENT_PACK_ID }?.id
                }
                if (localPackDetailId !in localPacks.map { it.id }) {
                    localPackDetailId = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (request != localRequest) return@launch
                if (showFullLoadingState) {
                    localState = PanelUiState.Error(error.message ?: "本地表情加载失败")
                } else {
                    operationMessage = error.message ?: "本地表情刷新失败"
                }
            }
        }
    }

    fun loadOnlinePacks() {
        val request = ++onlinePacksRequest
        onlinePacksState = PanelUiState.Loading
        scope.launch {
            val result = actions.loadOnlinePacks()
            if (request != onlinePacksRequest) return@launch
            onlinePacksState = result.fold(
                onSuccess = {
                    if (selectedOnlinePackId !in it.map(StickerPack::id)) {
                        selectedOnlinePackId = null
                        onlineItemsRequest++
                        onlineItemsState = PanelUiState.Empty("选择一个在线表情包")
                    }
                    if (it.isEmpty()) PanelUiState.Empty("暂无在线表情包") else PanelUiState.Content(it)
                },
                onFailure = { PanelUiState.Error(it.message ?: "在线表情包加载失败") },
            )
        }
    }

    fun loadMyUploads() {
        val request = ++myUploadsRequest
        myUploadsState = PanelUiState.Loading
        scope.launch {
            val result = actions.loadMyUploads()
            if (request != myUploadsRequest || !showingMyUploads) return@launch
            myUploadsState = result.fold(
                onSuccess = { if (it.isEmpty()) PanelUiState.Empty("还没有上传表情包") else PanelUiState.Content(it) },
                onFailure = { PanelUiState.Error(it.message ?: "我的上传加载失败") },
            )
        }
    }

    fun loadOnlinePack(pack: StickerPack) {
        val request = ++onlineItemsRequest
        onlineItemsState = PanelUiState.Loading
        scope.launch {
            val result = actions.loadOnlineItems(pack)
            if (request != onlineItemsRequest || selectedOnlinePackId != pack.id) return@launch
            onlineItemsState = result.fold(
                {
                    val unique = it.distinctBy(::stickerSelectionKey)
                    if (unique.isEmpty()) PanelUiState.Empty("这个表情包还是空的") else PanelUiState.Content(unique)
                },
                { PanelUiState.Error(it.message ?: "表情加载失败") },
            )
        }
    }

    fun stopOnlineSave() {
        onlineSaveJob?.cancel()
        onlineSaveJob = null
        onlineSaveProgress = null
    }

    fun startOnlineSave(packId: String, items: List<StickerItem>, title: String = "正在保存表情包") {
        val uniqueItems = items.distinctBy(::stickerSelectionKey)
        if (uniqueItems.isEmpty()) return
        stopOnlineSave()
        multiSelectMode = false
        selectedStickerKeys = emptySet()
        onlineSaveProgress = PanelSaveProgress(title, uniqueItems.size)
        onlineSaveJob = scope.launch {
            var succeeded = 0
            var failed = 0
            try {
                uniqueItems.parallelForEachWithProgress(
                    maxConcurrency = PANEL_BULK_DOWNLOAD_CONCURRENCY,
                    transform = { item -> actions.saveOnlineSticker(packId, item) },
                    onItemComplete = { _, total, _, result ->
                        if (result.isSuccess) succeeded++ else failed++
                        onlineSaveProgress = PanelSaveProgress(title, total, succeeded, failed)
                    },
                )
            } finally {
                onlineSaveProgress = null
                onlineSaveJob = null
            }
            refreshLocal()
            operationMessage = if (failed == 0) {
                "已保存 $succeeded 个表情"
            } else {
                "保存完成：成功 $succeeded 个，失败 $failed 个"
            }
        }
    }

    fun send(item: StickerItem) {
        if (sending) return
        sending = true
        scope.launch {
            val result = onSend(item)
            sending = false
            showToastSuspend(context, result.exceptionOrNull()?.message ?: "表情发送成功")
            if (result.isSuccess) {
                refreshLocal()
                if (PanelSettings.panelAutoClose) onDismiss()
            }
        }
    }

    fun searchSimilar(loadImage: suspend () -> Result<ByteArray>) {
        onlineQuery = ""
        similaritySearchActive = true
        destination = StickerDestination.ONLINE_SEARCH
        val request = ++searchRequest
        searchState = PanelUiState.Loading
        scope.launch {
            val imageBytes = loadImage().getOrElse { error ->
                if (request == searchRequest) {
                    searchState = PanelUiState.Error(error.message ?: "无法读取搜索图片")
                }
                return@launch
            }
            if (request != searchRequest) return@launch
            val result = actions.searchSimilar(imageBytes)
            if (request != searchRequest) return@launch
            searchState = result.fold(
                { if (it.isEmpty()) PanelUiState.Empty("没有找到相似表情") else PanelUiState.Content(it) },
                { PanelUiState.Error(it.message ?: "相似表情搜索失败") },
            )
        }
    }

    fun clearSimilaritySearch() {
        searchRequest++
        similaritySearchActive = false
        onlineQuery = ""
        searchState = PanelUiState.Empty("输入关键词搜索在线表情")
    }

    LaunchedEffect(operationMessage) {
        val message = operationMessage ?: return@LaunchedEffect
        showToastSuspend(context, message)
        operationMessage = null
    }

    LaunchedEffect(Unit) {
        refreshLocal()
    }

    LaunchedEffect(destination) {
        PanelSettings.stickerLastDestination = destination.name
        if (destination == StickerDestination.ONLINE && onlinePacksState == PanelUiState.Loading) {
            loadOnlinePacks()
        }
    }

    val recent = localPacks.firstOrNull { it.id == RECENT_PACK_ID }
    val editablePacks = localPacks.filter { it.id != RECENT_PACK_ID }
    val selectedPack = editablePacks.firstOrNull { it.id == selectedPackId } ?: editablePacks.firstOrNull()
    val localDetailPack = if (localPackLayout == StickerPackLayout.TABS) null
    else editablePacks.firstOrNull { it.id == localPackDetailId }
    val activeOnlineState = if (showingMyUploads) myUploadsState else onlinePacksState
    val unsortedOnlinePacks = (activeOnlineState as? PanelUiState.Content)?.value.orEmpty()
    val onlinePacks = remember(unsortedOnlinePacks, onlineSortMode) {
        when (onlineSortMode) {
            1 -> unsortedOnlinePacks.sortedByDescending(StickerPack::uploadTime)
            2 -> unsortedOnlinePacks.sortedByDescending(StickerPack::downloadCount)
            else -> unsortedOnlinePacks
        }
    }
    val selectedOnlinePack = onlinePacks.firstOrNull { it.id == selectedOnlinePackId }
    val onlineItems = (onlineItemsState as? PanelUiState.Content)?.value.orEmpty()
    val multiSelectItems = when {
        destination == StickerDestination.PACKS && localDetailPack != null -> localDetailPack.items
        destination == StickerDestination.ONLINE && selectedOnlinePack != null -> onlineItems
        else -> emptyList()
    }
    val deletingLocalItems = destination == StickerDestination.PACKS && localDetailPack != null
    val localCatalogVisible = localPackLayout != StickerPackLayout.TABS && localDetailPack == null
    val localActionPack = if (localPackLayout == StickerPackLayout.TABS) selectedPack else localDetailPack
    val recentItems = remember(recent?.items, recentMostUsed) {
        recent?.items.orEmpty().let { items ->
            if (recentMostUsed) {
                items.sortedWith(compareByDescending<StickerItem> { it.sendCount }.thenByDescending { it.lastSentAt })
            } else {
                items.sortedByDescending(StickerItem::lastSentAt)
            }
        }
    }
    val localSearchResults = remember(localPacks, query) {
        if (query.isBlank()) emptyList()
        else editablePacks.flatMap { pack ->
            pack.items.filter { it.matchesLocalSearch(pack, query) }
        }
    }
    val localFilterActive = localPackFilterQuery.trim().isNotEmpty()
    val visibleLocalPacks = remember(localPacks, localPackFilterQuery, localCatalogVisible) {
        if (!localCatalogVisible || localPackFilterQuery.isBlank()) editablePacks
        else editablePacks.filter { it.title.contains(localPackFilterQuery.trim(), ignoreCase = true) }
    }
    val visibleLocalActionPack = remember(localActionPack, localPackFilterQuery, localCatalogVisible) {
        localActionPack?.let { pack ->
            if (localCatalogVisible || localPackFilterQuery.isBlank()) pack
            else pack.copy(items = pack.items.filter { it.matchesLocalSearch(pack, localPackFilterQuery) })
        }
    }

    fun showStickerPackPicker(items: List<StickerItem>) {
        if (items.isEmpty()) return
        showPanelPackPicker(
            context = context,
            title = "保存到表情包",
            createLabel = "新建表情包",
            itemCountLabel = { count -> "$count 个表情" },
            packIcon = MaterialSymbols.Outlined.Folder,
            packs = editablePacks.map { PanelPackChoice(it.id, it.title, it.itemCount) },
            onCreatePack = actions.createPack,
            onSelect = { packId -> startOnlineSave(packId, items, "正在保存表情") },
        )
    }

    fun saveWholeOnlinePack(pack: StickerPack, items: List<StickerItem>) {
        if (items.isEmpty()) return
        scope.launch {
            val packId = actions.ensurePack(pack.title)
            packId.fold(
                onSuccess = { startOnlineSave(it, items, "正在保存表情包“${pack.title}”") },
                onFailure = { operationMessage = it.message ?: "无法创建本地表情包" },
            )
        }
    }

    val rail = buildList {
        add(PanelRailItem(StickerDestination.RECENT, MaterialSymbols.Outlined.History, "最近使用"))
        add(PanelRailItem(StickerDestination.PACKS, MaterialSymbols.Outlined.Folder, "本地表情包"))
        add(PanelRailItem(StickerDestination.SEARCH, MaterialSymbols.Outlined.Manage_search, "本地搜索"))
        add(PanelRailItem(StickerDestination.ONLINE, MaterialSymbols.Outlined.Cloud, "在线表情包"))
        add(PanelRailItem(StickerDestination.ONLINE_SEARCH, MaterialSymbols.Outlined.Travel_explore, "在线搜索"))
        add(PanelRailItem(StickerDestination.SETTINGS, MaterialSymbols.Outlined.Settings, "设置"))
    }

    val title = when (destination) {
        StickerDestination.RECENT -> "最近使用"
        StickerDestination.SEARCH -> "本地搜索"
        StickerDestination.PACKS -> localDetailPack?.title ?: "本地表情包"
        StickerDestination.ONLINE -> selectedOnlinePack?.title
            ?: if (showingMyUploads) "我的上传" else "在线表情包"

        StickerDestination.ONLINE_SEARCH -> "在线搜索"
        StickerDestination.SETTINGS -> "设置"
    }
    fun cancelReorder() {
        reorderTarget = null
        reorderPackId = null
        reorderKeys = emptyList()
    }

    fun changeLocalSortMode(target: StickerReorderTarget, mode: LocalSortMode) {
        when (target) {
            StickerReorderTarget.PACKS -> {
                localPackSortMode = mode
                PanelSettings.stickerPackSortMode = mode
                if (mode == LocalSortMode.CUSTOM && !PanelSettings.stickerPackCustomSortHintShown) {
                    PanelSettings.stickerPackCustomSortHintShown = true
                    scope.launch { showToastSuspend(context, "长按「自定义」字样开始排序") }
                }
            }

            StickerReorderTarget.ITEMS -> {
                localItemSortMode = mode
                PanelSettings.stickerItemSortMode = mode
                if (mode == LocalSortMode.CUSTOM && !PanelSettings.stickerItemCustomSortHintShown) {
                    PanelSettings.stickerItemCustomSortHintShown = true
                    scope.launch { showToastSuspend(context, "长按「自定义」字样开始排序") }
                }
            }
        }
        refreshLocal()
    }

    fun startReorder(target: StickerReorderTarget) {
        when (target) {
            StickerReorderTarget.PACKS -> {
                if (editablePacks.isEmpty()) return
                reorderPackId = null
                reorderKeys = editablePacks.map(StickerPack::id)
            }

            StickerReorderTarget.ITEMS -> {
                val pack = localActionPack ?: return
                val paths = pack.items.mapNotNull(StickerItem::localPath)
                if (paths.isEmpty()) return
                reorderPackId = pack.id
                reorderKeys = paths
            }
        }
        reorderTarget = target
    }

    fun saveReorder() {
        val target = reorderTarget ?: return
        val requested = reorderKeys
        val packId = reorderPackId
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (target) {
                    StickerReorderTarget.PACKS -> actions.savePackOrder(requested)
                    StickerReorderTarget.ITEMS -> packId?.let {
                        actions.saveItemOrder(it, requested)
                    } ?: Result.failure(IllegalStateException("未选择表情包"))
                }
            }
            if (result.isSuccess) {
                cancelReorder()
                refreshLocal()
                operationMessage = "已保存自定义顺序"
            } else {
                operationMessage = result.exceptionOrNull()?.message ?: "保存排序失败"
            }
        }
    }

    val panelActions = if (reorderTarget != null) {
        panelReorderActions(::cancelReorder, ::saveReorder)
    } else if (multiSelectMode && multiSelectItems.isNotEmpty()) {
        panelMultiSelectActions(
            items = multiSelectItems,
            selectedKeys = selectedStickerKeys,
            key = ::stickerSelectionKey,
            terminalIcon = if (deletingLocalItems) MaterialSymbols.Outlined.Delete else MaterialSymbols.Outlined.Save,
            terminalLabel = if (deletingLocalItems) "删除" else "保存",
            onClose = {
                multiSelectMode = false
                selectedStickerKeys = emptySet()
            },
            onSelectionChange = { selectedStickerKeys = it },
            onTerminalAction = { items ->
                if (deletingLocalItems) prompt = StickerPrompt.DeleteStickers(items)
                else showStickerPackPicker(items)
            },
        )
    } else when (destination) {
        StickerDestination.PACKS -> if (localCatalogVisible) {
            buildList {
                add(PanelAction(MaterialSymbols.Outlined.Add, "新建表情包") { prompt = StickerPrompt.CreatePack })
                add(PanelAction(MaterialSymbols.Outlined.Upload_file, "导入") {
                    prompt = StickerPrompt.Import(null)
                })
                add(PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::refreshLocal))
                add(
                    panelLocalSortAction(
                        mode = localPackSortMode,
                        enabled = editablePacks.isNotEmpty(),
                        onModeChange = { changeLocalSortMode(StickerReorderTarget.PACKS, it) },
                        onStartCustomOrder = { startReorder(StickerReorderTarget.PACKS) },
                    ),
                )
            }
        } else buildList {
            if (localPackLayout != StickerPackLayout.TABS) {
                add(PanelAction(MaterialSymbols.Outlined.Arrow_back, "返回") { localPackDetailId = null })
            } else {
                add(PanelAction(MaterialSymbols.Outlined.Add, "新建表情包") { prompt = StickerPrompt.CreatePack })
            }
            add(PanelAction(MaterialSymbols.Outlined.Edit, "重命名", localActionPack != null) {
                localActionPack?.let { prompt = StickerPrompt.RenamePack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Delete, "删除", localActionPack != null) {
                localActionPack?.let { prompt = StickerPrompt.DeletePack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Upload_file, "导入") {
                prompt = StickerPrompt.Import(localActionPack)
            })
            add(PanelAction(MaterialSymbols.Outlined.Upload, "上传", localActionPack != null) {
                localActionPack?.let { prompt = StickerPrompt.UploadPack(it) }
            })
            if (localPackLayout != StickerPackLayout.TABS) {
                add(PanelAction(MaterialSymbols.Outlined.Select_all, "多选", !localActionPack?.items.isNullOrEmpty()) {
                    multiSelectMode = true
                    selectedStickerKeys = emptySet()
                })
            }
            add(PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::refreshLocal))
            add(
                panelLocalSortAction(
                    mode = localItemSortMode,
                    enabled = !localActionPack?.items.isNullOrEmpty(),
                    onModeChange = { changeLocalSortMode(StickerReorderTarget.ITEMS, it) },
                    onStartCustomOrder = { startReorder(StickerReorderTarget.ITEMS) },
                ),
            )
        }

        StickerDestination.ONLINE -> if (selectedOnlinePack == null && !showingMyUploads) {
            listOf(
                PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::loadOnlinePacks),
                PanelAction(MaterialSymbols.Outlined.Person, "我的上传") {
                    showingMyUploads = true
                    selectedOnlinePackId = null
                    loadMyUploads()
                },
                PanelAction(
                    icon = MaterialSymbols.Outlined.Sort,
                    label = when (onlineSortMode) {
                        1 -> "上传时间"
                        2 -> "下载次数"
                        else -> "默认"
                    },
                    showLabel = true,
                ) {
                    onlineSortMode = (onlineSortMode + 1) % 3
                    PanelSettings.onlineStickerSortMode = onlineSortMode
                },
            )
        } else if (selectedOnlinePack == null) {
            listOf(
                PanelAction(MaterialSymbols.Outlined.Arrow_back, "返回") {
                    showingMyUploads = false
                    myUploadsRequest++
                },
                PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::loadMyUploads),
            )
        } else {
            listOf(
                PanelAction(MaterialSymbols.Outlined.Arrow_back, "返回") {
                    selectedOnlinePackId = null
                    onlineItemsRequest++
                    onlineItemsState = PanelUiState.Empty("选择一个在线表情包")
                },
                PanelAction(MaterialSymbols.Outlined.Refresh, "刷新") {
                    loadOnlinePack(selectedOnlinePack)
                },
                PanelAction(MaterialSymbols.Outlined.Select_all, "多选", onlineItems.isNotEmpty()) {
                    multiSelectMode = true
                    selectedStickerKeys = emptySet()
                },
                PanelAction(MaterialSymbols.Outlined.Save, "保存", onlineItems.isNotEmpty()) {
                    saveWholeOnlinePack(selectedOnlinePack, onlineItems)
                },
            )
        }

        else -> emptyList()
    }
    val actionSearch = when {
        reorderTarget != null || multiSelectMode -> null

        destination == StickerDestination.PACKS -> PanelActionSearch(
            expanded = localPackFilterExpanded,
            value = localPackFilterQuery,
            label = if (localCatalogVisible) "筛选本地表情包" else "筛选当前表情包",
            actionIndex = (panelActions.size - 1).coerceAtLeast(0),
            onValueChange = { localPackFilterQuery = it },
            onExpandedChange = { localPackFilterExpanded = it },
        )

        destination == StickerDestination.ONLINE && selectedOnlinePack == null && !showingMyUploads -> {
            PanelActionSearch(
                expanded = onlinePackSearchExpanded,
                value = onlinePackQuery,
                label = "筛选在线表情包",
                actionIndex = 2,
                onValueChange = { onlinePackQuery = it },
                onExpandedChange = { onlinePackSearchExpanded = it },
            )
        }

        else -> null
    }

    Box(Modifier.fillMaxSize()) {
        PanelShell(
            railItems = rail,
            selected = destination,
            title = title,
            actions = panelActions,
            actionSearch = actionSearch,
            wrapActions = wrapActions,
            onSelect = {
                if (reorderTarget != null) return@PanelShell
                multiSelectMode = false
                selectedStickerKeys = emptySet()
                destination = it
            },
            onDismiss = onDismiss,
            onBack = {
                when {
                    reorderTarget != null -> cancelReorder()

                    multiSelectMode -> {
                        multiSelectMode = false
                        selectedStickerKeys = emptySet()
                    }

                    destination == StickerDestination.ONLINE && selectedOnlinePack != null -> {
                        selectedOnlinePackId = null
                        onlineItemsRequest++
                        onlineItemsState = PanelUiState.Empty("选择一个在线表情包")
                    }

                    destination == StickerDestination.ONLINE && showingMyUploads -> {
                        showingMyUploads = false
                        myUploadsRequest++
                    }

                    destination == StickerDestination.PACKS &&
                            localPackLayout != StickerPackLayout.TABS && localPackDetailId != null -> {
                        localPackDetailId = null
                    }

                    else -> onDismiss()
                }
            },
            titleContent = if (destination == StickerDestination.RECENT) ({
                RecentModeTitle(recentMostUsed) { mostUsed ->
                    recentMostUsed = mostUsed
                    PanelSettings.stickerRecentSortMode = if (mostUsed) 1 else 0
                }
            }) else null,
        ) {
            when (reorderTarget) {
                StickerReorderTarget.PACKS -> StickerPackReorderContent(
                    packs = reorderKeys.mapNotNull { key -> editablePacks.firstOrNull { it.id == key } },
                    onMove = { from, to -> reorderKeys = reorderKeys.moveItem(from, to) },
                )

                StickerReorderTarget.ITEMS -> StickerItemReorderContent(
                    stickers = reorderKeys.mapNotNull { key ->
                        localActionPack?.items?.firstOrNull { it.localPath == key }
                    },
                    onMove = { from, to -> reorderKeys = reorderKeys.moveItem(from, to) },
                )

                null -> when (destination) {
                StickerDestination.RECENT -> PanelStateContent(localState, ::refreshLocal) {
                    StickerGridOrEmpty(
                        stickers = recentItems,
                        message = "还没有发送过表情",
                        onSend = ::send,
                        onLongPress = { previewSticker = it },
                    )
                }

                StickerDestination.SEARCH -> PanelStateContent(localState, ::refreshLocal) {
                    SearchStickerContent(
                        query = query,
                        onQueryChange = { query = it },
                        results = localSearchResults,
                        onSearch = null,
                        emptyMessage = if (query.isBlank()) "输入文件名或表情包名称" else "没有找到本地表情",
                        onSend = ::send,
                        onLongPress = { previewSticker = it },
                    )
                }

                StickerDestination.PACKS -> PanelStateContent(localState, ::refreshLocal) {
                    LocalPacksContent(
                        packs = visibleLocalPacks,
                        layout = localPackLayout,
                        selectedPack = visibleLocalActionPack,
                        filterActive = localFilterActive,
                        gridState = localPackGridState,
                        listState = localPackListState,
                        itemGridState = localItemGridState,
                        onSelectPack = {
                            selectedPackId = it.id
                            if (localPackLayout != StickerPackLayout.TABS) {
                                localPackFilterQuery = ""
                                localPackFilterExpanded = false
                                localPackDetailId = it.id
                                scope.launch { localItemGridState.scrollToItem(0) }
                            }
                        },
                        onImport = {
                            prompt = StickerPrompt.Import(localActionPack)
                        },
                        onSend = ::send,
                        onLongPress = { previewSticker = it },
                        selectable = multiSelectMode && localDetailPack != null,
                        selectedKeys = selectedStickerKeys,
                        onToggleSelection = { sticker ->
                            val key = stickerSelectionKey(sticker)
                            selectedStickerKeys = selectedStickerKeys.toMutableSet().apply {
                                if (!add(key)) remove(key)
                            }
                        },
                        onRangeStart = { quickSelectionBase = selectedStickerKeys },
                        onSelectRange = { first, last ->
                            val items = localDetailPack?.items.orEmpty()
                            val range = minOf(first, last)..maxOf(first, last)
                            selectedStickerKeys = quickSelectionBase +
                                    range.mapNotNull { index -> items.getOrNull(index)?.let(::stickerSelectionKey) }
                        }
                    )
                }

                StickerDestination.ONLINE -> if (selectedOnlinePack == null) {
                    PanelStateContent(
                        activeOnlineState,
                        if (showingMyUploads) ::loadMyUploads else ::loadOnlinePacks,
                    ) { packs ->
                        val visiblePacks = when (onlineSortMode) {
                            1 -> packs.sortedByDescending(StickerPack::uploadTime)
                            2 -> packs.sortedByDescending(StickerPack::downloadCount)
                            else -> packs
                        }.filter { pack ->
                            onlinePackQuery.isBlank() || pack.title.contains(onlinePackQuery, ignoreCase = true)
                        }
                        if (visiblePacks.isEmpty() && onlinePackQuery.isNotBlank()) {
                            PanelEmptyAction("没有找到在线表情包")
                        } else {
                            StickerPackCatalog(
                                packs = visiblePacks,
                                layout = onlinePackLayout,
                                columnCount = PanelSettings.stickerColumnCount.coerceIn(1, 15),
                                gridState = onlinePackGridState,
                                listState = onlinePackListState,
                                onSelectPack = { pack ->
                                    onlinePackSearchExpanded = false
                                    onlinePackQuery = ""
                                    multiSelectMode = false
                                    selectedStickerKeys = emptySet()
                                    selectedOnlinePackId = pack.id
                                    scope.launch { onlineItemGridState.scrollToItem(0) }
                                    loadOnlinePack(pack)
                                },
                            )
                        }
                    }
                } else {
                    PanelStateContent(
                        state = onlineItemsState,
                        onRetry = { loadOnlinePack(selectedOnlinePack) },
                    ) {
                        StickerGridOrEmpty(
                            stickers = it,
                            message = "暂无表情",
                            onSend = ::send,
                            onLongPress = { sticker -> previewSticker = sticker },
                            gridState = onlineItemGridState,
                            selectable = multiSelectMode,
                            selectedKeys = selectedStickerKeys,
                            onToggleSelection = { sticker ->
                                val key = stickerSelectionKey(sticker)
                                selectedStickerKeys = selectedStickerKeys.toMutableSet().apply {
                                    if (!add(key)) remove(key)
                                }
                            },
                            onRangeStart = { quickSelectionBase = selectedStickerKeys },
                            onSelectRange = { first, last ->
                                val range = minOf(first, last)..maxOf(first, last)
                                selectedStickerKeys = quickSelectionBase +
                                        range.mapNotNull { index -> onlineItems.getOrNull(index)?.let(::stickerSelectionKey) }
                            },
                        )
                    }
                }

                StickerDestination.ONLINE_SEARCH -> SearchStickerContent(
                    query = onlineQuery,
                    inputEnabled = !similaritySearchActive,
                    onQueryChange = {
                        onlineQuery = it
                        searchRequest++
                        searchState = PanelUiState.Empty(
                            if (it.isBlank()) "输入关键词搜索在线表情" else "点击搜索查找在线表情",
                        )
                    },
                    results = (searchState as? PanelUiState.Content)?.value.orEmpty(),
                    onSearch = {
                        if (onlineQuery.isBlank()) return@SearchStickerContent
                        val request = ++searchRequest
                        val requestedQuery = onlineQuery
                        searchState = PanelUiState.Loading
                        scope.launch {
                            val result = actions.searchOnline(requestedQuery)
                            if (request != searchRequest || requestedQuery != onlineQuery) return@launch
                            searchState = result.fold(
                                { if (it.isEmpty()) PanelUiState.Empty("没有找到在线表情") else PanelUiState.Content(it) },
                                { PanelUiState.Error(it.message ?: "在线搜索失败") },
                            )
                        }
                    },
                    imageSearchActive = similaritySearchActive,
                    onImageSearch = if (similaritySearchActive) ::clearSimilaritySearch else ({
                        actions.pickSimilarityImage { result ->
                            result.fold(
                                onSuccess = { prompt = StickerPrompt.ConfirmSimilarityBytes(it) },
                                onFailure = { operationMessage = it.message ?: "无法读取所选图片" },
                            )
                        }
                    }),
                    state = searchState,
                    emptyMessage = "输入关键词搜索在线表情",
                    onSend = ::send,
                    onLongPress = { previewSticker = it },
                )

                StickerDestination.SETTINGS -> StickerSettingsContent(
                    localPackLayout = localPackLayout,
                    onlinePackLayout = onlinePackLayout,
                    wrapActions = wrapActions,
                    onLocalPackLayoutChange = {
                        localPackLayout = it
                        localPackDetailId = null
                        PanelSettings.localStickerPackLayout = it
                    },
                    onOnlinePackLayoutChange = {
                        onlinePackLayout = it
                        PanelSettings.onlineStickerPackLayout = it
                    },
                    onWrapActionsChange = {
                        wrapActions = it
                        PanelSettings.wrapPanelActions = it
                    },
                )
                }
            }
        }

        uploadProgress?.let { PanelProgressOverlay("正在上传表情包", it) }
        progressMessage?.let { PanelProgressOverlay(it) }
        telegramProgress?.let { progress ->
            TelegramImportProgressOverlay(
                progress = progress,
                onCancel = {
                    telegramImportJob?.cancel()
                    telegramImportJob = null
                    telegramProgress = null
                },
            )
        }
        onlineSaveProgress?.let { progress ->
            PanelSaveProgressOverlay(progress, onCancel = ::stopOnlineSave)
        }
        if (sending) PanelProgressOverlay("正在发送表情...")

        previewSticker?.let { item ->
            StickerPreviewOverlay(
                sticker = item,
                onDismiss = { previewSticker = null },
                onSend = {
                    previewSticker = null
                    send(item)
                },
                onSearchSimilar = {
                    previewSticker = null
                    prompt = StickerPrompt.ConfirmSimilaritySticker(item)
                },
                onSetTitle = if (item.localPath != null) ({
                    previewSticker = null
                    prompt = StickerPrompt.SetStickerTitle(item)
                }) else null,
                onSetCover = if (item.localPath != null) ({
                    previewSticker = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            actions.setPackCover(item.localPath)
                        }
                        operationMessage = result.exceptionOrNull()?.message ?: "已设置为封面"
                        if (result.isSuccess) refreshLocal()
                    }
                }) else null,
                onDelete = if (item.localPath != null) ({
                    previewSticker = null
                    prompt = StickerPrompt.DeleteSticker(item)
                }) else null,
                onSave = if (item.localPath == null) ({
                    previewSticker = null
                    showStickerPackPicker(listOf(item))
                }) else null,
            )
        }

        when (val currentPrompt = prompt) {
            is StickerPrompt.Import -> StickerImportPrompt(
                includeLocalImport = localPackLayout == StickerPackLayout.TABS || currentPrompt.pack != null,
                includeTelegramImport = localPackLayout == StickerPackLayout.TABS || currentPrompt.pack == null,
                onDismiss = { prompt = null },
                onSelect = { mode ->
                    prompt = null
                    if (mode == StickerImportMode.TELEGRAM) {
                        if (!PanelSettings.isValidTelegramBotToken(PanelSettings.telegramBotToken)) {
                            scope.launch { showToastSuspend(context, "请先在设置中填写 Telegram Bot Token") }
                        } else {
                            telegramNamePrompt = true
                        }
                    } else {
                        val pack = currentPrompt.pack
                        if (pack == null) {
                            scope.launch { showToastSuspend(context, "请先新建或选择一个本地表情包") }
                        } else {
                            actions.importSticker(
                                pack.id,
                                mode,
                                { progressMessage = "正在导入表情..." },
                                { result ->
                                    progressMessage = null
                                    operationMessage = result.exceptionOrNull()?.message ?: "表情导入完成"
                                    if (result.isSuccess) refreshLocal()
                                },
                            )
                        }
                    }
                },
            )

            StickerPrompt.CreatePack -> PanelTextPrompt(
                title = "新建表情包",
                label = "表情包名称",
                confirmText = "创建",
                onDismiss = { prompt = null },
                onConfirm = { name ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { actions.createPack(name) }
                        prompt = null
                        operationMessage = result.exceptionOrNull()?.message ?: "表情包已创建"
                        if (result.isSuccess) refreshLocal()
                    }
                },
            )

            is StickerPrompt.RenamePack -> PanelTextPrompt(
                title = "重命名表情包",
                label = "表情包名称",
                initialValue = currentPrompt.pack.title,
                confirmText = "保存",
                onDismiss = { prompt = null },
                onConfirm = { name ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { actions.renamePack(currentPrompt.pack.id, name) }
                        prompt = null
                        operationMessage = result.exceptionOrNull()?.message ?: "表情包已重命名"
                        if (result.isSuccess) refreshLocal()
                    }
                },
            )

            is StickerPrompt.DeletePack -> PanelConfirmation(
                title = "删除表情包",
                message = "删除“${currentPrompt.pack.title}”及其中的所有表情？",
                confirmText = "删除",
                onDismiss = { prompt = null },
                onConfirm = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { actions.deletePack(currentPrompt.pack.id) }
                        prompt = null
                        operationMessage = result.exceptionOrNull()?.message ?: "表情包已删除"
                        if (result.isSuccess) refreshLocal()
                    }
                },
            )

            is StickerPrompt.UploadPack -> PanelConfirmation(
                title = "上传表情包",
                message = "将“${currentPrompt.pack.title}”中的 ${currentPrompt.pack.items.size} 个表情上传到 FunBox？",
                confirmText = "上传",
                onDismiss = { prompt = null },
                onConfirm = {
                    val pack = currentPrompt.pack
                    prompt = null
                    uploadProgress = 0f
                    scope.launch {
                        val result = actions.uploadPack(pack) { uploadProgress = it.coerceIn(0f, 1f) }
                        uploadProgress = null
                        operationMessage = result.fold({ it }, { it.message ?: "上传失败" })
                    }
                },
            )

            is StickerPrompt.SetStickerTitle -> PanelTextPrompt(
                title = "设置名称",
                label = "表情名称",
                initialValue = currentPrompt.item.customTitle.orEmpty(),
                confirmText = "保存",
                allowBlank = true,
                onDismiss = { prompt = null },
                onConfirm = { title ->
                    scope.launch {
                        val path = currentPrompt.item.localPath ?: return@launch
                        val result = withContext(Dispatchers.IO) { actions.setCustomTitle(path, title) }
                        prompt = null
                        operationMessage = result.exceptionOrNull()?.message
                            ?: if (title.isBlank()) "已清除表情名称" else "表情名称已保存"
                        if (result.isSuccess) refreshLocal()
                    }
                },
            )

            is StickerPrompt.DeleteSticker -> PanelConfirmation(
                title = "删除表情",
                message = "从本地表情包中删除这个表情？",
                confirmText = "删除",
                onDismiss = { prompt = null },
                onConfirm = {
                    scope.launch {
                        val path = currentPrompt.item.localPath ?: return@launch
                        val result = withContext(Dispatchers.IO) { actions.deleteSticker(path) }
                        prompt = null
                        operationMessage = result.exceptionOrNull()?.message ?: "表情已删除"
                        if (result.isSuccess) refreshLocal()
                    }
                },
            )

            is StickerPrompt.DeleteStickers -> PanelConfirmation(
                title = "删除表情",
                message = "从本地表情包中删除选中的 ${currentPrompt.items.size} 个表情？",
                confirmText = "删除",
                onDismiss = { prompt = null },
                onConfirm = {
                    scope.launch {
                        val paths = currentPrompt.items.mapNotNull(StickerItem::localPath)
                        val result = withContext(Dispatchers.IO) { actions.deleteStickers(paths) }
                        prompt = null
                        operationMessage = result.fold(
                            onSuccess = { "已删除 $it 个表情" },
                            onFailure = { it.message ?: "表情删除失败" },
                        )
                        if (result.isSuccess) {
                            multiSelectMode = false
                            selectedStickerKeys = emptySet()
                            refreshLocal()
                        }
                    }
                },
            )

            is StickerPrompt.ConfirmSimilaritySticker -> PanelConfirmation(
                title = "搜索相似表情",
                message = SIMILARITY_SEARCH_PRIVACY_MESSAGE,
                confirmText = "上传并搜索",
                onDismiss = { prompt = null },
                onConfirm = {
                    val item = currentPrompt.item
                    prompt = null
                    searchSimilar { actions.loadSimilarityImage(item) }
                },
            )

            is StickerPrompt.ConfirmSimilarityBytes -> PanelConfirmation(
                title = "搜索相似表情",
                message = SIMILARITY_SEARCH_PRIVACY_MESSAGE,
                confirmText = "上传并搜索",
                onDismiss = { prompt = null },
                onConfirm = {
                    val bytes = currentPrompt.bytes
                    prompt = null
                    searchSimilar { Result.success(bytes) }
                },
            )

            null -> Unit
        }

        if (telegramNamePrompt) TelegramStickerSetPrompt(
            onDismiss = { telegramNamePrompt = false },
            onConfirm = { value ->
                telegramNamePrompt = false
                telegramImportJob = scope.launch {
                    try {
                        val result = actions.importTelegramStickerSet(value) { progress ->
                            withContext(Dispatchers.Main) { telegramProgress = progress }
                        }
                        telegramProgress = null
                        operationMessage = result.fold(
                            onSuccess = {
                                buildString {
                                    append("已导入 ${it.imported} 个表情到「${it.packName}」")
                                    if (it.unchanged > 0) append("，${it.unchanged} 个无需更新")
                                    if (it.failed > 0) append("，${it.failed} 个失败")
                                }
                            },
                            onFailure = { it.message ?: "Telegram 表情包导入失败" },
                        )
                        if (result.isSuccess) refreshLocal()
                    } finally {
                        telegramProgress = null
                        telegramImportJob = null
                    }
                }
            },
        )
    }
}

@Composable
private fun StickerGridOrEmpty(
    stickers: List<StickerItem>,
    message: String,
    onSend: (StickerItem) -> Unit,
    onLongPress: (StickerItem) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    selectable: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onToggleSelection: ((StickerItem) -> Unit)? = null,
    onRangeStart: (() -> Unit)? = null,
    onSelectRange: ((Int, Int) -> Unit)? = null,
) {
    if (stickers.isEmpty()) {
        PanelEmptyAction(message)
        return
    }
    val gestureScope = rememberCoroutineScope()
    val dragModifier = if (selectable && onSelectRange != null) {
        Modifier.pointerInput(stickers, gridState) {
            var start = -1
            var current = -1
            var lastPosition: Offset
            var scrollJob: Job? = null

            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    gridState.itemIndexAt(offset)?.let { startIndex ->
                        start = startIndex
                        current = startIndex
                        lastPosition = offset
                        onRangeStart?.invoke()
                        onSelectRange(start, current)

                        scrollJob?.cancel()
                        scrollJob = gestureScope.launch {
                            while (isActive) {
                                val viewportHeight = size.height.toFloat().coerceAtLeast(1f)
                                val amount = when {
                                    lastPosition.y < viewportHeight * 0.2f -> -18f
                                    lastPosition.y > viewportHeight * 0.8f -> 18f
                                    else -> 0f
                                }
                                if (amount != 0f) {
                                    gridState.scrollBy(amount)
                                    gridState.itemIndexAt(lastPosition)?.let { index ->
                                        if (index != current) {
                                            current = index
                                            onSelectRange(start, current)
                                        }
                                    }
                                }
                                delay(16.milliseconds)
                            }
                        }
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    lastPosition = change.position
                    gridState.itemIndexAt(change.position)?.let { index ->
                        if (index != current) {
                            current = index
                            onSelectRange(start, current)
                        }
                    }
                },
                onDragEnd = {
                    scrollJob?.cancel()
                    scrollJob = null
                },
                onDragCancel = {
                    scrollJob?.cancel()
                    scrollJob = null
                },
            )
        }
    } else Modifier
    val keyedStickers = remember(stickers) {
        panelItemsWithStableKeys(stickers, ::stickerSelectionKey)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(PanelSettings.stickerColumnCount.coerceIn(1, 15)),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .then(dragModifier),
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(keyedStickers, key = { _, it -> it.first }) { _, keyedSticker ->
            val sticker = keyedSticker.second
            val context = LocalContext.current
            val imageData = sticker.localPath ?: sticker.thumbnailUrl
            Box(
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
                    .combinedClickable(
                        onClick = {
                            if (selectable) onToggleSelection?.invoke(sticker) else onSend(sticker)
                        },
                        onLongClick = if (selectable) null else ({ onLongPress(sticker) }),
                    )
                    .padding(2.dp),
            ) {
                StickerAsyncImage(
                    request = stickerImageRequest(
                        context = context,
                        data = imageData,
                        securedObject = sticker.localPath == null && sticker.thumbnailUrl != null,
                    ),
                    contentDescription = sticker.customTitle ?: sticker.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (selectable) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Checkbox(
                            checked = stickerSelectionKey(sticker) in selectedKeys,
                            onCheckedChange = { onToggleSelection?.invoke(sticker) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp),
                        )
                    }
                }
                SendCountBadge(
                    count = sticker.sendCount,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                )
            }
        }
    }
}

private fun stickerSelectionKey(item: StickerItem): String = item.remoteObjectId ?: item.id

private fun LazyGridState.itemIndexAt(position: Offset): Int? = layoutInfo.visibleItemsInfo
    .firstOrNull { info ->
        position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
                position.y >= info.offset.y && position.y < info.offset.y + info.size.height
    }
    ?.index

@Composable
private fun SearchStickerContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<StickerItem>,
    onSearch: (() -> Unit)?,
    onImageSearch: (() -> Unit)? = null,
    inputEnabled: Boolean = true,
    imageSearchActive: Boolean = false,
    emptyMessage: String,
    onSend: (StickerItem) -> Unit,
    onLongPress: (StickerItem) -> Unit,
    state: PanelUiState<List<StickerItem>>? = null,
) {
    Column(Modifier.fillMaxSize()) {
        PanelSearchField(
            value = query,
            onValueChange = onQueryChange,
            label = "搜索",
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            enabled = inputEnabled,
            onSearch = onSearch,
            extraTrailingIcon = if (onImageSearch == null) null else ({
                IconButton(onClick = onImageSearch) {
                    Icon(
                        if (imageSearchActive) MaterialSymbols.Outlined.Close
                        else MaterialSymbols.Outlined.Image_search,
                        if (imageSearchActive) "清除以图搜图结果" else "选择图片搜索相似表情",
                    )
                }
            }),
        )
        if (state != null && state !is PanelUiState.Content) {
            PanelStateContent(state, content = {})
        } else {
            Box(Modifier.weight(1f)) {
                StickerGridOrEmpty(results, emptyMessage, onSend, onLongPress)
            }
        }
    }
}

private fun StickerItem.matchesLocalSearch(pack: StickerPack, query: String): Boolean {
    val term = query.trim()
    return term.isBlank() ||
            title.contains(term, ignoreCase = true) ||
            customTitle?.contains(term, ignoreCase = true) == true ||
            pack.title.contains(term, ignoreCase = true)
}

@Composable
private fun LocalPacksContent(
    packs: List<StickerPack>,
    layout: StickerPackLayout,
    selectedPack: StickerPack?,
    filterActive: Boolean,
    gridState: LazyGridState,
    listState: LazyListState,
    itemGridState: LazyGridState,
    onSelectPack: (StickerPack) -> Unit,
    onImport: () -> Unit,
    onSend: (StickerItem) -> Unit,
    onLongPress: (StickerItem) -> Unit,
    selectable: Boolean,
    selectedKeys: Set<String>,
    onToggleSelection: (StickerItem) -> Unit,
    onRangeStart: () -> Unit,
    onSelectRange: (Int, Int) -> Unit,
) {
    if (layout == StickerPackLayout.TABS) {
        Column(Modifier.fillMaxSize()) {
            if (packs.isNotEmpty()) {
                PanelPackChips(
                    packs = packs,
                    selectedId = selectedPack?.id,
                    id = StickerPack::id,
                    title = StickerPack::title,
                    onSelect = onSelectPack,
                )
            }
            Box(Modifier.weight(1f)) {
            if (selectedPack == null) {
                PanelEmptyAction("暂无本地表情包", "新建表情包后即可导入")
            } else if (selectedPack.items.isEmpty()) {
                if (filterActive) PanelEmptyAction("当前表情包没有匹配的表情")
                else PanelEmptyAction("这个表情包还是空的", "导入表情", onImport)
            } else {
                    StickerGridOrEmpty(selectedPack.items, "暂无表情", onSend, onLongPress)
                }
            }
        }
    } else if (selectedPack == null) {
        if (packs.isEmpty()) {
            if (filterActive) PanelEmptyAction("没有找到本地表情包")
            else PanelEmptyAction("暂无本地表情包", "新建表情包后即可导入")
        } else {
            StickerPackCatalog(
                packs = packs,
                layout = layout,
                columnCount = PanelSettings.stickerColumnCount.coerceIn(1, 15),
                gridState = gridState,
                listState = listState,
                onSelectPack = onSelectPack,
            )
        }
    } else if (selectedPack.items.isEmpty()) {
        if (filterActive) PanelEmptyAction("当前表情包没有匹配的表情")
        else PanelEmptyAction("这个表情包还是空的", "导入表情", onImport)
    } else {
        StickerGridOrEmpty(
            stickers = selectedPack.items,
            message = "暂无表情",
            onSend = onSend,
            onLongPress = onLongPress,
            gridState = itemGridState,
            selectable = selectable,
            selectedKeys = selectedKeys,
            onToggleSelection = onToggleSelection,
            onRangeStart = onRangeStart,
            onSelectRange = onSelectRange,
        )
    }
}

@Composable
private fun StickerPackReorderContent(
    packs: List<StickerPack>,
    onMove: (Int, Int) -> Unit,
) {
    PanelReorderableList(
        items = packs,
        itemKey = StickerPack::id,
        onMove = onMove,
        modifier = Modifier.fillMaxSize(),
    ) { pack, dragHandleModifier ->
        ListItem(
            colors = panelListItemColors(),
            headlineContent = {
                Text(pack.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = { Text("${pack.itemCount} 个表情") },
            leadingContent = { StickerPackThumbnail(pack, Modifier.size(48.dp)) },
            trailingContent = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Drag_handle,
                        contentDescription = "拖动 ${pack.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun StickerItemReorderContent(
    stickers: List<StickerItem>,
    onMove: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    PanelReorderableList(
        items = stickers,
        itemKey = { requireNotNull(it.localPath) },
        onMove = onMove,
        modifier = Modifier.fillMaxSize(),
    ) { sticker, dragHandleModifier ->
        ListItem(
            colors = panelListItemColors(),
            headlineContent = {
                Text(
                    sticker.customTitle?.takeIf(String::isNotBlank) ?: sticker.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text("已发送 ${sticker.sendCount} 次")
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
                ) {
                    StickerAsyncImage(
                        request = stickerImageRequest(context, sticker.localPath, securedObject = false),
                        contentDescription = sticker.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
            trailingContent = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Drag_handle,
                        contentDescription = "拖动 ${sticker.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun StickerPackCatalog(
    packs: List<StickerPack>,
    layout: StickerPackLayout,
    columnCount: Int,
    gridState: LazyGridState,
    listState: LazyListState,
    onSelectPack: (StickerPack) -> Unit,
) {
    if (layout == StickerPackLayout.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(packs, key = { it.id }) { pack ->
                Column(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clickable { onSelectPack(pack) }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StickerPackThumbnail(
                        pack = pack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                    Text(
                        text = pack.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(packs, key = { it.id }) { pack ->
                Row(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clickable { onSelectPack(pack) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StickerPackThumbnail(pack, Modifier.size(48.dp))
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val metadataMaxWidth = maxWidth * 0.55f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = pack.title,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = pack.badge ?: "${pack.itemCount} 个",
                                modifier = Modifier
                                    .widthIn(max = metadataMaxWidth)
                                    .padding(start = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerPackThumbnail(pack: StickerPack, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        StickerAsyncImage(
            request = stickerImageRequest(
                context,
                pack.cover,
                securedObject = pack.source == PanelSource.ONLINE || pack.source == PanelSource.SHARED,
            ),
            contentDescription = pack.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun StickerAsyncImage(
    request: ImageRequest,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    var state by remember(request) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(modifier) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            imageLoader = GlobalImageLoader,
            contentScale = contentScale,
            onState = { state = it },
            modifier = Modifier.fillMaxSize(),
        )
        when (state) {
            is AsyncImagePainter.State.Loading, AsyncImagePainter.State.Empty -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            is AsyncImagePainter.State.Error -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Close,
                        contentDescription = "缩略图加载失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            is AsyncImagePainter.State.Success -> Unit
        }
    }
}

@Composable
private fun StickerPreviewOverlay(
    sticker: StickerItem,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    onSearchSimilar: () -> Unit,
    onSave: (() -> Unit)?,
    onSetTitle: (() -> Unit)?,
    onSetCover: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val data = sticker.localPath ?: sticker.imageUrl ?: sticker.thumbnailUrl
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = null, onClick = {}),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StickerAsyncImage(
                request = stickerImageRequest(
                    context,
                    data,
                    securedObject = sticker.localPath == null && data != null,
                ),
                contentDescription = sticker.customTitle ?: sticker.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            FlowRow(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onSend) { Text("发送") }
                TextButton(onClick = onSearchSimilar) { Text("搜索相似") }
                if (onSave != null) TextButton(onClick = onSave) { Text("保存到本地") }
                if (onSetTitle != null) TextButton(onClick = onSetTitle) { Text("设置名称") }
                if (onSetCover != null) TextButton(onClick = onSetCover) { Text("设置为封面") }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun stickerImageRequest(
    context: Context,
    data: String?,
    securedObject: Boolean,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(data)
        .apply {
            if (securedObject && data != null) {
                val cacheKey = "funbox-object-aes-v1:$data"
                memoryCacheKey(cacheKey)
                diskCacheKey(cacheKey)
            }
        }
        .build()
}

@Composable
private fun StickerImportPrompt(
    includeLocalImport: Boolean,
    includeTelegramImport: Boolean,
    onDismiss: () -> Unit,
    onSelect: (StickerImportMode) -> Unit,
) {
    PanelImportModePrompt(
        options = buildList {
            if (includeLocalImport) {
                add(
                    PanelImportOption(
                        mode = StickerImportMode.MULTIPLE_FILES,
                        title = "选择多个图片文件",
                        description = "从系统文件选择器一次选择一个或多个文件",
                        icon = MaterialSymbols.Outlined.Upload_file,
                    ),
                )
                add(
                    PanelImportOption(
                        mode = StickerImportMode.DIRECTORY,
                        title = "导入整个目录",
                        description = "递归导入所选目录内支持的图片文件",
                        icon = MaterialSymbols.Outlined.Folder,
                    ),
                )
            }
            if (includeTelegramImport) {
                add(
                    PanelImportOption(
                        mode = StickerImportMode.TELEGRAM,
                        title = "从 Telegram 导入",
                        description = "输入表情包名称或链接并创建新的本地表情包",
                        icon = TelegramIcon,
                    ),
                )
            }
        },
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}

@Composable
private fun TelegramStickerSetPrompt(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val extracted = TelegramStickerPackRepository.extractStickerSetName(input)
    PanelFullOverlay(onDismiss = onDismiss) {
        Text("从 Telegram 导入", style = MaterialTheme.typography.titleMedium)
        Text(
            "输入表情包名称，或 t.me/addstickers 链接。Telegram 表情包会创建为新的本地包。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("表情包名称或链接") },
            supportingText = when {
                input.isBlank() -> null
                extracted == null -> ({ Text("请输入有效的 Telegram 表情包名称或链接") })
                input.trim() != extracted -> ({ Text("将导入：$extracted") })
                else -> null
            },
            isError = input.isNotBlank() && extracted == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(
                onClick = { extracted?.let(onConfirm) },
                enabled = extracted != null,
            ) { Text("确定") }
        }
    }
}

@Composable
private fun TelegramImportProgressOverlay(
    progress: TelegramStickerImportProgress,
    onCancel: () -> Unit,
) {
    PanelFullOverlay(onDismiss = onCancel) {
        Text(
            when (progress.phase) {
                TelegramStickerImportPhase.DOWNLOAD -> "正在下载 Telegram 表情包"
                TelegramStickerImportPhase.CONVERSION -> "正在转换 Telegram 表情"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(
            progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            buildString {
                append("已完成 ${progress.completed}/${progress.total}")
                progress.currentItem?.takeIf(String::isNotBlank)?.let { append(" · $it") }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("中断") }
        }
    }
}

@Composable
private fun StickerSettingsContent(
    localPackLayout: StickerPackLayout,
    onlinePackLayout: StickerPackLayout,
    wrapActions: Boolean,
    onLocalPackLayoutChange: (StickerPackLayout) -> Unit,
    onOnlinePackLayoutChange: (StickerPackLayout) -> Unit,
    onWrapActionsChange: (Boolean) -> Unit,
) {
    var columns by remember { mutableIntStateOf(PanelSettings.stickerColumnCount.coerceIn(1, 15)) }
    var maxHistory by remember { mutableLongStateOf(PanelSettings.stickerMaxHistory.coerceAtLeast(1L)) }
    var autoClose by remember { mutableStateOf(PanelSettings.panelAutoClose) }
    var telegramToken by remember { mutableStateOf(PanelSettings.telegramBotToken) }
    var removeRoundedVideoMask by remember {
        mutableStateOf(PanelSettings.stickerRemoveRoundedVideoMask)
    }
    var clientIdPrompt by remember { mutableStateOf(false) }
    var telegramTokenPrompt by remember { mutableStateOf(false) }
    var numberPrompt by remember { mutableStateOf(false) }
    var historyPrompt by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                PanelTelegramBotTokenSetting(
                    configured = telegramToken.isNotBlank(),
                    onClick = { telegramTokenPrompt = true },
                )
            }
            item {
                ListItem(
                    colors = panelListItemColors(),
                    headlineContent = { Text("自动移除视频贴纸圆角遮罩") },
                    supportingContent = { Text("仅影响从 Telegram 导入的 WEBM 表情") },
                    trailingContent = {
                        Switch(
                            checked = removeRoundedVideoMask,
                            onCheckedChange = {
                                removeRoundedVideoMask = it
                                PanelSettings.stickerRemoveRoundedVideoMask = it
                            },
                        )
                    },
                )
            }
            item { PanelFunBoxApiClientIdSetting { clientIdPrompt = true } }
            item {
                PanelDropdownSetting(
                    title = "本地表情包一级界面",
                    selected = localPackLayout,
                    options = listOf(
                        StickerPackLayout.TABS to "Tab 栏",
                        StickerPackLayout.GRID to "网格",
                        StickerPackLayout.LIST to "列表",
                    ),
                    onSelected = onLocalPackLayoutChange,
                )
            }
            item {
                PanelDropdownSetting(
                    title = "在线表情包一级界面",
                    selected = onlinePackLayout,
                    options = listOf(
                        StickerPackLayout.GRID to "网格",
                        StickerPackLayout.LIST to "列表",
                    ),
                    onSelected = onOnlinePackLayoutChange,
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { numberPrompt = true },
                    colors = panelListItemColors(),
                    headlineContent = { Text("每行表情数量") },
                    supportingContent = { Text("$columns · 点击输入自定义数量") },
                )
                Slider(
                    value = columns.coerceIn(2, 10).toFloat(),
                    onValueChange = {
                        columns = it.roundToLong().toInt().coerceIn(2, 10)
                        PanelSettings.stickerColumnCount = columns
                    },
                    valueRange = 2f..10f,
                    steps = 7,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            panelCollectionSettings(
                maxHistory = maxHistory,
                onMaxHistoryChange = {
                    maxHistory = it
                    PanelSettings.stickerMaxHistory = it
                },
                onCustomHistory = { historyPrompt = true },
                autoClose = autoClose,
                onAutoCloseChange = {
                    autoClose = it
                    PanelSettings.panelAutoClose = it
                },
                wrapActions = wrapActions,
                onWrapActionsChange = onWrapActionsChange,
            )
        }
        if (clientIdPrompt) PanelFunBoxApiClientIdPrompt(
            onDismiss = { clientIdPrompt = false },
            onConfirm = {
                PanelSettings.funBoxApiClientWxId = it
                clientIdPrompt = false
            },
        )
        if (telegramTokenPrompt) PanelTelegramBotTokenPrompt(
            initialValue = telegramToken,
            onDismiss = { telegramTokenPrompt = false },
            onConfirm = {
                telegramToken = it
                PanelSettings.telegramBotToken = it
                telegramTokenPrompt = false
            },
        )
        if (numberPrompt) PanelNumberPrompt(
            title = "每行表情数量",
            label = "数量（1-15）",
            initialValue = columns.toLong(),
            minValue = 1,
            maxValue = 15,
            onDismiss = { numberPrompt = false },
            onConfirm = {
                columns = it.toInt()
                PanelSettings.stickerColumnCount = columns
                numberPrompt = false
            },
        )
        if (historyPrompt) PanelNumberPrompt(
            title = "最大历史数量",
            label = "数量（至少 1）",
            initialValue = maxHistory,
            minValue = 1,
            onDismiss = { historyPrompt = false },
            onConfirm = {
                maxHistory = it
                PanelSettings.stickerMaxHistory = it
                historyPrompt = false
            },
        )
    }
}
