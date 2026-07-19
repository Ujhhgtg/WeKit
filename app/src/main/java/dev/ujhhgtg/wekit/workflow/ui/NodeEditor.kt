package dev.ujhhgtg.wekit.workflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import dev.ujhhgtg.wekit.workflow.engine.OperationRegistry
import dev.ujhhgtg.wekit.workflow.model.WorkflowCondition
import dev.ujhhgtg.wekit.workflow.model.WorkflowNode
import dev.ujhhgtg.wekit.workflow.model.WorkflowValue
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun valueLabel(v: WorkflowValue): String = when (v) {
    is WorkflowValue.Literal -> "\"${v.raw}\""
    is WorkflowValue.Variable -> "{{${v.name}}}"
    is WorkflowValue.TriggerInput -> "触发器.${v.field}"
    is WorkflowValue.Template -> v.tpl
}

private fun conditionSummary(c: WorkflowCondition): String = when (c) {
    is WorkflowCondition.Equals -> "${valueLabel(c.left)} == ${valueLabel(c.right)}"
    is WorkflowCondition.NotEquals -> "${valueLabel(c.left)} != ${valueLabel(c.right)}"
    is WorkflowCondition.Contains -> "${valueLabel(c.value)} 包含 ${valueLabel(c.sub)}"
    is WorkflowCondition.StartsWith -> "${valueLabel(c.value)} 开头为 ${valueLabel(c.prefix)}"
    is WorkflowCondition.Matches -> "${valueLabel(c.value)} 匹配 /${c.regex}/"
    is WorkflowCondition.IsEmpty -> "${valueLabel(c.value)} 为空"
    is WorkflowCondition.IsNotEmpty -> "${valueLabel(c.value)} 非空"
    is WorkflowCondition.And -> c.items.joinToString(" AND ") { conditionSummary(it) }
    is WorkflowCondition.Or -> c.items.joinToString(" OR ") { conditionSummary(it) }
    is WorkflowCondition.Not -> "非(${conditionSummary(c.inner)})"
}

private fun <T> List<T>.replaceAt(index: Int, item: T): List<T> =
    toMutableList().also { it[index] = item }

private fun newId(): String = UUID.randomUUID().toString()

