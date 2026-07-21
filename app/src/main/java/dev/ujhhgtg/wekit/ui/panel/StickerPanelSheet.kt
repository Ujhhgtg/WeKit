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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Done_all
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Sort
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Upload_file
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
    val uploadPack: suspend (StickerPack, (Float) -> Unit) -> Result<String> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val setCustomTitle: suspend (String, String) -> Result<Unit> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val deleteSticker: suspend (String) -> Result<Unit> = {
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

fun showStickerPanelSheet(
    context: Context,
    packs: List<StickerPack>,
    actions: StickerPanelActions = StickerPanelActions(),
    onSend: suspend (StickerItem) -> Result<Unit>,
) {
    showPanelDialog(context) {
        StickerPanelContent(
            initialPacks = packs,
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
}

@Composable
private fun StickerPanelContent(
    initialPacks: List<StickerPack>,
    actions: StickerPanelActions,
    onSend: suspend (StickerItem) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localPacks by remember { mutableStateOf(initialPacks) }
    var destination by remember {
        mutableStateOf(
            StickerDestination.entries.firstOrNull { it.name == PanelSettings.stickerLastDestination }
                ?: StickerDestination.RECENT,
        )
    }
    var selectedPackId by remember {
        mutableStateOf(initialPacks.firstOrNull { it.id != RECENT_PACK_ID }?.id)
    }
    var localPackDetailId by remember { mutableStateOf<String?>(null) }
    var localPackLayout by remember { mutableStateOf(PanelSettings.localStickerPackLayout) }
    var onlinePackLayout by remember { mutableStateOf(PanelSettings.onlineStickerPackLayout) }
    var query by remember { mutableStateOf("") }
    var onlineQuery by remember { mutableStateOf("") }
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
    var prompt by remember { mutableStateOf<StickerPrompt?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    var telegramNamePrompt by remember { mutableStateOf(false) }
    var telegramProgress by remember { mutableStateOf<TelegramStickerImportProgress?>(null) }
    var telegramImportJob by remember { mutableStateOf<Job?>(null) }
    var uploadProgress by remember { mutableStateOf<Float?>(null) }
    var onlineMultiSelect by remember { mutableStateOf(false) }
    var selectedOnlineStickerKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var quickSelectionBase by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlineSaveProgress by remember { mutableStateOf<PanelSaveProgress?>(null) }
    var onlineSaveJob by remember { mutableStateOf<Job?>(null) }
    var sending by remember { mutableStateOf(false) }
    var previewSticker by remember { mutableStateOf<StickerItem?>(null) }
    var recentMostUsed by remember { mutableStateOf(PanelSettings.stickerRecentSortMode == 1) }
    var onlineSortMode by remember { mutableIntStateOf(PanelSettings.onlineStickerSortMode.coerceIn(0, 2)) }
    var localRequest by remember { mutableIntStateOf(0) }
    val localPackGridState = rememberLazyGridState()
    val localPackListState = rememberLazyListState()
    val localItemGridState = rememberLazyGridState()
    val onlinePackGridState = rememberLazyGridState()
    val onlinePackListState = rememberLazyListState()
    val onlineItemGridState = rememberLazyGridState()

    fun refreshLocal() {
        val request = ++localRequest
        scope.launch {
            val packs = withContext(Dispatchers.IO) { actions.reloadLocal() }
            if (request != localRequest) return@launch
            localPacks = packs
            if (selectedPackId !in localPacks.map { it.id }) {
                selectedPackId = localPacks.firstOrNull { it.id != RECENT_PACK_ID }?.id
            }
            if (localPackDetailId !in localPacks.map { it.id }) {
                localPackDetailId = null
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
        onlineMultiSelect = false
        selectedOnlineStickerKeys = emptySet()
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
                if (PanelSettings.stickerAutoClose) onDismiss()
            }
        }
    }

    LaunchedEffect(operationMessage) {
        val message = operationMessage ?: return@LaunchedEffect
        showToastSuspend(context, message)
        operationMessage = null
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
            pack.items.filter {
                it.title.contains(query, true) ||
                        it.customTitle?.contains(query, true) == true ||
                        pack.title.contains(query, true)
            }
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
        add(PanelRailItem(StickerDestination.SEARCH, MaterialSymbols.Outlined.Search, "本地搜索"))
        add(PanelRailItem(StickerDestination.PACKS, MaterialSymbols.Outlined.Folder, "本地表情包"))
        add(PanelRailItem(StickerDestination.ONLINE, MaterialSymbols.Outlined.Cloud, "在线表情包"))
        add(PanelRailItem(StickerDestination.ONLINE_SEARCH, MaterialSymbols.Outlined.Search, "在线搜索"))
        add(PanelRailItem(StickerDestination.SETTINGS, MaterialSymbols.Outlined.Settings, "设置"))
    }

    val title = when (destination) {
        StickerDestination.RECENT -> "最近使用"
        StickerDestination.SEARCH -> "本地搜索"
        StickerDestination.PACKS -> "本地表情包"
        StickerDestination.ONLINE -> selectedOnlinePack?.title
            ?: if (showingMyUploads) "我的上传" else "在线表情包"

        StickerDestination.ONLINE_SEARCH -> "在线搜索"
        StickerDestination.SETTINGS -> "设置"
    }
    val localCatalogVisible = localPackLayout != StickerPackLayout.TABS && localDetailPack == null
    val localActionPack = if (localPackLayout == StickerPackLayout.TABS) selectedPack else localDetailPack
    val panelActions = if (onlineMultiSelect && destination == StickerDestination.ONLINE && selectedOnlinePack != null) {
        listOf(
            PanelAction(MaterialSymbols.Outlined.Close, "关闭", showLabel = true) {
                onlineMultiSelect = false
                selectedOnlineStickerKeys = emptySet()
            },
            PanelAction(MaterialSymbols.Outlined.Select_all, "全选", onlineItems.isNotEmpty(), showLabel = true) {
                selectedOnlineStickerKeys = onlineItems.mapTo(linkedSetOf(), ::stickerSelectionKey)
            },
            PanelAction(MaterialSymbols.Outlined.Deselect, "反选", onlineItems.isNotEmpty(), showLabel = true) {
                selectedOnlineStickerKeys = invertPanelSelection(
                    selectedOnlineStickerKeys,
                    onlineItems,
                    ::stickerSelectionKey,
                )
            },
            PanelAction(MaterialSymbols.Outlined.Done_all, "连选", selectedOnlineStickerKeys.size > 1, showLabel = true) {
                selectedOnlineStickerKeys = closePanelSelectionRange(
                    selectedOnlineStickerKeys,
                    onlineItems,
                    ::stickerSelectionKey,
                )
            },
            PanelAction(MaterialSymbols.Outlined.Save, "保存", selectedOnlineStickerKeys.isNotEmpty(), showLabel = true) {
                showStickerPackPicker(onlineItems.filter { stickerSelectionKey(it) in selectedOnlineStickerKeys })
            },
        )
    } else when (destination) {
        StickerDestination.PACKS -> if (localCatalogVisible) {
            listOf(
                PanelAction(MaterialSymbols.Outlined.Add, "新建表情包") { prompt = StickerPrompt.CreatePack },
                PanelAction(MaterialSymbols.Outlined.Upload_file, "导入") {
                    prompt = StickerPrompt.Import(null)
                },
                PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::refreshLocal),
            )
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
            add(PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::refreshLocal))
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
                    onlineMultiSelect = true
                    selectedOnlineStickerKeys = emptySet()
                },
                PanelAction(MaterialSymbols.Outlined.Save, "保存", onlineItems.isNotEmpty()) {
                    saveWholeOnlinePack(selectedOnlinePack, onlineItems)
                },
            )
        }

        else -> emptyList()
    }

    Box(Modifier.fillMaxSize()) {
        PanelShell(
            railItems = rail,
            selected = destination,
            title = title,
            actions = panelActions,
            onSelect = {
                onlineMultiSelect = false
                selectedOnlineStickerKeys = emptySet()
                destination = it
            },
            onDismiss = onDismiss,
            onBack = {
                when {
                    onlineMultiSelect -> {
                        onlineMultiSelect = false
                        selectedOnlineStickerKeys = emptySet()
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
            when (destination) {
                StickerDestination.RECENT -> StickerGridOrEmpty(
                    stickers = recentItems,
                    message = "还没有发送过表情",
                    onSend = ::send,
                    onLongPress = { previewSticker = it },
                )

                StickerDestination.SEARCH -> SearchStickerContent(
                    query = query,
                    onQueryChange = { query = it },
                    results = localSearchResults,
                    onSearch = null,
                    emptyMessage = if (query.isBlank()) "输入文件名或表情包名称" else "没有找到本地表情",
                    onSend = ::send,
                    onLongPress = { previewSticker = it },
                )

                StickerDestination.PACKS -> LocalPacksContent(
                    packs = editablePacks,
                    layout = localPackLayout,
                    selectedPack = if (localPackLayout == StickerPackLayout.TABS) selectedPack else localDetailPack,
                    gridState = localPackGridState,
                    listState = localPackListState,
                    itemGridState = localItemGridState,
                    onSelectPack = {
                        selectedPackId = it.id
                        if (localPackLayout != StickerPackLayout.TABS) {
                            localPackDetailId = it.id
                            scope.launch { localItemGridState.scrollToItem(0) }
                        }
                    },
                    onImport = {
                        prompt = StickerPrompt.Import(localActionPack)
                    },
                    onSend = ::send,
                    onLongPress = { previewSticker = it },
                )

                StickerDestination.ONLINE -> if (selectedOnlinePack == null) {
                    PanelStateContent(
                        activeOnlineState,
                        if (showingMyUploads) ::loadMyUploads else ::loadOnlinePacks,
                    ) { packs ->
                        StickerPackCatalog(
                            packs = when (onlineSortMode) {
                                1 -> packs.sortedByDescending(StickerPack::uploadTime)
                                2 -> packs.sortedByDescending(StickerPack::downloadCount)
                                else -> packs
                            },
                            layout = onlinePackLayout,
                            columnCount = PanelSettings.stickerColumnCount.coerceIn(1, 15),
                            gridState = onlinePackGridState,
                            listState = onlinePackListState,
                            onSelectPack = { pack ->
                                onlineMultiSelect = false
                                selectedOnlineStickerKeys = emptySet()
                                selectedOnlinePackId = pack.id
                                scope.launch { onlineItemGridState.scrollToItem(0) }
                                loadOnlinePack(pack)
                            },
                        )
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
                            selectable = onlineMultiSelect,
                            selectedKeys = selectedOnlineStickerKeys,
                            onToggleSelection = { sticker ->
                                val key = stickerSelectionKey(sticker)
                                selectedOnlineStickerKeys = selectedOnlineStickerKeys.toMutableSet().apply {
                                    if (!add(key)) remove(key)
                                }
                            },
                            onRangeStart = { quickSelectionBase = selectedOnlineStickerKeys },
                            onSelectRange = { first, last ->
                                val range = minOf(first, last)..maxOf(first, last)
                                selectedOnlineStickerKeys = quickSelectionBase +
                                        range.mapNotNull { index -> onlineItems.getOrNull(index)?.let(::stickerSelectionKey) }
                            },
                        )
                    }
                }

                StickerDestination.ONLINE_SEARCH -> SearchStickerContent(
                    query = onlineQuery,
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
                    state = searchState,
                    emptyMessage = "输入关键词搜索在线表情",
                    onSend = ::send,
                    onLongPress = { previewSticker = it },
                )

                StickerDestination.SETTINGS -> StickerSettingsContent(
                    localPackLayout = localPackLayout,
                    onlinePackLayout = onlinePackLayout,
                    onLocalPackLayoutChange = {
                        localPackLayout = it
                        localPackDetailId = null
                        PanelSettings.localStickerPackLayout = it
                    },
                    onOnlinePackLayoutChange = {
                        onlinePackLayout = it
                        PanelSettings.onlineStickerPackLayout = it
                    },
                )
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
                onSetTitle = if (item.localPath != null) ({
                    previewSticker = null
                    prompt = StickerPrompt.SetStickerTitle(item)
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
        itemsIndexed(stickers, key = { index, it -> "${stickerSelectionKey(it)}#$index" }) { _, sticker ->
            val context = LocalContext.current
            val imageData = sticker.localPath ?: sticker.thumbnailUrl
            Box(
                modifier = Modifier
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
            onSearch = onSearch,
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

@Composable
private fun LocalPacksContent(
    packs: List<StickerPack>,
    layout: StickerPackLayout,
    selectedPack: StickerPack?,
    gridState: LazyGridState,
    listState: LazyListState,
    itemGridState: LazyGridState,
    onSelectPack: (StickerPack) -> Unit,
    onImport: () -> Unit,
    onSend: (StickerItem) -> Unit,
    onLongPress: (StickerItem) -> Unit,
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
                    PanelEmptyAction("这个表情包还是空的", "导入表情", onImport)
                } else {
                    StickerGridOrEmpty(selectedPack.items, "暂无表情", onSend, onLongPress)
                }
            }
        }
    } else if (selectedPack == null) {
        if (packs.isEmpty()) {
            PanelEmptyAction("暂无本地表情包", "新建表情包后即可导入")
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
        PanelEmptyAction("这个表情包还是空的", "导入表情", onImport)
    } else {
        StickerGridOrEmpty(
            stickers = selectedPack.items,
            message = "暂无表情",
            onSend = onSend,
            onLongPress = onLongPress,
            gridState = itemGridState,
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
                        .fillMaxWidth()
                        .clickable { onSelectPack(pack) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StickerPackThumbnail(pack, Modifier.size(48.dp))
                    Text(
                        text = pack.title,
                        modifier = Modifier
                            .weight(0.45f)
                            .padding(start = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = " · ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = pack.badge ?: "${pack.itemCount} 个",
                        modifier = Modifier.weight(0.55f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
    onSave: (() -> Unit)?,
    onSetTitle: (() -> Unit)?,
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
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSend) { Text("发送") }
                if (onSave != null) TextButton(onClick = onSave) { Text("保存到本地") }
                if (onSetTitle != null) TextButton(onClick = onSetTitle) { Text("设置名称") }
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
    onLocalPackLayoutChange: (StickerPackLayout) -> Unit,
    onOnlinePackLayoutChange: (StickerPackLayout) -> Unit,
) {
    var columns by remember { mutableIntStateOf(PanelSettings.stickerColumnCount.coerceIn(1, 15)) }
    var maxHistory by remember { mutableLongStateOf(PanelSettings.stickerMaxHistory.coerceAtLeast(1L)) }
    var sortType by remember { mutableIntStateOf(PanelSettings.stickerSortType) }
    var autoClose by remember { mutableStateOf(PanelSettings.stickerAutoClose) }
    var telegramToken by remember { mutableStateOf(PanelSettings.telegramBotToken) }
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
                newestFirst = sortType == 1,
                onNewestFirstChange = {
                    sortType = if (it) 1 else 0
                    PanelSettings.stickerSortType = sortType
                },
                autoClose = autoClose,
                onAutoCloseChange = {
                    autoClose = it
                    PanelSettings.stickerAutoClose = it
                },
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
