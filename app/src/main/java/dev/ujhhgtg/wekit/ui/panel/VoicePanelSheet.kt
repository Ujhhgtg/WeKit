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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.ujhhgtg.wekit.ui.utils.ListItem
import dev.ujhhgtg.wekit.ui.utils.ReorderableList
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Manage_search
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Text_to_speech
import com.composables.icons.materialsymbols.outlined.Travel_explore
import com.composables.icons.materialsymbols.outlined.Upload_file
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneExample
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneVoice
import dev.ujhhgtg.wekit.features.items.chat.panel.LocalSortMode
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSource
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelUiState
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelUiText
import dev.ujhhgtg.wekit.features.items.chat.panel.RECENT_PACK_ID
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceDestination
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceItem
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePack
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePackLayout
import dev.ujhhgtg.wekit.features.items.chat.panel.VoicePreview
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceProviderPage
import dev.ujhhgtg.wekit.features.items.chat.panel.parallelForEachWithProgress
import dev.ujhhgtg.wekit.features.items.chat.panel.panelUiText
import dev.ujhhgtg.wekit.features.items.chat.panel.toPanelUiText
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.VoiceProvider
import dev.ujhhgtg.wekit.features.items.chat.panel.voice.VoiceProviderRegistry
import dev.ujhhgtg.wekit.utils.MediaFileTypeDetector
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val deleteLocalVoices: suspend (List<String>) -> Result<Int> = {
        Result.failure(UnsupportedOperationException())
    },
    val savePackOrder: suspend (List<String>) -> Result<Unit> = {
        Result.failure(UnsupportedOperationException())
    },
    val saveItemOrder: suspend (String, List<String>) -> Result<Unit> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
    val preview: suspend (VoiceItem) -> Result<VoicePreview> = { Result.failure(UnsupportedOperationException()) },
    val releasePreview: (VoicePreview) -> Unit = {},
    val send: suspend (VoiceItem) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
    val ensureLocalPack: suspend (String, String?) -> Result<String> = { _, _ ->
        Result.failure(UnsupportedOperationException())
    },
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

private enum class VoiceReorderTarget {
    PACKS,
    ITEMS,
}

