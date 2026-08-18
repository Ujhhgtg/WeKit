@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.workspace.WorkspaceStore
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Memory (§8): a prominent master switch plus a read-only view of the parsed MEMORY.md index. No
 * CRUD here — memory files are managed by the agent itself. Entering while memory is off stays on
 * the page and shows the index area hidden behind an informational note; only an in-page disable
 * pops back. If the index fails to parse, a warning clarifies it is only a display issue.
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val memoryEnabled by WeAgentService.memoryEnabled

    // null while loading; ParseResult afterwards.
    var index by remember { mutableStateOf<MemoryIndex?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        index = withContext(Dispatchers.IO) { parseMemoryIndex() }
        loaded = true
    }

    // InstallerX pattern: pop only on an in-page disable, on the committed state rather than the
    // click (WeAgentService.memoryEnabled updates only after persistence). Entering with memory
    // already off never sets the flag, so the page stays; flipping back on cancels the pop.
    var exitAfterDisable by remember { mutableStateOf(false) }

    LaunchedEffect(memoryEnabled) {
        if (memoryEnabled) exitAfterDisable = false
        else if (exitAfterDisable) onBack()
    }

    AgentSettingsScaffold(title = stringResource(R.string.agent_memory_title), onBack = onBack) {
        item {
            MemoryMasterSwitchBar(
                checked = memoryEnabled,
                onCheckedChange = {
                    exitAfterDisable = !it
                    WeAgentService.setMemoryEnabled(it)
                },
            )
        }

        if (!loaded) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@AgentSettingsScaffold
        }

        if (!memoryEnabled) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.agent_memory_disabled_summary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@AgentSettingsScaffold
        }

        val idx = index
        when {
            idx == null || idx.parseFailed -> item {
                SegmentedColumn(
                    modifier = Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                    title = stringResource(R.string.agent_memory_index_title),
                ) {
                    item {
                        Text(
                            stringResource(R.string.agent_memory_index_parse_failed),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            idx.entries.isEmpty() -> item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_memory_index_empty),
                    modifier = Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                )
            }

            else -> item {
                SegmentedColumn(
                    modifier = Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                    title = stringResource(R.string.agent_memory_index_title),
                ) {
                    idx.entries.forEach { e ->
                        item {
                            BaseWidget(title = e.title, description = e.description)
                        }
                    }
                }
            }
        }
    }
}

/** InstallerX-style prominent master switch: the whole row toggles, the container color follows the state. */
@Composable
private fun MemoryMasterSwitchBar(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "MemoryMasterSwitchBarContainer",
    )
    val contentColor = if (checked) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(35.dp))
            .background(containerColor)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.agent_memory_title),
            style = MaterialTheme.typography.titleLarge,
            color = contentColor,
        )
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = {
                Icon(
                    imageVector = if (checked) MaterialSymbols.Outlined.Check else MaterialSymbols.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
            colors = SwitchDefaults.colors(
                checkedIconColor = MaterialTheme.colorScheme.primary,
                uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
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
