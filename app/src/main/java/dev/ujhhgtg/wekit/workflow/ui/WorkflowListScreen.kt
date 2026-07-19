package dev.ujhhgtg.wekit.workflow.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import dev.ujhhgtg.wekit.ui.agent.settings.AgentSettingsScaffold
import dev.ujhhgtg.wekit.ui.agent.settings.EmptyHint
import dev.ujhhgtg.wekit.ui.agent.settings.AGENT_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.workflow.data.WorkflowRepository
import dev.ujhhgtg.wekit.workflow.model.Workflow
import dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger

@Composable
fun WorkflowListScreen(
    onOpenWorkflow: (workflowId: String) -> Unit,
    onNewWorkflow: () -> Unit,
    onBack: () -> Unit,
) {
    val workflows by WorkflowRepository.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    AgentSettingsScaffold(title = "工作流", onBack = onBack) {
        item {
            Button(
                onClick = onNewWorkflow,
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Text("新建工作流")
            }
        }

        if (workflows.isEmpty()) {
            item {
                EmptyHint("还没有工作流。点击「新建工作流」开始。")
            }
        }

        items(workflows.size, key = { workflows[it].id }) { i ->
            val wf = workflows[i]
            var enabled by remember(wf.enabled) { mutableStateOf(wf.enabled) }
            Card(modifier = androidx.compose.ui.Modifier.padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = wf.name.ifBlank { "无标题工作流" },
                    summary = triggerSummary(wf) + if (wf.description.isNotBlank()) " · " + wf.description else "",
                    checked = enabled,
                    onCheckedChange = { v ->
                        enabled = v
                        scope.launch { WorkflowRepository.setEnabled(wf.id, v) }
                    },
                )
                ArrowPreference(title = "编辑", onClick = { onOpenWorkflow(wf.id) })
            }
        }

        item {
            Spacer(modifier = androidx.compose.ui.Modifier.height(AGENT_CONTENT_BOTTOM_INSET))
        }
    }
}

private fun triggerSummary(wf: Workflow): String = when (wf.trigger) {
    is WorkflowTrigger.NewMessage -> "收到消息"
    is WorkflowTrigger.NewMoment -> "新朋友圈"
    is WorkflowTrigger.NewTransfer -> "收到转账"
    is WorkflowTrigger.NewRedPacket -> "收到红包"
    is WorkflowTrigger.Schedule -> "定时触发"
    null -> "手动触发"
}
