@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ujhhgtg.wekit.activity.settings

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Vertical_align_bottom
import com.composables.icons.materialsymbols.outlined.Vertical_align_top
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.ui.content.m3AppBarColor
import dev.ujhhgtg.wekit.ui.content.m3BackdropLayer
import dev.ujhhgtg.wekit.ui.content.rememberMaterial3BlurBackdrop
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.crash.CrashLogsManager
import dev.ujhhgtg.wekit.utils.formatBytesSize
import dev.ujhhgtg.wekit.utils.formatEpoch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.readText
import androidx.compose.animation.core.tween as animTween

private const val LOGS_TAG = "SettingsActivity"

/** Which log kind a page is showing. */
private enum class LogKind { RUN, CRASH }

// ---------------------------------------------------------------------------
//  Parsed models
// ---------------------------------------------------------------------------

/** One run-log entry: a header line plus any continuation (stack-trace) lines folded into [message]. */
private data class RunLogEntry(
    val time: String?,
    val level: Char?,
    val tag: String?,
    val message: String,
)

/** One crash-report section: a "==== Title ====" block and the body lines beneath it. */
private data class CrashSection(
    val title: String,
    val body: String,
)

// WeLogger writes each entry as: "$ts $level/$TAG $tag: $msg"
//   ts    = yyyy-MM-dd HH:mm:ss.SSS
//   level = one of V D I W E A
//   $TAG  = BuildConfig.TAG (the module tag), $tag = caller tag
// e.g. "2026-07-05 14:30:22.123 E/WeKit AggregateChats: something failed"
// Groups: 1=date 2=time(+ms) 3=level 4=moduleTag 5=callerTag 6=message
private val RUN_LOG_REGEX = Regex(
    """^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEAF])/(\S+)\s+([^:]*): (.*)$""",
)

/**
 * Parses raw run-log lines into [RunLogEntry] cards. A line matching [RUN_LOG_REGEX] starts a new
 * card; any other line (stack-trace continuation, multi-line message) folds into the previous card
 * so multi-line entries stay together. Leading orphan lines become metadata-less cards.
 */
private fun parseRunLog(text: String): List<RunLogEntry> {
    val out = ArrayList<RunLogEntry>()
    for (line in text.lineSequence()) {
        if (line.isEmpty() && out.isEmpty()) continue
        val m = RUN_LOG_REGEX.matchEntire(line)
        when {
            m != null -> {
                val (_, time, level, _, tag, msg) = m.destructured
                out.add(
                    RunLogEntry(
                        time = time,
                        level = level.firstOrNull(),
                        tag = tag.trim().ifEmpty { null },
                        message = msg,
                    ),
                )
            }

            out.isNotEmpty() -> {
                val prev = out.removeAt(out.size - 1)
                out.add(prev.copy(message = if (prev.message.isEmpty()) line else prev.message + "\n" + line))
            }

            else -> out.add(RunLogEntry(time = null, level = null, tag = null, message = line))
        }
    }
    return out
}

/**
 * Splits a crash report into [CrashSection] cards. The report format (see CrashInfoCollector) is a
 * sequence of `"===="` fenced blocks: a fence line, a title line, a fence line, then the body up to
 * the next fence. Any preamble before the first section becomes its own untitled card.
 */
private fun parseCrashLog(text: String): List<CrashSection> {
    val lines = text.lines()
    val fence = "========================================"
    val out = ArrayList<CrashSection>()

    var i = 0
    val preamble = StringBuilder()
    // Collect anything before the first fenced title as a preamble card.
    while (i < lines.size && !(lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence)) {
        preamble.appendLine(lines[i]); i++
    }
    val pre = preamble.toString().trim()
    if (pre.isNotEmpty()) out.add(CrashSection(title = "", body = pre))

    while (i < lines.size) {
        // Expect: fence / title / fence / body...
        if (lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence) {
            val title = lines[i + 1].trim()
            i += 3
            val body = StringBuilder()
            while (i < lines.size && !(lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence)) {
                body.appendLine(lines[i]); i++
            }
            out.add(CrashSection(title = title, body = body.toString().trim()))
        } else {
            i++
        }
    }
    return out
}

// ---------------------------------------------------------------------------
//  File operations: share (FileProvider) + save (SAF)
// ---------------------------------------------------------------------------

