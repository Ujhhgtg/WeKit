package moe.ouom.wekit.hooks.items.contacts

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
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
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.utils.RuntimeConfig
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.WeDatabaseApi
import moe.ouom.wekit.hooks.api.core.model.WeGroup
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.logging.WeLogger

@HookItem(path = "联系人与群组/分裂群组", desc = "让群聊一分为二")
object SplitChatroom : ClickableHookItem() {

    private val TAG = nameof(SplitChatroom)

    override fun onClick(context: Context) {
        val groups = try {
            WeDatabaseApi.getGroups()
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取群聊列表失败", e)
            ToastUtils.showToast(context, "获取数据失败: ${e.message}")
            return
        }

        if (groups.isEmpty()) {
            ToastUtils.showToast(context, "未获取到群聊列表, 请确认是否已登录或数据是否同步")
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
        try {
            val activity = RuntimeConfig.getLauncherUiActivity()
            if (activity == null) {
                WeLogger.e(TAG, "LauncherUI Activity is null")
                return
            }

            val chattingUIClass = "com.tencent.mm.ui.chatting.ChattingUI".toClass()
            val intent = Intent(activity, chattingUIClass)

            val rawId = chatroomId.substringBefore("@")
            val targetSplitId = "${rawId}@@chatroom"

            WeLogger.i(TAG, "Launching ChattingUI for chatroom: $chatroomId")

            intent.putExtra("Chat_User", targetSplitId)
            intent.putExtra("Chat_Mode", 1)

            activity.startActivity(intent)
        } catch (e: Exception) {
            WeLogger.e(TAG, "跳转失败", e)
        }
    }

    override fun noSwitchWidget(): Boolean = true
}

// ---------------------------------------------------------------------------
//  Internal step state
// ---------------------------------------------------------------------------

private sealed interface Step {
    data object Search : Step
    data class Results(val filtered: List<WeGroup>) : Step
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
    var step by remember { mutableStateOf<Step>(Step.Search) }

    when (val s = step) {
        is Step.Search -> SearchStep(
            onDismiss = onDismiss,
            onQuery = { keyword ->
                val filtered = if (keyword.isEmpty()) allGroups else allGroups.filter { g ->
                    g.nickname.contains(keyword, ignoreCase = true) ||
                            g.pyInitial.contains(keyword, ignoreCase = true) ||
                            g.quanPin.contains(keyword, ignoreCase = true) ||
                            g.username.contains(keyword, ignoreCase = true)
                }
                step = Step.Results(filtered)
            },
        )

        is Step.Results -> ResultsStep(
            filtered = s.filtered,
            onDismiss = onDismiss,
            onBack = { step = Step.Search },
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
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
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
        title = { Text("选择目标群组 (共匹配到 ${filtered.size} 个)") },
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
                filtered.forEach { group ->
                    val name = group.nickname.ifBlank { "未命名群组" }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(group.username) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(text = name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = group.username,
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
        },
        dismissButton = { TextButton(onClick = onBack) { Text("返回搜索") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}