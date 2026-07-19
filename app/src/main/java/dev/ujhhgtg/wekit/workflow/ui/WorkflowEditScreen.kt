package dev.ujhhgtg.wekit.workflow.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.ui.agent.settings.AGENT_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.ui.agent.settings.AgentSettingsScaffold
import dev.ujhhgtg.wekit.ui.content.MiuixSmallTitle
import dev.ujhhgtg.wekit.workflow.data.WorkflowRepository
import dev.ujhhgtg.wekit.workflow.model.Workflow
import dev.ujhhgtg.wekit.workflow.model.WorkflowNode
import dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import java.util.UUID

@Composable
fun WorkflowEditScreen(
    workflowId: String?,  // null = new workflow
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var workflow by remember { mutableStateOf<Workflow?>(null) }
    LaunchedEffect(workflowId) {
        workflow = if (workflowId != null) WorkflowRepository.getById(workflowId) else null
    }

    var name by remember(workflow) { mutableStateOf(workflow?.name ?: "") }
    var description by remember(workflow) { mutableStateOf(workflow?.description ?: "") }
    var trigger by remember(workflow) { mutableStateOf(workflow?.trigger) }
    var nodes by remember(workflow) { mutableStateOf(workflow?.nodes ?: emptyList()) }

    val availableVars = remember(nodes) { collectVars(nodes) }
    val triggerVars = remember(trigger) { triggerFieldNames(trigger) }

    fun save() {
        val id = workflowId ?: UUID.randomUUID().toString()
        val updated = Workflow(
            id = id,
            name = name.ifBlank { "无标题工作流" },
            description = description,
            trigger = trigger,
            nodes = nodes,
            enabled = workflow?.enabled ?: true,
        )
        scope.launch { WorkflowRepository.upsert(updated); onBack() }
    }

    AgentSettingsScaffold(
        title = if (workflowId == null) "新建工作流" else "编辑工作流",
        onBack = { onBack() },
    ) {
        // Basic info section
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "描述（可选）",
                    useLabelAsPlaceholder = true,
                    maxLines = 2,
                )
            }
        }

        // Trigger section
        item {
            MiuixSmallTitle("触发器")
            Card(Modifier.padding(bottom = 6.dp)) {
                TriggerSection(trigger = trigger, onTriggerChange = { trigger = it })
            }
        }

        // Available trigger variables hint
        if (trigger != null) {
            item { TriggerContextHint(trigger!!) }
        }

        // Nodes section
        item { MiuixSmallTitle("操作序列") }

        // Render root-level nodes
        itemsIndexed(nodes) { i, node ->
            NodeCard(
                node = node,
                depth = 0,
                availableVars = availableVars,
                triggerVars = triggerVars,
                onReplace = { newNode -> nodes = nodes.toMutableList().also { it[i] = newNode } },
                onDelete = { nodes = nodes.toMutableList().also { it.removeAt(i) } },
                onMoveUp = if (i > 0) ({
                    nodes = nodes.toMutableList().also { list ->
                        val tmp = list[i]; list[i] = list[i - 1]; list[i - 1] = tmp
                    }
                }) else null,
                onMoveDown = if (i < nodes.lastIndex) ({
                    nodes = nodes.toMutableList().also { list ->
                        val tmp = list[i]; list[i] = list[i + 1]; list[i + 1] = tmp
                    }
                }) else null,
            )
        }

        // Add node + Save buttons
        item {
            var showAddNode by remember { mutableStateOf(false) }
            Column(Modifier.padding(top = 8.dp, bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                Button(
                    onClick = { showAddNode = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) { Text("+ 添加操作") }
                Button(
                    onClick = { save() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存工作流") }
            }
            AddNodeSheet(
                show = showAddNode,
                onDismiss = { showAddNode = false },
            ) { newNode ->
                nodes = nodes + newNode
                showAddNode = false
            }
        }
    }
}

private fun collectVars(nodeList: List<WorkflowNode>): List<String> {
    val vars = mutableListOf<String>()
    for (node in nodeList) {
        when (node) {
            is WorkflowNode.SetVariable -> vars += node.name
            is WorkflowNode.Action -> node.outputVar?.let { vars += it }
            is WorkflowNode.If -> {
                vars += collectVars(node.thenNodes)
                vars += collectVars(node.elseNodes)
            }
            is WorkflowNode.RepeatWithEach -> {
                vars += node.itemVar
                vars += collectVars(node.body)
            }
            is WorkflowNode.Repeat -> vars += collectVars(node.body)
            is WorkflowNode.Stop,
            is WorkflowNode.Comment -> Unit
        }
    }
    return vars.distinct()
}

private fun triggerFieldNames(t: WorkflowTrigger?): List<String> = when (t) {
    is WorkflowTrigger.NewMessage   -> listOf("sender", "talker", "content", "msgType", "msgSvrId", "isGroup")
    is WorkflowTrigger.NewMoment    -> listOf("sender", "content", "snsId", "type")
    is WorkflowTrigger.NewTransfer  -> listOf("sender", "amount", "currency", "note", "msgSvrId")
    is WorkflowTrigger.NewRedPacket -> listOf("sender", "groupId", "senderName", "msgSvrId")
    is WorkflowTrigger.Schedule     -> listOf("firedAt")
    null                            -> emptyList()
}