/**
 * Shares a log file as a text/plain attachment. Uses WeChat's built-in FileProvider authority
 * (`<host>.external.fileprovider`, whose paths cover external + root storage) since this activity
 * runs inside the host process. Falls back to sharing the file's text inline if the provider throws.
 */
private fun shareLogFile(context: Context, localizedContext: Context, file: Path) {
    val f = file.toFile()
    val authority = "${HostInfo.packageName}.external.fileprovider"
    val sendIntent = runCatching {
        val uri = FileProvider.getUriForFile(context, authority, f)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrElse {
        WeLogger.w(LOGS_TAG, "FileProvider share failed, falling back to inline text", it)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            putExtra(Intent.EXTRA_TEXT, runCatching { file.readText() }.getOrDefault(""))
        }
    }
    val chooser = Intent.createChooser(
        sendIntent,
        localizedContext.getString(R.string.logs_share_chooser),
    )
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }
        .onFailure { WeLogger.e(LOGS_TAG, "failed to launch share chooser", it) }
}

/**
 * Opens the system document creator so the user can save a copy of [file] wherever they choose,
 * mirroring the config-export flow in SettingsActivity.
 */
private fun saveLogFile(context: Context, localizedContext: Context, file: Path) {
    TransparentActivity.launch(context) {
        val launcher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri == null) {
                finish(); return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "w")!!.use { out ->
                        file.toFile().inputStream().use { it.copyTo(out) }
                    }
                }.onFailure {
                    WeLogger.e(LOGS_TAG, "failed to save log", it)
                    showToastSuspend(localizedContext.getString(R.string.logs_save_failed))
                }.onSuccess {
                    showToastSuspend(localizedContext.getString(R.string.logs_save_success))
                }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        launcher.launch(file.name)
    }
}

// Bottom padding so scrollable content clears the floating bar (mirrors SettingsActivity's inset).
private val LOGS_BOTTOM_INSET = 88.dp

private val LOG_TABS = listOf(LogKind.RUN, LogKind.CRASH)

// ---------------------------------------------------------------------------
//  Page 2 — Logs
// ---------------------------------------------------------------------------

@Composable
fun LogsPager() {
    val context = LocalComponentActivity.current
    val localizedContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val kind = LOG_TABS[selectedTab]

    // One LazyListState per tab, retained across refreshes so scroll position survives a reload.
    val runListState = rememberLazyListState()
    val crashListState = rememberLazyListState()
    val listState = if (kind == LogKind.RUN) runListState else crashListState

    // Bumping this key forces the visible tab to re-list its files and re-read the selection.
    var refreshKey by remember { mutableIntStateOf(0) }
    // The file currently selected in the visible tab, hoisted so the toolbar can share/save it.
    var currentFile by remember { mutableStateOf<Path?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val barBackdrop = rememberMaterial3BlurBackdrop()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            // The blur layer spans the app bar and the tab row beneath it, mirroring the old
            // miuix TopAppBar bottomContent arrangement.
            Column(modifier = Modifier.m3AppBarBlur(barBackdrop)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_logs)) },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = barBackdrop.m3AppBarColor(),
                        scrolledContainerColor = barBackdrop.m3AppBarColor(),
                    ),
                    actions = {
                        IconButton(onClick = {
                            currentFile?.let { shareLogFile(context, localizedContext, it) }
                                ?: scope.launch {
                                    showToastSuspend(localizedContext.getString(R.string.logs_nothing_to_share))
                                }
                        }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Share,
                                contentDescription = stringResource(R.string.logs_share),
                            )
                        }
                        IconButton(onClick = {
                            currentFile?.let { saveLogFile(context, localizedContext, it) }
                                ?: scope.launch {
                                    showToastSuspend(localizedContext.getString(R.string.logs_nothing_to_save))
                                }
                        }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Save,
                                contentDescription = stringResource(R.string.logs_save),
                            )
                        }
                        // Overflow menu anchored to the icon, matching the Settings-page dropdown style.
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.More_vert,
                                contentDescription = stringResource(R.string.accessibility_overflow_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_refresh)) },
                                leadingIcon = { Icon(MaterialSymbols.Outlined.Refresh, null) },
                                onClick = { menuExpanded = false; refreshKey++ },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_go_top)) },
                                leadingIcon = { Icon(MaterialSymbols.Outlined.Vertical_align_top, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_go_bottom)) },
                                leadingIcon = { Icon(MaterialSymbols.Outlined.Vertical_align_bottom, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val end = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                        listState.animateScrollToItem(end)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_clear)) },
                                leadingIcon = { Icon(MaterialSymbols.Outlined.Delete_sweep, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            when (kind) {
                                                LogKind.RUN -> WeLogger.allLogFiles
                                                    .forEach { runCatching { it.toFile().delete() } }

                                                LogKind.CRASH -> CrashLogsManager.deleteAllCrashLogs()
                                            }
                                        }
                                        refreshKey++
                                    }
                                },
                            )
                        }
                    },
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                ) {
                    LOG_TABS.forEachIndexed { index, tabKind ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    stringResource(
                                        if (tabKind == LogKind.RUN) R.string.logs_tab_runtime
                                        else R.string.logs_tab_crash
                                    )
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Crossfade(targetState = kind, animationSpec = tween(200), label = "logKind") { k ->
            LogTabContent(
                kind = k,
                listState = if (k == LogKind.RUN) runListState else crashListState,
                backdropLayer = Modifier.m3BackdropLayer(barBackdrop),
                innerPadding = innerPadding,
                refreshKey = refreshKey,
                isRefreshing = isRefreshing,
                onRefreshingChange = { isRefreshing = it },
                onRefreshRequested = { refreshKey++ },
                onCurrentFileChange = { if (k == kind) currentFile = it },
            )
        }
    }
}

