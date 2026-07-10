package dev.ujhhgtg.wekit.ui.agent

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Call_split
import com.composables.icons.materialsymbols.outlined.Cancel
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Copy_all
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Menu
import com.composables.icons.materialsymbols.outlined.Menu_open
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Star
import com.composables.icons.materialsymbols.outlined.Stop
import com.composables.icons.materialsymbols.outlinedfilled.Star
import dev.ujhhgtg.wekit.activity.agent.WeAgentSettingsActivity
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.ChatRow
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * The expanded agent panel (§1.3), intentionally lean: a collapsible session sidebar (hidden by
 * default, opened from the top-left icon), the streaming chat view, an input bar, a manual-approval
 * card, and a settings-entry button. All detailed configuration lives in the dedicated settings
 * Activity (Phase 8), not here.
 */
@Composable
fun WeAgentPanel(onDismiss: () -> Unit) {
    // The session sidebar is collapsed by default; the header icon toggles it.
    var sidebarOpen by remember { mutableStateOf(false) }

    // Scrim + centered card.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                // Swallow clicks so tapping the card doesn't dismiss.
                .clickable(enabled = false) {},
        ) {
            // Chat fills the whole card; the sidebar floats above it when open.
            Box(Modifier.fillMaxSize()) {
                ChatPane(
                    modifier = Modifier.fillMaxSize(),
                    onDismiss = onDismiss,
                    onOpenSidebar = { sidebarOpen = true },
                )
                SessionSidebar(open = sidebarOpen, onClose = { sidebarOpen = false })
            }
        }
    }
}

/**
 * Overlay session drawer: hidden until [open]. When open it dims the chat behind a scrim (tap to
 * close) and slides a panel in from the left, covering part of the chat rather than pushing it.
 */
