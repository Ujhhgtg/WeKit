package dev.ujhhgtg.wekit.workflow.engine

import dev.ujhhgtg.wekit.agent.tool.AgentToolArgs
import dev.ujhhgtg.wekit.agent.tool.AgentToolDescriptor
import dev.ujhhgtg.wekit.agent.tool.AgentToolsProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Thin wrapper around [AgentToolsProvider.ALL_TOOLS] that lets [WorkflowEngine] call any
 * [@WeKitOperation][dev.ujhhgtg.wekit.features.core.WeKitOperation] by name.
 *
 * The registry is an object (singleton) so the lazy-initialised provider list is shared
 * across all workflow executions.
 */
object OperationRegistry {

    private val byName: Map<String, AgentToolDescriptor> by lazy {
        AgentToolsProvider.ALL_TOOLS.associateBy { it.name }
    }

    /** Returns every known operation descriptor in alphabetical order. */
    val all: List<AgentToolDescriptor> by lazy {
        AgentToolsProvider.ALL_TOOLS.sortedBy { it.name }
    }

    /** Finds an operation by its declared [name], or null if not registered. */
    fun find(name: String): AgentToolDescriptor? = byName[name]

    /**
     * Invokes [operationName] with [namedArgs] (a plain string map) and returns the
     * model-readable result string.
     *
     * Builds a [JsonObject] from the string map so the [AgentToolArgs] accessor methods
     * work correctly. Throws [OperationNotFoundException] if the name is unknown; any
     * exception thrown by the operation itself propagates unchanged.
     */
    suspend fun invoke(operationName: String, namedArgs: Map<String, String>): String {
        val descriptor = find(operationName)
            ?: throw OperationNotFoundException(operationName)

        val jsonArgs: JsonObject = buildJsonObject {
            namedArgs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return descriptor.invoker(AgentToolArgs(jsonArgs))
    }

    class OperationNotFoundException(name: String) :
        IllegalArgumentException("No @WeKitOperation named '$name' is registered.")
}
