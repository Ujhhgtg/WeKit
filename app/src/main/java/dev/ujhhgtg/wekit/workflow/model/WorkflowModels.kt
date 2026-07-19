package dev.ujhhgtg.wekit.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Top-level Workflow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A complete workflow definition. Stored as JSON in [WorkflowEntity.workflowJson].
 *
 * [trigger] is null for manually-triggered workflows. [nodes] is the root-level
 * action sequence; control-flow nodes (If, RepeatWithEach, Repeat) embed their
 * sub-sequences directly, forming a tree — not a DAG.
 */
@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val description: String = "",
    val trigger: WorkflowTrigger? = null,
    val nodes: List<WorkflowNode> = emptyList(),
    val enabled: Boolean = true,
)

// ─────────────────────────────────────────────────────────────────────────────
// Triggers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * What event activates a workflow. Each subtype documents the [TriggerContext] fields
 * it injects at runtime (available as `trigger.*` values in nodes).
 */
@Serializable
sealed interface WorkflowTrigger {

    /**
     * Fires when a new message arrives (or is sent).
     * Context fields: `sender`, `talker`, `content`, `msgType`, `msgSvrId`, `isGroup`.
     */
    @Serializable @SerialName("NewMessage")
    data class NewMessage(
        val talkerRegex: String? = null,
        val contentRegex: String? = null,
        val direction: MessageDirection = MessageDirection.RECEIVED,
    ) : WorkflowTrigger

    /**
     * Fires when a new Moments post becomes visible.
     * Context fields: `sender`, `content`, `snsId`, `type`.
     */
    @Serializable @SerialName("NewMoment")
    data class NewMoment(
        /** Whitelist of sender wxids; null = match any sender. */
        val senderWxids: List<String>? = null,
    ) : WorkflowTrigger

    /**
     * Fires when a transfer message is received.
     * Context fields: `sender`, `amount`, `currency`, `note`, `msgSvrId`.
     */
    @Serializable @SerialName("NewTransfer")
    data class NewTransfer(
        val senderWxids: List<String>? = null,
    ) : WorkflowTrigger

    /**
     * Fires when a red-packet message is received.
     * Context fields: `sender`, `groupId` (empty string for private chat), `senderName`, `msgSvrId`.
     */
    @Serializable @SerialName("NewRedPacket")
    data class NewRedPacket(
        val senderWxids: List<String>? = null,
    ) : WorkflowTrigger

    /**
     * Fires on a schedule.
     * Context fields: `firedAt` (ISO-8601 timestamp string).
     */
    @Serializable @SerialName("Schedule")
    data class Schedule(val spec: ScheduleSpec) : WorkflowTrigger
}

@Serializable
enum class MessageDirection { RECEIVED, SENT, BOTH }

@Serializable
sealed interface ScheduleSpec {
    @Serializable @SerialName("Interval")
    data class Interval(val seconds: Long) : ScheduleSpec

    @Serializable @SerialName("Daily")
    data class Daily(val minuteOfDay: Int) : ScheduleSpec   // 0..1439 (local time)

    @Serializable @SerialName("Cron")
    data class Cron(val expr: String) : ScheduleSpec        // 5-field cron, local TZ

    @Serializable @SerialName("Once")
    data class Once(val epochMillis: Long) : ScheduleSpec
}

// ─────────────────────────────────────────────────────────────────────────────
// Nodes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single step in a workflow's node sequence. All nodes carry a stable [id] (UUID)
 * used by the editor to identify cards during drag-reorder operations.
 *
 * Control-flow nodes embed their sub-sequences directly, producing a tree structure
 * that maps naturally to the indented list editor UI.
 */
@Serializable
sealed interface WorkflowNode {
    val id: String

    /**
     * Invoke a named operation (declared with [@WeKitOperation]).
     *
     * [operationName] matches [dev.ujhhgtg.wekit.agent.tool.AgentToolDescriptor.name].
     * [inputs] maps each parameter name to a [WorkflowValue] — resolved at runtime.
     * [outputVar] optionally stores the operation's String return value as a variable
     * that subsequent nodes can reference via [WorkflowValue.Variable].
     */
    @Serializable @SerialName("Action")
    data class Action(
        override val id: String,
        val operationName: String,
        val inputs: Map<String, WorkflowValue> = emptyMap(),
        val outputVar: String? = null,
    ) : WorkflowNode