@Composable
private fun SessionSidebar(open: Boolean, onClose: () -> Unit) {
    // The wrapper Box is ALWAYS composed (it's a sibling of the chat pane), so both child
    // AnimatedVisibility nodes stay in composition across open/close. That's what lets the panel
    // play its slide-IN on open — previously the panel's AnimatedVisibility was nested inside the
    // scrim's, so it first entered composition already-visible and skipped its enter transition
    // (only the outer fade showed). When closed, both children collapse to nothing and the empty
    // transparent Box has no pointer modifiers, so touches pass through to the chat beneath.
    Box(Modifier.fillMaxSize()) {
        // Scrim over the chat; tapping it closes the sidebar. Fades in/out.
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onClose),
            )
        }
        // Drawer panel: slides in from the left on open and back out on close (now symmetric).
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(220)) { -it },
            exit = slideOutHorizontally(tween(220)) { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            SessionDrawerContent(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.62f)
                    // Swallow taps so they don't fall through to the scrim.
                    .clickable(enabled = false) {},
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun SessionDrawerContent(modifier: Modifier, onClose: () -> Unit) {
    val sessions = WeAgentService.uiSessions
    val current by WeAgentService.currentSessionId
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topEnd = 20.dp, bottomStart = 20.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.width(36.dp)) {
                    Icon(MaterialSymbols.Outlined.Menu_open, contentDescription = "收起会话侧栏")
                }
                Text("会话", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { WeAgentService.newSession(); onClose() }) {
                    Icon(MaterialSymbols.Outlined.Add, contentDescription = "新建会话")
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sessions, key = { it.id }) { s ->
                    val selected = s.id == current
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { WeAgentService.switchSession(s.id); onClose() },
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // Star toggles favorite (pins to top + delete-protects). Delete only shows
                            // for non-favorited sessions — a favorite must be un-starred before deleting.
                            IconButton(onClick = { WeAgentService.toggleFavorite(s.id) }, modifier = Modifier.width(28.dp)) {
                                Icon(
                                    if (s.favorite) MaterialSymbols.OutlinedFilled.Star else MaterialSymbols.Outlined.Star,
                                    contentDescription = if (s.favorite) "取消收藏" else "收藏",
                                    tint = if (s.favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                    modifier = Modifier.width(16.dp),
                                )
                            }
                            if (!s.favorite) {
                                IconButton(onClick = { WeAgentService.deleteSession(s.id) }, modifier = Modifier.width(28.dp)) {
                                    Icon(MaterialSymbols.Outlined.Delete, contentDescription = "删除", modifier = Modifier.width(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPane(modifier: Modifier, onDismiss: () -> Unit, onOpenSidebar: () -> Unit) {
    // Hoisted here so the preset picker can insert text into the input bar.
    var inputText by remember { mutableStateOf("") }

    Column(modifier.padding(8.dp)) {
        // Header: sidebar toggle + title + settings entry + close.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenSidebar) {
                Icon(MaterialSymbols.Outlined.Menu, contentDescription = "展开会话侧栏")
            }
            Text("WeAgent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val ctx = dev.ujhhgtg.wekit.utils.HostInfo.application
                ctx.startActivity(
                    Intent(ctx, WeAgentSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                onDismiss()
            }) {
                Icon(MaterialSymbols.Outlined.Settings, contentDescription = "设置")
            }
            IconButton(onClick = onDismiss) {
                Icon(MaterialSymbols.Outlined.Close, contentDescription = "关闭")
            }
        }

        MessageList(Modifier.weight(1f), onRestoreInput = { inputText = it })

        ApprovalCard()

        InputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onInsertPreset = { preset ->
                inputText = if (inputText.isBlank()) preset else "$inputText\n$preset"
            },
        )
    }
}

/**
 * Compact token-usage strip shown above the input bar when "显示用量详细信息" is on (§ item 3),
 * following the common coding-agent convention: last request's input/output tokens plus the
 * context-window fill (percentage + a thin bar) when the current model declares a window. Renders
 * nothing until the first response of a session arrives.
 */
@Composable
private fun UsageStrip() {
    val usage by WeAgentService.currentUsage
    val currentModelId by WeAgentService.currentModelId
    val resolvedCtx by WeAgentService.currentContextWindow
    val models = WeAgentService.availableModels

    if (usage == null) return
    val u = usage ?: return

    // Context window (tokens) of the model actually used this turn. Prefer the service-published
    // resolved window (covers "默认", where currentModelId is null and wouldn't match any entry);
    // fall back to a direct lookup for an explicitly-bound model.
    val ctx = resolvedCtx ?: models.firstOrNull { it.id == currentModelId }?.contextWindow
    // Prompt tokens are the context occupancy; fall back to total when prompt isn't reported.
    val used = u.promptTokens ?: u.totalTokens
    val pct = if (ctx != null && ctx > 0 && used != null) (used.toFloat() / ctx).coerceIn(0f, 1f) else null

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val parts = buildList {
                u.promptTokens?.let { add("↑ ${fmtTokens(it)}") }
                u.completionTokens?.let { add("↓ ${fmtTokens(it)}") }
                u.totalTokens?.let { add("Σ ${fmtTokens(it)}") }
            }
            Text(
                parts.joinToString("   ").ifEmpty { "用量：暂无数据" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (pct != null) {
                Text(
                    "${(pct * 100).toInt()}% / ${fmtTokens(ctx!!)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (pct != null) {
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (pct >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** Compact token count: 1234 -> "1.2k", 980 -> "980". */
private fun fmtTokens(n: Int): String =
    if (n >= 1000) "%.1fk".format(n / 1000f) else n.toString()

/**
 * Multiline input with a bottom action row: a [+] menu on the left and a send button on the right.
 * The [+] opens a nested two-level menu for in-session quick actions (§1.3): switch model, bind a
 * workspace, toggle memory (inline switch), switch the system-prompt profile, and insert a preset
 * prompt. Model/workspace/prompt/preset each open a submenu; memory toggles inline.
 */
@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onInsertPreset: (String) -> Unit,
) {
    val ballState = WeAgentService.ballState.value
    val queue by WeAgentService.queuedMessage
    val queueText = queue  // resolve the delegate so we can smart-cast below
    val isRunning = ballState == WeAgentService.BallState.RUNNING || ballState == WeAgentService.BallState.PENDING_APPROVAL

    fun send() {
        if (text.isNotBlank()) { WeAgentService.sendMessage(text); onTextChange("") }
    }

    // --- Queued-message mode ---
    if (queueText != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                UsageStrip()
                Spacer(Modifier.height(2.dp))

                // Read-only preview of the queued text.
                OutlinedTextField(
                    value = queueText,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    minLines = 2,
                    maxLines = 6,
                    enabled = false,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlusMenu(onInsertPreset = onInsertPreset)
                    Spacer(Modifier.weight(1f))
                    if (isRunning) {
                        IconButton(onClick = { WeAgentService.cancelTurn() }) {
                            Icon(MaterialSymbols.Outlined.Stop, contentDescription = "中断")
                        }
                    }
                    IconButton(onClick = { WeAgentService.cancelQueuedMessage() }) {
                        Icon(MaterialSymbols.Outlined.Cancel, contentDescription = "取消排队")
                    }
                }
            }
        }
        return
    }

    // --- Normal mode ---
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            UsageStrip()
            Spacer(Modifier.height(2.dp))

            // Multiline text area.
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("给 WeAgent 发消息…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                minLines = 2,
                maxLines = 6,
            )
            // Action row: [+] left, [Send](./Interrupt) right.
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlusMenu(onInsertPreset = onInsertPreset)
                Spacer(Modifier.weight(1f))
                if (isRunning) {
                    IconButton(onClick = { WeAgentService.cancelTurn() }) {
                        Icon(MaterialSymbols.Outlined.Stop, contentDescription = "中断")
                    }
                }
                IconButton(
                    onClick = ::send,
                    enabled = text.isNotBlank(),
                ) {
                    Icon(MaterialSymbols.Outlined.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/** State for which submenu (if any) of the [PlusMenu] is currently open. */
private enum class PlusSubmenu { NONE, MODEL, WORKSPACE, PROFILE, PRESET }

/**
 * The nested "+" quick-action menu. The root lists the current selections; tapping model/workspace/
 * profile/preset opens a submenu, while memory toggles inline via a trailing [Switch].
 */
@Composable
private fun PlusMenu(onInsertPreset: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var submenu by remember { mutableStateOf(PlusSubmenu.NONE) }

    val models = WeAgentService.availableModels
    val systemPrompts = WeAgentService.availableSystemPrompts
    val workspaces = WeAgentService.availableWorkspaces
    val presets = WeAgentService.availablePresets
    val currentModelId by WeAgentService.currentModelId
    val currentSystemPromptId by WeAgentService.currentSystemPromptId
    val currentWorkspaceId by WeAgentService.currentWorkspaceId
    val memoryOn by WeAgentService.memoryEnabled

    // A null model id means "默认": the session follows the settings default model, resolved at turn
    // time (mirrors workspace/systemPrompt). Show "默认" for null rather than "未选择".
    val modelLabel = if (currentModelId == null) "默认"
    else models.firstOrNull { it.id == currentModelId }?.label ?: "未选择"
    // A null workspace means "默认": the session follows the settings default workspace, resolved
    // dynamically at turn time (see WeAgentService.runTurn).
    // id semantics: null = "默认" (follow settings default), "" = "无" (explicitly none), else the item.
    val workspaceLabel = when (currentWorkspaceId) {
        null -> "默认"; "" -> "无"
        else -> workspaces.firstOrNull { it.id == currentWorkspaceId }?.name ?: "默认"
    }
    val systemPromptLabel = when (currentSystemPromptId) {
        null -> "默认"; "" -> "无"
        else -> systemPrompts.firstOrNull { it.id == currentSystemPromptId }?.name ?: "默认"
    }

    fun close() { expanded = false; submenu = PlusSubmenu.NONE }

    Box {
        IconButton(onClick = { expanded = true; submenu = PlusSubmenu.NONE }) {
            Icon(MaterialSymbols.Outlined.Add, contentDescription = "快捷操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = ::close) {
            when (submenu) {
                PlusSubmenu.NONE -> {
                    DropdownMenuItem(
                        text = { NestedRow("模型", modelLabel) },
                        onClick = { submenu = PlusSubmenu.MODEL },
                    )
                    DropdownMenuItem(
                        text = { NestedRow("工作区", workspaceLabel) },
                        onClick = { submenu = PlusSubmenu.WORKSPACE },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("记忆", modifier = Modifier.weight(1f))
                                Switch(checked = memoryOn, onCheckedChange = { WeAgentService.setMemoryEnabled(it) })
                            }
                        },
                        onClick = { WeAgentService.setMemoryEnabled(!memoryOn) },
                    )
                    DropdownMenuItem(
                        text = { NestedRow("系统提示词", systemPromptLabel) },
                        onClick = { submenu = PlusSubmenu.PROFILE },
                    )
                    DropdownMenuItem(
                        text = { NestedRow("预设提示词", "点击选择") },
                        onClick = { submenu = PlusSubmenu.PRESET },
                    )
                }

                PlusSubmenu.MODEL -> {
                    SubmenuHeader("模型") { submenu = PlusSubmenu.NONE }
                    DropdownMenuItem(
                        text = { Text("默认" + if (currentModelId == null) "  ✓" else "") },
                        onClick = { WeAgentService.setSessionModel(null); close() },
                    )
                    if (models.isEmpty()) DropdownMenuItem(text = { Text("请先在设置中添加模型") }, onClick = ::close)
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label + if (m.id == currentModelId) "  ✓" else "") },
                            onClick = { WeAgentService.setSessionModel(m.id); close() },
                        )
                    }
                }

                PlusSubmenu.WORKSPACE -> {
                    SubmenuHeader("工作区") { submenu = PlusSubmenu.NONE }
                    DropdownMenuItem(
                        text = { Text("默认" + if (currentWorkspaceId == null) "  ✓" else "") },
                        onClick = { WeAgentService.setSessionWorkspace(null); close() },
                    )
                    // "无" = explicitly no workspace (sentinel ""), distinct from "默认" (follow default).
                    DropdownMenuItem(
                        text = { Text("无" + if (currentWorkspaceId == "") "  ✓" else "") },
                        onClick = { WeAgentService.setSessionWorkspace(""); close() },
                    )
                    workspaces.forEach { w ->
                        DropdownMenuItem(
                            text = { Text(w.name + if (w.id == currentWorkspaceId) "  ✓" else "") },
                            onClick = { WeAgentService.setSessionWorkspace(w.id); close() },
                        )
                    }
                }

                PlusSubmenu.PROFILE -> {
                    SubmenuHeader("系统提示词") { submenu = PlusSubmenu.NONE }
                    DropdownMenuItem(
                        text = { Text("默认" + if (currentSystemPromptId == null) "  ✓" else "") },
                        onClick = { WeAgentService.setSessionSystemPrompt(null); close() },
                    )
                    // "无" = explicitly no system prompt (sentinel ""), distinct from "默认" (follow default).
                    DropdownMenuItem(
                        text = { Text("无" + if (currentSystemPromptId == "") "  ✓" else "") },
                        onClick = { WeAgentService.setSessionSystemPrompt(""); close() },
                    )
                    systemPrompts.forEach { sp ->
                        DropdownMenuItem(
                            text = { Text(sp.name + if (sp.id == currentSystemPromptId) "  ✓" else "") },
                            onClick = { WeAgentService.setSessionSystemPrompt(sp.id); close() },
                        )
                    }
                }

                PlusSubmenu.PRESET -> {
                    SubmenuHeader("预设提示词") { submenu = PlusSubmenu.NONE }
                    if (presets.isEmpty()) DropdownMenuItem(text = { Text("暂无预设") }, onClick = ::close)
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.title) },
                            onClick = { onInsertPreset(preset.content); close() },
                        )
                    }
                }
            }
        }
    }
}

/** A root menu row showing a label on the left and the current value on the right. */
@Composable
private fun NestedRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A submenu title row with a back affordance. */
@Composable
private fun SubmenuHeader(title: String, onBack: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹ ", color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
        },
        onClick = onBack,
    )
}

