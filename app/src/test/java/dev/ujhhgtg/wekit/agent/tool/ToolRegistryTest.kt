package dev.ujhhgtg.wekit.agent.tool

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolRegistryTest {
    @Test
    fun `dynamic baseline includes load skill exactly once`() {
        val provider = object : ToolProvider {
            override val id = "builtin"
            override val name = "Builtin"
            override val kind = ProviderKind.BUILTIN
            override val isAvailable = true
            override fun listTools() = listOf("edit", "exec", "load_skill").map {
                ProviderTool(it, it, JsonObject(emptyMap()), sideEffect = true)
            }
            override suspend fun execute(toolName: String, arguments: JsonObject) = toolName
        }
        val registry = ToolRegistry(listOf(provider))

        val names = registry.requestTools(ToolLoadingMode.DYNAMIC, setOf("load_skill"))
            .map { it.exposedName }

        assertEquals(listOf("discover_tools", "edit", "exec", "load_skill"), names)
    }
}
