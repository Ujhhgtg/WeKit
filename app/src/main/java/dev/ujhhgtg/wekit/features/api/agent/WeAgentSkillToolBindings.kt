package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.skill.SkillStore
import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.core.WeKitOperation

/**
 * The `load_skill` `@AgentTool` (§ Skills). Skills use the dynamic-discovery model: only enabled
 * skills' `name`+`description` are advertised in the system prompt; the model calls this tool to
 * pull a skill's full instructions on demand (progressive disclosure).
 *
 * Read-only (`sideEffect = false`) → factory-default ENABLED, so loading a skill never needs
 * approval. A skill's *instructions* may direct the model toward tools that do require approval;
 * those are gated independently at call time.
 */
object WeAgentSkillToolBindings {

    @WeKitOperation(
        name = "load_skill",
        description = "Load an available skill's full instructions by name (see the skills catalog in the " +
                "system prompt). Optionally pass 'resource' to read a bundled file from the skill's folder " +
                "instead of the instructions. Returns the content as text.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun loadSkill(
        @Param("The skill name from the catalog, e.g. pdf-forms") name: String,
        @Param("Optional: a bundled resource file path relative to the skill folder to read instead of SKILL.md")
        resource: String?,
    ): String {
        val skill = SkillStore.get(name)
            ?: return "No such skill '$name'. Check the skills catalog in the system prompt for exact names."
        if (!skill.enabled) return "Skill '$name' is disabled."

        if (!resource.isNullOrBlank()) {
            return SkillStore.readResource(name, resource).fold(
                onSuccess = { it },
                onFailure = { "Failed to read resource '$resource' of skill '$name': ${it.message}" },
            )
        }

        return buildString {
            append("# Skill: ").append(skill.name).append("\n\n")
            append(skill.body)
            if (skill.resources.isNotEmpty()) {
                append("\n\n---\n")
                append("Bundled resources (call load_skill again with the 'resource' argument to read one): ")
                append(skill.resources.joinToString(", "))
            }
        }
    }
}