@Composable
private fun MessageList(modifier: Modifier, onRestoreInput: (String) -> Unit = {}) {
    val messages = WeAgentService.uiMessages
    // The list state lives in WeAgentService so it survives this panel being disposed when the ball
    // closes; that's what keeps the scroll position put across open/close instead of resetting to the
    // top. We only pull to the bottom on a real new row or a session switch — never on a plain reopen.
    val listState = WeAgentService.messageListState
    val currentSessionId by WeAgentService.currentSessionId
    // For each user message, the createdAt of the assistant message that immediately preceded it.
    // "回到此处" / "创建分支" on a user row rewinds/branches to that assistant turn, not to the user
    // message itself — so the user's prompt ends up re-entering via the input bar instead of being
    // baked into history. Null means no preceding assistant turn (rewind clears all; branch is empty).
    val prevAssistantAt by remember {
        derivedStateOf {
            buildMap {
                var last: java.time.Instant? = null
                for (m in messages) {
                    if (m.role == ChatRow.Role.USER) put(m.id, last)
                    else if (m.role == ChatRow.Role.ASSISTANT) last = m.createdAt
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // Track the size seen at the start of THIS composition so the first emission (which just
        // reopening the panel produces) never scrolls — that's what preserves the position. After
        // that, a grown size animates to the bottom, and a session switch snaps there instantly once.
        var lastSize = -1
        snapshotFlow { messages.size to currentSessionId }
            .collect { (size, sessionId) ->
                if (size == 0) {
                    lastSize = 0
                    return@collect
                }
                when {
                    // New session (first show / switch): snap to the bottom once, no animation.
                    WeAgentService.messageListSyncedSessionId != sessionId -> {
                        WeAgentService.messageListSyncedSessionId = sessionId
                        listState.scrollToItem(messages.lastIndex)
                    }
                    // First emission of this composition (a plain reopen): keep the saved position.
                    lastSize == -1 -> Unit
                    // A genuine new row arrived while visible: follow it to the bottom.
                    size > lastSize -> listState.animateScrollToItem(messages.lastIndex)
                }
                lastSize = size
            }
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(messages, key = { it.id }) { row ->
            MessageBubble(
                row = row,
                onRestoreInput = onRestoreInput,
                prevAssistantTimestamp = prevAssistantAt[row.id],
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatRow,
    onRestoreInput: (String) -> Unit = {},
    prevAssistantTimestamp: java.time.Instant? = null,
) {
    when (row.role) {
        ChatRow.Role.TOOL -> ToolCard(row)
        else -> {
            val (bg, align) = when (row.role) {
                ChatRow.Role.USER -> MaterialTheme.colorScheme.primaryContainer to Alignment.End
                ChatRow.Role.SYSTEM_NOTE -> MaterialTheme.colorScheme.errorContainer to Alignment.CenterHorizontally
                else -> MaterialTheme.colorScheme.surfaceVariant to Alignment.Start
            }
            // The text card renders when there's text, or when there's no reasoning at all (so an
            // empty assistant turn still shows a placeholder). When reasoning exists but the turn
            // only made tool calls (no text), the text card is skipped.
            val showTextCard = row.text.isNotEmpty() || row.reasoning.isNullOrBlank()
            Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
                // Reasoning is its own separate, collapsible card above the message text.
                // Shown as soon as reasoning starts streaming (reasoningStreaming=true) and keeps
                // displaying completed reasoning after the turn ends (reasoningStreaming=false).
                if (row.reasoningStreaming || row.reasoning?.isNotBlank() == true) {
                    ReasoningCard(reasoning = row.reasoning, isStreaming = row.reasoningStreaming)
                    // Only separate from the text card when it actually follows; otherwise the outer
                    // LazyColumn's spacing already provides the gap to the next row (avoids a double gap).
                    if (showTextCard) Spacer(Modifier.height(6.dp))
                }
                if (showTextCard) {
                    // Long-pressing an assistant or user message opens a context menu.
                    var menuOpen by remember(row.id) { mutableStateOf(false) }
                    // Two-step confirmation state for "回到此处": first click arms it (turns red),
                    // second click executes. Resets whenever the menu closes.
                    var confirmGoBack by remember(row.id) { mutableStateOf(false) }
                    val longPressable = (row.role == ChatRow.Role.ASSISTANT || row.role == ChatRow.Role.USER) && row.text.isNotEmpty()
                    // A running turn blocks "回到此处" (the UI contract: truncation only when idle).
                    val sessionRunning = WeAgentService.ballState.value.let {
                        it == WeAgentService.BallState.RUNNING || it == WeAgentService.BallState.PENDING_APPROVAL
                    }
                    val sessionId = WeAgentService.currentSessionId.value
                    // The timestamp passed to truncateToMessage / branchSession.
                    // • Assistant row  → the row's own createdAt (unchanged behavior).
                    // • User row       → the preceding assistant turn's createdAt, so the user
                    //   message itself is NOT baked into history — it's restored to the input bar
                    //   instead. Instant.EPOCH when there is no preceding assistant message, which
                    //   the SQL interprets as "before all real messages" (clears / empty branch).
                    val anchorAt = if (row.role == ChatRow.Role.USER) {
                        prevAssistantTimestamp ?: java.time.Instant.EPOCH
                    } else {
                        row.createdAt
                    }
                    Box {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = bg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = if (longPressable) {
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { menuOpen = true },
                                )
                            } else Modifier,
                        ) {
                            // Assistant replies are rendered as Markdown; user/system text stays verbatim.
                            if (row.role == ChatRow.Role.ASSISTANT && row.text.isNotEmpty()) {
                                MarkdownText(
                                    markdown = row.text,
                                    modifier = Modifier.padding(10.dp),
                                )
                            } else {
                                Text(
                                    row.text.ifEmpty { "…" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                        if (longPressable) {
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = {
                                    menuOpen = false
                                    confirmGoBack = false
                                },
                            ) {
                                // --- 复制 ---
                                DropdownMenuItem(
                                    text = { Text("复制") },
                                    leadingIcon = {
                                        Icon(MaterialSymbols.Outlined.Copy_all, contentDescription = null)
                                    },
                                    onClick = {
                                        copyToClipboard(row.text)
                                        showToast("已复制")
                                        menuOpen = false
                                    },
                                )
                                // --- 回到此处 (two-step: first click arms, second executes) ---
                                // User rows: truncates to the preceding assistant turn and restores
                                // the user message text to the input bar.
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (confirmGoBack) "确认回到此处" else "回到此处",
                                            color = if (confirmGoBack) MaterialTheme.colorScheme.error
                                                    else LocalContentColor.current,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            MaterialSymbols.Outlined.History,
                                            contentDescription = null,
                                            tint = if (confirmGoBack) MaterialTheme.colorScheme.error
                                                   else LocalContentColor.current,
                                        )
                                    },
                                    enabled = !sessionRunning,
                                    onClick = {
                                        if (confirmGoBack) {
                                            if (sessionId != null) {
                                                WeAgentService.truncateToMessage(sessionId, anchorAt)
                                                if (row.role == ChatRow.Role.USER) onRestoreInput(row.text)
                                            }
                                            menuOpen = false
                                            confirmGoBack = false
                                        } else {
                                            confirmGoBack = true
                                        }
                                    },
                                )
                                // --- 创建分支 ---
                                // User rows: branches from the preceding assistant turn and restores
                                // the user message text to the input bar in the new session.
                                DropdownMenuItem(
                                    text = { Text("创建分支") },
                                    leadingIcon = {
                                        Icon(MaterialSymbols.Outlined.Call_split, contentDescription = null)
                                    },
                                    onClick = {
                                        if (sessionId != null) {
                                            WeAgentService.branchSession(sessionId, anchorAt)
                                            if (row.role == ChatRow.Role.USER) onRestoreInput(row.text)
                                        }
                                        menuOpen = false
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

/**
 * Tool-call card: header shows tool name + status; body is split into an input section (arguments
 * JSON) and an output section (result), separated by a thin divider. Collapsed by default.
 * Long-pressing the header copies both sections to the clipboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCard(row: ChatRow) {
    var expanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val caretRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "caret")

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { expanded = !expanded },
                            onLongClick = { menuOpen = true },
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🛠 ${row.toolName ?: "tool"}${row.toolStatus?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (expanded) "收起 " else "展开 ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(caretRotation),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Copy_all, contentDescription = null) },
                        onClick = {
                            val content = buildString {
                                row.toolInput?.let { append("输入：\n$it") }
                                if (row.text.isNotEmpty()) {
                                    if (row.toolInput != null) append("\n\n输出：\n")
                                    append(row.text)
                                }
                            }
                            copyToClipboard(content)
                            showToast("已复制")
                            menuOpen = false
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(220)),
            ) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    val toolInput = row.toolInput
                    // Input (arguments JSON)
                    if (toolInput != null) {
                        Text(
                            "输入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            toolInput,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    // Output (result)
                    Text(
                        "输出",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        row.text.ifEmpty { "（执行中…）" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Reasoning ("思考过程") card. While the turn is streaming ([isStreaming]=true) it shows an
 * animated "思考中···" header with a sweeping aurora shimmer — conveying that the model is actively
 * thinking. After streaming ends the title reverts to "💭 思考过程" and the card behaves like a
 * normal collapsible. Long-pressing the header copies the reasoning to the clipboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReasoningCard(
    reasoning: String?,
    isStreaming: Boolean,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    // expanded tracks the user's manual preference. effectiveExpanded also opens the body
    // automatically while streaming so reasoning text is visible as it arrives.
    var expanded by remember { mutableStateOf(false) }
    // Auto-collapse when streaming ends. LaunchedEffect fires on first composition (initial
    // isStreaming value) and on every subsequent change, so a DB-loaded card (isStreaming=false)
    // starts collapsed, and a just-finished streaming card collapses automatically.
    LaunchedEffect(isStreaming) { if (!isStreaming) expanded = false }
    val effectiveExpanded = expanded || isStreaming
    var menuOpen by remember { mutableStateOf(false) }
    val caretRotation by animateFloatAsState(if (effectiveExpanded) 180f else 0f, tween(220), label = "caret")

    val infiniteTransition = rememberInfiniteTransition(label = "reasoning-anim")

    // Shimmer sweep: a diagonal gradient that travels left→right across the card header.
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    // Cycling dots for "思考中·" / "思考中··" / "思考中···"
    val dotPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "dots",
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val titleText = if (isStreaming) "思考中${"·".repeat(dotPhase.toInt() % 3 + 1)}" else "💭 思考过程"

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // Aurora shimmer drawn behind the header row only when streaming.
                    .then(
                        if (isStreaming) Modifier.drawBehind {
                            drawRect(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        primary.copy(alpha = 0.18f),
                                        secondary.copy(alpha = 0.30f),
                                        primary.copy(alpha = 0.18f),
                                        Color.Transparent,
                                    ),
                                    start = Offset(size.width * (shimmerProgress - 0.4f), 0f),
                                    end   = Offset(size.width * (shimmerProgress + 0.4f), size.height),
                                )
                            )
                        } else Modifier
                    ),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { expanded = !expanded },
                            onLongClick = { menuOpen = true },
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        titleText,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Caret always shown so the user can collapse during or after streaming.
                    Text(
                        if (effectiveExpanded) "收起 " else "展开 ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(caretRotation),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Copy_all, contentDescription = null) },
                        onClick = {
                            copyToClipboard(reasoning ?: "")
                            showToast("已复制")
                            menuOpen = false
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = effectiveExpanded && !reasoning.isNullOrBlank(),
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(220)),
            ) {
                Text(
                    reasoning ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard() {
    val pending by WeAgentService.pendingApproval
    val p = pending ?: return
    var reason by remember(p) { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("请求执行工具「${p.pending.toolName}」", style = MaterialTheme.typography.titleSmall)
            Text(p.pending.argumentsJson, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            p.pending.modelExplanation?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("拒绝理由（可选）") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    WeAgentService.resolveApproval(
                        dev.ujhhgtg.wekit.agent.engine.ManualApprovalResult.Rejected(reason.ifBlank { null })
                    )
                }) { Text("拒绝") }
                TextButton(onClick = {
                    WeAgentService.resolveApproval(dev.ujhhgtg.wekit.agent.engine.ManualApprovalResult.Approved)
                }) { Text("同意") }
            }
        }
    }
}
