package dev.ujhhgtg.wekit.features.items.system.agent

import android.content.Intent
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.activity.agent.WeAgentSettingsActivity
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-facing WeAgent entry (§0). Always enabled: the service is unconditionally available and the
 * overlay visibility is governed by the persisted [dev.ujhhgtg.wekit.agent.data.OverlayMode]
 * (default DISABLED). Tapping the row opens the full [WeAgentSettingsActivity].
 *
 * All detailed configuration (model providers, MCP servers, tool permissions, prompts, workspaces,
 * skills, global settings) lives in that Activity — not inline here.
 */
@Feature(
    id = "WeAgent",
    nameRes = "feature_we_agent_name",
    categoryIds = [FeatureCategoryIds.SYSTEM_PRIVACY],
    descriptionRes = "feature_we_agent_description",
)
object WeAgent : ClickableFeature() {

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    override fun onEnable() {
        // Capture the legacy feature preference before it becomes irrelevant; it seeds the
        // one-time overlay-mode migration in [WeAgentSettings.overlayMode].
        val legacyFeatureEnabled = WePrefs.getBoolOrDef(technicalId, false)
        WeAgentService.init()
        MainScope().launch {
            val mode = withContext(Dispatchers.IO) { WeAgentSettings.overlayMode(legacyFeatureEnabled) }
            withContext(Dispatchers.Main) {
                // Apply the overlay mode before mounting so the initial attach is gated.
                WeAgentOverlayController.setMode(mode)
                // Mount the overlay on the main thread (WindowManager requirement).
                WeAgentOverlayController.show()
            }
        }
    }

    override fun onDisable() {
        MainScope().launch(Dispatchers.Main) {
            WeAgentOverlayController.hide()
        }
    }

    override fun onClick(context: ComponentActivity) {
        WeAgentService.init()
        context.startActivity(
            Intent(context, WeAgentSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