fun showVoicePanelSheet(
    context: Context,
    actions: VoicePanelActions,
    onDismiss: () -> Unit = {},
) {
    showPanelDialog(context, onDismiss) {
        VoicePanelContent(
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
    data class DeleteLocalVoices(val items: List<VoiceItem>) : VoicePrompt
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
    val state: PanelUiState<VoiceProviderPage>,
)

@Composable
private fun VoicePanelContent(
    actions: VoicePanelActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val localizedContext = LocalWeKitLocalizedContext.current
    val currentLocalizedContext by rememberUpdatedState(localizedContext)
    val scope = rememberCoroutineScope()
    val rememberedNavigation = remember {
        PanelNavigationMemory.voice.takeIf { PanelSettings.rememberPanelNavigation }
    }
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
    var localPacks by remember { mutableStateOf<List<VoicePack>>(emptyList()) }
    var localState by remember { mutableStateOf<PanelUiState<Unit>>(PanelUiState.Loading) }
    var selectedLocalId by remember {
        mutableStateOf(rememberedNavigation?.selectedLocalPackId)
    }
    var localPackDetailId by remember { mutableStateOf(rememberedNavigation?.localPackDetailId) }
    var localPackLayout by remember { mutableStateOf(PanelSettings.localVoicePackLayout) }
    var wrapActions by remember { mutableStateOf(PanelSettings.wrapPanelActions) }
    var localQuery by remember { mutableStateOf("") }
    var localPackFilterQuery by remember { mutableStateOf("") }
    var localPackFilterExpanded by remember { mutableStateOf(false) }
    var destination by remember {
        mutableStateOf(
            rememberedNavigation?.destination
                ?: VoiceDestination.entries.firstOrNull { it.name == PanelSettings.voiceLastDestination }
                ?: VoiceDestination.RECENT,
        )
    }
    var prompt by remember { mutableStateOf<VoicePrompt?>(null) }
    var operationMessage by remember { mutableStateOf<PanelUiText?>(null) }
    var progressMessage by remember { mutableStateOf<PanelUiText?>(null) }
    var ttsMode by remember { mutableStateOf(rememberedNavigation?.ttsMode ?: TtsMode.EDGE) }
    var ttsText by remember { mutableStateOf("") }
    var selectedEdgeVoice by remember { mutableStateOf(PanelSettings.selectedEdgeVoice) }
    var convertedTts by remember { mutableStateOf<VoicePreview?>(null) }
    var convertedTtsTitle by remember { mutableStateOf<PanelUiText?>(null) }
    var clones by remember { mutableStateOf<List<CloneVoice>>(emptyList()) }
    var selectedCloneId by remember { mutableStateOf("") }
    var managingClones by remember { mutableStateOf(rememberedNavigation?.managingClones == true) }
    var cloneSource by remember { mutableStateOf(rememberedNavigation?.cloneSource) }
    var exampleGroups by remember { mutableStateOf<PanelUiState<List<String>>>(PanelUiState.Loading) }
    var selectedExampleGroup by remember { mutableStateOf(rememberedNavigation?.selectedExampleGroup) }
    var examples by remember { mutableStateOf<PanelUiState<List<CloneExample>>>(PanelUiState.Loading) }
    var provider by remember {
        mutableStateOf(
            VoiceProviderRegistry.get(
                rememberedNavigation?.providerId ?: PanelSettings.selectedVoiceProvider,
            ),
        )
    }
    var providerParent by remember { mutableStateOf(rememberedNavigation?.providerParent) }
    var providerPage by remember { mutableIntStateOf(rememberedNavigation?.providerPage ?: 0) }
    var providerFilterQuery by remember { mutableStateOf("") }
    var providerSearchExpanded by remember { mutableStateOf(false) }
    var providerState by remember { mutableStateOf<PanelUiState<VoiceProviderPage>>(PanelUiState.Loading) }
    var providerRootSnapshot by remember { mutableStateOf<ProviderRootSnapshot?>(null) }
    var providerRequest by remember { mutableIntStateOf(0) }
    var onlineSearchQuery by remember { mutableStateOf(rememberedNavigation?.onlineSearchQuery.orEmpty()) }
    var onlineSearchParent by remember { mutableStateOf(rememberedNavigation?.onlineSearchParent) }
    var onlineSearchPage by remember { mutableIntStateOf(rememberedNavigation?.onlineSearchPage ?: 0) }
    var onlineSearchExecuted by remember {
        mutableStateOf(rememberedNavigation?.onlineSearchExecuted == true)
    }
    var onlineSearchState by remember {
        mutableStateOf<PanelUiState<VoiceProviderPage>>(
            if (onlineSearchExecuted || onlineSearchParent != null) {
                PanelUiState.Loading
            } else {
                PanelUiState.Empty(panelUiText(R.string.voice_panel_search_prompt))
            },
        )
    }
    var onlineSearchRootSnapshot by remember { mutableStateOf<ProviderRootSnapshot?>(null) }
    var onlineSearchRequest by remember { mutableIntStateOf(0) }
    var sharedPacksState by remember { mutableStateOf<PanelUiState<List<VoicePack>>>(PanelUiState.Loading) }
    var sharedPacksRequest by remember { mutableIntStateOf(0) }
    var selectedSharedPack by remember { mutableStateOf(rememberedNavigation?.selectedSharedPack) }
    var sharedQuery by remember { mutableStateOf("") }
    var sharedSearchExpanded by remember { mutableStateOf(false) }
    var sharedItemsState by remember {
        mutableStateOf<PanelUiState<List<VoiceItem>>>(
            PanelUiState.Empty(panelUiText(R.string.voice_panel_select_pack)),
        )
    }
    var sharedItemsRequest by remember { mutableIntStateOf(0) }
    var cloneSharedPack by remember { mutableStateOf(rememberedNavigation?.cloneSharedPack) }
    var cloneSharedPacksState by remember { mutableStateOf<PanelUiState<List<VoicePack>>>(PanelUiState.Loading) }
    var cloneSharedPacksRequest by remember { mutableIntStateOf(0) }
    var cloneSharedItemsState by remember {
        mutableStateOf<PanelUiState<List<VoiceItem>>>(
            PanelUiState.Empty(panelUiText(R.string.voice_panel_select_shared_pack)),
        )
    }
    var cloneSharedItemsRequest by remember { mutableIntStateOf(0) }
    var exampleGroupsRequest by remember { mutableIntStateOf(0) }
    var examplesRequest by remember { mutableIntStateOf(0) }
    var localRequest by remember { mutableIntStateOf(0) }
    var recentMostUsed by remember { mutableStateOf(PanelSettings.voiceRecentSortMode == 1) }
    var localPackSortMode by remember { mutableStateOf(PanelSettings.voicePackSortMode) }
    var localItemSortMode by remember { mutableStateOf(PanelSettings.voiceItemSortMode) }
    var reorderTarget by remember { mutableStateOf<VoiceReorderTarget?>(null) }
    var reorderPackId by remember { mutableStateOf<String?>(null) }
    var reorderKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var batchMode by remember { mutableStateOf(false) }
    var selectedDownloadIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlineSaveProgress by remember { mutableStateOf<PanelSaveProgress?>(null) }
    var onlineSaveJob by remember { mutableStateOf<Job?>(null) }
    val providerRootListState = rememberLazyListState()
    val providerChildListState = rememberLazyListState()
    val onlineSearchRootListState = rememberLazyListState()
    val onlineSearchChildListState = rememberLazyListState()
    val localPackListState = rememberLazyListState()
    val localItemListState = rememberLazyListState()
    val sharedPackListState = rememberLazyListState()
    val sharedItemListState = rememberLazyListState()
    val navigationSnapshot by rememberUpdatedState(
        VoicePanelNavigation(
            destination = destination,
            selectedLocalPackId = selectedLocalId,
            localPackDetailId = localPackDetailId,
            ttsMode = ttsMode,
            managingClones = managingClones,
            cloneSource = cloneSource,
            cloneSharedPack = cloneSharedPack,
            selectedExampleGroup = selectedExampleGroup,
            providerId = provider.id,
            providerParent = providerParent,
            providerPage = providerPage,
            onlineSearchQuery = onlineSearchQuery,
            onlineSearchParent = onlineSearchParent,
            onlineSearchPage = onlineSearchPage,
            onlineSearchExecuted = onlineSearchExecuted,
            selectedSharedPack = selectedSharedPack,
        ),
    )

    DisposableEffect(Unit) {
        onDispose {
            if (PanelSettings.rememberPanelNavigation) {
                PanelNavigationMemory.voice = navigationSnapshot
            } else {
                PanelNavigationMemory.voice = null
            }
        }
    }

    DisposableEffect(convertedTts) {
        val generated = convertedTts
        onDispose {
            generated?.let(actions.releasePreview)
        }
    }

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
                if (selectedLocalId !in localPacks.map { it.id }) {
                    selectedLocalId = localPacks.firstOrNull { it.id != RECENT_PACK_ID }?.id
                }
                if (localPackDetailId !in localPacks.map { it.id }) {
                    localPackDetailId = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (request != localRequest) return@launch
                if (showFullLoadingState) {
                    localState = PanelUiState.Error(error.toPanelUiText(R.string.voice_panel_error_local_load))
                } else {
                    operationMessage = error.toPanelUiText(R.string.voice_panel_error_local_refresh)
                }
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
        providerState = PanelUiState.Loading
        scope.launch {
            val result = requestedProvider.browse(requestedParent, requestedPage)
            if (request != providerRequest) return@launch
            providerState = result.fold(
                {
                    val uniqueItems = it.items.distinctBy(::voiceSelectionKey)
                    if (uniqueItems.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_no_more))
                    else PanelUiState.Content(it.copy(items = uniqueItems))
                },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_voice_load)) },
            )
        }
    }

    fun loadOnlineSearch(reset: Boolean = false) {
        if (reset) onlineSearchPage = 0
        val request = ++onlineSearchRequest
        val requestedProvider = provider
        val requestedParent = onlineSearchParent
        val requestedPage = onlineSearchPage
        val requestedQuery = onlineSearchQuery.trim()
        if (requestedParent == null && requestedQuery.isBlank()) {
            onlineSearchExecuted = false
            onlineSearchState = PanelUiState.Empty(panelUiText(R.string.voice_panel_search_prompt))
            return
        }
        onlineSearchExecuted = true
        onlineSearchState = PanelUiState.Loading
        scope.launch {
            val result = if (requestedParent == null) {
                requestedProvider.search(requestedQuery, requestedPage)
            } else {
                requestedProvider.browse(requestedParent, requestedPage)
            }
            if (request != onlineSearchRequest) return@launch
            onlineSearchState = result.fold(
                {
                    val uniqueItems = it.items.distinctBy(::voiceSelectionKey)
                    if (uniqueItems.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_no_more))
                    else PanelUiState.Content(it.copy(items = uniqueItems))
                },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_online_search)) },
            )
        }
    }

    fun selectSharedPack(pack: VoicePack, resetFilter: Boolean) {
        selectedSharedPack = pack
        if (resetFilter) {
            sharedQuery = ""
            sharedSearchExpanded = false
        }
        val request = ++sharedItemsRequest
        sharedItemsState = PanelUiState.Loading
        scope.launch {
            val result = actions.loadSharedPack(pack.id)
            if (request != sharedItemsRequest || selectedSharedPack?.id != pack.id) return@launch
            sharedItemsState = result.fold(
                {
                    val uniqueItems = it.distinctBy(::voiceSelectionKey)
                    if (uniqueItems.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_pack))
                    else PanelUiState.Content(uniqueItems)
                },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_pack_load)) },
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
                { packs ->
                    if (packs.isEmpty()) {
                        selectedSharedPack = null
                        sharedItemsRequest++
                        sharedItemsState = PanelUiState.Empty(panelUiText(R.string.voice_panel_select_pack))
                        PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_no_shared_packs))
                    } else {
                        val current = packs.firstOrNull { it.id == selectedSharedPack?.id }
                        when {
                            localPackLayout == VoicePackLayout.TABS -> {
                                selectSharedPack(current ?: packs.first(), resetFilter = false)
                            }

                            current != null -> selectSharedPack(current, resetFilter = false)

                            selectedSharedPack != null -> {
                                selectedSharedPack = null
                                sharedItemsRequest++
                                sharedItemsState = PanelUiState.Empty(panelUiText(R.string.voice_panel_select_pack))
                            }
                        }
                        PanelUiState.Content(packs)
                    }
                },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_shared_pack_load)) },
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
                { if (it.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_no_available_shared_packs)) else PanelUiState.Content(it) },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_shared_pack_load)) },
            )
        }
    }

    fun loadCloneSharedPack(pack: VoicePack) {
        cloneSharedPack = pack
        val request = ++cloneSharedItemsRequest
        cloneSharedItemsState = PanelUiState.Loading
        scope.launch {
            val result = actions.loadSharedPack(pack.id)
            if (request != cloneSharedItemsRequest || cloneSharedPack?.id != pack.id) return@launch
            cloneSharedItemsState = result.fold(
                { if (it.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_shared_pack_no_voice)) else PanelUiState.Content(it) },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_shared_pack_read)) },
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
        }.onFailure { operationMessage = it.toPanelUiText(R.string.voice_panel_error_audio_play) }
    }

    fun seekPreview(positionMs: Long) {
        runCatching {
            val position = positionMs.coerceIn(0L, previewDurationMs.coerceAtLeast(0L))
            player.seekTo(position.toInt())
            previewPositionMs = position
        }.onFailure { operationMessage = it.toPanelUiText(R.string.voice_panel_error_audio_seek) }
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
        progressMessage = panelUiText(R.string.voice_panel_progress_audio_load)
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
                    operationMessage = it.toPanelUiText(R.string.voice_panel_error_audio_play)
                }
            }.onFailure { operationMessage = it.toPanelUiText(R.string.voice_panel_error_audio_load) }
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
        progressMessage = panelUiText(R.string.voice_panel_progress_send)
        scope.launch {
            val result = actions.send(item)
            progressMessage = null
            showToastSuspend(
                context,
                result.exceptionOrNull()?.message
                    ?: currentLocalizedContext.getString(R.string.voice_panel_send_success),
            )
            if (result.isSuccess) {
                refreshLocal()
                if (PanelSettings.panelAutoClose) onDismiss()
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
                { if (it.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_example_groups)) else PanelUiState.Content(it) },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_example_load)) },
            )
        }
    }

    fun loadExamples(group: String) {
        selectedExampleGroup = group
        val request = ++examplesRequest
        examples = PanelUiState.Loading
        scope.launch {
            val result = actions.loadExamples(group)
            if (request != examplesRequest || selectedExampleGroup != group) return@launch
            examples = result.fold(
                { if (it.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_examples)) else PanelUiState.Content(it) },
                { PanelUiState.Error(it.toPanelUiText(R.string.voice_panel_error_example_load)) },
            )
        }
    }

    LaunchedEffect(destination) {
        PanelSettings.voiceLastDestination = destination.name
        when (destination) {
            VoiceDestination.TTS -> {
                refreshClones()
                if (managingClones) {
                    when (cloneSource) {
                        SOURCE_SHARED -> {
                            if (cloneSharedPacksState == PanelUiState.Loading) loadCloneSharedPacks()
                            cloneSharedPack?.let(::loadCloneSharedPack)
                        }

                        SOURCE_EXAMPLES -> {
                            if (exampleGroups == PanelUiState.Loading) loadExampleGroups()
                            selectedExampleGroup?.let(::loadExamples)
                        }
                    }
                }
            }

            VoiceDestination.ONLINE -> if (providerState == PanelUiState.Loading) loadProvider()
            VoiceDestination.ONLINE_SEARCH -> if (onlineSearchState == PanelUiState.Loading) {
                loadOnlineSearch()
            }
            VoiceDestination.SHARED -> if (sharedPacksState == PanelUiState.Loading) loadMySharedPacks()
            else -> Unit
        }
    }

    LaunchedEffect(
        destination,
        provider.id,
        providerParent?.id,
        providerPage,
        providerFilterQuery,
        onlineSearchParent?.id,
        onlineSearchPage,
        onlineSearchQuery,
        selectedSharedPack?.id,
        sharedQuery,
        localPackDetailId,
    ) {
        batchMode = false
        selectedDownloadIds = emptySet()
    }

    val resolvedOperationMessage = operationMessage?.resolve()
    LaunchedEffect(operationMessage, resolvedOperationMessage) {
        operationMessage ?: return@LaunchedEffect
        showToastSuspend(context, requireNotNull(resolvedOperationMessage))
        operationMessage = null
    }

    LaunchedEffect(Unit) {
        refreshLocal()
    }

    val selectedClone = clones.firstOrNull { it.id == selectedCloneId }
    val resolvedConvertedTtsTitle = convertedTtsTitle?.resolve()

    fun clearConvertedTts() {
        convertedTts?.let(actions.releasePreview)
        convertedTts = null
        convertedTtsTitle = null
    }

    fun convertTts() {
        val count = ttsText.codePointCount(0, ttsText.length)
        when {
            ttsText.isBlank() -> operationMessage = panelUiText(R.string.voice_panel_tts_empty)
            count > 256 -> operationMessage = panelUiText(R.string.voice_panel_tts_too_long, 256)
            ttsMode == TtsMode.CLONE && selectedClone == null -> operationMessage = panelUiText(R.string.voice_panel_tts_choose_voice_first)
            else -> {
                clearConvertedTts()
                progressMessage = panelUiText(R.string.voice_panel_progress_tts_convert)
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
                            TtsMode.SYSTEM -> panelUiText(R.string.tts_mode_system)
                            TtsMode.EDGE -> panelUiText(R.string.tts_mode_edge)
                            TtsMode.CLONE -> selectedClone?.name?.let(PanelUiText::Raw)
                                ?: panelUiText(R.string.tts_mode_clone)
                        }
                    }.onFailure { operationMessage = it.toPanelUiText(R.string.voice_panel_error_tts_convert) }
                }
            }
        }
    }

    fun sendConvertedTts() {
        val generated = convertedTts ?: return
        progressMessage = panelUiText(R.string.voice_panel_progress_send)
        scope.launch {
            val result = actions.sendConverted(generated, requireNotNull(resolvedConvertedTtsTitle))
            progressMessage = null
            showToastSuspend(
                context,
                result.exceptionOrNull()?.message
                    ?: currentLocalizedContext.getString(R.string.voice_panel_send_success),
            )
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
    val sharedCatalogVisible = localPackLayout == VoicePackLayout.LIST && selectedSharedPack == null
    val localFilterActive = localPackFilterQuery.trim().isNotEmpty()
    val visibleLocalPacks = remember(localPacks, localPackFilterQuery, localCatalogVisible) {
        if (!localCatalogVisible || localPackFilterQuery.isBlank()) editableLocalPacks
        else editableLocalPacks.filter {
            it.title.contains(localPackFilterQuery.trim(), ignoreCase = true)
        }
    }
    val visibleSelectedLocal = remember(selectedLocal, localPackFilterQuery, localCatalogVisible) {
        selectedLocal?.let { pack ->
            if (localCatalogVisible || localPackFilterQuery.isBlank()) pack
            else pack.copy(items = pack.items.filter { it.matchesLocalSearch(pack, localPackFilterQuery) })
        }
    }
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
        PanelRailItem(VoiceDestination.RECENT, MaterialSymbols.Outlined.History, stringResource(R.string.panel_recent)),
        PanelRailItem(VoiceDestination.LOCAL, MaterialSymbols.Outlined.Folder, stringResource(R.string.voice_panel_local_packs)),
        PanelRailItem(VoiceDestination.SEARCH, MaterialSymbols.Outlined.Manage_search, stringResource(R.string.panel_local_search)),
        PanelRailItem(VoiceDestination.TTS, MaterialSymbols.Outlined.Text_to_speech, stringResource(R.string.voice_panel_tts)),
        PanelRailItem(VoiceDestination.ONLINE, MaterialSymbols.Outlined.Cloud, stringResource(R.string.voice_panel_online_packs)),
        PanelRailItem(VoiceDestination.ONLINE_SEARCH, MaterialSymbols.Outlined.Travel_explore, stringResource(R.string.panel_online_search)),
        PanelRailItem(VoiceDestination.SHARED, MaterialSymbols.Outlined.Share, stringResource(R.string.voice_panel_shared_packs)),
        PanelRailItem(VoiceDestination.SETTINGS, MaterialSymbols.Outlined.Settings, stringResource(R.string.panel_settings)),
    )

    val title = when (destination) {
        VoiceDestination.RECENT -> stringResource(R.string.panel_recent)
        VoiceDestination.SEARCH -> stringResource(R.string.panel_local_search)
        VoiceDestination.LOCAL -> localDetailPack?.title ?: stringResource(R.string.voice_panel_local_packs)
        VoiceDestination.TTS -> stringResource(R.string.voice_panel_tts)
        VoiceDestination.ONLINE -> stringResource(R.string.voice_panel_online_packs)
        VoiceDestination.ONLINE_SEARCH -> stringResource(R.string.panel_online_search)
        VoiceDestination.SHARED -> if (sharedCatalogVisible) stringResource(R.string.voice_panel_my_shared_packs)
        else selectedSharedPack?.title ?: stringResource(R.string.voice_panel_my_shared_packs)
        VoiceDestination.SETTINGS -> stringResource(R.string.panel_settings)
    }

    val visibleProviderState = remember(providerState, providerFilterQuery) {
        val term = providerFilterQuery.trim()
        if (term.isBlank() || providerState !is PanelUiState.Content) {
            providerState
        } else {
            val page = (providerState as PanelUiState.Content<VoiceProviderPage>).value
            val items = page.items.filter { it.title.contains(term, ignoreCase = true) }
            if (items.isEmpty()) PanelUiState.Empty(panelUiText(R.string.voice_panel_empty_current_page_no_match))
            else PanelUiState.Content(page.copy(items = items))
        }
    }

    val batchCandidates = when (destination) {
        VoiceDestination.LOCAL -> localDetailPack?.items.orEmpty()

        VoiceDestination.ONLINE -> (visibleProviderState as? PanelUiState.Content)?.value?.items
            .orEmpty().filterNot(VoiceItem::isContainer).distinctBy(::voiceSelectionKey)

        VoiceDestination.ONLINE_SEARCH -> (onlineSearchState as? PanelUiState.Content)?.value?.items
            .orEmpty().filterNot(VoiceItem::isContainer).distinctBy(::voiceSelectionKey)

        VoiceDestination.SHARED -> if (selectedSharedPack == null) emptyList() else {
            (sharedItemsState as? PanelUiState.Content)?.value.orEmpty().filter {
                sharedQuery.isBlank() || it.title.contains(sharedQuery, ignoreCase = true)
            }.distinctBy(::voiceSelectionKey)
        }

        else -> emptyList()
    }
    val deletingLocalVoices = destination == VoiceDestination.LOCAL && localDetailPack != null

    fun stopOnlineSave() {
        onlineSaveJob?.cancel()
        onlineSaveJob = null
        onlineSaveProgress = null
    }

    fun startVoiceSave(
        packId: String,
        items: List<VoiceItem>,
        title: PanelUiText = panelUiText(R.string.voice_panel_progress_save_voice),
    ) {
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
                    maxConcurrency = PanelSettings.effectivePanelDownloadConcurrency,
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
                panelUiText(R.string.voice_panel_saved_count, succeeded)
            } else {
                panelUiText(R.string.voice_panel_save_result, succeeded, failed)
            }
        }
    }

    fun showVoicePackPicker(items: List<VoiceItem>) {
        if (items.isEmpty()) return
        showPanelPackPicker(
            context = context,
            title = localizedContext.getString(R.string.voice_panel_save_to_pack),
            createLabel = localizedContext.getString(R.string.voice_panel_new_pack),
            itemCountLabel = { count -> localizedContext.resources.getQuantityString(R.plurals.voice_count, count, count) },
            packIcon = MaterialSymbols.Outlined.Folder,
            packs = editableLocalPacks.map { PanelPackChoice(it.id, it.title, it.itemCount) },
            onCreatePack = actions.createLocalPack,
            onSelect = { packId -> startVoiceSave(packId, items) },
        )
    }

    fun saveWholeVoicePack(parent: VoiceItem) {
        stopOnlineSave()
        onlineSaveProgress = PanelSaveProgress(panelUiText(R.string.voice_panel_progress_read_pack, parent.title), 1)
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
                    operationMessage = result.exceptionOrNull()?.toPanelUiText(R.string.voice_panel_error_read_pack)
                    return@launch
                }
                val providerPage = result.getOrThrow()
                collected += providerPage.items.filterNot(VoiceItem::isContainer)
                hasMore = providerPage.hasMore
                page++
            } while (hasMore)
            val packId = actions.ensureLocalPack(
                parent.metadata["localPackId"] ?: parent.title,
                parent.metadata["legacyPackName"] ?: parent.title,
            )
            if (packId.isFailure) {
                onlineSaveProgress = null
                onlineSaveJob = null
                operationMessage = packId.exceptionOrNull()?.toPanelUiText(R.string.voice_panel_error_create_local_pack)
                return@launch
            }
            onlineSaveJob = null
            onlineSaveProgress = null
            startVoiceSave(packId.getOrThrow(), collected, panelUiText(R.string.voice_panel_progress_save_pack, parent.title))
        }
    }

    fun cancelReorder() {
        reorderTarget = null
        reorderPackId = null
        reorderKeys = emptyList()
    }

    fun changeLocalSortMode(target: VoiceReorderTarget, mode: LocalSortMode) {
        when (target) {
            VoiceReorderTarget.PACKS -> {
                localPackSortMode = mode
                PanelSettings.voicePackSortMode = mode
                if (mode == LocalSortMode.CUSTOM && !PanelSettings.voicePackCustomSortHintShown) {
                    PanelSettings.voicePackCustomSortHintShown = true
                    scope.launch { showToastSuspend(context, currentLocalizedContext.getString(R.string.panel_sort_custom_hint)) }
                }
            }

            VoiceReorderTarget.ITEMS -> {
                localItemSortMode = mode
                PanelSettings.voiceItemSortMode = mode
                if (mode == LocalSortMode.CUSTOM && !PanelSettings.voiceItemCustomSortHintShown) {
                    PanelSettings.voiceItemCustomSortHintShown = true
                    scope.launch { showToastSuspend(context, currentLocalizedContext.getString(R.string.panel_sort_custom_hint)) }
                }
            }
        }
        refreshLocal()
    }

    fun startReorder(target: VoiceReorderTarget) {
        when (target) {
            VoiceReorderTarget.PACKS -> {
                if (editableLocalPacks.isEmpty()) return
                reorderPackId = null
                reorderKeys = editableLocalPacks.map(VoicePack::id)
            }

            VoiceReorderTarget.ITEMS -> {
                val pack = selectedLocal ?: return
                val paths = pack.items.mapNotNull(VoiceItem::localPath)
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
                    VoiceReorderTarget.PACKS -> actions.savePackOrder(requested)
                    VoiceReorderTarget.ITEMS -> packId?.let { actions.saveItemOrder(it, requested) }
                        ?: Result.failure(IllegalStateException(currentLocalizedContext.getString(R.string.voice_panel_error_no_pack_selected)))
                }
            }
            if (result.isSuccess) {
                cancelReorder()
                refreshLocal()
                operationMessage = panelUiText(R.string.panel_sort_saved)
            } else {
                operationMessage = result.exceptionOrNull()?.toPanelUiText(R.string.panel_sort_save_failed)
            }
        }
    }

    val panelActions = if (reorderTarget != null) {
        panelReorderActions(::cancelReorder, ::saveReorder)
    } else if (batchMode) {
        panelMultiSelectActions(
            items = batchCandidates,
            selectedKeys = selectedDownloadIds,
            key = ::voiceSelectionKey,
            terminalIcon = if (deletingLocalVoices) MaterialSymbols.Outlined.Delete else MaterialSymbols.Outlined.Save,
            terminalLabel = stringResource(if (deletingLocalVoices) R.string.panel_action_delete else R.string.action_save),
            onClose = {
                batchMode = false
                selectedDownloadIds = emptySet()
            },
            onSelectionChange = { selectedDownloadIds = it },
            onTerminalAction = { items ->
                if (deletingLocalVoices) prompt = VoicePrompt.DeleteLocalVoices(items)
                else showVoicePackPicker(items)
            },
        )
    } else when (destination) {
        VoiceDestination.LOCAL -> if (localCatalogVisible) {
            buildList {
                add(PanelAction(MaterialSymbols.Outlined.Add, stringResource(R.string.voice_panel_new_pack)) { prompt = VoicePrompt.CreateLocalPack })
                add(PanelAction(MaterialSymbols.Outlined.Refresh, stringResource(R.string.panel_action_refresh), onClick = ::refreshLocal))
                add(
                    panelLocalSortAction(
                        mode = localPackSortMode,
                        enabled = editableLocalPacks.isNotEmpty(),
                        onModeChange = { changeLocalSortMode(VoiceReorderTarget.PACKS, it) },
                        onStartCustomOrder = { startReorder(VoiceReorderTarget.PACKS) },
                    ),
                )
            }
        } else buildList {
            if (localPackLayout == VoicePackLayout.LIST) {
                add(PanelAction(MaterialSymbols.Outlined.Arrow_back, stringResource(R.string.panel_action_back)) { localPackDetailId = null })
            } else {
                add(PanelAction(MaterialSymbols.Outlined.Add, stringResource(R.string.voice_panel_new_pack)) { prompt = VoicePrompt.CreateLocalPack })
            }
            add(PanelAction(MaterialSymbols.Outlined.Edit, stringResource(R.string.panel_action_rename), selectedLocal != null) {
                selectedLocal?.let { prompt = VoicePrompt.RenameLocalPack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Delete, stringResource(R.string.panel_action_delete), selectedLocal != null) {
                selectedLocal?.let { prompt = VoicePrompt.DeleteLocalPack(it) }
            })
            add(PanelAction(MaterialSymbols.Outlined.Upload_file, stringResource(R.string.panel_action_import), selectedLocal != null) {
                selectedLocal?.let { prompt = VoicePrompt.ImportLocal(it) }
            })
            if (localPackLayout == VoicePackLayout.LIST) {
                add(PanelAction(MaterialSymbols.Outlined.Select_all, stringResource(R.string.panel_action_multi_select), !selectedLocal?.items.isNullOrEmpty()) {
                    batchMode = true
                    selectedDownloadIds = emptySet()
                })
            }
            add(PanelAction(MaterialSymbols.Outlined.Refresh, stringResource(R.string.panel_action_refresh), onClick = ::refreshLocal))
            add(
                panelLocalSortAction(
                    mode = localItemSortMode,
                    enabled = !selectedLocal?.items.isNullOrEmpty(),
                    onModeChange = { changeLocalSortMode(VoiceReorderTarget.ITEMS, it) },
                    onStartCustomOrder = { startReorder(VoiceReorderTarget.ITEMS) },
                ),
            )
        }

        VoiceDestination.SEARCH -> emptyList()
        VoiceDestination.ONLINE -> buildList {
            add(PanelAction(MaterialSymbols.Outlined.Arrow_back, stringResource(R.string.panel_action_back), providerParent != null) {
                providerParent = null
                providerRootSnapshot?.let { snapshot ->
                    providerPage = snapshot.page
                    providerState = snapshot.state
                } ?: loadProvider(true)
                providerRootSnapshot = null
            })
            add(PanelAction(MaterialSymbols.Outlined.Refresh, stringResource(R.string.panel_action_refresh)) { loadProvider() })
            if (providerParent != null && batchCandidates.isNotEmpty()) {
                add(PanelAction(MaterialSymbols.Outlined.Select_all, stringResource(R.string.panel_action_multi_select)) {
                    batchMode = true
                    selectedDownloadIds = emptySet()
                })
                add(PanelAction(MaterialSymbols.Outlined.Save, stringResource(R.string.action_save)) {
                    providerParent?.let(::saveWholeVoicePack)
                })
            }
        }

        VoiceDestination.ONLINE_SEARCH -> buildList {
            if (onlineSearchParent != null) {
                add(PanelAction(MaterialSymbols.Outlined.Arrow_back, stringResource(R.string.voice_panel_back_to_search_results)) {
                    onlineSearchParent = null
                    onlineSearchRootSnapshot?.let { snapshot ->
                        onlineSearchPage = snapshot.page
                        onlineSearchState = snapshot.state
                    } ?: loadOnlineSearch(true)
                    onlineSearchRootSnapshot = null
                })
            }
            add(PanelAction(MaterialSymbols.Outlined.Refresh, stringResource(R.string.panel_action_refresh), onlineSearchQuery.isNotBlank()) {
                loadOnlineSearch()
            })
            if (batchCandidates.isNotEmpty()) {
                add(PanelAction(MaterialSymbols.Outlined.Select_all, stringResource(R.string.panel_action_multi_select)) {
                    batchMode = true
                    selectedDownloadIds = emptySet()
                })
            }
            if (onlineSearchParent != null && batchCandidates.isNotEmpty()) {
                add(PanelAction(MaterialSymbols.Outlined.Save, stringResource(R.string.action_save)) {
                    onlineSearchParent?.let(::saveWholeVoicePack)
                })
            }
        }

        VoiceDestination.SHARED -> buildList {
            if (localPackLayout == VoicePackLayout.LIST && selectedSharedPack != null) {
                add(PanelAction(MaterialSymbols.Outlined.Arrow_back, stringResource(R.string.voice_panel_back_to_shared_packs)) {
                    selectedSharedPack = null
                    sharedQuery = ""
                    sharedSearchExpanded = false
                    sharedItemsRequest++
                    sharedItemsState = PanelUiState.Empty(panelUiText(R.string.voice_panel_select_pack))
                })
            }
            if (batchCandidates.isNotEmpty()) {
                add(PanelAction(MaterialSymbols.Outlined.Select_all, stringResource(R.string.panel_action_multi_select)) {
                    batchMode = true
                    selectedDownloadIds = emptySet()
                })
            }
            add(PanelAction(MaterialSymbols.Outlined.Add, stringResource(R.string.voice_panel_new_pack)) { prompt = VoicePrompt.CreateSharedPack })
            selectedSharedPack?.let { pack ->
                add(PanelAction(MaterialSymbols.Outlined.Edit, stringResource(R.string.panel_action_rename)) {
                    prompt = VoicePrompt.RenameSharedPack(pack)
                })
                add(PanelAction(MaterialSymbols.Outlined.Delete, stringResource(R.string.panel_action_delete)) {
                    prompt = VoicePrompt.DeleteSharedPack(pack)
                })
                add(PanelAction(MaterialSymbols.Outlined.Check_circle, stringResource(R.string.voice_panel_submit_for_review)) {
                    prompt = VoicePrompt.ConfirmSharedPack(pack)
                })
                add(PanelAction(MaterialSymbols.Outlined.Upload_file, stringResource(R.string.voice_panel_upload_voice)) {
                    actions.uploadSharedVoice(pack.id, { progressMessage = panelUiText(R.string.voice_panel_progress_upload) }) { result ->
                        progressMessage = null
                        operationMessage = result.fold(
                            onSuccess = { PanelUiText.Raw(it) },
                            onFailure = { it.toPanelUiText(R.string.voice_panel_error_upload) },
                        )
                        if (result.isSuccess) loadMySharedPacks()
                    }
                })
            }
            add(PanelAction(MaterialSymbols.Outlined.Refresh, stringResource(R.string.panel_action_refresh), onClick = ::loadMySharedPacks))
        }

        else -> emptyList()
    }
    val actionSearch = when {
        reorderTarget != null || batchMode -> null

        destination == VoiceDestination.LOCAL -> PanelActionSearch(
            expanded = localPackFilterExpanded,
            value = localPackFilterQuery,
            label = stringResource(if (localCatalogVisible) R.string.voice_panel_filter_local_packs else R.string.voice_panel_filter_current_pack),
            actionIndex = (panelActions.size - 1).coerceAtLeast(0),
            onValueChange = { localPackFilterQuery = it },
            onExpandedChange = { localPackFilterExpanded = it },
        )

        destination == VoiceDestination.ONLINE -> PanelActionSearch(
            expanded = providerSearchExpanded,
            value = providerFilterQuery,
            label = stringResource(if (providerParent == null) R.string.voice_panel_filter_current_pack else R.string.voice_panel_filter_current_voice),
            onValueChange = { providerFilterQuery = it },
            onExpandedChange = { providerSearchExpanded = it },
        )

        destination == VoiceDestination.SHARED -> PanelActionSearch(
            expanded = sharedSearchExpanded,
            value = sharedQuery,
            label = stringResource(if (sharedCatalogVisible) R.string.voice_panel_filter_shared_packs else R.string.voice_panel_filter_current_shared_pack),
            actionIndex = (panelActions.size - 1).coerceAtLeast(0),
            onValueChange = { sharedQuery = it },
            onExpandedChange = { sharedSearchExpanded = it },
        )

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
                if (reorderTarget == null) destination = it
            },
            onDismiss = onDismiss,
            onBack = {
                when {
                    reorderTarget != null -> cancelReorder()

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
                            providerState = snapshot.state
                        } ?: loadProvider(true)
                        providerRootSnapshot = null
                    }

                    destination == VoiceDestination.ONLINE_SEARCH && onlineSearchParent != null -> {
                        onlineSearchParent = null
                        onlineSearchRootSnapshot?.let { snapshot ->
                            onlineSearchPage = snapshot.page
                            onlineSearchState = snapshot.state
                        } ?: loadOnlineSearch(true)
                        onlineSearchRootSnapshot = null
                    }

                    destination == VoiceDestination.SHARED &&
                            localPackLayout == VoicePackLayout.LIST && selectedSharedPack != null -> {
                        selectedSharedPack = null
                        sharedQuery = ""
                        sharedSearchExpanded = false
                        sharedItemsRequest++
                        sharedItemsState = PanelUiState.Empty(panelUiText(R.string.voice_panel_select_pack))
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
                when (reorderTarget) {
                    VoiceReorderTarget.PACKS -> VoicePackReorderContent(
                        packs = reorderKeys.mapNotNull { key ->
                            editableLocalPacks.firstOrNull { it.id == key }
                        },
                        onMove = { from, to -> reorderKeys = reorderKeys.moveItem(from, to) },
                    )

                    VoiceReorderTarget.ITEMS -> VoiceItemReorderContent(
                        voices = reorderKeys.mapNotNull { key ->
                            selectedLocal?.items?.firstOrNull { it.localPath == key }
                        },
                        onMove = { from, to -> reorderKeys = reorderKeys.moveItem(from, to) },
                    )

                    null -> when (destination) {
                    VoiceDestination.RECENT -> PanelStateContent(localState, ::refreshLocal) {
                        if (recent == null || recent.items.isEmpty()) {
                            PanelEmptyAction(stringResource(R.string.voice_panel_empty_never_sent))
                        } else {
                            VoiceList(
                                voices = recentItems,
                                playingId = playingId,
                                onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                                onSend = ::send,
                            )
                        }
                    }

                    VoiceDestination.SEARCH -> PanelStateContent(localState, ::refreshLocal) {
                        VoiceSearchContent(
                            packs = editableLocalPacks,
                            query = localQuery,
                            onQueryChange = { localQuery = it },
                            playingId = playingId,
                            onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                            onSend = ::send,
                        )
                    }

                    VoiceDestination.LOCAL -> PanelStateContent(localState, ::refreshLocal) {
                        LocalVoiceContent(
                            packs = visibleLocalPacks,
                            layout = localPackLayout,
                            selected = visibleSelectedLocal,
                            filterActive = localFilterActive,
                            playingId = playingId,
                            packListState = localPackListState,
                            itemListState = localItemListState,
                            onSelectPack = {
                                selectedLocalId = it.id
                                if (localPackLayout == VoicePackLayout.LIST) {
                                    localPackFilterQuery = ""
                                    localPackFilterExpanded = false
                                    localPackDetailId = it.id
                                    scope.launch { localItemListState.scrollToItem(0) }
                                }
                            },
                            onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                            onSend = ::send,
                            onImport = { selectedLocal?.let { prompt = VoicePrompt.ImportLocal(it) } },
                            selectable = batchMode && localDetailPack != null,
                            selectedIds = selectedDownloadIds,
                            onToggleSelection = { item ->
                                val key = voiceSelectionKey(item)
                                selectedDownloadIds = selectedDownloadIds.toMutableSet().apply {
                                    if (!add(key)) remove(key)
                                }
                            }
                        )
                    }

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
                                preview("tts-converted", requireNotNull(resolvedConvertedTtsTitle)) {
                                    Result.success(generated.copy(temporary = false))
                                }
                            }
                        },
                        onSendConverted = ::sendConvertedTts,
                        onSynthesize = {
                            val count = ttsText.codePointCount(0, ttsText.length)
                            if (ttsText.isBlank()) operationMessage = panelUiText(R.string.voice_panel_tts_empty)
                            else if (count > 256) operationMessage = panelUiText(R.string.voice_panel_tts_too_long, 256)
                            else {
                                progressMessage = panelUiText(R.string.voice_panel_progress_tts_synthesize)
                                scope.launch {
                                    val result = when (ttsMode) {
                                        TtsMode.SYSTEM -> actions.synthesizeSystem(ttsText)
                                        TtsMode.EDGE -> actions.synthesizeEdge(ttsText, selectedEdgeVoice)
                                        TtsMode.CLONE -> selectedClone?.let { actions.synthesizeClone(ttsText, it) }
                                            ?: Result.failure(
                                                IllegalStateException(
                                                    currentLocalizedContext.getString(
                                                        R.string.voice_panel_tts_choose_voice_first,
                                                    ),
                                                ),
                                            )
                                    }
                                    progressMessage = null
                                    showToastSuspend(
                                        context,
                                        result.exceptionOrNull()?.message
                                            ?: currentLocalizedContext.getString(R.string.voice_panel_send_success),
                                    )
                                    if (result.isSuccess && PanelSettings.panelAutoClose) onDismiss()
                                }
                            }
                        },
                    )

                    VoiceDestination.ONLINE -> OnlineVoiceContent(
                        provider = provider,
                        state = visibleProviderState,
                        playingId = playingId,
                        listState = if (providerParent == null) providerRootListState else providerChildListState,
                        onProvider = {
                            provider = it
                            PanelSettings.selectedVoiceProvider = it.id
                            providerParent = null
                            providerRootSnapshot = null
                            providerFilterQuery = ""
                            providerSearchExpanded = false
                            onlineSearchParent = null
                            onlineSearchRootSnapshot = null
                            onlineSearchExecuted = false
                            onlineSearchRequest++
                            onlineSearchState = PanelUiState.Empty(panelUiText(
                                if (onlineSearchQuery.isBlank()) R.string.voice_panel_search_prompt
                                else R.string.voice_panel_search_action_hint,
                            ))
                            scope.launch {
                                providerRootListState.scrollToItem(0)
                                providerChildListState.scrollToItem(0)
                            }
                            loadProvider(true)
                        },
                        onOpen = { item ->
                            if (providerParent == null) {
                                providerRootSnapshot = ProviderRootSnapshot(
                                    page = providerPage,
                                    state = providerState,
                                )
                            }
                            providerParent = item
                            providerFilterQuery = ""
                            providerSearchExpanded = false
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

                    VoiceDestination.ONLINE_SEARCH -> OnlineVoiceSearchContent(
                        provider = provider,
                        query = onlineSearchQuery,
                        state = onlineSearchState,
                        playingId = playingId,
                        listState = if (onlineSearchParent == null) {
                            onlineSearchRootListState
                        } else {
                            onlineSearchChildListState
                        },
                        onProvider = {
                            provider = it
                            PanelSettings.selectedVoiceProvider = it.id
                            providerRequest++
                            providerParent = null
                            providerRootSnapshot = null
                            providerFilterQuery = ""
                            providerState = PanelUiState.Loading
                            onlineSearchRequest++
                            onlineSearchParent = null
                            onlineSearchRootSnapshot = null
                            onlineSearchPage = 0
                            onlineSearchExecuted = false
                            onlineSearchState = PanelUiState.Empty(panelUiText(
                                if (onlineSearchQuery.isBlank()) R.string.voice_panel_search_prompt
                                else R.string.voice_panel_search_action_hint,
                            ))
                        },
                        onQueryChange = {
                            onlineSearchQuery = it
                            onlineSearchRequest++
                            onlineSearchParent = null
                            onlineSearchRootSnapshot = null
                            onlineSearchPage = 0
                            onlineSearchExecuted = false
                            onlineSearchState = PanelUiState.Empty(panelUiText(
                                if (it.isBlank()) R.string.voice_panel_search_prompt
                                else R.string.voice_panel_search_action_hint,
                            ))
                        },
                        onSearch = { loadOnlineSearch(true) },
                        onOpen = { item ->
                            if (onlineSearchParent == null) {
                                onlineSearchRootSnapshot = ProviderRootSnapshot(
                                    page = onlineSearchPage,
                                    state = onlineSearchState,
                                )
                            }
                            onlineSearchParent = item
                            scope.launch { onlineSearchChildListState.scrollToItem(0) }
                            loadOnlineSearch(true)
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
                            if (onlineSearchPage > 0) onlineSearchPage--
                            loadOnlineSearch()
                        },
                        onNext = {
                            onlineSearchPage++
                            loadOnlineSearch()
                        },
                        onRetry = { loadOnlineSearch() },
                    )

                    VoiceDestination.SHARED -> SharedVoiceContent(
                        packsState = sharedPacksState,
                        layout = localPackLayout,
                        selectedPack = selectedSharedPack,
                        itemsState = sharedItemsState,
                        query = sharedQuery,
                        playingId = playingId,
                        packListState = sharedPackListState,
                        itemListState = sharedItemListState,
                        selectable = batchMode,
                        selectedIds = selectedDownloadIds,
                        onToggleSelection = { item ->
                            val key = voiceSelectionKey(item)
                            selectedDownloadIds = selectedDownloadIds.toMutableSet().apply {
                                if (!add(key)) remove(key)
                            }
                        },
                        onSelectPack = { pack ->
                            selectSharedPack(pack, resetFilter = localPackLayout == VoicePackLayout.LIST)
                            scope.launch { sharedItemListState.scrollToItem(0) }
                        },
                        onPreview = { item -> preview(item.id, item.title, item) { actions.preview(item) } },
                        onSend = ::send,
                        onRetryPacks = ::loadMySharedPacks,
                        onRetryItems = {
                            selectedSharedPack?.let {
                                selectSharedPack(it, resetFilter = false)
                            }
                        },
                    )

                    VoiceDestination.SETTINGS -> VoiceSettingsContent(
                        localPackLayout = localPackLayout,
                        wrapActions = wrapActions,
                        onLocalPackLayoutChange = {
                            localPackLayout = it
                            localPackDetailId = null
                            sharedQuery = ""
                            sharedSearchExpanded = false
                            if (it == VoicePackLayout.TABS) {
                                val packs = (sharedPacksState as? PanelUiState.Content)?.value.orEmpty()
                                val target = packs.firstOrNull { pack -> pack.id == selectedSharedPack?.id }
                                    ?: packs.firstOrNull()
                                target?.let { pack -> selectSharedPack(pack, resetFilter = false) }
                            } else {
                                selectedSharedPack = null
                                sharedItemsRequest++
                                sharedItemsState = PanelUiState.Empty(panelUiText(R.string.voice_panel_select_pack))
                            }
                            PanelSettings.localVoicePackLayout = it
                        },
                        onWrapActionsChange = {
                            wrapActions = it
                            PanelSettings.wrapPanelActions = it
                        },
                    )
                    }
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
                            .onFailure { operationMessage = it.toPanelUiText(R.string.voice_panel_error_voice_select) }
                    }
                },
                onSelect = { voice ->
                    clearConvertedTts()
                    scope.launch {
                        actions.selectClone(voice.id).onSuccess { selectedCloneId = voice.id }
                            .onFailure { operationMessage = it.toPanelUiText(R.string.voice_panel_error_voice_select) }
                    }
                },
                onDelete = { prompt = VoicePrompt.DeleteClone(it) },
                onImportFile = {
                    actions.importClone({ progressMessage = panelUiText(R.string.voice_panel_progress_import_clone) }) { result ->
                        progressMessage = null
                        operationMessage = result.fold(
                            onSuccess = { panelUiText(R.string.voice_panel_clone_imported) },
                            onFailure = { it.toPanelUiText(R.string.voice_panel_error_import_clone) },
                        )
                        if (result.isSuccess) refreshClones()
                    }
                },
                onChooseVoice = { prompt = VoicePrompt.NameCloneSource(it) },
                onPreviewVoice = { item ->
                    preview("clone-source:${item.id}", item.title) { actions.preview(item) }
                },
                onSelectSharedPack = { pack ->
                    loadCloneSharedPack(pack)
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
                    loadExamples(group)
                },
                onPreviewExample = { example ->
                    preview("example:${example.group}/${example.fileName}", example.title) {
                        actions.previewExample(example)
                    }
                },
                onAddExample = { example ->
                    progressMessage = panelUiText(R.string.voice_panel_progress_import_example)
                    scope.launch {
                        val result = actions.addExample(example)
                        progressMessage = null
                        operationMessage = result.fold(
                            onSuccess = { panelUiText(R.string.voice_panel_clone_imported) },
                            onFailure = { it.toPanelUiText(R.string.voice_panel_error_import_example) },
                        )
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
            VoicePrompt.CreateLocalPack -> PanelTextPrompt(
                stringResource(R.string.voice_panel_new_pack),
                stringResource(R.string.voice_pack_name),
                confirmText = stringResource(R.string.panel_action_create),
                onDismiss = { prompt = null },
            ) { name ->
                scope.launch {
                    val result = actions.createLocalPack(name)
                    prompt = null
                    operationMessage = result.fold(
                        onSuccess = { panelUiText(R.string.voice_panel_pack_created) },
                        onFailure = { it.toPanelUiText(R.string.voice_panel_error_create_pack) },
                    )
                    if (result.isSuccess) refreshLocal()
                }
            }

            is VoicePrompt.ImportLocal -> VoiceImportModePrompt(
                onDismiss = { prompt = null },
                onSelect = { mode ->
                    prompt = null
                    actions.importVoice(current.pack.id, mode, { progressMessage = panelUiText(R.string.voice_panel_progress_import) }) { result ->
                        progressMessage = null
                        operationMessage = result.fold(
                            onSuccess = { panelUiText(R.string.voice_panel_import_complete) },
                            onFailure = { it.toPanelUiText(R.string.voice_panel_error_import) },
                        )
                        if (result.isSuccess) refreshLocal()
                    }
                },
            )

            is VoicePrompt.RenameLocalPack -> PanelTextPrompt(
                stringResource(R.string.voice_panel_rename_pack),
                stringResource(R.string.voice_pack_name),
                current.pack.title,
                stringResource(R.string.action_save),
                onDismiss = { prompt = null },
            ) { name ->
                scope.launch {
                    val result = actions.renameLocalPack(current.pack.id, name)
                    prompt = null
                    operationMessage = result.fold(
                        onSuccess = { panelUiText(R.string.voice_panel_pack_renamed) },
                        onFailure = { it.toPanelUiText(R.string.voice_panel_error_rename_pack) },
                    )
                    if (result.isSuccess) refreshLocal()
                }
            }

            is VoicePrompt.DeleteLocalPack -> PanelConfirmation(
                stringResource(R.string.voice_panel_delete_pack),
                stringResource(R.string.voice_panel_delete_pack_message, current.pack.title),
                stringResource(R.string.panel_action_delete),
                { prompt = null },
            ) {
                scope.launch {
                    val result = actions.deleteLocalPack(current.pack.id)
                    prompt = null
                    operationMessage = result.fold(
                        onSuccess = { panelUiText(R.string.voice_panel_pack_deleted) },
                        onFailure = { it.toPanelUiText(R.string.voice_panel_error_delete_pack) },
                    )
                    if (result.isSuccess) refreshLocal()
                }
            }

            is VoicePrompt.DeleteLocalVoices -> PanelConfirmation(
                stringResource(R.string.voice_panel_delete_voices),
                pluralStringResource(
                    R.plurals.voice_panel_delete_selected_message,
                    current.items.size,
                    current.items.size,
                ),
                stringResource(R.string.panel_action_delete),
                { prompt = null },
            ) {
                scope.launch {
                    val paths = current.items.mapNotNull(VoiceItem::localPath)
                    val result = actions.deleteLocalVoices(paths)
                    prompt = null
                    operationMessage = result.fold(
                        onSuccess = { panelUiText(R.string.voice_panel_deleted_count, it) },
                        onFailure = { it.toPanelUiText(R.string.voice_panel_error_delete_voices) },
                    )
                    if (result.isSuccess) {
                        stopPreview()
                        batchMode = false
                        selectedDownloadIds = emptySet()
                        refreshLocal()
                    }
                }
            }

            VoicePrompt.CreateSharedPack -> PanelTextPrompt(
                stringResource(R.string.voice_panel_new_shared_pack),
                stringResource(R.string.voice_pack_name),
                confirmText = stringResource(R.string.voice_panel_confirm_create),
                onDismiss = { prompt = null },
            ) { name ->
                scope.launch {
                    val result = actions.createSharedPack(name)
                    operationMessage = result.fold({ PanelUiText.Raw(it) }, { PanelUiText.Raw(it.message.orEmpty()) })
                    prompt = null
                    if (result.isSuccess) loadMySharedPacks()
                }
            }

            is VoicePrompt.RenameSharedPack -> PanelTextPrompt(
                stringResource(R.string.voice_panel_set_new_name),
                stringResource(R.string.voice_pack_name),
                current.pack.title,
                stringResource(R.string.dialog_confirm),
                onDismiss = { prompt = null },
            ) { name ->
                scope.launch {
                    val result = actions.renameSharedPack(current.pack.id, name)
                    operationMessage = result.fold({ PanelUiText.Raw(it) }, { PanelUiText.Raw(it.message.orEmpty()) })
                    prompt = null
                    if (result.isSuccess) loadMySharedPacks()
                }
            }

            is VoicePrompt.DeleteSharedPack -> PanelConfirmation(
                stringResource(R.string.voice_panel_delete_pack),
                stringResource(R.string.voice_panel_delete_shared_pack_message, current.pack.title),
                stringResource(R.string.voice_panel_confirm_delete),
                { prompt = null },
            ) {
                scope.launch {
                    val result = actions.deleteSharedPack(current.pack.id)
                    operationMessage = result.fold({ PanelUiText.Raw(it) }, { PanelUiText.Raw(it.message.orEmpty()) })
                    prompt = null
                    if (result.isSuccess) {
                        selectedSharedPack = null
                        sharedItemsRequest++
                        sharedItemsState = PanelUiState.Empty(panelUiText(R.string.voice_panel_select_pack))
                        loadMySharedPacks()
                    }
                }
            }

            is VoicePrompt.ConfirmSharedPack -> PanelConfirmation(
                stringResource(R.string.voice_panel_confirm_pack),
                stringResource(R.string.voice_panel_confirm_pack_message),
                stringResource(R.string.dialog_confirm),
                { prompt = null },
            ) {
                scope.launch {
                    val result = actions.confirmSharedPack(current.pack.id)
                    operationMessage = result.fold({ PanelUiText.Raw(it) }, { PanelUiText.Raw(it.message.orEmpty()) })
                    prompt = null
                    if (result.isSuccess) loadMySharedPacks()
                }
            }

            is VoicePrompt.NameCloneSource -> PanelTextPrompt(
                stringResource(R.string.voice_panel_import_clone),
                stringResource(R.string.voice_clone_name),
                current.item.title,
                stringResource(R.string.panel_action_import),
                onDismiss = { prompt = null },
            ) { name ->
                progressMessage = panelUiText(R.string.voice_panel_progress_import_clone)
                scope.launch {
                    val result = actions.importCloneFromVoice(name, current.item)
                    progressMessage = null
                    operationMessage = result.fold(
                        onSuccess = { panelUiText(R.string.voice_panel_clone_imported) },
                        onFailure = { it.toPanelUiText(R.string.voice_panel_error_import_clone) },
                    )
                    prompt = null
                    refreshClones()
                }
            }

            is VoicePrompt.DeleteClone -> PanelConfirmation(
                stringResource(R.string.voice_panel_delete_clone),
                stringResource(R.string.voice_panel_delete_clone_message, current.voice.name),
                stringResource(R.string.panel_action_delete),
                { prompt = null },
            ) {
                scope.launch {
                    val result = actions.deleteClone(current.voice.id)
                    prompt = null
                    operationMessage = result.fold(
                        onSuccess = { panelUiText(R.string.voice_panel_clone_deleted) },
                        onFailure = { it.toPanelUiText(R.string.voice_panel_error_delete_clone) },
                    )
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
                title = stringResource(R.string.voice_import_files_title),
                description = stringResource(R.string.voice_import_files_description),
                icon = MaterialSymbols.Outlined.Upload_file,
            ),
            PanelImportOption(
                mode = VoiceImportMode.DIRECTORY,
                title = stringResource(R.string.panel_import_directory_title),
                description = stringResource(R.string.voice_import_directory_description),
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
    filterActive: Boolean,
    playingId: String?,
    packListState: LazyListState,
    itemListState: LazyListState,
    onSelectPack: (VoicePack) -> Unit,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onImport: () -> Unit,
    selectable: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (VoiceItem) -> Unit,
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
                PanelEmptyAction(
                    stringResource(R.string.voice_panel_empty_no_local_packs),
                    stringResource(R.string.voice_panel_empty_create_pack_hint),
                )
            } else if (selected.items.isEmpty()) {
                if (filterActive) {
                    PanelEmptyAction(stringResource(R.string.voice_panel_empty_no_current_pack_match))
                } else {
                    PanelEmptyAction(
                        stringResource(R.string.voice_panel_empty_pack),
                        stringResource(R.string.voice_panel_import_from_files),
                        onImport,
                    )
                }
            } else {
                VoiceList(selected.items, playingId, onPreview, onSend)
            }
        }
    } else if (selected == null) {
        if (packs.isEmpty()) {
            if (filterActive) {
                PanelEmptyAction(stringResource(R.string.voice_panel_empty_no_local_pack_match))
            } else {
                PanelEmptyAction(
                    stringResource(R.string.voice_panel_empty_no_local_packs),
                    stringResource(R.string.voice_panel_empty_create_pack_hint),
                )
            }
        } else {
            VoicePackList(packs, packListState, onSelectPack)
        }
    } else if (selected.items.isEmpty()) {
        if (filterActive) {
            PanelEmptyAction(stringResource(R.string.voice_panel_empty_no_current_pack_match))
        } else {
            PanelEmptyAction(
                stringResource(R.string.voice_panel_empty_pack),
                stringResource(R.string.voice_panel_import_from_files),
                onImport,
            )
        }
    } else {
        VoiceList(
            voices = selected.items,
            playingId = playingId,
            onPreview = onPreview,
            onSend = onSend,
            listState = itemListState,
            selectable = selectable,
            selectedIds = selectedIds,
            onToggleSelection = onToggleSelection,
        )
    }
}

