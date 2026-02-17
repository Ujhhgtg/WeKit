package moe.ouom.wekit.hooks.item.chat.msg

import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.item.chat.msg.autoreply.AutoReplyEngine
import moe.ouom.wekit.hooks.item.chat.msg.autoreply.AutoReplyRule
import moe.ouom.wekit.hooks.item.chat.msg.autoreply.MatchRule
import moe.ouom.wekit.hooks.sdk.api.WeDatabaseListener
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.ui.creator.dialog.hooks.BaseHooksSettingsDialog
import moe.ouom.wekit.util.log.WeLogger

@HookItem(path = "聊天与消息/自动回复", desc = "按照规则自动回复收到的消息")
class AutoReply : BaseClickableFunctionHookItem(), WeDatabaseListener.DatabaseInsertListener {
    // type=1 文本
    // type=3 图片
    // type=43 视频
    // type=48 位置
    // type=50 视频/语音通话
    // type=419430449 转账
    // type=436207665 红包
    // type=1040187441 音乐
    // type=1090519089 音乐
    private val rules = mutableListOf(
        AutoReplyRule(
            id = 0,
            name = "test",
            matchRule = MatchRule.Exact("ping"),
            replyText = "pong",
            enabled = false
        ),
        AutoReplyRule(
            id = 1,
            name = "test2",
            matchRule = MatchRule.JavaScript("""
                function onMessage(talker, content, type, isSend) {
                    if (talker === 'lovaxi' || content.includes('wxid_gu29l8l2zmvc22')) {
                        return "调试:\ntalker=" + talker + "\ncontent=" + content + "\ntype=" + type + "\nisSend=" + isSend;
                    }
                    return null;
                }
            """.trimIndent()),
            replyText = "pong",
            enabled = false
        )
    )

    override fun entry(classLoader: ClassLoader) {
        WeLogger.i("AutoReply", "entry() called, registering DB listener")
        WeDatabaseListener.addListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val isSend  = values.getAsInteger("isSend")  ?: return
        if (isSend != 0) return // ignore outgoing

        val talker  = values.getAsString("talker")   ?: return
        val content = values.getAsString("content")  ?: return
        val type    = values.getAsInteger("type")    ?: 0

        WeLogger.i("AutoReply", "Message received from $talker (type=$type)")

        AutoReplyEngine.evaluate(rules, talker, content, type, isSend)
    }

    override fun unload(classLoader: ClassLoader) {
        WeLogger.i("AutoReply", "unload() called, removing DB listener")
        WeDatabaseListener.removeListener(this)
        super.unload(classLoader)
    }

    override fun onClick(context: Context?) {
        if (context == null) return
        showComposeDialog(context) { onDismiss ->
            BaseHooksSettingsDialog("自动回复", onDismiss) {
                AutoReplySettingsDialogContent(rules)
            }
        }
    }
}

@Composable
private fun AutoReplySettingsDialogContent(rules: MutableList<AutoReplyRule>) {
    var snapshot by remember { mutableStateOf(rules.toList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refresh() { snapshot = rules.toList() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("规则列表 (${snapshot.size})", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showAddDialog = true }) { Text("+ 添加") }
        }

        Spacer(Modifier.height(8.dp))

        if (snapshot.isEmpty()) {
            Text(
                "暂无规则",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                snapshot.forEach { rule ->
                    RuleCard(
                        rule = rule,
                        onToggle = {
                            val idx = rules.indexOfFirst { it.id == rule.id }
                            if (idx != -1) { rules[idx] = rule.copy(enabled = !rule.enabled) }
                            refresh()
                        },
                        onDelete = {
                            rules.removeAll { it.id == rule.id }
                            refresh()
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AddRuleDialog(
            onConfirm = { newRule ->
                rules.add(newRule)
                refresh()
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun RuleCard(rule: AutoReplyRule, onToggle: () -> Unit, onDelete: () -> Unit) {
    val matchLabel = when (rule.matchRule) {
        is MatchRule.Exact      -> "精确: \"${rule.matchRule.pattern}\""
        is MatchRule.Contains   -> "包含: \"${rule.matchRule.keyword}\""
        is MatchRule.Regex      -> "正则: \"${rule.matchRule.pattern}\""
        is MatchRule.JavaScript -> "JS 脚本"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                Text(matchLabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rule.matchRule !is MatchRule.JavaScript && rule.replyText.isNotBlank()) {
                    Text("回复: \"${rule.replyText}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddRuleDialog(onConfirm: (AutoReplyRule) -> Unit, onDismiss: () -> Unit) {
    val matchTypes = listOf("精确匹配", "包含关键词", "正则表达式", "JavaScript")
    var selectedType by remember { mutableIntStateOf(0) }
    var ruleName     by remember { mutableStateOf("") }
    var pattern      by remember { mutableStateOf("") }
    var replyText    by remember { mutableStateOf("") }

    val isJs = selectedType == 3

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自动回复规则") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("规则名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))

                // Match type selector
                Text("匹配方式", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    matchTypes.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = selectedType == idx,
                            onClick = { selectedType = idx },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (isJs) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text("JS 脚本 (必须定义 onMessage)") },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = {
                            Text(
                                "function onMessage(talker, content, type, isSend) {\n" +
                                        "  if (content === 'ping') return 'pong';\n" +
                                        "  return null;\n" +
                                        "}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                } else {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = {
                            Text(when (selectedType) {
                                0    -> "匹配内容 (精确)"
                                1    -> "关键词 (包含)"
                                else -> "正则表达式"
                            })
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        label = { Text("回复内容") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ruleName.isBlank() || pattern.isBlank()) return@TextButton
                    if (!isJs && replyText.isBlank()) return@TextButton
                    val matchRule = when (selectedType) {
                        0    -> MatchRule.Exact(pattern)
                        1    -> MatchRule.Contains(pattern)
                        2    -> MatchRule.Regex(pattern)
                        else -> MatchRule.JavaScript(pattern)
                    }
                    onConfirm(
                        AutoReplyRule(
                            name = ruleName,
                            matchRule = matchRule,
                            replyText = replyText
                        )
                    )
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}