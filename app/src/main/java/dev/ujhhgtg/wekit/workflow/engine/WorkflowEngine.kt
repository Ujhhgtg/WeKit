package dev.ujhhgtg.wekit.workflow.engine

import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.workflow.model.WorkflowCondition
import dev.ujhhgtg.wekit.workflow.model.WorkflowNode
import dev.ujhhgtg.wekit.workflow.model.WorkflowValue
import kotlinx.coroutines.coroutineScope
import dev.ujhhgtg.wekit.workflow.model.Workflow

private const val TAG = "WorkflowEngine"

/**
 * Interprets a [Workflow]'s node tree and executes it against a [TriggerContext].
 *
 * The engine is stateless — instantiate once and reuse across executions.
 * Each call to [execute] creates an isolated [ExecutionContext] so concurrent
 * workflows do not share mutable state.
 *
 * ## Execution semantics
 * - Nodes run sequentially within a sequence.
 * - [WorkflowNode.Stop] terminates the current sequence (and all parent sequences).
 * - Uncaught exceptions in [WorkflowNode.Action] are logged and treated as non-fatal;
 *   the engine continues with the next node. This mirrors how Apple Shortcuts handles
 *   action errors by default.
 * - The engine is safe to call from any coroutine dispatcher.
 */
object WorkflowEngine {

    sealed interface ExecutionResult {
        data object Completed : ExecutionResult
        data class Stopped(val reason: String) : ExecutionResult
        data class Error(val message: String, val cause: Throwable?) : ExecutionResult
    }

