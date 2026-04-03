package dev.ujhhgtg.wekit.hooks.items.contacts

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Search
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.model.WeGroup
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.showToast

@HookItem(path = "联系人与群组/分裂群组", desc = "让群聊一分为二")
object SplitChatroom : ClickableHookItem() {

    private val TAG = nameOf(SplitChatroom)

    override fun onClick(context: Context) {
        val groups = try {
            WeDatabaseApi.getGroups()
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取群聊列表失败", e)
            showToast(context, "获取数据失败: ${e.message}")
            return
        }

        if (groups.isEmpty()) {
            showToast(context, "未获取到群聊列表, 请确认是否已登录或数据是否同步")
            return
        }

        showComposeDialog(context) {
            SplitChatroomDialog(
                allGroups = groups,
                onDismiss = onDismiss,
                onSelect = { chatroomId ->
                    onDismiss()
                    jumpToSplitChatroom(chatroomId)
                },
            )
        }
    }

    private fun jumpToSplitChatroom(chatroomId: String) {
        runCatching {
            val activity = LauncherUI.getInstance()!!
            val chattingUIClass = ChattingUI::class.java
            val intent = Intent(activity, chattingUIClass)

            val rawId = chatroomId.substringBefore("@")
            val targetSplitId = "${rawId}@@chatroom"

            WeLogger.i(TAG, "launching ChattingUI for chatroom: $chatroomId")

            intent.putExtra("Chat_User", targetSplitId)
            intent.putExtra("Chat_Mode", 1)

            activity.startActivity(intent)
        }.onFailure { WeLogger.e(TAG, "exception occured", it) }
    }

    override val noSwitchWidget: Boolean
        get() = true
}

// ---------------------------------------------------------------------------
//  Internal step state
// ---------------------------------------------------------------------------

private sealed interface DialogPhase {
    data object Search : DialogPhase
    data class Results(val filtered: List<WeGroup>) : DialogPhase
}

// ---------------------------------------------------------------------------
//  Top-level dialog orchestrator
// ---------------------------------------------------------------------------

@Composable
private fun SplitChatroomDialog(
    allGroups: List<WeGroup>,
    onDismiss: () -> Unit,
    onSelect: (chatroomId: String) -> Unit,
) {
    var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Search) }

    when (val s = phase) {
        is DialogPhase.Search -> SearchStep(
            onDismiss = onDismiss,
            onQuery = { keyword ->
                val filtered = if (keyword.isEmpty()) allGroups else allGroups.filter { g ->
                    g.nickname.contains(keyword, ignoreCase = true) ||
                            g.nicknameShortPinyin.contains(keyword, ignoreCase = true) ||
                            g.nicknamePinyin.contains(keyword, ignoreCase = true) ||
                            g.wxId.contains(keyword, ignoreCase = true)
                }
                phase = DialogPhase.Results(filtered)
            },
        )

        is DialogPhase.Results -> ResultsStep(
            filtered = s.filtered,
            onDismiss = onDismiss,
            onBack = { phase = DialogPhase.Search },
            onSelect = onSelect,
        )
    }
}

// ---------------------------------------------------------------------------
//  Step 1 – search input
// ---------------------------------------------------------------------------

@Composable
private fun SearchStep(
    onDismiss: () -> Unit,
    onQuery: (keyword: String) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialogContent(
        title = { Text("搜索群组") },
        text = {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("输入群名 / 拼音 / ID (留空显示全部)") },
                    leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onQuery(keyword.trim()) }),
                )
            }
        },
        confirmButton = { Button(onClick = { onQuery(keyword.trim()) }) { Text("查询") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ---------------------------------------------------------------------------
//  Step 2 – filter results list
// ---------------------------------------------------------------------------

@Composable
private fun ResultsStep(
    filtered: List<WeGroup>,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelect: (chatroomId: String) -> Unit,
) {
    AlertDialogContent(
        title = { Text("选择目标群组 (共 ${filtered.size} 个)") },
        text = {
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "未找到匹配的群组",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(filtered) { group ->
                        val name = group.nickname.ifBlank { "未命名群组" }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(group.wxId) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(text = name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = group.wxId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("返回搜索") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