// ─────────────────────────────────────────────────────────────────────────────
// NodeCard — public entry point
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NodeCard(
    node: WorkflowNode,
    depth: Int,
    availableVars: List<String>,
    triggerVars: List<String>,
    onReplace: (WorkflowNode) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val indent = Modifier.padding(start = (depth * 16).dp)
    when (node) {
        is WorkflowNode.Action -> ActionCard(node, indent, availableVars, triggerVars, onReplace, onDelete, onMoveUp, onMoveDown)
        is WorkflowNode.If -> IfCard(node, depth, indent, availableVars, triggerVars, onReplace, onDelete, onMoveUp, onMoveDown)
        is WorkflowNode.RepeatWithEach -> RepeatWithEachCard(node, depth, indent, availableVars, triggerVars, onReplace, onDelete, onMoveUp, onMoveDown)
        is WorkflowNode.Repeat -> RepeatCard(node, depth, indent, availableVars, triggerVars, onReplace, onDelete, onMoveUp, onMoveDown)
        is WorkflowNode.SetVariable -> SetVariableCard(node, indent, availableVars, triggerVars, onReplace, onDelete, onMoveUp, onMoveDown)
        is WorkflowNode.Stop -> StopCard(node, indent, onDelete, onMoveUp, onMoveDown)
        is WorkflowNode.Comment -> CommentCard(node, indent, onDelete, onMoveUp, onMoveDown)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared node header row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NodeHeader(
    label: String,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.weight(1f),
        )
        extra?.invoke()
        if (onMoveUp != null) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(MaterialSymbols.Outlined.Arrow_upward, contentDescription = "上移", modifier = Modifier.size(18.dp))
            }
        }
        if (onMoveDown != null) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(MaterialSymbols.Outlined.Arrow_downward, contentDescription = "下移", modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(MaterialSymbols.Outlined.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ActionCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionCard(
    node: WorkflowNode.Action,
    indent: Modifier,
    availableVars: List<String>,
    triggerVars: List<String>,
    onReplace: (WorkflowNode) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var showEditor by remember { mutableStateOf(false) }
    Card(modifier = indent.padding(bottom = 6.dp)) {
        NodeHeader(
            label = "操作",
            onDelete = onDelete,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            extra = {
                IconButton(onClick = { showEditor = true }, modifier = Modifier.size(32.dp)) {
                    Icon(MaterialSymbols.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
            },
        )
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(text = node.operationName, style = MiuixTheme.textStyles.main)
            node.inputs.forEach { (k, v) ->
                Text(
                    text = "$k: ${valueLabel(v)}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
            if (!node.outputVar.isNullOrBlank()) {
                Text(
                    text = "→ ${node.outputVar}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    ActionEditorDialog(
        show = showEditor,
        node = node,
        availableVars = availableVars,
        triggerVars = triggerVars,
        onDismiss = { showEditor = false },
        onConfirm = { updated -> onReplace(updated); showEditor = false },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SubNodeList — shared recursive child-list renderer
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SubNodeList(
    title: String,
    nodes: List<WorkflowNode>,
    depth: Int,
    availableVars: List<String>,
    triggerVars: List<String>,
    onChange: (List<WorkflowNode>) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16 + 8).dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { showAdd = true }, modifier = Modifier.size(28.dp)) {
            Icon(MaterialSymbols.Outlined.Add, contentDescription = "添加节点", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
            Icon(
                if (expanded) MaterialSymbols.Outlined.Expand_less else MaterialSymbols.Outlined.Expand_more,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier.size(16.dp),
            )
        }
    }

    AnimatedVisibility(visible = expanded) {
        Column {
            if (nodes.isEmpty()) {
                Text(
                    text = "（空）",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(start = (depth * 16 + 16).dp, bottom = 4.dp),
                )
            }
            nodes.forEachIndexed { i, child ->
                NodeCard(
                    node = child,
                    depth = depth + 1,
                    availableVars = availableVars,
                    triggerVars = triggerVars,
                    onReplace = { onChange(nodes.replaceAt(i, it)) },
                    onDelete = { onChange(nodes.toMutableList().also { l -> l.removeAt(i) }) },
                    onMoveUp = if (i > 0) ({ onChange(nodes.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }) else null,
                    onMoveDown = if (i < nodes.lastIndex) ({ onChange(nodes.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }) else null,
                )
            }
        }
    }

    AddNodeSheet(show = showAdd, onDismiss = { showAdd = false }, onAddNode = { onChange(nodes + it) })
}

// ─────────────────────────────────────────────────────────────────────────────
// IfCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IfCard(
    node: WorkflowNode.If,
    depth: Int,
    indent: Modifier,
    availableVars: List<String>,
    triggerVars: List<String>,
    onReplace: (WorkflowNode) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Card(modifier = indent.padding(bottom = 6.dp)) {
        NodeHeader(label = "如果 / 否则", onDelete = onDelete, onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        Text(
            text = conditionSummary(node.condition),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        SubNodeList(
            title = "如果为真",
            nodes = node.thenNodes,
            depth = depth,
            availableVars = availableVars,
            triggerVars = triggerVars,
            onChange = { onReplace(node.copy(thenNodes = it)) },
        )
        SubNodeList(
            title = "否则",
            nodes = node.elseNodes,
            depth = depth,
            availableVars = availableVars,
            triggerVars = triggerVars,
            onChange = { onReplace(node.copy(elseNodes = it)) },
        )
        Spacer(Modifier.height(6.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RepeatWithEachCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RepeatWithEachCard(
    node: WorkflowNode.RepeatWithEach,
    depth: Int,
    indent: Modifier,
    availableVars: List<String>,
    triggerVars: List<String>,
    onReplace: (WorkflowNode) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Card(modifier = indent.padding(bottom = 6.dp)) {
        NodeHeader(label = "对每个...重复", onDelete = onDelete, onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Text(
                text = "集合: ${valueLabel(node.collection)}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            Text(
                text = "元素变量: ${node.itemVar}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
        SubNodeList(
            title = "循环体",
            nodes = node.body,
            depth = depth,
            availableVars = availableVars + node.itemVar,
            triggerVars = triggerVars,
            onChange = { onReplace(node.copy(body = it)) },
        )
        Spacer(Modifier.height(6.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RepeatCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RepeatCard(
    node: WorkflowNode.Repeat,
    depth: Int,
    indent: Modifier,
    availableVars: List<String>,
    triggerVars: List<String>,
    onReplace: (WorkflowNode) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Card(modifier = indent.padding(bottom = 6.dp)) {
        NodeHeader(label = "重复N次", onDelete = onDelete, onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        Text(
            text = "次数: ${valueLabel(node.count)}",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        SubNodeList(
            title = "循环体",
            nodes = node.body,
            depth = depth,
            availableVars = availableVars + "_index",
            triggerVars = triggerVars,
            onChange = { onReplace(node.copy(body = it)) },
        )
        Spacer(Modifier.height(6.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SetVariableCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SetVariableCard(
    node: WorkflowNode.SetVariable,
    indent: Modifier,
    @Suppress("UNUSED_PARAMETER") availableVars: List<String>,
    @Suppress("UNUSED_PARAMETER") triggerVars: List<String>,
    @Suppress("UNUSED_PARAMETER") onReplace: (WorkflowNode) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Card(modifier = indent.padding(bottom = 6.dp)) {
        NodeHeader(label = "设置变量", onDelete = onDelete, onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        Text(
            text = "${node.name} = ${valueLabel(node.value)}",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// StopCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StopCard(
    node: WorkflowNode.Stop,
    indent: Modifier,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Card(modifier = indent.padding(bottom = 6.dp)) {
        NodeHeader(label = "停止", onDelete = onDelete, onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        if (node.reason.isNotBlank()) {
            Text(
                text = node.reason,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CommentCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CommentCard(
    node: WorkflowNode.Comment,
    indent: Modifier,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Card(modifier = indent.padding(bottom = 6.dp)) {
        NodeHeader(label = "注释", onDelete = onDelete, onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        Text(
            text = node.text,
            style = MiuixTheme.textStyles.body2.copy(fontStyle = FontStyle.Italic),
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ActionEditorDialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ActionEditorDialog(
    show: Boolean,
    node: WorkflowNode.Action?,
    availableVars: List<String>,
    triggerVars: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (WorkflowNode.Action) -> Unit,
) {
    val allOps = remember { OperationRegistry.all }
    val opNames = remember(allOps) { allOps.map { it.name } }
    val opLabels = remember(allOps) { allOps.map { "${it.group} / ${it.name}" } }

    var selectedIndex by remember(node, show) {
        mutableStateOf(if (node != null) opNames.indexOf(node.operationName).coerceAtLeast(0) else 0)
    }
    val selectedOp = allOps.getOrNull(selectedIndex)

    var inputs by remember(node, show) { mutableStateOf(node?.inputs ?: emptyMap()) }
    var outputVar by remember(node, show) { mutableStateOf(node?.outputVar.orEmpty()) }

    WindowDialog(show = show, title = if (node == null) "添加操作" else "编辑操作", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (opLabels.isNotEmpty()) {
                WindowDropdownPreference(
                    title = "操作",
                    items = opLabels,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { idx -> selectedIndex = idx; inputs = emptyMap() },
                )
            }
            Spacer(Modifier.height(8.dp))
            selectedOp?.params?.forEach { param ->
                val currentValue = inputs[param.name] ?: WorkflowValue.Literal("")
                Text(
                    text = "${param.name}${if (param.required) " *" else ""}",
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                if (param.description.isNotBlank()) {
                    Text(
                        text = param.description,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                ValueEditor(
                    value = currentValue,
                    availableVars = availableVars,
                    triggerVars = triggerVars,
                    onChange = { inputs = inputs + (param.name to it) },
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = outputVar,
                onValueChange = { outputVar = it },
                label = "结果存入变量（可留空）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "确定",
                    onClick = {
                        val action = WorkflowNode.Action(
                            id = node?.id ?: newId(),
                            operationName = selectedOp?.name ?: "",
                            inputs = inputs,
                            outputVar = outputVar.trim().ifBlank { null },
                        )
                        onConfirm(action)
                    },
                    enabled = selectedOp != null,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AddNodeSheet
// ─────────────────────────────────────────────────────────────────────────────

private data class NodeTemplate(val label: String, val factory: () -> WorkflowNode)

private val NODE_TEMPLATES = listOf(
    NodeTemplate("操作") {
        WorkflowNode.Action(id = newId(), operationName = "", inputs = emptyMap())
    },
    NodeTemplate("如果 / 否则") {
        WorkflowNode.If(id = newId(), condition = WorkflowCondition.IsNotEmpty(WorkflowValue.Literal("")))
    },
    NodeTemplate("对每个...重复") {
        WorkflowNode.RepeatWithEach(id = newId(), collection = WorkflowValue.Literal(""), itemVar = "item")
    },
    NodeTemplate("重复N次") {
        WorkflowNode.Repeat(id = newId(), count = WorkflowValue.Literal("3"))
    },
    NodeTemplate("设置变量") {
        WorkflowNode.SetVariable(id = newId(), name = "myVar", value = WorkflowValue.Literal(""))
    },
    NodeTemplate("停止") { WorkflowNode.Stop(id = newId()) },
    NodeTemplate("注释") { WorkflowNode.Comment(id = newId(), text = "") },
)

@Composable
fun AddNodeSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onAddNode: (WorkflowNode) -> Unit,
) {
    WindowDialog(show = show, title = "添加节点", onDismissRequest = onDismiss) {
        Column {
            NODE_TEMPLATES.forEach { template ->
                TextButton(
                    text = template.label,
                    onClick = { onAddNode(template.factory()); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ValueEditor
// ─────────────────────────────────────────────────────────────────────────────

private val VALUE_TYPE_LABELS = listOf("文字", "变量", "触发器输入", "模板")

@Composable
fun ValueEditor(
    value: WorkflowValue,
    availableVars: List<String>,
    triggerVars: List<String>,
    onChange: (WorkflowValue) -> Unit,
) {
    val selectedTypeIndex = when (value) {
        is WorkflowValue.Literal -> 0
        is WorkflowValue.Variable -> 1
        is WorkflowValue.TriggerInput -> 2
        is WorkflowValue.Template -> 3
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        WindowDropdownPreference(
            title = "类型",
            items = VALUE_TYPE_LABELS,
            selectedIndex = selectedTypeIndex,
            onSelectedIndexChange = { idx ->
                onChange(
                    when (idx) {
                        0 -> WorkflowValue.Literal(if (value is WorkflowValue.Literal) value.raw else "")
                        1 -> WorkflowValue.Variable(availableVars.firstOrNull() ?: "")
                        2 -> WorkflowValue.TriggerInput(triggerVars.firstOrNull() ?: "")
                        else -> WorkflowValue.Template(if (value is WorkflowValue.Template) value.tpl else "")
                    }
                )
            },
        )
        Spacer(Modifier.height(4.dp))
        when (value) {
            is WorkflowValue.Literal -> {
                TextField(
                    value = value.raw,
                    onValueChange = { onChange(WorkflowValue.Literal(it)) },
                    label = "字面值",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is WorkflowValue.Variable -> {
                if (availableVars.isNotEmpty()) {
                    WindowDropdownPreference(
                        title = "变量",
                        items = availableVars,
                        selectedIndex = availableVars.indexOf(value.name).coerceAtLeast(0),
                        onSelectedIndexChange = { onChange(WorkflowValue.Variable(availableVars[it])) },
                    )
                } else {
                    TextField(
                        value = value.name,
                        onValueChange = { onChange(WorkflowValue.Variable(it)) },
                        label = "变量名",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            is WorkflowValue.TriggerInput -> {
                if (triggerVars.isNotEmpty()) {
                    WindowDropdownPreference(
                        title = "触发器字段",
                        items = triggerVars,
                        selectedIndex = triggerVars.indexOf(value.field).coerceAtLeast(0),
                        onSelectedIndexChange = { onChange(WorkflowValue.TriggerInput(triggerVars[it])) },
                    )
                } else {
                    TextField(
                        value = value.field,
                        onValueChange = { onChange(WorkflowValue.TriggerInput(it)) },
                        label = "触发器字段",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            is WorkflowValue.Template -> {
                TextField(
                    value = value.tpl,
                    onValueChange = { onChange(WorkflowValue.Template(it)) },
                    label = "模板（用 {{变量名}} 或 {{trigger.字段}} 插值）",
                    useLabelAsPlaceholder = true,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}