@Composable
private fun VoicePackList(
    packs: List<VoicePack>,
    listState: LazyListState,
    onSelectPack: (VoicePack) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        items(packs, key = VoicePack::id) { pack ->
            Column(Modifier.animateItem()) {
                ListItem(
                    modifier = Modifier.clickable { onSelectPack(pack) },
                    colors = panelListItemColors(),
                    content = {
                        Text(pack.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.voice_count, pack.itemCount, pack.itemCount))
                    },
                    leadingContent = { VoicePackIcon(pack) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun VoicePackReorderContent(
    packs: List<VoicePack>,
    onMove: (Int, Int) -> Unit,
) {
    ReorderableList(
        items = packs,
        itemKey = VoicePack::id,
        onMove = onMove,
        modifier = Modifier.fillMaxSize(),
    ) { pack, dragHandleModifier ->
        ListItem(
            colors = panelListItemColors(),
            content = {
                Text(pack.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(pluralStringResource(R.plurals.voice_count, pack.itemCount, pack.itemCount))
            },
            leadingContent = { VoicePackIcon(pack) },
            trailingContent = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Drag_handle,
                        contentDescription = stringResource(R.string.voice_drag_pack, pack.title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun VoicePackIcon(pack: VoicePack) {
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MaterialSymbols.Outlined.Folder, contentDescription = null)
        if (pack.source == PanelSource.LOCAL) {
            SendCountBadge(
                count = pack.items.sumOf(VoiceItem::sendCount),
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun VoiceItemReorderContent(
    voices: List<VoiceItem>,
    onMove: (Int, Int) -> Unit,
) {
    ReorderableList(
        items = voices,
        itemKey = { requireNotNull(it.localPath) },
        onMove = onMove,
        modifier = Modifier.fillMaxSize(),
    ) { voice, dragHandleModifier ->
        ListItem(
            colors = panelListItemColors(),
            content = {
                Text(voice.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    buildList {
                        if (voice.durationMs > 0) add(formatDuration(voice.durationMs))
                        add(pluralStringResource(
                            R.plurals.voice_sent_count,
                            voice.sendCount.toInt(),
                            voice.sendCount,
                        ))
                    }.joinToString(" · "),
                )
            },
            leadingContent = { Icon(MaterialSymbols.Outlined.Mic, null) },
            trailingContent = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Drag_handle,
                        contentDescription = stringResource(R.string.voice_drag_item, voice.title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
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
            pack.items.filter { it.matchesLocalSearch(pack, term) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PanelSearchField(
            value = query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.panel_local_search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) {
                PanelEmptyAction(stringResource(R.string.voice_panel_local_search_hint))
            } else if (results.isEmpty()) {
                PanelEmptyAction(stringResource(R.string.voice_panel_empty_no_local_voice_match))
            }
            else VoiceList(results, playingId, onPreview, onSend)
        }
    }
}

private fun VoiceItem.matchesLocalSearch(pack: VoicePack, query: String): Boolean {
    val term = query.trim()
    return term.isBlank() ||
            title.contains(term, ignoreCase = true) ||
            pack.title.contains(term, ignoreCase = true)
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
    terminalActionLabel: String? = null,
) {
    val resolvedTerminalActionLabel = terminalActionLabel ?: stringResource(R.string.panel_action_send)
    val resolvedListState = listState ?: rememberLazyListState()
    val durationOverrides = LocalVoiceDurationOverrides.current
    val keyedVoices = remember(voices) {
        panelItemsWithStableKeys(voices, ::voiceSelectionKey)
    }
    LazyColumn(Modifier.fillMaxSize(), state = resolvedListState) {
        items(keyedVoices, key = { it.first }) { keyedVoice ->
            val voice = keyedVoice.second
            val durationMs = durationOverrides[voiceSelectionKey(voice)] ?: voice.durationMs
            Column(Modifier.animateItem()) {
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
                    content = { Text(voice.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (durationMs > 0) ({
                        Text(formatDuration(durationMs))
                    }) else null,
                    trailingContent = if (voice.isContainer || selectable) null else ({
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SendCountBadge(voice.sendCount, Modifier.padding(end = 2.dp))
                            IconButton(onClick = { onPreview(voice) }) {
                                Icon(
                                    if (playingId == voice.id) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                                    if (playingId == voice.id) {
                                        stringResource(R.string.panel_action_pause)
                                    } else {
                                        stringResource(R.string.panel_action_preview)
                                    },
                                )
                            }
                            if (onAdd != null) {
                                IconButton(onClick = { onAdd(voice) }) {
                                    Icon(
                                        MaterialSymbols.Outlined.Download,
                                        stringResource(R.string.voice_panel_add_to_local),
                                    )
                                }
                            }
                            IconButton(onClick = { onSend(voice) }) {
                                Icon(terminalActionIcon, resolvedTerminalActionLabel)
                            }
                        }
                    }),
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun voiceSelectionKey(item: VoiceItem): String =
    item.remoteObjectId ?: item.remoteUrl ?: item.id

@Composable
private fun OnlineVoiceContent(
    provider: VoiceProvider,
    state: PanelUiState<VoiceProviderPage>,
    playingId: String?,
    listState: LazyListState,
    onProvider: (VoiceProvider) -> Unit,
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
        VoiceProviderSelector(provider, onProvider)
        OnlineVoiceResults(
            state = state,
            playingId = playingId,
            listState = listState,
            onOpen = onOpen,
            onPreview = onPreview,
            onSend = onSend,
            onAdd = onAdd,
            onPrevious = onPrevious,
            onNext = onNext,
            onRetry = onRetry,
            selectable = selectable,
            selectedIds = selectedIds,
            onToggleSelection = onToggleSelection,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OnlineVoiceSearchContent(
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
        VoiceProviderSelector(provider, onProvider)
        PanelSearchField(
            value = query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.panel_online_search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            onSearch = onSearch,
        )
        HorizontalDivider()
        OnlineVoiceResults(
            state = state,
            playingId = playingId,
            listState = listState,
            onOpen = onOpen,
            onPreview = onPreview,
            onSend = onSend,
            onAdd = onAdd,
            onPrevious = onPrevious,
            onNext = onNext,
            onRetry = onRetry,
            selectable = selectable,
            selectedIds = selectedIds,
            onToggleSelection = onToggleSelection,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VoiceProviderSelector(
    provider: VoiceProvider,
    onProvider: (VoiceProvider) -> Unit,
) {
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
}

@Composable
private fun OnlineVoiceResults(
    state: PanelUiState<VoiceProviderPage>,
    playingId: String?,
    listState: LazyListState,
    onOpen: (VoiceItem) -> Unit,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onAdd: ((VoiceItem) -> Unit)?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    selectable: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: ((VoiceItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
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
                OutlinedButton(onClick = onPrevious, enabled = state.value.page > 0) {
                    Text(stringResource(R.string.panel_action_previous_page))
                }
                Text(
                    stringResource(R.string.voice_panel_page_number, state.value.page + 1),
                    Modifier.padding(horizontal = 12.dp),
                )
                OutlinedButton(onClick = onNext, enabled = state.value.hasMore) {
                    Text(stringResource(R.string.panel_action_next_page))
                }
            }
        }
    }
}

@Composable
private fun SharedVoiceContent(
    packsState: PanelUiState<List<VoicePack>>,
    layout: VoicePackLayout,
    selectedPack: VoicePack?,
    itemsState: PanelUiState<List<VoiceItem>>,
    query: String,
    playingId: String?,
    packListState: LazyListState,
    itemListState: LazyListState,
    onSelectPack: (VoicePack) -> Unit,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onRetryPacks: () -> Unit,
    onRetryItems: () -> Unit,
    selectable: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelection: ((VoiceItem) -> Unit)? = null,
) {
    PanelStateContent(packsState, onRetryPacks) { packs ->
        if (layout == VoicePackLayout.TABS) {
            Column(Modifier.fillMaxSize()) {
                PanelPackChips(
                    packs = packs,
                    selectedId = selectedPack?.id,
                    id = VoicePack::id,
                    title = VoicePack::title,
                    onSelect = onSelectPack,
                )
                Box(Modifier.weight(1f)) {
                    SharedVoiceItems(
                        selectedPack = selectedPack,
                        itemsState = itemsState,
                        query = query,
                        playingId = playingId,
                        listState = itemListState,
                        onPreview = onPreview,
                        onSend = onSend,
                        onRetry = onRetryItems,
                        selectable = selectable,
                        selectedIds = selectedIds,
                        onToggleSelection = onToggleSelection,
                    )
                }
            }
        } else if (selectedPack == null) {
            val visiblePacks = packs.filter {
                query.isBlank() || it.title.contains(query, ignoreCase = true) ||
                        it.badge?.contains(query, ignoreCase = true) == true
            }
            if (visiblePacks.isEmpty()) {
                PanelEmptyAction(stringResource(R.string.voice_panel_empty_no_shared_pack_match))
            }
            else VoicePackList(visiblePacks, packListState, onSelectPack)
        } else {
            SharedVoiceItems(
                selectedPack = selectedPack,
                itemsState = itemsState,
                query = query,
                playingId = playingId,
                listState = itemListState,
                onPreview = onPreview,
                onSend = onSend,
                onRetry = onRetryItems,
                selectable = selectable,
                selectedIds = selectedIds,
                onToggleSelection = onToggleSelection,
            )
        }
    }
}

@Composable
private fun SharedVoiceItems(
    selectedPack: VoicePack?,
    itemsState: PanelUiState<List<VoiceItem>>,
    query: String,
    playingId: String?,
    listState: LazyListState,
    onPreview: (VoiceItem) -> Unit,
    onSend: (VoiceItem) -> Unit,
    onRetry: () -> Unit,
    selectable: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: ((VoiceItem) -> Unit)?,
) {
    if (selectedPack == null) {
        PanelEmptyAction(stringResource(R.string.voice_panel_select_shared_pack))
        return
    }
    PanelStateContent(itemsState, onRetry) { voices ->
        val visibleVoices = voices.filter {
            query.isBlank() || it.title.contains(query, ignoreCase = true)
        }
        if (visibleVoices.isEmpty()) {
            PanelEmptyAction(stringResource(R.string.voice_panel_empty_no_shared_voice_match))
        }
        else VoiceList(
            voices = visibleVoices,
            playingId = playingId,
            onPreview = onPreview,
            onSend = onSend,
            listState = listState,
            selectable = selectable,
            selectedIds = selectedIds,
            onToggleSelection = onToggleSelection,
        )
    }
}

@Composable
private fun VoiceSettingsContent(
    localPackLayout: VoicePackLayout,
    wrapActions: Boolean,
    onLocalPackLayoutChange: (VoicePackLayout) -> Unit,
    onWrapActionsChange: (Boolean) -> Unit,
) {
    var maxHistory by remember { mutableLongStateOf(PanelSettings.voiceMaxHistory.coerceAtLeast(1L)) }
    var downloadConcurrency by remember {
        mutableIntStateOf(PanelSettings.effectivePanelDownloadConcurrency)
    }
    var conversionConcurrency by remember {
        mutableIntStateOf(PanelSettings.effectivePanelConversionConcurrency)
    }
    var autoClose by remember { mutableStateOf(PanelSettings.panelAutoClose) }
    var rememberNavigation by remember { mutableStateOf(PanelSettings.rememberPanelNavigation) }
    var clientIdPrompt by remember { mutableStateOf(false) }
    var historyPrompt by remember { mutableStateOf(false) }
    var downloadConcurrencyPrompt by remember { mutableStateOf(false) }
    var conversionConcurrencyPrompt by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { PanelFunBoxApiClientIdSetting { clientIdPrompt = true } }
            item {
                PanelDropdownSetting(
                    title = stringResource(R.string.voice_setting_pack_layout),
                    selected = localPackLayout,
                    options = listOf(
                        VoicePackLayout.TABS to stringResource(R.string.panel_layout_tabs),
                        VoicePackLayout.LIST to stringResource(R.string.panel_layout_list),
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
                downloadConcurrency = downloadConcurrency,
                onCustomDownloadConcurrency = { downloadConcurrencyPrompt = true },
                conversionConcurrency = conversionConcurrency,
                onCustomConversionConcurrency = { conversionConcurrencyPrompt = true },
                autoClose = autoClose,
                onAutoCloseChange = {
                    autoClose = it
                    PanelSettings.panelAutoClose = it
                },
                wrapActions = wrapActions,
                onWrapActionsChange = onWrapActionsChange,
                rememberNavigation = rememberNavigation,
                onRememberNavigationChange = {
                    rememberNavigation = it
                    PanelSettings.rememberPanelNavigation = it
                    if (!it) PanelNavigationMemory.clear()
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
            title = stringResource(R.string.panel_setting_max_history),
            label = stringResource(R.string.panel_number_at_least_one),
            initialValue = maxHistory,
            minValue = 1,
            onDismiss = { historyPrompt = false },
            onConfirm = {
                maxHistory = it
                PanelSettings.voiceMaxHistory = it
                historyPrompt = false
            },
        )
        if (downloadConcurrencyPrompt) PanelNumberPrompt(
            title = stringResource(R.string.panel_setting_download_concurrency),
            label = stringResource(R.string.panel_number_tasks_1_32),
            initialValue = downloadConcurrency.toLong(),
            minValue = PanelSettings.MIN_PANEL_CONCURRENCY.toLong(),
            maxValue = PanelSettings.MAX_PANEL_DOWNLOAD_CONCURRENCY.toLong(),
            onDismiss = { downloadConcurrencyPrompt = false },
            onConfirm = {
                downloadConcurrency = it.toInt()
                PanelSettings.panelDownloadConcurrency = downloadConcurrency
                downloadConcurrencyPrompt = false
            },
        )
        if (conversionConcurrencyPrompt) PanelNumberPrompt(
            title = stringResource(R.string.panel_setting_conversion_concurrency),
            label = stringResource(R.string.panel_number_tasks_1_8),
            initialValue = conversionConcurrency.toLong(),
            minValue = PanelSettings.MIN_PANEL_CONCURRENCY.toLong(),
            maxValue = PanelSettings.MAX_PANEL_CONVERSION_CONCURRENCY.toLong(),
            onDismiss = { conversionConcurrencyPrompt = false },
            onConfirm = {
                conversionConcurrency = it.toInt()
                PanelSettings.panelConversionConcurrency = conversionConcurrency
                conversionConcurrencyPrompt = false
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
                IconButton(onClick = onSystemBack) {
                    Icon(MaterialSymbols.Outlined.Arrow_back, stringResource(R.string.panel_action_back))
                }
            }
            Text(
                stringResource(R.string.tts_choose_or_manage_voice),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.dialog_close))
            }
        }
        when (source) {
            null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.voice_panel_import_from_device))
                    }
                    OutlinedButton(onClick = { onSource(SOURCE_PANEL) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.voice_panel_choose_from_panel))
                    }
                }
                OutlinedButton(onClick = { onSource(SOURCE_EXAMPLES) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.voice_panel_voice_examples))
                }
                LazyColumn(Modifier.weight(1f)) {
                    item(key = "none") {
                        ListItem(
                            modifier = Modifier
                                .animateItem()
                                .clickable(onClick = onSelectNone),
                            colors = panelListItemColors(),
                            content = { Text(stringResource(R.string.panel_none)) },
                            supportingContent = {
                                Text(stringResource(R.string.voice_panel_no_clone_voice))
                            },
                            leadingContent = {
                                RadioButton(selected = selectedId.isBlank(), onClick = onSelectNone)
                            },
                        )
                    }
                    items(clones, key = { it.id }) { voice ->
                        ListItem(
                            modifier = Modifier
                                .animateItem()
                                .clickable { onSelect(voice) },
                            colors = panelListItemColors(),
                            content = { Text(voice.name) },
                            leadingContent = {
                                RadioButton(selected = voice.id == selectedId, onClick = { onSelect(voice) })
                            },
                            trailingContent = {
                                IconButton(onClick = { onDelete(voice) }) {
                                    Icon(
                                        MaterialSymbols.Outlined.Delete,
                                        stringResource(R.string.voice_panel_delete_clone_voice),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            SOURCE_PANEL -> {
                Text(stringResource(R.string.voice_panel_choose_from_panel), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSource(SOURCE_LOCAL) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.voice_panel_local_packs))
                    }
                    OutlinedButton(onClick = { onSource(SOURCE_SHARED) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.voice_panel_shared_packs))
                    }
                }
            }

            SOURCE_LOCAL -> VoiceList(
                localPacks.flatMap { it.items },
                playingId,
                onPreview = onPreviewVoice,
                onSend = onChooseVoice,
                terminalActionIcon = MaterialSymbols.Outlined.Check_circle,
                terminalActionLabel = stringResource(R.string.voice_panel_choose_clone_source),
            )

            SOURCE_SHARED -> if (sharedPack == null) {
                PanelStateContent(sharedPacksState) { packs ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(packs, key = VoicePack::id) { pack ->
                            ListItem(
                                modifier = Modifier
                                    .animateItem()
                                    .clickable { onSelectSharedPack(pack) },
                                colors = panelListItemColors(),
                                content = { Text(pack.title) },
                                supportingContent = {
                                    Text(pluralStringResource(R.plurals.voice_count, pack.itemCount, pack.itemCount))
                                },
                                leadingContent = { Icon(MaterialSymbols.Outlined.Folder, null) },
                            )
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = onBackSharedPacks) {
                    Icon(MaterialSymbols.Outlined.Arrow_back, null)
                    Text(stringResource(R.string.voice_panel_back_to_shared_packs))
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
                            terminalActionLabel = stringResource(R.string.voice_panel_choose_clone_source),
                        )
                    }
                }
            }

            SOURCE_EXAMPLES -> {
                if (selectedExampleGroup == null) {
                    PanelStateContent(exampleGroupsState, onLoadGroups) { groups ->
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(groups, key = { it }) { group ->
                                ListItem(
                                    modifier = Modifier
                                        .animateItem()
                                        .clickable { onSelectExampleGroup(group) },
                                    colors = panelListItemColors(),
                                    content = { Text(group) },
                                    leadingContent = { Icon(MaterialSymbols.Outlined.Folder, null) },
                                )
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBackExamples) {
                            Icon(
                                MaterialSymbols.Outlined.Arrow_back,
                                stringResource(R.string.voice_panel_back_to_example_groups),
                            )
                        }
                        Text(
                            stringResource(R.string.voice_panel_example_group_title, selectedExampleGroup),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    PanelStateContent(examplesState) { examples ->
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(examples, key = { "${it.group}/${it.fileName}" }) { example ->
                                ListItem(
                                    modifier = Modifier.animateItem(),
                                    colors = panelListItemColors(),
                                    content = {
                                        Text(example.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = { onPreviewExample(example) }) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Play_arrow,
                                                    stringResource(R.string.panel_action_preview),
                                                )
                                            }
                                            IconButton(onClick = { onAddExample(example) }) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Download,
                                                    stringResource(R.string.voice_panel_add_to_local),
                                                )
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
        Text(stringResource(R.string.voice_panel_preview_title), style = MaterialTheme.typography.titleMedium)
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
                    if (playing) {
                        stringResource(R.string.panel_action_pause)
                    } else {
                        stringResource(R.string.panel_action_resume)
                    },
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

private fun fallbackAudioMime(path: String): String = when (MediaFileTypeDetector.detectAudio(path.asPath)) {
    MediaFileTypeDetector.AudioFormat.MP3 -> "audio/mpeg"
    MediaFileTypeDetector.AudioFormat.M4A -> "audio/mp4"
    MediaFileTypeDetector.AudioFormat.AAC -> "audio/aac"
    MediaFileTypeDetector.AudioFormat.WAV -> "audio/wav"
    MediaFileTypeDetector.AudioFormat.AMR -> "audio/amr"
    MediaFileTypeDetector.AudioFormat.SILK -> "audio/silk"
    MediaFileTypeDetector.AudioFormat.FLAC -> "audio/flac"
    else -> "application/octet-stream"
}