@Composable
private fun LogTabContent(
    kind: LogKind,
    listState: LazyListState,
    backdropLayer: Modifier,
    innerPadding: PaddingValues,
    refreshKey: Int,
    isRefreshing: Boolean,
    onRefreshingChange: (Boolean) -> Unit,
    onRefreshRequested: () -> Unit,
    onCurrentFileChange: (Path?) -> Unit,
) {
    val context = LocalContext.current
    // Files available for this tab, newest first.
    var files by remember(kind) { mutableStateOf<List<Path>>(emptyList()) }
    var selectedIndex by rememberSaveable(kind) { mutableIntStateOf(0) }
    // Parsed content of the selected file (type depends on kind).
    var runEntries by remember(kind) { mutableStateOf<List<RunLogEntry>>(emptyList()) }
    var crashSections by remember(kind) { mutableStateOf<List<CrashSection>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    // Whether the file listing has completed at least once; keeps the spinner up on first open
    // until we actually know whether there are files (the selected file is null until then).
    var listed by remember(kind) { mutableStateOf(false) }

    // (Re)list files whenever the tab is shown or a refresh is requested.
    LaunchedEffect(kind, refreshKey) {
        val result = withContext(Dispatchers.IO) {
            when (kind) {
                LogKind.RUN -> WeLogger.allLogFiles
                LogKind.CRASH -> CrashLogsManager.allCrashLogs
            }
        }
        files = result
        if (selectedIndex >= result.size) selectedIndex = 0
        listed = true
    }

    val selectedFile = files.getOrNull(selectedIndex)
    LaunchedEffect(selectedFile) { onCurrentFileChange(selectedFile) }

    // Read + parse the selected file off the main thread. refreshKey re-reads the same file;
    // `listed` gates the empty case so the spinner doesn't flash off before listing finishes.
    LaunchedEffect(selectedFile, refreshKey, listed) {
        loading = true
        if (selectedFile == null) {
            runEntries = emptyList(); crashSections = emptyList()
            // Only settle to "not loading" once listing is done and there is genuinely no file.
            if (listed) {
                loading = false
                onRefreshingChange(false)
            }
            return@LaunchedEffect
        }
        val text = withContext(Dispatchers.IO) { readLog(context, selectedFile) }
        when (kind) {
            LogKind.RUN -> runEntries = withContext(Dispatchers.Default) { parseRunLog(text) }
            LogKind.CRASH -> crashSections = withContext(Dispatchers.Default) { parseCrashLog(text) }
        }
        loading = false
        onRefreshingChange(false)
    }

    PullToRefreshBox(
        // Show the refresh indicator both for user pulls and while a file is being read/parsed,
        // so opening or switching to a large log surfaces the same loading affordance.
        isRefreshing = isRefreshing || loading,
        onRefresh = { onRefreshingChange(true); onRefreshRequested() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = backdropLayer
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "picker") {
                FileSelector(
                    files = files,
                    selectedIndex = selectedIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0)),
                    onSelected = { selectedIndex = it },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (files.isEmpty()) {
                // Only announce "no logs" once listing has finished, so it doesn't flash under the spinner.
                if (listed) {
                    item(key = "empty-files") {
                        LogsEmpty(
                            stringResource(
                                if (kind == LogKind.RUN) R.string.logs_no_runtime else R.string.logs_no_crash
                            )
                        )
                    }
                }
            } else when (kind) {
                LogKind.RUN -> {
                    if (runEntries.isEmpty() && !loading) {
                        item(key = "empty-run") { LogsEmpty(stringResource(R.string.logs_file_empty)) }
                    }
                    items(runEntries.size, key = { "run-$it" }) { i -> RunLogCard(runEntries[i]) }
                }

                LogKind.CRASH -> {
                    if (crashSections.isEmpty() && !loading) {
                        item(key = "empty-crash") { LogsEmpty(stringResource(R.string.logs_file_empty)) }
                    }
                    items(crashSections.size, key = { "crash-$it" }) { i -> CrashSectionCard(crashSections[i]) }
                }
            }

            item(key = "bottom-inset") { Spacer(Modifier.height(LOGS_BOTTOM_INSET)) }
        }
    }
}

