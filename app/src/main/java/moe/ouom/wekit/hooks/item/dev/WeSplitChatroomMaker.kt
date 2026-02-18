package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.api.WeDatabaseApi
import moe.ouom.wekit.hooks.sdk.api.model.WeGroup
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.ui.creator.dialog.hooks.BaseHooksSettingsDialog
import moe.ouom.wekit.utils.Initiator.loadClass
import moe.ouom.wekit.utils.common.Toasts
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "开发者选项/分裂群组", desc = "让群聊一分为二")
class WeSplitChatroomMaker : BaseClickableFunctionHookItem() {

    override fun onClick(context: Context?) {
        context ?: return

        val api = WeDatabaseApi.INSTANCE
        if (api == null) {
            Toasts.showToast(context, "数据库 API 未初始化，请先进入微信主界面")
            return
        }

        val groups = try {
            api.getChatroomList()
        } catch (e: Exception) {
            WeLogger.e("WeSchemeInvocation", "获取群聊列表失败", e)
            Toasts.showToast(context, "获取数据失败: ${e.message}")
            return
        }

        if (groups.isEmpty()) {
            Toasts.showToast(context, "未获取到群聊列表，请确认是否已登录或数据是否同步")
            return
        }

        showComposeDialog(context) { onDismiss ->
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
            val activity = RuntimeConfig.getLauncherUIActivity()
            if (activity == null) {
                WeLogger.e("WeSchemeInvocation", "LauncherUI Activity is null")
                return
            }

            val chattingUIClass = loadClass("com.tencent.mm.ui.chatting.ChattingUI")
            val intent = Intent(activity, chattingUIClass)

            val rawId = chatroomId.substringBefore("@")
            val targetSplitId = "${rawId}@@chatroom"

            WeLogger.i("WeSchemeInvocation", "Launching ChattingUI for chatroom: $chatroomId")

            intent.putExtra("Chat_User", targetSplitId)
            intent.putExtra("Chat_Mode", 1)

            activity.startActivity(intent)
        } catch (e: Exception) {
            WeLogger.e("WeSchemeInvocation", "跳转失败", e)
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

    BaseHooksSettingsDialog("分裂群组 - 搜索", onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("输入群名 / 拼音 / ID（留空显示全部）") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQuery(keyword.trim()) }),
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onQuery(keyword.trim()) }) { Text("查询") }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

// ---------------------------------------------------------------------------
//  Step 2 – filtered results list
// ---------------------------------------------------------------------------

@Composable
private fun ResultsStep(
    filtered: List<WeGroup>,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSelect: (chatroomId: String) -> Unit,
) {
    BaseHooksSettingsDialog("选择目标群聊（${filtered.size}）", onDismiss) {
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "未找到匹配的群聊",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            filtered.forEach { group ->
                val name = group.nickname.ifBlank { "未命名群聊" }
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

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("返回搜索") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    }
}