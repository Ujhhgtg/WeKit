package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.core.WeKitOperation
import dev.ujhhgtg.wekit.workflow.data.WorkflowRepository
import dev.ujhhgtg.wekit.workflow.engine.TriggerContext
import dev.ujhhgtg.wekit.workflow.engine.WorkflowEngine
import dev.ujhhgtg.wekit.workflow.model.Workflow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * WeAgent built-in tools for managing the Workflow system. Exposed to the model under the
 * [WeKitOperation.BUILTIN_WORKFLOW] provider group so they appear as a distinct section in the
 * tool-permission settings UI.
 *
 * Also declares [callWeAgent], the "Call WeAgent" workflow action — it injects text into a WeAgent
 * session and runs a turn, enabling workflows to delegate complex reasoning back to the model.
 */
object WeAgentWorkflowToolBindings {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    @WeKitOperation(
        name = "workflow-list",
        description = "List all workflows. Returns a JSON array of {id, name, description, triggerType, enabled, nodeCount}.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_WORKFLOW,
    )
    suspend fun workflowList(): String {
        val workflows = WorkflowRepository.getAll()
        return json.encodeToString(
            workflows.map { wf ->
                mapOf(
                    "id" to wf.id,
                    "name" to wf.name,
                    "description" to wf.description,
                    "triggerType" to (wf.trigger?.let { it::class.simpleName } ?: "null"),
                    "enabled" to wf.enabled.toString(),
                    "nodeCount" to wf.nodes.size.toString(),
                )
            }
        )
    }

    @WeKitOperation(
        name = "workflow-get",
        description = "Get the full JSON definition of a workflow by its id.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_WORKFLOW,
    )
    suspend fun workflowGet(
        @Param("Workflow id") id: String,
    ): String = WorkflowRepository.getById(id)
        ?.let { json.encodeToString(it) }
        ?: "Error: workflow '$id' not found"

    @WeKitOperation(
        name = "workflow-create",
        description = "Create a new workflow from a JSON definition. The id field in the JSON is ignored and replaced with a new UUID.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_WORKFLOW,
    )
    suspend fun workflowCreate(
        @Param("JSON-serialized Workflow definition") workflowJson: String,
    ): String {
        val parsed = runCatching { json.decodeFromString<Workflow>(workflowJson) }
            .getOrElse { return "Error: invalid workflow JSON — ${it.message}" }
        val created = parsed.copy(id = UUID.randomUUID().toString())
        WorkflowRepository.upsert(created)
        return "Created workflow '${created.name}' with id=${created.id}"
    }

    @WeKitOperation(
        name = "workflow-update",
        description = "Replace an existing workflow's full definition. The id in the JSON must match the target workflow.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_WORKFLOW,
    )
    suspend fun workflowUpdate(
        @Param("JSON-serialized updated Workflow") workflowJson: String,
    ): String {
        val parsed = runCatching { json.decodeFromString<Workflow>(workflowJson) }
            .getOrElse { return "Error: invalid workflow JSON — ${it.message}" }
        WorkflowRepository.upsert(parsed)
        return "Updated workflow '${parsed.name}' (id=${parsed.id})"
    }

    @WeKitOperation(
        name = "workflow-set-enabled",
        description = "Enable or disable a workflow.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_WORKFLOW,
    )
    suspend fun workflowSetEnabled(
        @Param("Workflow id") id: String,
        @Param("true to enable, false to disable") enabled: Boolean,
    ): String {
        WorkflowRepository.getById(id) ?: return "Error: workflow '$id' not found"
        WorkflowRepository.setEnabled(id, enabled)
        return if (enabled) "Enabled workflow $id" else "Disabled workflow $id"
    }

    @WeKitOperation(
        name = "workflow-delete",
        description = "Permanently delete a workflow by id.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_WORKFLOW,
    )
    suspend fun workflowDelete(
        @Param("Workflow id to delete") id: String,
    ): String {
        WorkflowRepository.getById(id) ?: return "Error: workflow '$id' not found"
        WorkflowRepository.delete(id)
        return "Deleted workflow $id"
    }

    @WeKitOperation(
        name = "workflow-run",
        description = "Manually execute a workflow with an empty trigger context.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_WORKFLOW,
    )
    suspend fun workflowRun(
        @Param("Workflow id to execute") id: String,
    ): String {
        val wf = WorkflowRepository.getById(id)
            ?: return "Error: workflow '$id' not found"
        val result = WorkflowEngine.execute(wf, TriggerContext(emptyMap()))
        return result.toString()
    }

    /**
     * The "Call WeAgent" workflow action — injects [content] into a WeAgent session and runs a
     * background turn. This lets workflows delegate reasoning or multi-step logic back to the model.
     *
     * Placed in the [WeKitOperation.BUILTIN_WECHAT] group so it shows up alongside other WeChat
     * actions in the workflow editor's action picker.
     */
    @WeKitOperation(
        name = "call-weagent",
        description = "Inject text into a WeAgent session and run a turn. sessionId can be left empty to create a new background session.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_WECHAT,
    )
    suspend fun callWeAgent(
        @Param("Session id; leave empty to open a new background session") sessionId: String?,
        @Param("Content to send to the agent (template placeholders are already expanded by the workflow engine)") content: String,
    ): String {
        val session = if (sessionId.isNullOrBlank()) {
            WeAgentService.createBackgroundSession()
                ?: return "Error: could not create a background session (no model configured?)"
        } else {
            sessionId
        }
        WeAgentService.runTriggeredTurn(session, content)
        return "Sent to WeAgent session $session"
    }
}
