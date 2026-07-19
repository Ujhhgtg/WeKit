package dev.ujhhgtg.wekit.activity.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.ui.agent.settings.BuiltinProvidersScreen
import dev.ujhhgtg.wekit.ui.agent.settings.ExternalServicesScreen
import dev.ujhhgtg.wekit.ui.agent.settings.McpServerDetailScreen
import dev.ujhhgtg.wekit.ui.agent.settings.McpServersScreen
import dev.ujhhgtg.wekit.ui.agent.settings.MemoryScreen
import dev.ujhhgtg.wekit.ui.agent.settings.ModelProviderDetailScreen
import dev.ujhhgtg.wekit.ui.agent.settings.ModelProvidersScreen
import dev.ujhhgtg.wekit.ui.agent.settings.PromptsScreen
import dev.ujhhgtg.wekit.ui.agent.settings.SkillsScreen
import dev.ujhhgtg.wekit.ui.agent.settings.ToolPermissionListScreen
import dev.ujhhgtg.wekit.ui.agent.settings.WeAgentHomeScreen
import dev.ujhhgtg.wekit.ui.agent.settings.WorkspacesScreen
import dev.ujhhgtg.wekit.ui.agent.settings.builtinProviderTools
import dev.ujhhgtg.wekit.ui.content.MiuixStackNavigator
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings

/**
 * Dedicated WeAgent configuration Activity (§8). Deliberately separate from the floating overlay:
 * the overlay stays lean while all detailed configuration (model providers, MCP servers, tool
 * permissions, prompts, workspaces, skills, global settings) lives here.
 *
 * Navigation mirrors [dev.ujhhgtg.wekit.activity.settings.SettingsActivity]: a stack of screens
 * rendered with the miuix predictive-back drill-down transition (slide-from-right + squircle clip +
 * background parallax/dim), supporting arbitrary depth.
 *
 * Named `*SettingsActivity` so [dev.ujhhgtg.wekit.loader.utils.ActivityProxy] routes it through the
 * opaque splash proxy when launched from WeChat's host process.
 */
@Keep
class WeAgentSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ensure the backend is initialized even if the overlay feature hasn't been toggled yet.
        WeAgentService.init()

        setContent {
            val dark = ThemeSettings.themeMode.resolve()
            ModuleTheme(darkTheme = dark) {
                WeAgentSettingsRoot(onFinish = { finish() })
            }
        }
    }
}

/** In-Activity navigation targets. [Home] is the stack root and also hosts global settings. */
sealed interface AgentSettingsScreen {
    data object Home : AgentSettingsScreen
    data object ModelProviders : AgentSettingsScreen
    data class ModelProviderDetail(val providerId: String) : AgentSettingsScreen
    data object BuiltinTools : AgentSettingsScreen
    data class BuiltinToolPermissions(val providerId: String, val name: String) : AgentSettingsScreen
    data object McpServers : AgentSettingsScreen
    data class McpServerDetail(val serverId: String) : AgentSettingsScreen
    data object Prompts : AgentSettingsScreen
    data object Workspaces : AgentSettingsScreen
    data object Memory : AgentSettingsScreen
    data object Skills : AgentSettingsScreen
    data object ExternalServices : AgentSettingsScreen
}

@Composable
private fun WeAgentSettingsRoot(onFinish: () -> Unit) {
    // Screen back-stack. index 0 is Home; the last element is the visible top screen.
    val backStack = remember { mutableStateListOf<AgentSettingsScreen>(AgentSettingsScreen.Home) }
    MiuixStackNavigator(
        stack = backStack,
        onExitRoot = onFinish,
    ) { screen, push, pop -> RenderScreen(screen, push, pop) }
}

@Composable
private fun RenderScreen(
    screen: AgentSettingsScreen,
    push: (AgentSettingsScreen) -> Unit,
    pop: () -> Unit,
) {
    when (screen) {
        AgentSettingsScreen.Home -> WeAgentHomeScreen(onOpen = push)
        AgentSettingsScreen.ModelProviders -> ModelProvidersScreen(
            onBack = pop,
            onOpenProvider = { push(AgentSettingsScreen.ModelProviderDetail(it)) },
        )

        is AgentSettingsScreen.ModelProviderDetail -> ModelProviderDetailScreen(
            providerId = screen.providerId,
            onBack = pop,
        )

        AgentSettingsScreen.BuiltinTools -> BuiltinProvidersScreen(
            onBack = pop,
            onOpenProvider = { id, name -> push(AgentSettingsScreen.BuiltinToolPermissions(id, name)) },
        )

        is AgentSettingsScreen.BuiltinToolPermissions -> ToolPermissionListScreen(
            title = screen.name,
            providerId = screen.providerId,
            tools = builtinProviderTools(screen.providerId),
            onBack = pop,
        )

        AgentSettingsScreen.McpServers -> McpServersScreen(
            onBack = pop,
            onOpenServer = { push(AgentSettingsScreen.McpServerDetail(it)) },
        )

        is AgentSettingsScreen.McpServerDetail -> McpServerDetailScreen(serverId = screen.serverId, onBack = pop)
        AgentSettingsScreen.Prompts -> PromptsScreen(onBack = pop)
        AgentSettingsScreen.Workspaces -> WorkspacesScreen(onBack = pop)
        AgentSettingsScreen.Memory -> MemoryScreen(onBack = pop)
        AgentSettingsScreen.Skills -> SkillsScreen(onBack = pop)
        AgentSettingsScreen.ExternalServices -> ExternalServicesScreen(onBack = pop)
    }
}
