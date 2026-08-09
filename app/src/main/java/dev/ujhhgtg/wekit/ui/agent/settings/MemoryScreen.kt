package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.agent.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Memory (§8): a global on/off switch plus a read-only view of the parsed MEMORY.md index. No CRUD
 * here — memory files are managed by the agent itself. If the index fails to parse, a warning is
 * shown that clarifies it is only a display issue and does not affect the agent.
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    // null while loading; ParseResult afterwards.
    var index by remember { mutableStateOf<MemoryIndex?>(null) }

    LaunchedEffect(Unit) {
        enabled = WeAgentSettings.memoryEnabled()
        index = withContext(Dispatchers.IO) { parseMemoryIndex() }
        loaded = true
    }

    AgentSettingsScaffold(title = stringResource(R.string.agent_memory_title), onBack = onBack) {
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.agent_memory_enable_title),
                    summary = stringResource(R.string.agent_memory_enable_summary),
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        scope.launch {
                            WeAgentSettings.set(WeAgentSettings.KEY_MEMORY_ENABLED, on.toString())
                            BuiltinToolProvider.fsToolsVisible = on || WeAgentSettings.workspaceEnabled()
                        }
                    },
                )
            }
        }

        if (!loaded) {
            item { EmptyHint(stringResource(R.string.common_loading)) }; return@AgentSettingsScaffold
        }

        item { SmallTitle(stringResource(R.string.agent_memory_index_title)) }
        val idx = index
        when {
            idx == null || idx.parseFailed -> item {
                Card(Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                    Text(
                        stringResource(R.string.agent_memory_index_parse_failed),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            idx.entries.isEmpty() -> item { EmptyHint(stringResource(R.string.agent_memory_index_empty)) }
            else -> item {
                Card(Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                    idx.entries.forEach { e ->
                        ArrowPreference(title = e.title, summary = e.description, onClick = {})
                    }
                }
            }
        }
    }
}

private data class MemoryIndexEntry(val title: String, val description: String)
private data class MemoryIndex(val entries: List<MemoryIndexEntry>, val parseFailed: Boolean)

/**
 * Parses MEMORY.md's index lines of the form `- [Title](file.md) — description`. Any exception is
 * treated as a parse failure (surfaced as a non-blocking warning).
 */
private fun parseMemoryIndex(): MemoryIndex = runCatching {
    val text = WorkspaceStore.readMemoryIndex()
    val re = Regex("""^\s*[-*]\s*\[([^\]]+)]\([^)]*\)\s*[—\-:]*\s*(.*)$""")
    val entries = text.lineSequence().mapNotNull { line ->
        re.find(line)?.let { MemoryIndexEntry(it.groupValues[1].trim(), it.groupValues[2].trim()) }
    }.toList()
    MemoryIndex(entries, parseFailed = false)
}.getOrElse { MemoryIndex(emptyList(), parseFailed = true) }