    /**
     * Execute [workflow] with the given [ctx].
     *
     * Suspends until all nodes have been processed or a [WorkflowNode.Stop] is reached.
     */
    suspend fun execute(workflow: Workflow, ctx: TriggerContext): ExecutionResult =
        coroutineScope {
            val vars = mutableMapOf<String, String>()
            runNodes(workflow.nodes, ctx, vars)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runNodes(
        nodes: List<WorkflowNode>,
        ctx: TriggerContext,
        vars: MutableMap<String, String>,
    ): ExecutionResult {
        for (node in nodes) {
            val result = runNode(node, ctx, vars)
            if (result !is ExecutionResult.Completed) return result
        }
        return ExecutionResult.Completed
    }

    private suspend fun runNode(
        node: WorkflowNode,
        ctx: TriggerContext,
        vars: MutableMap<String, String>,
    ): ExecutionResult = when (node) {
        is WorkflowNode.Action -> runAction(node, ctx, vars)
        is WorkflowNode.If -> runIf(node, ctx, vars)
        is WorkflowNode.RepeatWithEach -> runRepeatWithEach(node, ctx, vars)
        is WorkflowNode.Repeat -> runRepeat(node, ctx, vars)
        is WorkflowNode.SetVariable -> {
            vars[node.name] = resolve(node.value, ctx, vars)
            ExecutionResult.Completed
        }
        is WorkflowNode.Stop -> ExecutionResult.Stopped(node.reason)
        is WorkflowNode.Comment -> ExecutionResult.Completed   // no-op
    }

    private suspend fun runAction(
        node: WorkflowNode.Action,
        ctx: TriggerContext,
        vars: MutableMap<String, String>,
    ): ExecutionResult {
        val resolvedInputs = node.inputs.mapValues { (_, v) -> resolve(v, ctx, vars) }
        return try {
            val result = OperationRegistry.invoke(node.operationName, resolvedInputs)
            if (node.outputVar != null) vars[node.outputVar] = result
            WeLogger.d(TAG, "action '${node.operationName}' → ${result.take(120)}")
            ExecutionResult.Completed
        } catch (e: OperationRegistry.OperationNotFoundException) {
            WeLogger.w(TAG, "unknown operation '${node.operationName}': ${e.message}")
            ExecutionResult.Error(e.message ?: "Unknown operation", e)
        } catch (e: Exception) {
            WeLogger.w(TAG, "action '${node.operationName}' threw: ${e.message}")
            // Non-fatal: continue execution (mirrors Shortcuts default behaviour)
            ExecutionResult.Completed
        }
    }

    private suspend fun runIf(
        node: WorkflowNode.If,
        ctx: TriggerContext,
        vars: MutableMap<String, String>,
    ): ExecutionResult {
        val branch = if (evalCondition(node.condition, ctx, vars)) node.thenNodes else node.elseNodes
        return runNodes(branch, ctx, vars)
    }

    private suspend fun runRepeatWithEach(
        node: WorkflowNode.RepeatWithEach,
        ctx: TriggerContext,
        vars: MutableMap<String, String>,
    ): ExecutionResult {
        val raw = resolve(node.collection, ctx, vars)
        val items = raw.split("\n").filter { it.isNotEmpty() }
        for (item in items) {
            vars[node.itemVar] = item
            val result = runNodes(node.body, ctx, vars)
            if (result !is ExecutionResult.Completed) return result
        }
        return ExecutionResult.Completed
    }

    private suspend fun runRepeat(
        node: WorkflowNode.Repeat,
        ctx: TriggerContext,
        vars: MutableMap<String, String>,
    ): ExecutionResult {
        val count = resolve(node.count, ctx, vars).trim().toIntOrNull() ?: 0
        repeat(count) { index ->
            vars["_index"] = index.toString()
        }
        for (i in 0 until count) {
            vars["_index"] = i.toString()
            val result = runNodes(node.body, ctx, vars)
            if (result !is ExecutionResult.Completed) return result
        }
        return ExecutionResult.Completed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value resolution
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolve(
        value: WorkflowValue,
        ctx: TriggerContext,
        vars: Map<String, String>,
    ): String = when (value) {
        is WorkflowValue.Literal     -> value.raw
        is WorkflowValue.Variable    -> vars[value.name] ?: ""
        is WorkflowValue.TriggerInput -> ctx.fields[value.field] ?: ""
        is WorkflowValue.Template    -> expandTemplate(value.tpl, ctx, vars)
    }

    /**
     * Expands `{{varName}}` and `{{trigger.field}}` placeholders in [tpl].
     *
     * - `{{trigger.field}}` → looked up from [TriggerContext.fields]
     * - `{{name}}` → looked up from the mutable variable table
     * - Unknown placeholders are replaced with an empty string.
     */
    private fun expandTemplate(
        tpl: String,
        ctx: TriggerContext,
        vars: Map<String, String>,
    ): String = PLACEHOLDER_REGEX.replace(tpl) { match ->
        val key = match.groupValues[1].trim()
        if (key.startsWith("trigger.")) {
            ctx.fields[key.removePrefix("trigger.")] ?: ""
        } else {
            vars[key] ?: ""
        }
    }

    private val PLACEHOLDER_REGEX = Regex("""\{\{([^}]+)}}""")

    // ─────────────────────────────────────────────────────────────────────────
    // Condition evaluation
    // ─────────────────────────────────────────────────────────────────────────

    private fun evalCondition(
        condition: WorkflowCondition,
        ctx: TriggerContext,
        vars: Map<String, String>,
    ): Boolean {
        fun v(value: WorkflowValue) = resolve(value, ctx, vars)
        return when (condition) {
            is WorkflowCondition.Equals      -> v(condition.left) == v(condition.right)
            is WorkflowCondition.NotEquals   -> v(condition.left) != v(condition.right)
            is WorkflowCondition.Contains    -> v(condition.value).contains(v(condition.sub))
            is WorkflowCondition.StartsWith  -> v(condition.value).startsWith(v(condition.prefix))
            is WorkflowCondition.Matches     -> runCatching {
                v(condition.value).contains(Regex(condition.regex))
            }.getOrDefault(false)
            is WorkflowCondition.IsEmpty     -> v(condition.value).isEmpty()
            is WorkflowCondition.IsNotEmpty  -> v(condition.value).isNotEmpty()
            is WorkflowCondition.And         -> condition.items.all { evalCondition(it, ctx, vars) }
            is WorkflowCondition.Or          -> condition.items.any { evalCondition(it, ctx, vars) }
            is WorkflowCondition.Not         -> !evalCondition(condition.inner, ctx, vars)
        }
    }
}
