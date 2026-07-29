package dev.ujhhgtg.wekit.activity.testsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.ui.content.nukex.NukeButton
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCheckbox
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDialogSurface
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDialogTextField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDivider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyph
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyphKind
import dev.ujhhgtg.wekit.ui.content.nukex.NukePreferenceRow
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSelectPreference
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSwitch
import dev.ujhhgtg.wekit.ui.content.nukex.NukeText
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTextField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTheme
import dev.ujhhgtg.wekit.ui.content.nukex.nukeJellyClickable

@Composable
internal fun NukeConfigurationDialog(
    feature: PreviewFeature,
    onDismiss: () -> Unit,
) {
    if (feature.id == "AIChat") {
        NukeAiChatConfigurationDialog(onDismiss = onDismiss)
        return
    }

    val manualClick = "模拟手动点击"
    val networkRequest = "网络请求"
    val blacklist = "黑名单"
    val whitelist = "白名单"
    val defaultReply = "谢谢老板"
    var delayText by remember {
        mutableStateOf(if (feature.id == "ChatAvatarRotator") "1000" else "0")
    }
    var replyAfterReceive by remember { mutableStateOf(true) }
    var replyContent by remember(defaultReply) { mutableStateOf(defaultReply) }
    var receiveMode by remember(manualClick) { mutableStateOf(manualClick) }
    var listMode by remember(blacklist) { mutableStateOf(blacklist) }
    var selectingContacts by remember { mutableStateOf(false) }
    var selectedContacts by remember { mutableStateOf(emptySet<String>()) }

    if (selectingContacts) {
        NukeContactSelectionDialog(
            title = if (listMode == whitelist) {
                "选择红包白名单"
            } else {
                "选择红包黑名单"
            },
            initialSelected = selectedContacts,
            onDismiss = { selectingContacts = false },
            onConfirm = {
                selectedContacts = it
                selectingContacts = false
            },
        )
        return
    }

    NukeDialogSurface(
        title = when (feature.id) {
            "AutoReceiveRedPacket" ->
                "自动领取红包设置"
            "ChatAvatarRotator" ->
                "聊天头像旋转设置"
            else -> feature.title
        },
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
            NukeButton(
                text = "保存",
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = dismiss,
            )
        },
    ) {
        if (feature.id == "AutoReceiveRedPacket") {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                NukeSelectPreference(
                    title = "领取方式",
                    description = "选择打开微信红包界面并模拟点击，或直接发送领取请求",
                    options = listOf(manualClick, networkRequest),
                    selected = receiveMode,
                    optionLabel = { it },
                    onSelected = { receiveMode = it },
                )
                NukeSelectPreference(
                    title = "领取名单模式",
                    description = if (listMode == whitelist) {
                        "只领取所选好友或群聊中的红包"
                    } else {
                        "跳过所选好友或群聊中的红包"
                    },
                    options = listOf(blacklist, whitelist),
                    selected = listMode,
                    optionLabel = { it },
                    onSelected = { listMode = it },
                )
                NukePreferenceRow(
                    title = if (listMode == whitelist) {
                        "配置白名单"
                    } else {
                        "配置黑名单"
                    },
                    description = "已选 ${selectedContacts.size} 个",
                    trailing = {
                        NukeGlyph(
                            kind = NukeGlyphKind.Chevron,
                            color = NukeTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = { selectingContacts = true },
                )
                Spacer(Modifier.height(12.dp))
                NukeDialogTextField(
                    label = "领取延迟（毫秒）",
                    placeholder = "0–60000",
                    value = delayText,
                    onValueChange = { delayText = it.filter(Char::isDigit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        NukeText(
                            text = "领取后自动回复",
                            color = NukeTheme.colors.textPrimary,
                            fontSize = 14,
                            lineHeight = 19,
                            fontWeight = FontWeight.SemiBold,
                        )
                        NukeText(
                            text = "成功领取后向红包来源会话发送消息",
                            color = NukeTheme.colors.textSecondary,
                            fontSize = 12,
                            lineHeight = 17,
                        )
                    }
                    NukeSwitch(
                        checked = replyAfterReceive,
                        onCheckedChange = { replyAfterReceive = it },
                    )
                }
                Spacer(Modifier.height(12.dp))
                NukeDialogTextField(
                    label = "回复内容",
                    placeholder = $$"可使用 $amount 表示红包金额",
                    value = replyContent,
                    onValueChange = { replyContent = it },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    enabled = replyAfterReceive,
                )
            }
        } else {
            NukeDialogTextField(
                label = if (feature.id == "ChatAvatarRotator") {
                    "旋转周期（毫秒）"
                } else {
                    ""
                },
                placeholder = if (feature.id == "ChatAvatarRotator") {
                    "数值越小旋转越快"
                } else {
                    ""
                },
                value = delayText,
                onValueChange = { delayText = it.filter(Char::isDigit) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

private data class PreviewContact(
    val id: String,
    val name: String,
    val kind: String,
)

private val previewContacts = listOf(
    PreviewContact("filehelper", "文件传输助手", "好友"),
    PreviewContact("wekit_demo", "WeKit 测试账号", "好友"),
    PreviewContact("preview@chatroom", "UI 研究群", "群聊"),
)

@Composable
internal fun NukeContactSelectionDialog(
    title: String,
    initialSelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    var confirmedSelection by remember { mutableStateOf<Set<String>?>(null) }
    val contacts = remember(query) {
        if (query.isBlank()) {
            previewContacts
        } else {
            previewContacts.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
            }
        }
    }

    NukeDialogSurface(
        title = title,
        onDismiss = {
            val confirmed = confirmedSelection
            if (confirmed == null) {
                onDismiss()
            } else {
                onConfirm(confirmed)
            }
        },
        actions = { dismiss ->
            NukeButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
            NukeButton(
                text = "确定（${selected.size}）",
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = {
                    confirmedSelection = selected
                    dismiss()
                },
            )
        },
    ) {
        NukeTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "搜索好友、群聊或 wxid",
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            NukeButton(
                text = "全选当前结果",
                modifier = Modifier.weight(1f),
                enabled = contacts.isNotEmpty(),
                onClick = { selected = selected + contacts.map(PreviewContact::id) },
            )
            NukeButton(
                text = "清空",
                modifier = Modifier.weight(1f),
                enabled = selected.isNotEmpty(),
                onClick = { selected = emptySet() },
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(
                count = contacts.size,
                key = { contacts[it].id },
            ) { index ->
                val contact = contacts[index]
                val checked = contact.id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .nukeJellyClickable(
                            onClick = {
                                selected = if (checked) {
                                    selected - contact.id
                                } else {
                                    selected + contact.id
                                }
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(NukeTheme.colors.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        NukeText(
                            text = contact.name.firstOrNull()?.uppercase() ?: "",
                            color = NukeTheme.colors.accent,
                            fontSize = 15,
                            lineHeight = null,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        NukeText(
                            text = contact.name,
                            color = NukeTheme.colors.textPrimary,
                            fontSize = 14,
                            lineHeight = 19,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        NukeText(
                            text = "${contact.kind} · ${contact.id}",
                            color = NukeTheme.colors.textSecondary,
                            fontSize = 11,
                            lineHeight = 16,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    NukeCheckbox(
                        checked = checked,
                        onCheckedChange = {
                            selected = if (it) {
                                selected + contact.id
                            } else {
                                selected - contact.id
                            }
                        },
                    )
                }
                if (index < contacts.lastIndex) {
                    NukeDivider(startPadding = 0.dp, endPadding = 0.dp)
                }
            }
        }
    }
}

@Composable
internal fun NukeHookerDetailDialog(
    hookerName: String,
    hookerId: String,
    normal: Boolean,
    reason: String,
    onDismiss: () -> Unit,
) {
    NukeDialogSurface(
        title = "Hooker 详情",
        onDismiss = onDismiss,
        actions = { dismiss ->
            NukeButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
            NukeButton(
                text = "解锁",
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = !normal,
                onClick = dismiss,
            )
        },
    ) {
        val details = buildString {
            appendLine("Name: $hookerName")
            appendLine("ID: $hookerId")
            appendLine("Status: ${if (normal) "NORMAL" else "LOCKED"}")
            appendLine("Reason: $reason")
            appendLine("Hooker class: $hookerId")
            appendLine("Class location: N/A")
            appendLine(
                "Class loader: LspModuleClassLoader[module=/data/app/~~1vrZu2E7oXIR0IUjd14lcw==" +
                    "/me.dartcv.nuke-TxZsXtY6rnVtbBmtq-9ZJQ==/base.apk, " +
                    "l1[DexPathList[[dex file \"InMemoryDexFile[cookie=[0, 4940029055681]]\"], " +
                    "nativeLibraryDirectories=[/data/app/~~1vrZu2E7oXIR0IUjd14lcw==" +
                    "/me.dartcv.nuke-TxZsXtY6rnVtbBmtq-9ZJQ==/base.apk!/lib/arm64-v8a, " +
                    "/system/lib64, /system_ext/lib64]]]]"
            )
            appendLine("UI location: N/A")
            appendLine("Is target process: true")
            appendLine("Current process: com.tencent.mm")
            appendLine("Default enabled: true")
            appendLine("Requires restart: false")
            appendLine("Available: true")
            appendLine("Ignore security mode: false")
            appendLine("Phase: N/A")
            appendLine("Timestamp: N/A")
            appendLine()
            appendLine("Throwable:")
            appendLine("N/A")
            appendLine()
            appendLine("Message:")
            appendLine("N/A")
            appendLine()
            appendLine("Stack trace:")
            appendLine("N/A")
        }
        NukeText(
            text = details,
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            color = NukeTheme.colors.textSecondary,
            fontSize = 12,
            lineHeight = 17,
        )
    }
}
