package dev.ujhhgtg.wekit.ui.panel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Done_all
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Text_to_speech
import com.composables.icons.materialsymbols.outlined.Upload_file
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneExample
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneVoice
import dev.ujhhgtg.wekit.features.items.chat.panel.PANEL_BULK_DOWNLOAD_CONCURRENCY
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelUiState
import dev.ujhhgtg.wekit.features.items.chat.panel.RECENT_PACK_ID
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceDestination
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceItem
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePack
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePackLayout
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePreview
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceProviderPage
import dev.ujhhgtg.wekit.features.items.chat.panel.parallelForEachWithProgress
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.VoiceProvider
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.VoiceProviderRegistry
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.time.Duration.Companion.milliseconds

data class VoicePanelActions(
    val reloadLocal: suspend () -> List<VoicePack> = { emptyList() },
    val importVoice: (
        packId: String,
        mode: VoiceImportMode,
        onStarted: () -> Unit,
        onComplete: (Result<Unit>) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
    val createLocalPack: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val renameLocalPack: suspend (String, String) -> Result<Unit> = { _, _ -> Result.failure(UnsupportedOperationException()) },
    val deleteLocalPack: suspend (String) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val preview: suspend (VoiceItem) -> Result<VoicePreview> = { Result.failure(UnsupportedOperationException()) },
    val releasePreview: (VoicePreview) -> Unit = {},
    val send: suspend (VoiceItem) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val ensureLocalPack: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val addToLocal: suspend (String, VoiceItem) -> Result<Unit> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val synthesizeEdge: suspend (String, String) -> Result<Unit> = { _, _ -> Result.failure(UnsupportedOperationException()) },
    val synthesizeSystem: suspend (String) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val convertEdge: suspend (String, String) -> Result<VoicePreview> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val convertSystem: suspend (String) -> Result<VoicePreview> = {
        Result.failure(UnsupportedOperationException())
    },
    val loadClones: suspend () -> List<CloneVoice> = { emptyList() },
    val selectedCloneId: suspend () -> String = { "" },
    val selectClone: suspend (String?) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val deleteClone: suspend (String) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val importClone: (onStarted: () -> Unit, onComplete: (Result<Unit>) -> Unit) -> Unit = { _, _ -> },
    val importCloneFromVoice: suspend (String, VoiceItem) -> Result<Unit> = { _, _ -> Result.failure(UnsupportedOperationException()) },
    val synthesizeClone: suspend (String, CloneVoice) -> Result<Unit> = { _, _ -> Result.failure(UnsupportedOperationException()) },
    val convertClone: suspend (String, CloneVoice) -> Result<VoicePreview> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val sendConverted: suspend (VoicePreview, String) -> Result<Unit> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val loadExampleGroups: suspend () -> Result<List<String>> = { Result.success(emptyList()) },
    val loadExamples: suspend (String) -> Result<List<CloneExample>> = { Result.success(emptyList()) },
    val previewExample: suspend (CloneExample) -> Result<VoicePreview> = { Result.failure(UnsupportedOperationException()) },
    val addExample: suspend (CloneExample) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val loadCloneSharedPacks: suspend () -> Result<List<VoicePack>> = { Result.success(emptyList()) },
    val loadMySharedPacks: suspend () -> Result<List<VoicePack>> = { Result.success(emptyList()) },
    val loadSharedPack: suspend (String) -> Result<List<VoiceItem>> = { Result.success(emptyList()) },
    val createSharedPack: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val renameSharedPack: suspend (String, String) -> Result<String> = { _, _ -> Result.failure(UnsupportedOperationException()) },
    val deleteSharedPack: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val confirmSharedPack: suspend (String) -> Result<String> = { Result.failure(UnsupportedOperationException()) },
    val uploadSharedVoice: (
        packId: String,
        onStarted: () -> Unit,
        onComplete: (Result<String>) -> Unit,
    ) -> Unit = { _, _, _ -> },
)

enum class VoiceImportMode {
    MULTIPLE_FILES,
    DIRECTORY,
}

fun showVoicePanelSheet(
    context: Context,
    packs: List<VoicePack>,
    actions: VoicePanelActions,
    onDismiss: () -> Unit = {},
) {
    showPanelDialog(context, onDismiss) {
        VoicePanelContent(
            initialPacks = packs,
            actions = actions,
            onDismiss = ::dismiss,
        )
    }
}

private sealed interface VoicePrompt {
    data object CreateLocalPack : VoicePrompt
    data class ImportLocal(val pack: VoicePack) : VoicePrompt
    data class RenameLocalPack(val pack: VoicePack) : VoicePrompt
    data class DeleteLocalPack(val pack: VoicePack) : VoicePrompt
    data object CreateSharedPack : VoicePrompt
    data class RenameSharedPack(val pack: VoicePack) : VoicePrompt
    data class DeleteSharedPack(val pack: VoicePack) : VoicePrompt
    data class ConfirmSharedPack(val pack: VoicePack) : VoicePrompt
    data class NameCloneSource(val item: VoiceItem) : VoicePrompt
    data class DeleteClone(val voice: CloneVoice) : VoicePrompt
}

private val LocalVoiceDurationOverrides = staticCompositionLocalOf<Map<String, Long>> { emptyMap() }

private data class ProviderRootSnapshot(
    val page: Int,
    val query: String,
    val state: PanelUiState<VoiceProviderPage>,
)

@Composable
private fun VoicePanelContent(
    initialPacks: List<VoicePack>,
    actions: VoicePanelActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { MediaPlayer() }
    var playingId by remember { mutableStateOf<String?>(null) }
    var activePreviewId by remember { mutableStateOf<String?>(null) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewPositionMs by remember { mutableLongStateOf(0L) }
    var previewDurationMs by remember { mutableLongStateOf(0L) }
    var previewSizeBytes by remember { mutableLongStateOf(0L) }
    var previewMime by remember { mutableStateOf("application/octet-stream") }
    var resolvedDurations by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var activePreview by remember { mutableStateOf<VoicePreview?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewRequest by remember { mutableIntStateOf(0) }
    var localPacks by remember { mutableStateOf(initialPacks) }
    var selectedLocalId by remember {
        mutableStateOf(initialPacks.firstOrNull { it.id != RECENT_PACK_ID }?.id)
    }
    var localPackDetailId by remember { mutableStateOf<String?>(null) }
    var localPackLayout by remember { mutableStateOf(PanelSettings.localVoicePackLayout) }
    var localQuery by remember { mutableStateOf("") }
    var destination by remember {
        mutableStateOf(
            VoiceDestination.entries.firstOrNull { it.name == PanelSettings.voiceLastDestination }
                ?: VoiceDestination.RECENT,
        )
    }
    var prompt by remember { mutableStateOf<VoicePrompt?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    var ttsMode by remember { mutableStateOf(TtsMode.EDGE) }
    var ttsText by remember { mutableStateOf("") }
    var selectedEdgeVoice by remember { mutableStateOf(PanelSettings.selectedEdgeVoice) }
    var convertedTts by remember { mutableStateOf<VoicePreview?>(null) }
    var convertedTtsTitle by remember { mutableStateOf("") }
    var clones by remember { mutableStateOf<List<CloneVoice>>(emptyList()) }
    var selectedCloneId by remember { mutableStateOf("") }
    var managingClones by remember { mutableStateOf(false) }
    var cloneSource by remember { mutableStateOf<String?>(null) }
    var exampleGroups by remember { mutableStateOf<PanelUiState<List<String>>>(PanelUiState.Loading) }
    var selectedExampleGroup by remember { mutableStateOf<String?>(null) }
    var examples by remember { mutableStateOf<PanelUiState<List<CloneExample>>>(PanelUiState.Loading) }
    var provider by remember { mutableStateOf(VoiceProviderRegistry.get(PanelSettings.selectedVoiceProvider)) }
    var providerParent by remember { mutableStateOf<VoiceItem?>(null) }
    var providerPage by remember { mutableIntStateOf(0) }
    var providerQuery by remember { mutableStateOf("") }
    var providerState by remember { mutableStateOf<PanelUiState<VoiceProviderPage>>(PanelUiState.Loading) }
    var providerRootSnapshot by remember { mutableStateOf<ProviderRootSnapshot?>(null) }
    var providerRequest by remember { mutableIntStateOf(0) }
    var sharedPacksState by remember { mutableStateOf<PanelUiState<List<VoicePack>>>(PanelUiState.Loading) }
    var sharedPacksRequest by remember { mutableIntStateOf(0) }
    var selectedSharedPack by remember { mutableStateOf<VoicePack?>(null) }
    var sharedQuery by remember { mutableStateOf("") }
    var sharedItemsState by remember { mutableStateOf<PanelUiState<List<VoiceItem>>>(PanelUiState.Empty("选择一个语音包")) }
    var sharedItemsRequest by remember { mutableIntStateOf(0) }
    var cloneSharedPack by remember { mutableStateOf<VoicePack?>(null) }
    var cloneSharedPacksState by remember { mutableStateOf<PanelUiState<List<VoicePack>>>(PanelUiState.Loading) }
    var cloneSharedPacksRequest by remember { mutableIntStateOf(0) }
    var cloneSharedItemsState by remember { mutableStateOf<PanelUiState<List<VoiceItem>>>(PanelUiState.Empty("选择一个共享语音包")) }
    var cloneSharedItemsRequest by remember { mutableIntStateOf(0) }
    var exampleGroupsRequest by remember { mutableIntStateOf(0) }
    var examplesRequest by remember { mutableIntStateOf(0) }
    var localRequest by remember { mutableIntStateOf(0) }
    var recentMostUsed by remember { mutableStateOf(PanelSettings.voiceRecentSortMode == 1) }
    var batchMode by remember { mutableStateOf(false) }
    var selectedDownloadIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlineSaveProgress by remember { mutableStateOf<PanelSaveProgress?>(null) }
    var onlineSaveJob by remember { mutableStateOf<Job?>(null) }
    val providerRootListState = rememberLazyListState()
    val providerChildListState = rememberLazyListState()
    val localPackListState = rememberLazyListState()
    val localItemListState = rememberLazyListState()

    DisposableEffect(convertedTts) {
        val generated = convertedTts
        onDispose {
            generated?.let(actions.releasePreview)
        }
    }

    fun refreshLocal() {
        val request = ++localRequest
        scope.launch {
            val packs = withContext(Dispatchers.IO) { actions.reloadLocal() }
            if (request != localRequest) return@launch
            localPacks = packs
            if (selectedLocalId !in localPacks.map { it.id }) {
                selectedLocalId = localPacks.firstOrNull { it.id != RECENT_PACK_ID }?.id
            }
            if (localPackDetailId !in localPacks.map { it.id }) {
                localPackDetailId = null
            }
        }
    }

    fun refreshClones() {
        scope.launch {
            clones = withContext(Dispatchers.IO) { actions.loadClones() }
            selectedCloneId = withContext(Dispatchers.IO) { actions.selectedCloneId() }
        }
    }

    fun loadProvider(reset: Boolean = false) {
        if (reset) providerPage = 0
        val request = ++providerRequest
        val requestedProvider = provider
        val requestedParent = providerParent
        val requestedPage = providerPage
        val requestedQuery = providerQuery
        providerState = PanelUiState.Loading
        scope.launch {
            val result = if (requestedQuery.isBlank()) requestedProvider.browse(requestedParent, requestedPage)
            else requestedProvider.search(requestedQuery, requestedPage)
            if (request != providerRequest) return@launch
            providerState = result.fold(
                {
                    val uniqueItems = it.items.distinctBy(::voiceSelectionKey)
                    if (uniqueItems.isEmpty()) PanelUiState.Empty("没有更多语音")
                    else PanelUiState.Content(it.copy(items = uniqueItems))
                },
                { PanelUiState.Error(it.message ?: "语音加载失败") },
            )
        }
    }

    fun loadMySharedPacks() {
        val request = ++sharedPacksRequest
        sharedPacksState = PanelUiState.Loading
        scope.launch {
            val result = actions.loadMySharedPacks()
            if (request != sharedPacksRequest) return@launch
            sharedPacksState = result.fold(
                { if (it.isEmpty()) PanelUiState.Empty("还没有创建共享语音包") else PanelUiState.Content(it) },
                { PanelUiState.Error(it.message ?: "共享语音包加载失败") },
            )
        }
    }

    fun loadCloneSharedPacks() {
        val request = ++cloneSharedPacksRequest
        cloneSharedPacksState = PanelUiState.Loading
        scope.launch {
            val result = actions.loadCloneSharedPacks()
            if (request != cloneSharedPacksRequest) return@launch
            cloneSharedPacksState = result.fold(
                { if (it.isEmpty()) PanelUiState.Empty("暂无可选共享语音包") else PanelUiState.Content(it) },
                { PanelUiState.Error(it.message ?: "共享语音包加载失败") },
            )
        }
    }

    fun releaseActivePreview() {
        activePreview?.let(actions.releasePreview)
        activePreview = null
    }

    fun stopPreview() {
        runCatching { player.stop(); player.reset() }
        playingId = null
        activePreviewId = null
        previewTitle = null
        previewPlaying = false
        previewPositionMs = 0L
        previewDurationMs = 0L
        previewSizeBytes = 0L
        previewMime = "application/octet-stream"
        releaseActivePreview()
    }

    fun togglePreviewPlayback() {
        if (activePreview == null) return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                previewPlaying = false
                playingId = null
            } else {
                if (previewDurationMs in 1..previewPositionMs) {
                    player.seekTo(0)
                    previewPositionMs = 0L
                }
                player.start()
                previewPlaying = true
                playingId = activePreviewId
            }
        }.onFailure { operationMessage = it.message ?: "音频播放失败" }
    }

    fun seekPreview(positionMs: Long) {
        runCatching {
            val position = positionMs.coerceIn(0L, previewDurationMs.coerceAtLeast(0L))
            player.seekTo(position.toInt())
            previewPositionMs = position
        }.onFailure { operationMessage = it.message ?: "无法跳转播放位置" }
    }

    fun preview(
        id: String,
        title: String,
        sourceItem: VoiceItem? = null,
        resolve: suspend () -> Result<VoicePreview>,
    ) {
        if (playingId == id) {
            previewRequest++
            previewJob?.cancel()
            stopPreview()
            return
        }
        previewRequest++
        val request = previewRequest
        previewJob?.cancel()
        stopPreview()
        progressMessage = "正在加载音频..."
        previewJob = scope.launch {
            val result = resolve()
            if (request != previewRequest) {
                result.getOrNull()?.let(actions.releasePreview)
                return@launch
            }
            progressMessage = null
            result.onSuccess { preview ->
                runCatching {
                    player.reset()
                    player.setDataSource(preview.path)
                    player.prepare()
                    player.start()
                    activePreview = preview
                    activePreviewId = id
                    playingId = id
                    previewTitle = title
                    previewPlaying = true
                    previewPositionMs = 0L
                    previewDurationMs = player.duration.coerceAtLeast(0).toLong()
                    if (sourceItem != null && previewDurationMs > 0L) {
                        resolvedDurations = resolvedDurations + (voiceSelectionKey(sourceItem) to previewDurationMs)
                    }
                    previewSizeBytes = runCatching { preview.path.asPath.fileSize() }.getOrDefault(0L)
                    previewMime = resolveAudioMime(preview.path)
                    player.setOnCompletionListener {
                        previewPositionMs = previewDurationMs
                        previewPlaying = false
                        playingId = null
                    }
                }.onFailure {
                    actions.releasePreview(preview)
                    operationMessage = it.message ?: "音频播放失败"
                }
            }.onFailure { operationMessage = it.message ?: "音频加载失败" }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewRequest++
            previewJob?.cancel()
            runCatching { player.release() }
            releaseActivePreview()
        }
    }

    LaunchedEffect(activePreview, previewPlaying) {
        while (activePreview != null && previewPlaying) {
            previewPositionMs = runCatching { player.currentPosition.toLong() }
                .getOrDefault(previewPositionMs)
            delay(200L.milliseconds)
        }
    }

    fun send(item: VoiceItem) {
        progressMessage = "正在发送语音..."
        scope.launch {
            val result = actions.send(item)
            progressMessage = null
            showToastSuspend(context, result.exceptionOrNull()?.message ?: "语音发送成功")
            if (result.isSuccess) {
                refreshLocal()
                if (PanelSettings.voiceAutoClose) onDismiss()
            }
        }
    }

    fun loadExampleGroups() {
        val request = ++exampleGroupsRequest
        exampleGroups = PanelUiState.Loading
        scope.launch {
            val result = actions.loadExampleGroups()
            if (request != exampleGroupsRequest) return@launch
            exampleGroups = result.fold(
                { if (it.isEmpty()) PanelUiState.Empty("服务器上暂无语音示例目录") else PanelUiState.Content(it) },
                { PanelUiState.Error(it.message ?: "读取语音示例失败") },
            )
        }
    }

    LaunchedEffect(destination) {
        PanelSettings.voiceLastDestination = destination.name
        when (destination) {
            VoiceDestination.TTS -> refreshClones()
            VoiceDestination.ONLINE -> if (providerState == PanelUiState.Loading) loadProvider(true)
            VoiceDestination.SHARED -> if (sharedPacksState == PanelUiState.Loading) loadMySharedPacks()
            else -> Unit
        }
    }

    LaunchedEffect(
        destination,
        provider.id,
        providerParent?.id,
        providerPage,
        providerQuery,
        selectedSharedPack?.id,
        sharedQuery,
    ) {
        batchMode = false
        selectedDownloadIds = emptySet()
    }

    LaunchedEffect(operationMessage) {
        val message = operationMessage ?: return@LaunchedEffect
        showToastSuspend(context, message)
        operationMessage = null
    }

    val selectedClone = clones.firstOrNull { it.id == selectedCloneId }

    fun clearConvertedTts() {
        convertedTts?.let(actions.releasePreview)
        convertedTts = null
        convertedTtsTitle = ""
    }

    fun convertTts() {
        val count = ttsText.codePointCount(0, ttsText.length)
        when {
            ttsText.isBlank() -> operationMessage = "转换文字不能为空"
            count > 256 -> operationMessage = "转换文字不能超过 256 个字符"
            ttsMode == TtsMode.CLONE && selectedClone == null -> operationMessage = "请先选择音色"
            else -> {
                clearConvertedTts()
                progressMessage = "正在转换语音..."
                scope.launch {
                    val result = when (ttsMode) {
                        TtsMode.SYSTEM -> actions.convertSystem(ttsText)
                        TtsMode.EDGE -> actions.convertEdge(ttsText, selectedEdgeVoice)
                        TtsMode.CLONE -> actions.convertClone(ttsText, selectedClone!!)
                    }
                    progressMessage = null
                    result.onSuccess { preview ->
                        convertedTts = preview
                        convertedTtsTitle = when (ttsMode) {
                            TtsMode.SYSTEM -> "系统 TTS"
                            TtsMode.EDGE -> "Edge TTS"
                            TtsMode.CLONE -> selectedClone?.name ?: "克隆语音"
                        }
                    }.onFailure { operationMessage = it.message ?: "语音转换失败" }
                }
            }
        }
    }

    fun sendConvertedTts() {
        val generated = convertedTts ?: return
        progressMessage = "正在发送语音..."
        scope.launch {
            val result = actions.sendConverted(generated, convertedTtsTitle)
            progressMessage = null
            showToastSuspend(context, result.exceptionOrNull()?.message ?: "语音发送成功")
            if (result.isSuccess) {
                clearConvertedTts()
                onDismiss()
            }
        }
    }

    val recent = localPacks.firstOrNull { it.id == RECENT_PACK_ID }
    val editableLocalPacks = localPacks.filter { it.id != RECENT_PACK_ID }
    val selectedLocalTab = editableLocalPacks.firstOrNull { it.id == selectedLocalId }
        ?: editableLocalPacks.firstOrNull()
    val localDetailPack = if (localPackLayout == VoicePackLayout.TABS) null
    else editableLocalPacks.firstOrNull { it.id == localPackDetailId }
    val selectedLocal = if (localPackLayout == VoicePackLayout.TABS) selectedLocalTab else localDetailPack
    val localCatalogVisible = localPackLayout == VoicePackLayout.LIST && localDetailPack == null
    val recentItems = remember(recent?.items, recentMostUsed) {
        recent?.items.orEmpty().let { items ->
            if (recentMostUsed) {
                items.sortedWith(compareByDescending<VoiceItem> { it.sendCount }.thenByDescending { it.lastSentAt })
            } else {
                items.sortedByDescending(VoiceItem::lastSentAt)
            }
        }
    }
    val rail = listOf(
        PanelRailItem(VoiceDestination.RECENT, MaterialSymbols.Outlined.History, "最近使用"),
        PanelRailItem(VoiceDestination.SEARCH, MaterialSymbols.Outlined.Search, "本地搜索"),
        PanelRailItem(VoiceDestination.LOCAL, MaterialSymbols.Outlined.Folder, "本地语音包"),
        PanelRailItem(VoiceDestination.TTS, MaterialSymbols.Outlined.Text_to_speech, "文字转语音"),
        PanelRailItem(VoiceDestination.ONLINE, MaterialSymbols.Outlined.Cloud, "在线语音包"),
        PanelRailItem(VoiceDestination.SHARED, MaterialSymbols.Outlined.Share, "共享语音包"),
        PanelRailItem(VoiceDestination.SETTINGS, MaterialSymbols.Outlined.Settings, "设置"),
    )

    val title = when (destination) {
        VoiceDestination.RECENT -> "最近使用"
        VoiceDestination.SEARCH -> "本地搜索"
        VoiceDestination.LOCAL -> "本地语音包"
        VoiceDestination.TTS -> "文字转语音"
        VoiceDestination.ONLINE -> "在线语音包"
        VoiceDestination.SHARED -> selectedSharedPack?.title ?: "我的共享语音包"
        VoiceDestination.SETTINGS -> "设置"
    }

    val batchCandidates = when (destination) {
        VoiceDestination.ONLINE -> (providerState as? PanelUiState.Content)?.value?.items
            .orEmpty().filterNot(VoiceItem::isContainer).distinctBy(::voiceSelectionKey)

        VoiceDestination.SHARED -> if (selectedSharedPack == null) emptyList() else {
            (sharedItemsState as? PanelUiState.Content)?.value.orEmpty().filter {
                sharedQuery.isBlank() || it.title.contains(sharedQuery, ignoreCase = true)
            }.distinctBy(::voiceSelectionKey)
        }

        else -> emptyList()
    }

    fun stopOnlineSave() {
        onlineSaveJob?.cancel()
        onlineSaveJob = null
        onlineSaveProgress = null
    }

    fun startVoiceSave(packId: String, items: List<VoiceItem>, title: String = "正在保存语音") {
        val uniqueItems = items.distinctBy(::voiceSelectionKey)
        if (uniqueItems.isEmpty()) return
        stopOnlineSave()
        batchMode = false
        selectedDownloadIds = emptySet()
        onlineSaveProgress = PanelSaveProgress(title, uniqueItems.size)
        onlineSaveJob = scope.launch {
            var succeeded = 0
            var failed = 0
            try {
                uniqueItems.parallelForEachWithProgress(
                    maxConcurrency = PANEL_BULK_DOWNLOAD_CONCURRENCY,
                    transform = { item -> actions.addToLocal(packId, item) },
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
                "已保存 $succeeded 条语音"
            } else {
                "保存完成：成功 $succeeded 条，失败 $failed 条"
            }
        }
    }

    fun showVoicePackPicker(items: List<VoiceItem>) {
        if (items.isEmpty()) return
        showPanelPackPicker(
            context = context,
            title = "保存到语音包",
            createLabel = "新建语音包",
            itemCountLabel = { count -> "$count 条语音" },
            packIcon = MaterialSymbols.Outlined.Folder,
            packs = editableLocalPacks.map { PanelPackChoice(it.id, it.title, it.itemCount) },
            onCreatePack = actions.createLocalPack,
            onSelect = { packId -> startVoiceSave(packId, items) },
        )
    }

    fun saveWholeVoicePack() {
        val parent = providerParent ?: return
        stopOnlineSave()
        onlineSaveProgress = PanelSaveProgress("正在读取语音包“${parent.title}”", 1)
        onlineSaveJob = scope.launch {
            val collected = mutableListOf<VoiceItem>()
            var page = 0
            var hasMore: Boolean
            do {
                ensureActive()
                val result = provider.browse(parent, page)
                if (result.isFailure) {
                    onlineSaveProgress = null
                    onlineSaveJob = null
                    operationMessage = result.exceptionOrNull()?.message ?: "语音包读取失败"
                    return@launch
                }
                val providerPage = result.getOrThrow()
                collected += providerPage.items.filterNot(VoiceItem::isContainer)
                hasMore = providerPage.hasMore
                page++
            } while (hasMore)
            val packId = actions.ensureLocalPack(parent.title)
            if (packId.isFailure) {
                onlineSaveProgress = null
                onlineSaveJob = null
                operationMessage = packId.exceptionOrNull()?.message ?: "无法创建本地语音包"
                return@launch
            }
            onlineSaveJob = null
            onlineSaveProgress = null
            startVoiceSave(packId.getOrThrow(), collected, "正在保存语音包“${parent.title}”")
        }
    }

    val panelActions = if (batchMode) {
        listOf(
            PanelAction(MaterialSymbols.Outlined.Close, "关闭", showLabel = true) {
                batchMode = false
                selectedDownloadIds = emptySet()
            },
            PanelAction(
                MaterialSymbols.Outlined.Select_all,
                "全选",
                enabled = batchCandidates.isNotEmpty(),
                showLabel = true,
            ) {
                selectedDownloadIds = batchCandidates.mapTo(linkedSetOf(), ::voiceSelectionKey)
            },
            PanelAction(MaterialSymbols.Outlined.Deselect, "反选", batchCandidates.isNotEmpty(), showLabel = true) {
                selectedDownloadIds = invertPanelSelection(selectedDownloadIds, batchCandidates, ::voiceSelectionKey)
            },
            PanelAction(MaterialSymbols.Outlined.Done_all, "连选", selectedDownloadIds.size > 1, showLabel = true) {
                selectedDownloadIds = closePanelSelectionRange(selectedDownloadIds, batchCandidates, ::voiceSelectionKey)
            },
            PanelAction(
                icon = MaterialSymbols.Outlined.Save,
                label = "保存",
                enabled = selectedDownloadIds.isNotEmpty(),
                showLabel = true,
                onClick = {
                    showVoicePackPicker(batchCandidates.filter { voiceSelectionKey(it) in selectedDownloadIds })
                },
            ),
        )
    } else when (destination) {
        VoiceDestination.LOCAL -> if (localCatalogVisible) {
            listOf(
                PanelAction(MaterialSymbols.Outlined.Add, "新建语音包") { prompt = VoicePrompt.CreateLocalPack },
                PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::refreshLocal),
            )
        } else buildList {
            if (localPackLayout == VoicePackLayout.LIST) {
                add(PanelAction(MaterialSymbols.Outlined.Arrow_back, "返回") { localPackDetailId = null })
            } else {
                add(PanelAction(MaterialSymbols.Outlined.Add, "新建语音包") { prompt = VoicePrompt.CreateLocalPack })
            }
            add(PanelAction(MaterialSymbols.Outlined.Edit, "重命名", selectedLocal != null) {
                selectedLocal?.let { prompt = VoicePrompt.RenameLocalPack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Delete, "删除", selectedLocal != null) {
                selectedLocal?.let { prompt = VoicePrompt.DeleteLocalPack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Upload_file, "导入", selectedLocal != null) {
                selectedLocal?.let { prompt = VoicePrompt.ImportLocal(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::refreshLocal))
        }

        VoiceDestination.SEARCH -> emptyList()
        VoiceDestination.ONLINE -> buildList {
            add(PanelAction(MaterialSymbols.Outlined.Arrow_back, "返回", providerParent != null) {
                providerParent = null
                providerRootSnapshot?.let { snapshot ->
                    providerPage = snapshot.page
                    providerQuery = snapshot.query
                    providerState = snapshot.state
                } ?: loadProvider(true)
                providerRootSnapshot = null
            })
            add(PanelAction(MaterialSymbols.Outlined.Refresh, "刷新") { loadProvider() })
            if (providerParent != null && batchCandidates.isNotEmpty()) {
                add(PanelAction(MaterialSymbols.Outlined.Select_all, "多选") {
                    batchMode = true
                    selectedDownloadIds = emptySet()
                })
                add(PanelAction(MaterialSymbols.Outlined.Save, "保存") { saveWholeVoicePack() })
            }
        }

        VoiceDestination.SHARED -> buildList {
            if (selectedSharedPack != null) {
                add(PanelAction(MaterialSymbols.Outlined.Arrow_back, "返回共享语音包") {
                    selectedSharedPack = null
                    sharedQuery = ""
                    sharedItemsRequest++
                    sharedItemsState = PanelUiState.Empty("选择一个语音包")
                })
            }
            if (batchCandidates.isNotEmpty()) {
                add(PanelAction(MaterialSymbols.Outlined.Select_all, "多选") {
                    batchMode = true
                    selectedDownloadIds = emptySet()
                })
            }
            add(PanelAction(MaterialSymbols.Outlined.Add, "新建语音包") { prompt = VoicePrompt.CreateSharedPack })
            add(PanelAction(MaterialSymbols.Outlined.Edit, "重命名", selectedSharedPack != null) {
                selectedSharedPack?.let { prompt = VoicePrompt.RenameSharedPack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Delete, "删除", selectedSharedPack != null) {
                selectedSharedPack?.let { prompt = VoicePrompt.DeleteSharedPack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Check_circle, "确认提交审核", selectedSharedPack != null) {
                selectedSharedPack?.let { prompt = VoicePrompt.ConfirmSharedPack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Upload_file, "上传语音", selectedSharedPack != null) {
                selectedSharedPack?.let { pack ->
                    actions.uploadSharedVoice(pack.id, { progressMessage = "正在上传语音..." }) { result ->
                        progressMessage = null
                        operationMessage = result.fold({ it }, { it.message ?: "上传失败" })
                        if (result.isSuccess) loadMySharedPacks()
                    }
                }
            })
            add(PanelAction(MaterialSymbols.Outlined.Refresh, "刷新", onClick = ::loadMySharedPacks))
        }

        else -> emptyList()
    }

    Box(Modifier.fillMaxSize()) {
        PanelShell(
            railItems = rail,
            selected = destination,
            title = title,
            actions = panelActions,
            onSelect = { destination = it },
            onDismiss = onDismiss,
            onBack = {
                when {
                    batchMode -> {
                        batchMode = false
                        selectedDownloadIds = emptySet()
                    }

                    destination == VoiceDestination.SHARED && sharedQuery.isNotBlank() -> {
                        sharedQuery = ""
                    }

                    destination == VoiceDestination.ONLINE && providerParent != null -> {
                        providerParent = null
                        providerRootSnapshot?.let { snapshot ->
                            providerPage = snapshot.page
                            providerQuery = snapshot.query
                            providerState = snapshot.state
                        } ?: loadProvider(true)
                        providerRootSnapshot = null
                    }

                    destination == VoiceDestination.SHARED && selectedSharedPack != null -> {
                        selectedSharedPack = null
                        sharedQuery = ""
                        sharedItemsRequest++
                        sharedItemsState = PanelUiState.Empty("选择一个语音包")
                    }

                    destination == VoiceDestination.LOCAL &&
                            localPackLayout == VoicePackLayout.LIST && localPackDetailId != null -> {
                        localPackDetailId = null
                    }

                    else -> onDismiss()
                }
            },
            titleContent = if (destination == VoiceDestination.RECENT) ({
                RecentModeTitle(recentMostUsed) { mostUsed ->
                    recentMostUsed = mostUsed
                    PanelSettings.voiceRecentSortMode = if (mostUsed) 1 else 0
                }
            }) else null,
        ) {
            CompositionLocalProvider(LocalVoiceDurationOverrides provides resolvedDurations) {
                when (destination) {
                    VoiceDestination.RECENT -> if (recent == null || recent.items.isEmpty()) {
                        PanelEmptyAction("还没有发送过语音")
                    } else {
                        VoiceList(
                            voices = recentItems,
                            playingId = playingId,
                            onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                            onSend = ::send,
                        )
                    }

                    VoiceDestination.SEARCH -> VoiceSearchContent(
                        packs = editableLocalPacks,
                        query = localQuery,
                        onQueryChange = { localQuery = it },
                        playingId = playingId,
                        onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                        onSend = ::send,
                    )

                    VoiceDestination.LOCAL -> LocalVoiceContent(
                        packs = editableLocalPacks,
                        layout = localPackLayout,
                        selected = selectedLocal,
                        playingId = playingId,
                        packListState = localPackListState,
                        itemListState = localItemListState,
                        onSelectPack = {
                            selectedLocalId = it.id
                            if (localPackLayout == VoicePackLayout.LIST) {
                                localPackDetailId = it.id
                                scope.launch { localItemListState.scrollToItem(0) }
                            }
                        },
                        onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                        onSend = ::send,
                        onImport = { selectedLocal?.let { prompt = VoicePrompt.ImportLocal(it) } },
                    )

                    VoiceDestination.TTS -> TtsContent(
                        mode = ttsMode,
                        text = ttsText,
                        converted = convertedTts != null,
                        selectedClone = selectedClone,
                        selectedEdgeVoice = selectedEdgeVoice,
                        onModeChange = { clearConvertedTts(); ttsMode = it },
                        onTextChange = { clearConvertedTts(); ttsText = it },
                        onSelectEdgeVoice = { voice ->
                            clearConvertedTts()
                            selectedEdgeVoice = voice
                            PanelSettings.selectedEdgeVoice = voice
                        },
                        onChooseOrManage = { managingClones = true },
                        onConvert = ::convertTts,
                        onPreviewConverted = {
                            convertedTts?.let { generated ->
                                preview("tts-converted", convertedTtsTitle) {
                                    Result.success(generated.copy(temporary = false))
                                }
                            }
                        },
                        onSendConverted = ::sendConvertedTts,
                        onSynthesize = {
                            val count = ttsText.codePointCount(0, ttsText.length)
                            if (ttsText.isBlank()) operationMessage = "转换文字不能为空"
                            else if (count > 256) operationMessage = "转换文字不能超过 256 个字符"
                            else {
                                progressMessage = "正在合成语音..."
                                scope.launch {
                                    val result = when (ttsMode) {
                                        TtsMode.SYSTEM -> actions.synthesizeSystem(ttsText)
                                        TtsMode.EDGE -> actions.synthesizeEdge(ttsText, selectedEdgeVoice)
                                        TtsMode.CLONE -> selectedClone?.let { actions.synthesizeClone(ttsText, it) }
                                            ?: Result.failure(IllegalStateException("请先选择音色"))
                                    }
                                    progressMessage = null
                                    showToastSuspend(context, result.exceptionOrNull()?.message ?: "语音发送成功")
                                    if (result.isSuccess && PanelSettings.voiceAutoClose) onDismiss()
                                }
                            }
                        },
                    )

                    VoiceDestination.ONLINE -> OnlineVoiceContent(
                        provider = provider,
                        query = providerQuery,
                        state = providerState,
                        playingId = playingId,
                        listState = if (providerParent == null) providerRootListState else providerChildListState,
                        onProvider = {
                            provider = it
                            PanelSettings.selectedVoiceProvider = it.id
                            providerParent = null
                            providerRootSnapshot = null
                            providerQuery = ""
                            scope.launch {
                                providerRootListState.scrollToItem(0)
                                providerChildListState.scrollToItem(0)
                            }
                            loadProvider(true)
                        },
                        onQueryChange = {
                            providerQuery = it
                            providerRequest++
                            providerState = PanelUiState.Empty(
                                if (it.isBlank()) "点击刷新浏览语音" else "点击搜索查找语音",
                            )
                        },
                        onSearch = { loadProvider(true) },
                        onOpen = { item ->
                            if (providerParent == null) {
                                providerRootSnapshot = ProviderRootSnapshot(
                                    page = providerPage,
                                    query = providerQuery,
                                    state = providerState,
                                )
                            }
                            providerParent = item
                            providerQuery = ""
                            scope.launch { providerChildListState.scrollToItem(0) }
                            loadProvider(true)
                        },
                        onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                        onSend = ::send,
                        onAdd = { item -> showVoicePackPicker(listOf(item)) },
                        selectable = batchMode,
                        selectedIds = selectedDownloadIds,
                        onToggleSelection = { item ->
                            val key = voiceSelectionKey(item)
                            selectedDownloadIds = selectedDownloadIds.toMutableSet().apply {
                                if (!add(key)) remove(key)
                            }
                        },
                        onPrevious = {
                            if (providerPage > 0) providerPage--
                            loadProvider()
                        },
                        onNext = {
                            providerPage++
                            loadProvider()
                        },
                        onRetry = { loadProvider() },
                    )

                    VoiceDestination.SHARED -> SharedVoiceContent(
                        packsState = sharedPacksState,
                        selectedPack = selectedSharedPack,
                        itemsState = sharedItemsState,
                        query = sharedQuery,
                        onQueryChange = { sharedQuery = it },
                        playingId = playingId,
                        selectable = batchMode,
                        selectedIds = selectedDownloadIds,
                        onToggleSelection = { item ->
                            val key = voiceSelectionKey(item)
                            selectedDownloadIds = selectedDownloadIds.toMutableSet().apply {
                                if (!add(key)) remove(key)
                            }
                        },
                        onSelectPack = { pack ->
                            selectedSharedPack = pack
                            sharedQuery = ""
                            val request = ++sharedItemsRequest
                            sharedItemsState = PanelUiState.Loading
                            scope.launch {
                                val result = actions.loadSharedPack(pack.id)
                                if (request != sharedItemsRequest || selectedSharedPack?.id != pack.id) return@launch
                                sharedItemsState = result.fold(
                                    {
                                        val uniqueItems = it.distinctBy(::voiceSelectionKey)
                                        if (uniqueItems.isEmpty()) PanelUiState.Empty("语音包中还没有语音")
                                        else PanelUiState.Content(uniqueItems)
                                    },
                                    { PanelUiState.Error(it.message ?: "语音包加载失败") },
                                )
                            }
                        },
                        onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                        onSend = ::send,
                        onRetry = ::loadMySharedPacks,
                    )

                    VoiceDestination.SETTINGS -> VoiceSettingsContent(
                        localPackLayout = localPackLayout,
                        onLocalPackLayoutChange = {
                            localPackLayout = it
                            localPackDetailId = null
                            PanelSettings.localVoicePackLayout = it
                        },
                    )
                }
            }
        }

        if (managingClones) {
            CloneManagerOverlay(
                clones = clones,
                selectedId = selectedCloneId,
                source = cloneSource,
                localPacks = editableLocalPacks,
                sharedPacksState = cloneSharedPacksState,
                sharedPack = cloneSharedPack,
                sharedItemsState = cloneSharedItemsState,
                examplesState = examples,
                selectedExampleGroup = selectedExampleGroup,
                playingId = playingId,
                onDismiss = {
                    managingClones = false
                    cloneSource = null
                    selectedExampleGroup = null
                    cloneSharedItemsRequest++
                    exampleGroupsRequest++
                    examplesRequest++
                },
                onSource = { source ->
                    cloneSource = source
                    if (source == SOURCE_EXAMPLES) loadExampleGroups()
                    if (source == SOURCE_SHARED && cloneSharedPacksState == PanelUiState.Loading) loadCloneSharedPacks()
                },
                onSelectNone = {
                    clearConvertedTts()
                    scope.launch {
                        actions.selectClone(null)
                            .onSuccess { selectedCloneId = "" }
                            .onFailure { operationMessage = it.message ?: "音色选择失败" }
                    }
                },
                onSelect = { voice ->
                    clearConvertedTts()
                    scope.launch {
                        actions.selectClone(voice.id).onSuccess { selectedCloneId = voice.id }
                            .onFailure { operationMessage = it.message ?: "音色选择失败" }
                    }
                },
                onDelete = { prompt = VoicePrompt.DeleteClone(it) },
                onImportFile = {
                    actions.importClone({ progressMessage = "正在导入音色..." }) { result ->
                        progressMessage = null
                        operationMessage = result.exceptionOrNull()?.message ?: "音色已导入"
                        if (result.isSuccess) refreshClones()
                    }
                },
                onChooseVoice = { prompt = VoicePrompt.NameCloneSource(it) },
                onPreviewVoice = { item ->
                    preview("clone-source:${item.id}", item.title) { actions.preview(item) }
                },
                onSelectSharedPack = { pack ->
                    cloneSharedPack = pack
                    val request = ++cloneSharedItemsRequest
                    cloneSharedItemsState = PanelUiState.Loading
                    scope.launch {
                        val result = actions.loadSharedPack(pack.id)
                        if (request != cloneSharedItemsRequest || cloneSharedPack?.id != pack.id) return@launch
                        cloneSharedItemsState = result.fold(
                            { if (it.isEmpty()) PanelUiState.Empty("语音包中没有可用语音") else PanelUiState.Content(it) },
                            { PanelUiState.Error(it.message ?: "读取共享语音包失败") },
                        )
                    }
                },
                onBackSharedPacks = {
                    cloneSharedItemsRequest++
                    cloneSharedPack = null
                },
                onBackSource = {
                    stopPreview()
                    cloneSharedItemsRequest++
                    exampleGroupsRequest++
                    examplesRequest++
                    cloneSharedPack = null
                    selectedExampleGroup = null
                    cloneSource = when (cloneSource) {
                        SOURCE_LOCAL, SOURCE_SHARED -> SOURCE_PANEL
                        SOURCE_EXAMPLES -> null
                        else -> null
                    }
                },
                onBackExamples = {
                    stopPreview()
                    examplesRequest++
                    selectedExampleGroup = null
                },
                onLoadGroups = ::loadExampleGroups,
                exampleGroupsState = exampleGroups,
                onSelectExampleGroup = { group ->
                    selectedExampleGroup = group
                    val request = ++examplesRequest
                    examples = PanelUiState.Loading
                    scope.launch {
                        val result = actions.loadExamples(group)
                        if (request != examplesRequest || selectedExampleGroup != group) return@launch
                        examples = result.fold(
                            { if (it.isEmpty()) PanelUiState.Empty("该目录下暂无 wav 语音") else PanelUiState.Content(it) },
                            { PanelUiState.Error(it.message ?: "读取语音示例失败") },
                        )
                    }
                },
                onPreviewExample = { example ->
                    preview("example:${example.group}/${example.fileName}", example.title) {
                        actions.previewExample(example)
                    }
                },
                onAddExample = { example ->
                    progressMessage = "正在导入语音示例..."
                    scope.launch {
                        val result = actions.addExample(example)
                        progressMessage = null
                        operationMessage = result.exceptionOrNull()?.message ?: "音色已导入"
                        refreshClones()
                    }
                },
            )
        }

        previewTitle?.let { title ->
            VoicePreviewOverlay(
                title = title,
                playing = previewPlaying,
                positionMs = previewPositionMs,
                durationMs = previewDurationMs,
                sizeBytes = previewSizeBytes,
                mime = previewMime,
                onToggle = ::togglePreviewPlayback,
                onSeek = ::seekPreview,
                onDismiss = ::stopPreview,
            )
        }

        progressMessage?.let { PanelProgressOverlay(it) }
        onlineSaveProgress?.let { progress ->
            PanelSaveProgressOverlay(progress, onCancel = ::stopOnlineSave)
        }

        when (val current = prompt) {
            VoicePrompt.CreateLocalPack -> PanelTextPrompt("新建语音包", "语音包名称", confirmText = "创建", onDismiss = { prompt = null }) { name ->
                scope.launch {
                    val result = actions.createLocalPack(name)
                    prompt = null
                    operationMessage = result.exceptionOrNull()?.message ?: "语音包已创建"
                    if (result.isSuccess) refreshLocal()
                }
            }

            is VoicePrompt.ImportLocal -> VoiceImportModePrompt(
                onDismiss = { prompt = null },
                onSelect = { mode ->
                    prompt = null
                    actions.importVoice(current.pack.id, mode, { progressMessage = "正在导入语音..." }) { result ->
                        progressMessage = null
                        operationMessage = result.exceptionOrNull()?.message ?: "语音导入完成"
                        if (result.isSuccess) refreshLocal()
                    }
                },
            )

            is VoicePrompt.RenameLocalPack -> PanelTextPrompt(
                "重命名语音包",
                "语音包名称",
                current.pack.title,
                "保存",
                onDismiss = { prompt = null },
            ) { name ->
                scope.launch {
                    val result = actions.renameLocalPack(current.pack.id, name)
                    prompt = null
                    operationMessage = result.exceptionOrNull()?.message ?: "语音包已重命名"
                    if (result.isSuccess) refreshLocal()
                }
            }

            is VoicePrompt.DeleteLocalPack -> PanelConfirmation("删除语音包", "删除“${current.pack.title}”及其中的语音？", "删除", { prompt = null }) {
                scope.launch {
                    val result = actions.deleteLocalPack(current.pack.id)
                    prompt = null
                    operationMessage = result.exceptionOrNull()?.message ?: "语音包已删除"
                    if (result.isSuccess) refreshLocal()
                }
            }

            VoicePrompt.CreateSharedPack -> PanelTextPrompt("新建共享语音包", "语音包名称", confirmText = "确定创建", onDismiss = { prompt = null }) { name ->
                scope.launch {
                    val result = actions.createSharedPack(name)
                    operationMessage = result.fold({ it }, { it.message })
                    prompt = null
                    if (result.isSuccess) loadMySharedPacks()
                }
            }

            is VoicePrompt.RenameSharedPack -> PanelTextPrompt(
                "设置新的名字",
                "语音包名称",
                current.pack.title,
                "确认",
                onDismiss = { prompt = null },
            ) { name ->
                scope.launch {
                    val result = actions.renameSharedPack(current.pack.id, name)
                    operationMessage = result.fold({ it }, { it.message })
                    prompt = null
                    if (result.isSuccess) loadMySharedPacks()
                }
            }

            is VoicePrompt.DeleteSharedPack -> PanelConfirmation("删除语音包", "是否删除语音包 ${current.pack.title}？", "确认删除", { prompt = null }) {
                scope.launch {
                    val result = actions.deleteSharedPack(current.pack.id)
                    operationMessage = result.fold({ it }, { it.message })
                    prompt = null
                    if (result.isSuccess) {
                        selectedSharedPack = null
                        sharedItemsRequest++
                        sharedItemsState = PanelUiState.Empty("选择一个语音包")
                        loadMySharedPacks()
                    }
                }
            }

            is VoicePrompt.ConfirmSharedPack -> PanelConfirmation(
                "确认语音包",
                "确认后语音包将进入审核阶段，之后不能再删除或修改名字。",
                "确认",
                { prompt = null },
            ) {
                scope.launch {
                    val result = actions.confirmSharedPack(current.pack.id)
                    operationMessage = result.fold({ it }, { it.message })
                    prompt = null
                    if (result.isSuccess) loadMySharedPacks()
                }
            }

            is VoicePrompt.NameCloneSource -> PanelTextPrompt(
                "导入音色",
                "音色名称",
                current.item.title,
                "导入",
                onDismiss = { prompt = null },
            ) { name ->
                progressMessage = "正在导入音色..."
                scope.launch {
                    val result = actions.importCloneFromVoice(name, current.item)
                    progressMessage = null
                    operationMessage = result.exceptionOrNull()?.message ?: "音色已导入"
                    prompt = null
                    refreshClones()
                }
            }

            is VoicePrompt.DeleteClone -> PanelConfirmation("删除音色", "确定删除音色 ${current.voice.name} 吗？", "删除", { prompt = null }) {
                scope.launch {
                    val result = actions.deleteClone(current.voice.id)
                    prompt = null
                    operationMessage = result.exceptionOrNull()?.message ?: "音色已删除"
                    if (result.isSuccess) refreshClones()
                }
            }

            null -> Unit
        }
    }
}

@Composable
private fun VoiceImportModePrompt(
    onDismiss: () -> Unit,
    onSelect: (VoiceImportMode) -> Unit,
) {
    PanelImportModePrompt(
        options = listOf(
            PanelImportOption(
                mode = VoiceImportMode.MULTIPLE_FILES,
                title = "选择多个语音文件",
                description = "从系统文件选择器一次选择一个或多个文件",
                icon = MaterialSymbols.Outlined.Upload_file,
            ),
            PanelImportOption(
                mode = VoiceImportMode.DIRECTORY,
                title = "导入整个目录",
                description = "递归导入所选目录内支持的语音文件",
                icon = MaterialSymbols.Outlined.Folder,
            ),
        ),
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}

@Composable
private fun LocalVoiceContent(
    packs: List<VoicePack>,
    layout: VoicePackLayout,
    selected: VoicePack?,
    playingId: String?,
    packListState: LazyListState,
    itemListState: LazyListState,
    onSelectPack: (VoicePack) -> Unit,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onImport: () -> Unit,
) {
    if (layout == VoicePackLayout.TABS) {
        Column(Modifier.fillMaxSize()) {
            if (packs.isNotEmpty()) {
                PanelPackChips(
                    packs = packs,
                    selectedId = selected?.id,
                    id = VoicePack::id,
                    title = VoicePack::title,
                    onSelect = onSelectPack,
                )
            }
            if (selected == null) {
                PanelEmptyAction("暂无本地语音包", "请先新建语音包")
            } else if (selected.items.isEmpty()) {
                PanelEmptyAction("语音包中还没有语音", "从文件导入", onImport)
            } else {
                VoiceList(selected.items, playingId, onPreview, onSend)
            }
        }
    } else if (selected == null) {
        if (packs.isEmpty()) {
            PanelEmptyAction("暂无本地语音包", "请先新建语音包")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = packListState,
            ) {
                items(packs, key = VoicePack::id) { pack ->
                    ListItem(
                        modifier = Modifier.clickable { onSelectPack(pack) },
                        colors = panelListItemColors(),
                        headlineContent = {
                            Text(pack.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = { Text("${pack.itemCount} 条语音") },
                        leadingContent = { Icon(MaterialSymbols.Outlined.Folder, null) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    } else if (selected.items.isEmpty()) {
        PanelEmptyAction("语音包中还没有语音", "从文件导入", onImport)
    } else {
        VoiceList(
            voices = selected.items,
            playingId = playingId,
            onPreview = onPreview,
            onSend = onSend,
            listState = itemListState,
        )
    }
}

@Composable
private fun VoiceSearchContent(
    packs: List<VoicePack>,
    query: String,
    onQueryChange: (String) -> Unit,
    playingId: String?,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
) {
    val results = remember(packs, query) {
        val term = query.trim()
        if (term.isBlank()) emptyList()
        else packs.flatMap { pack ->
            pack.items.filter {
                it.title.contains(term, ignoreCase = true) || pack.title.contains(term, ignoreCase = true)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PanelSearchField(
            value = query,
            onValueChange = onQueryChange,
            label = "搜索本地语音",
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) PanelEmptyAction("输入文件名或语音包名称")
            else if (results.isEmpty()) PanelEmptyAction("没有找到本地语音")
            else VoiceList(results, playingId, onPreview, onSend)
        }
    }
}

@Composable
private fun VoiceList(
    voices: List<VoiceItem>,
    playingId: String?,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onAdd: ((VoiceItem) -> Unit)? = null,
    onOpen: ((VoiceItem) -> Unit)? = null,
    listState: LazyListState? = null,
    selectable: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelection: ((VoiceItem) -> Unit)? = null,
    terminalActionIcon: androidx.compose.ui.graphics.vector.ImageVector = MaterialSymbols.Outlined.Send,
    terminalActionLabel: String = "发送",
) {
    val resolvedListState = listState ?: rememberLazyListState()
    val durationOverrides = LocalVoiceDurationOverrides.current
    LazyColumn(Modifier.fillMaxSize(), state = resolvedListState) {
        itemsIndexed(voices, key = { index, voice -> "${voiceSelectionKey(voice)}#$index" }) { _, voice ->
            val durationMs = durationOverrides[voiceSelectionKey(voice)] ?: voice.durationMs
            ListItem(
                modifier = Modifier.clickable {
                    if (selectable && !voice.isContainer) onToggleSelection?.invoke(voice)
                    else if (voice.isContainer) onOpen?.invoke(voice) else onPreview(voice)
                },
                colors = panelListItemColors(),
                leadingContent = {
                    if (selectable && !voice.isContainer) {
                        Checkbox(
                            checked = voiceSelectionKey(voice) in selectedIds,
                            onCheckedChange = { onToggleSelection?.invoke(voice) },
                        )
                    } else {
                        Icon(
                            if (voice.isContainer) MaterialSymbols.Outlined.Folder else MaterialSymbols.Outlined.Mic,
                            null,
                        )
                    }
                },
                headlineContent = { Text(voice.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (durationMs > 0) ({
                    Text(formatDuration(durationMs))
                }) else null,
                trailingContent = if (voice.isContainer || selectable) null else ({
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SendCountBadge(voice.sendCount, Modifier.padding(end = 2.dp))
                        IconButton(onClick = { onPreview(voice) }) {
                            Icon(
                                if (playingId == voice.id) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                                if (playingId == voice.id) "暂停" else "试听",
                            )
                        }
                        if (onAdd != null) {
                            IconButton(onClick = { onAdd(voice) }) {
                                Icon(MaterialSymbols.Outlined.Download, "添加到本地")
                            }
                        }
                        IconButton(onClick = { onSend(voice) }) {
                            Icon(terminalActionIcon, terminalActionLabel)
                        }
                    }
                }),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
}

private fun voiceSelectionKey(item: VoiceItem): String =
    item.remoteObjectId ?: item.remoteUrl ?: item.id

@Composable
private fun OnlineVoiceContent(
    provider: VoiceProvider,
    query: String,
    state: PanelUiState<VoiceProviderPage>,
    playingId: String?,
    listState: LazyListState,
    onProvider: (VoiceProvider) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (VoiceItem) -> Unit,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onAdd: ((VoiceItem) -> Unit)?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    selectable: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelection: ((VoiceItem) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(VoiceProviderRegistry.providers, key = { it.id }) { item ->
                FilterChip(item.id == provider.id, { onProvider(item) }, label = { Text(item.name) })
            }
        }
        PanelSearchField(
            value = query,
            onValueChange = onQueryChange,
            label = "搜索语音",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            onSearch = onSearch,
        )
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            PanelStateContent(state, onRetry) { page ->
                VoiceList(
                    voices = page.items,
                    playingId = playingId,
                    onPreview = onPreview,
                    onSend = onSend,
                    onAdd = onAdd,
                    onOpen = onOpen,
                    listState = listState,
                    selectable = selectable,
                    selectedIds = selectedIds,
                    onToggleSelection = onToggleSelection,
                )
            }
        }
        if (state is PanelUiState.Content) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onPrevious, enabled = state.value.page > 0) { Text("上一页") }
                Text("第 ${state.value.page + 1} 页", Modifier.padding(horizontal = 12.dp))
                OutlinedButton(onClick = onNext, enabled = state.value.hasMore) { Text("下一页") }
            }
        }
    }
}

@Composable
private fun SharedVoiceContent(
    packsState: PanelUiState<List<VoicePack>>,
    selectedPack: VoicePack?,
    itemsState: PanelUiState<List<VoiceItem>>,
    query: String,
    onQueryChange: (String) -> Unit,
    playingId: String?,
    onSelectPack: (VoicePack) -> Unit,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onRetry: () -> Unit,
    selectable: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelection: ((VoiceItem) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        PanelSearchField(
            value = query,
            onValueChange = onQueryChange,
            label = if (selectedPack == null) "搜索共享语音包" else "搜索共享语音",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        PanelStateContent(packsState, onRetry) { packs ->
            val visiblePacks = packs.filter {
                query.isBlank() || it.title.contains(query, ignoreCase = true) ||
                        it.badge?.contains(query, ignoreCase = true) == true
            }
            if (selectedPack == null) {
                if (visiblePacks.isEmpty()) PanelEmptyAction("没有找到共享语音包")
                else PanelPackChips(
                    packs = visiblePacks,
                    selectedId = null,
                    id = VoicePack::id,
                    title = VoicePack::title,
                    onSelect = onSelectPack,
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (selectedPack == null) {
                PanelEmptyAction("选择一个共享语音包")
            } else {
                PanelStateContent(itemsState) { voices ->
                    val visibleVoices = voices.filter {
                        query.isBlank() || it.title.contains(query, ignoreCase = true)
                    }
                    if (visibleVoices.isEmpty()) PanelEmptyAction("没有找到共享语音")
                    else VoiceList(
                        voices = visibleVoices,
                        playingId = playingId,
                        onPreview = onPreview,
                        onSend = onSend,
                        selectable = selectable,
                        selectedIds = selectedIds,
                        onToggleSelection = onToggleSelection,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceSettingsContent(
    localPackLayout: VoicePackLayout,
    onLocalPackLayoutChange: (VoicePackLayout) -> Unit,
) {
    var maxHistory by remember { mutableLongStateOf(PanelSettings.voiceMaxHistory.coerceAtLeast(1L)) }
    var sortType by remember { mutableIntStateOf(PanelSettings.voiceSortType) }
    var autoClose by remember { mutableStateOf(PanelSettings.voiceAutoClose) }
    var clientIdPrompt by remember { mutableStateOf(false) }
    var historyPrompt by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { PanelFunBoxApiClientIdSetting { clientIdPrompt = true } }
            item {
                PanelDropdownSetting(
                    title = "本地语音包一级界面",
                    selected = localPackLayout,
                    options = listOf(
                        VoicePackLayout.TABS to "Tab 栏",
                        VoicePackLayout.LIST to "列表",
                    ),
                    onSelected = onLocalPackLayoutChange,
                )
            }
            panelCollectionSettings(
                maxHistory = maxHistory,
                onMaxHistoryChange = {
                    maxHistory = it
                    PanelSettings.voiceMaxHistory = it
                },
                onCustomHistory = { historyPrompt = true },
                newestFirst = sortType == 1,
                onNewestFirstChange = {
                    sortType = if (it) 1 else 0
                    PanelSettings.voiceSortType = sortType
                },
                autoClose = autoClose,
                onAutoCloseChange = {
                    autoClose = it
                    PanelSettings.voiceAutoClose = it
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
        if (historyPrompt) PanelNumberPrompt(
            title = "最大历史数量",
            label = "数量（至少 1）",
            initialValue = maxHistory,
            minValue = 1,
            onDismiss = { historyPrompt = false },
            onConfirm = {
                maxHistory = it
                PanelSettings.voiceMaxHistory = it
                historyPrompt = false
            },
        )
    }
}

@Composable
private fun CloneManagerOverlay(
    clones: List<CloneVoice>,
    selectedId: String,
    source: String?,
    localPacks: List<VoicePack>,
    sharedPacksState: PanelUiState<List<VoicePack>>,
    sharedPack: VoicePack?,
    sharedItemsState: PanelUiState<List<VoiceItem>>,
    examplesState: PanelUiState<List<CloneExample>>,
    selectedExampleGroup: String?,
    playingId: String?,
    onDismiss: () -> Unit,
    onSource: (String) -> Unit,
    onSelectNone: () -> Unit,
    onSelect: (CloneVoice) -> Unit,
    onDelete: (CloneVoice) -> Unit,
    onImportFile: () -> Unit,
    onChooseVoice: (VoiceItem) -> Unit,
    onPreviewVoice: (VoiceItem) -> Unit,
    onSelectSharedPack: (VoicePack) -> Unit,
    onBackSharedPacks: () -> Unit,
    onBackSource: () -> Unit,
    onBackExamples: () -> Unit,
    onLoadGroups: () -> Unit,
    exampleGroupsState: PanelUiState<List<String>>,
    onSelectExampleGroup: (String) -> Unit,
    onPreviewExample: (CloneExample) -> Unit,
    onAddExample: (CloneExample) -> Unit,
) {
    val onSystemBack = when {
        source == SOURCE_EXAMPLES && selectedExampleGroup != null -> onBackExamples
        source == SOURCE_SHARED && sharedPack != null -> onBackSharedPacks
        source != null -> onBackSource
        else -> onDismiss
    }
    PanelPageOverlay(onDismiss = onDismiss, onBack = onSystemBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (source != null) {
                IconButton(onClick = onSystemBack) { Icon(MaterialSymbols.Outlined.Arrow_back, "返回") }
            }
            Text("选择或管理音色", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(MaterialSymbols.Outlined.Close, "关闭") }
        }
        when (source) {
            null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) { Text("从本地导入") }
                    OutlinedButton(onClick = { onSource(SOURCE_PANEL) }, modifier = Modifier.weight(1f)) { Text("从语音面板选择") }
                }
                OutlinedButton(onClick = { onSource(SOURCE_EXAMPLES) }, modifier = Modifier.fillMaxWidth()) {
                    Text("语音示例")
                }
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable(onClick = onSelectNone),
                            colors = panelListItemColors(),
                            headlineContent = { Text("无") },
                            supportingContent = { Text("不使用克隆音色") },
                            leadingContent = {
                                RadioButton(selected = selectedId.isBlank(), onClick = onSelectNone)
                            },
                        )
                    }
                    items(clones, key = { it.id }) { voice ->
                        ListItem(
                            modifier = Modifier.clickable { onSelect(voice) },
                            colors = panelListItemColors(),
                            headlineContent = { Text(voice.name) },
                            leadingContent = {
                                RadioButton(selected = voice.id == selectedId, onClick = { onSelect(voice) })
                            },
                            trailingContent = {
                                IconButton(onClick = { onDelete(voice) }) {
                                    Icon(MaterialSymbols.Outlined.Delete, "删除音色")
                                }
                            },
                        )
                    }
                }
            }

            SOURCE_PANEL -> {
                Text("从语音面板选择", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSource(SOURCE_LOCAL) }, modifier = Modifier.weight(1f)) { Text("本地语音包") }
                    OutlinedButton(onClick = { onSource(SOURCE_SHARED) }, modifier = Modifier.weight(1f)) { Text("共享语音包") }
                }
            }

            SOURCE_LOCAL -> VoiceList(
                localPacks.flatMap { it.items },
                playingId,
                onPreview = onPreviewVoice,
                onSend = onChooseVoice,
                terminalActionIcon = MaterialSymbols.Outlined.Check_circle,
                terminalActionLabel = "选择为克隆来源",
            )

            SOURCE_SHARED -> if (sharedPack == null) {
                PanelStateContent(sharedPacksState) { packs ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(packs) { pack ->
                            ListItem(
                                modifier = Modifier.clickable { onSelectSharedPack(pack) },
                                colors = panelListItemColors(),
                                headlineContent = { Text(pack.title) },
                                supportingContent = { Text("${pack.itemCount} 条语音") },
                                leadingContent = { Icon(MaterialSymbols.Outlined.Folder, null) },
                            )
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = onBackSharedPacks) {
                    Icon(MaterialSymbols.Outlined.Arrow_back, null)
                    Text("返回共享语音包")
                }
                Text(sharedPack.title, style = MaterialTheme.typography.titleSmall)
                Box(Modifier.weight(1f)) {
                    PanelStateContent(sharedItemsState) { voices ->
                        VoiceList(
                            voices,
                            playingId,
                            onPreview = onPreviewVoice,
                            onSend = onChooseVoice,
                            terminalActionIcon = MaterialSymbols.Outlined.Check_circle,
                            terminalActionLabel = "选择为克隆来源",
                        )
                    }
                }
            }

            SOURCE_EXAMPLES -> {
                if (selectedExampleGroup == null) {
                    PanelStateContent(exampleGroupsState, onLoadGroups) { groups ->
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(groups) { group ->
                                ListItem(
                                    modifier = Modifier.clickable { onSelectExampleGroup(group) },
                                    colors = panelListItemColors(),
                                    headlineContent = { Text(group) },
                                    leadingContent = { Icon(MaterialSymbols.Outlined.Folder, null) },
                                )
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBackExamples) { Icon(MaterialSymbols.Outlined.Arrow_back, "返回示例目录") }
                        Text("语音示例 / $selectedExampleGroup", style = MaterialTheme.typography.titleSmall)
                    }
                    PanelStateContent(examplesState) { examples ->
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(examples, key = { "${it.group}/${it.fileName}" }) { example ->
                                ListItem(
                                    colors = panelListItemColors(),
                                    headlineContent = {
                                        Text(example.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = { onPreviewExample(example) }) {
                                                Icon(MaterialSymbols.Outlined.Play_arrow, "试听")
                                            }
                                            IconButton(onClick = { onAddExample(example) }) {
                                                Icon(MaterialSymbols.Outlined.Download, "添加到本地")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePreviewOverlay(
    title: String,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    sizeBytes: Long,
    mime: String,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    PanelFullOverlay(onDismiss) {
        Text("语音预览", style = MaterialTheme.typography.titleMedium)
        Text(
            title,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                    if (playing) "暂停" else "继续",
                )
            }
            Text(
                "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(formatFileSize(sizeBytes), style = MaterialTheme.typography.bodySmall)
                Text(
                    mime,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val SOURCE_PANEL = "panel"
private const val SOURCE_LOCAL = "local"
private const val SOURCE_SHARED = "shared"
private const val SOURCE_EXAMPLES = "examples"

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun resolveAudioMime(path: String): String {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            ?: fallbackAudioMime(path)
    } catch (_: Throwable) {
        fallbackAudioMime(path)
    } finally {
        retriever.release()
    }
}

private fun fallbackAudioMime(path: String): String = when (path.asPath.extension.lowercase()) {
    "mp3" -> "audio/mpeg"
    "aac", "m4a" -> "audio/aac"
    "wav" -> "audio/wav"
    "amr" -> "audio/amr"
    "silk" -> "audio/silk"
    else -> "application/octet-stream"
}