/** Reads the full log file (modern devices handle multi-MB logs fine). */
private fun readLog(context: Context, file: Path): String =
    runCatching { file.readText() }
        .getOrElse { context.getString(R.string.logs_read_failed, it.message.orEmpty()) }
// ---------------------------------------------------------------------------
//  File selector + cards + empty state
// ---------------------------------------------------------------------------

@Composable
private fun FileSelector(
    files: List<Path>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    val labels = remember(files) {
        files.map { "${it.name}  ·  ${formatBytesSize(runCatching { it.fileSize() }.getOrDefault(0))}" }
    }
    var showDialog by remember { mutableStateOf(false) }
    SegmentedColumn(modifier = modifier.fillMaxWidth()) {
        item {
            BaseWidget(
                title = stringResource(R.string.logs_select_file),
                description = files.getOrNull(selectedIndex)?.let {
                    formatEpoch(it.getLastModifiedTime().toMillis(), true)
                },
                onClick = { showDialog = true },
                trailingContent = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Expand_more,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.logs_select_file)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        labels.forEachIndexed { index, label ->
                            item(key = label) {
                                BaseWidget(
                                    title = label,
                                    titleStyle = MaterialTheme.typography.bodyMedium,
                                    selected = index == selectedIndex,
                                    onClick = {
                                        showDialog = false
                                        onSelected(index)
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.dialog_close))
                }
            },
        )
    }
}

/** Long messages (over this many lines) collapse to a preview with an expand toggle. */
private const val RUN_LOG_COLLAPSE_LINES = 5

@Composable
private fun RunLogCard(entry: RunLogEntry) {
    // Split once; if the message runs past the threshold, show a 5-line preview + expand toggle.
    val lines = remember(entry.message) { entry.message.split("\n") }
    val isLong = lines.size > RUN_LOG_COLLAPSE_LINES
    val head = remember(lines) { lines.take(RUN_LOG_COLLAPSE_LINES).joinToString("\n") }
    val rest = remember(lines) { lines.drop(RUN_LOG_COLLAPSE_LINES).joinToString("\n") }
    var expanded by remember(entry) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = animTween(250),
        label = "chevron",
    )

    BaseItemContainer(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.level?.let { level ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(levelColor(level))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(level.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    entry.tag?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    entry.time?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (isLong) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Expand_more,
                            contentDescription = stringResource(
                                if (expanded) R.string.logs_collapse else R.string.logs_expand
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }
            }
            if (entry.message.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Column {
                        Text(
                            text = if (isLong) head else entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isLong) {
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Text(
                                    text = rest,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
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
private fun CrashSectionCard(section: CrashSection) {
    BaseItemContainer(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (section.title.isNotEmpty()) {
                Text(
                    text = section.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
            SelectionContainer {
                Text(
                    text = section.body,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LogsEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Log-level chip background color, matching the run-log level chars WeLogger emits. */
private fun levelColor(level: Char): Color = when (level) {
    'E', 'F', 'A' -> Color(0xFFD32F2F)
    'W' -> Color(0xFFF57C00)
    'I' -> Color(0xFF388E3C)
    'D' -> Color(0xFF1976D2)
    'V' -> Color(0xFF757575)
    else -> Color(0xFF9E9E9E)
}