    /**
     * Conditional branch. Evaluates [condition]; if true runs [thenNodes], otherwise
     * [elseNodes] (which may be empty).
     */
    @Serializable @SerialName("If")
    data class If(
        override val id: String,
        val condition: WorkflowCondition,
        val thenNodes: List<WorkflowNode> = emptyList(),
        val elseNodes: List<WorkflowNode> = emptyList(),
    ) : WorkflowNode

    /**
     * Iterate over a list value. For each element, binds it to [itemVar] and runs [body].
     * The collection must resolve to a newline-separated string (the engine splits on `\n`).
     */
    @Serializable @SerialName("RepeatWithEach")
    data class RepeatWithEach(
        override val id: String,
        val collection: WorkflowValue,
        val itemVar: String,
        val body: List<WorkflowNode> = emptyList(),
    ) : WorkflowNode

    /**
     * Repeat [body] a fixed number of [count] times.
     * A loop-index variable `_index` (0-based) is available inside [body].
     */
    @Serializable @SerialName("Repeat")
    data class Repeat(
        override val id: String,
        val count: WorkflowValue,
        val body: List<WorkflowNode> = emptyList(),
    ) : WorkflowNode

    /**
     * Assign a computed [value] to the named variable [name].
     * The variable is then available via [WorkflowValue.Variable] in subsequent nodes.
     */
    @Serializable @SerialName("SetVariable")
    data class SetVariable(
        override val id: String,
        val name: String,
        val value: WorkflowValue,
    ) : WorkflowNode

    /** Stop execution immediately (like a `return` statement). */
    @Serializable @SerialName("Stop")
    data class Stop(override val id: String, val reason: String = "") : WorkflowNode

    /** Documentation node. Rendered in the editor; no runtime effect. */
    @Serializable @SerialName("Comment")
    data class Comment(override val id: String, val text: String) : WorkflowNode
}

// ─────────────────────────────────────────────────────────────────────────────
// Values
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A value that can be used as an operation input or in a condition.
 * Resolved at runtime by [dev.ujhhgtg.wekit.workflow.engine.WorkflowEngine].
 */
@Serializable
sealed interface WorkflowValue {

    /** A plain string constant. */
    @Serializable @SerialName("Literal")
    data class Literal(val raw: String) : WorkflowValue

    /**
     * Reference to a named runtime variable (set via [WorkflowNode.SetVariable] or an
     * [WorkflowNode.Action] [outputVar]).
     */
    @Serializable @SerialName("Variable")
    data class Variable(val name: String) : WorkflowValue

    /**
     * Reference to a field injected by the triggering event, e.g. `"sender"` for the
     * sender wxid. Available fields depend on the [WorkflowTrigger] subtype.
     */
    @Serializable @SerialName("TriggerInput")
    data class TriggerInput(val field: String) : WorkflowValue

    /**
     * A string template that may embed `{{varName}}` and `{{trigger.field}}` placeholders.
     * Resolved by expanding each placeholder against the runtime variable table and the
     * trigger context.
     */
    @Serializable @SerialName("Template")
    data class Template(val tpl: String) : WorkflowValue
}

// ─────────────────────────────────────────────────────────────────────────────
// Conditions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A boolean predicate evaluated at runtime by [dev.ujhhgtg.wekit.workflow.engine.WorkflowEngine].
 */
@Serializable
sealed interface WorkflowCondition {

    @Serializable @SerialName("Equals")
    data class Equals(val left: WorkflowValue, val right: WorkflowValue) : WorkflowCondition

    @Serializable @SerialName("NotEquals")
    data class NotEquals(val left: WorkflowValue, val right: WorkflowValue) : WorkflowCondition

    @Serializable @SerialName("Contains")
    data class Contains(val value: WorkflowValue, val sub: WorkflowValue) : WorkflowCondition

    @Serializable @SerialName("StartsWith")
    data class StartsWith(val value: WorkflowValue, val prefix: WorkflowValue) : WorkflowCondition

    @Serializable @SerialName("Matches")
    data class Matches(val value: WorkflowValue, val regex: String) : WorkflowCondition

    @Serializable @SerialName("IsEmpty")
    data class IsEmpty(val value: WorkflowValue) : WorkflowCondition

    @Serializable @SerialName("IsNotEmpty")
    data class IsNotEmpty(val value: WorkflowValue) : WorkflowCondition

    @Serializable @SerialName("And")
    data class And(val items: List<WorkflowCondition>) : WorkflowCondition

    @Serializable @SerialName("Or")
    data class Or(val items: List<WorkflowCondition>) : WorkflowCondition

    @Serializable @SerialName("Not")
    data class Not(val inner: WorkflowCondition) : WorkflowCondition
}
